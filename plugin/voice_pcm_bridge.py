"""Unregistered SOURCE/OFFLINE authenticated loopback adapter; synthetic sources only.
See streaming-prototype/AUTH_BRIDGE_CONTRACT.md. No production credential lookup.
"""
import hmac
import json
import re
import secrets
import socket
import struct
import sys
import threading
import time
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

if __package__:
    from .voice_pcm_diagnostics import DiagnosticRing
    from .voice_pcm_owner import HostPlaybackOwner
    from .voice_pcm_stream import Identity, Cancellation, Limits, StreamError, hps1_stream, OwnedSource, _close_response
else:
    from voice_pcm_diagnostics import DiagnosticRing
    from voice_pcm_owner import HostPlaybackOwner
    from voice_pcm_stream import Identity, Cancellation, Limits, StreamError, hps1_stream, OwnedSource, _close_response

CONNECTION = {'profile', 'session', 'host_epoch', 'device_epoch', 'epoch'}
FULL = CONNECTION | {'request_id', 'audio_id', 'generation'}


class BridgeError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


def exact(value, keys):
    if type(value) is not dict or set(value) != keys:
        raise BridgeError(400, 'schema')


class OfflineBridge:
    """One trusted synthetic source factory; bounded/nonblocking creation only.
    All state/effect boundaries serialize here, then delegate to the existing owner.
    Network reads/writes and cleanup never hold this lock. No paid source supported.
    """
    def __init__(self, profile, credential, source_factory, *, ack_seconds=3.0,
                 clock=time.monotonic, limits=Limits(), lease_seconds=30.0, synthetic_trial=False):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', profile):
            raise ValueError('profile')
        if type(credential) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{32,512}', credential):
            raise ValueError('ephemeral_credential')
        if not 0 < ack_seconds <= 10:
            raise ValueError('ack_deadline')
        if type(lease_seconds) not in (int, float) or not 0 < lease_seconds <= 60:
            raise ValueError('lease_deadline')
        self._trial_enabled = synthetic_trial is True
        self._trial_correlation = None
        self.trial_consumed = threading.Event()
        self.trial_effects = 0
        self._lease_seconds = lease_seconds
        self._lease_deadline = None
        self.diagnostics = DiagnosticRing(clock=clock)
        self._stream_started = None
        self.profile, self._credential = profile, credential
        self._factory, self._clock, self._limits = source_factory, clock, limits
        self._ack_seconds = ack_seconds
        self._lock = threading.Lock()
        self._owner = HostPlaybackOwner()
        self._host = secrets.token_hex(16)
        self._epoch = 0
        self._device = None
        self._scope = self._full = None
        self._enabled = self._acked = self._used = False
        self._challenge = self._identity = self._ticket = None
        self._active = None
        self._consent = False
        self._consent_revision = 0
        self._last_generation = -1
        self._kind = None
        self._request_render = False
        self._poisoned = self._completed = False
        self._poisoned_at = None
        # Observable proof that a reconnect reset the per-process generation space.
        self.generations_reset_at_connect = 0

    def authenticated(self, value):
        return isinstance(value, str) and hmac.compare_digest(
            value.encode('utf-8'), ('Bearer ' + self._credential).encode('utf-8'))

    def _revoke(self):
        self._owner.cancel()
        self._enabled = False
        self._acked = False
        self._lease_deadline = None
        self._completed = False
        self._ticket = None
        if self._active is not None:
            # Close is worker-owned and bounded by the source transport timeout.
            self._active[1].event.set()

    def _expire_lease_locked(self):
        if self._enabled and (self._lease_deadline is None or self._clock() >= self._lease_deadline):
            self.diagnostics.emit('lease_expired', scope=self._full or self._scope, outcome='expired')
            self._revoke()
            return True
        return False

    def expire_lease(self):
        """Cooperative sweep, NOT evidence of device/source termination."""
        with self._lock:
            return self._expire_lease_locked()

    def heartbeat(self, value):
        """HTTP authenticates before invoking; direct calls are trusted fixture only."""
        exact(value, CONNECTION)
        with self._lock:
            self._check(value)
            self._lease_deadline = self._clock() + self._lease_seconds
            self.diagnostics.emit('heartbeat', scope=self._scope, outcome='renewed')
            return {'status': 'renewed', 'lease_seconds': self._lease_seconds}

    def _elapsed(self):
        return None if self._stream_started is None else (self._clock() - self._stream_started) * 1000

    def _check(self, value, full=False):
        target = self._full if full else self._scope
        if value.get('profile') != self.profile:
            raise BridgeError(403, 'scope')
        if (target is None or any(type(value.get(k)) is not type(v) or value.get(k) != v
                                  for k, v in target.items())):
            raise BridgeError(409, 'stale_scope')
        if self._expire_lease_locked():
            raise BridgeError(410, 'lease_expired')
        if not self._enabled:
            raise BridgeError(409, 'revoked')

    def _device_live(self):
        """True while the previous device still owns a live session.

        A different device_epoch is only a hijack risk while an old session can
        still run. Once its lease has expired and no stream is active, that owner
        is gone, so refusing the new identity forever would just wedge the app.
        Called with the lock held, after _revoke() has already cancelled the owner.
        """
        if self._active is not None:
            return True
        return self._lease_deadline is not None and self._clock() < self._lease_deadline

    def connect(self, value):
        exact(value, {'profile', 'device_epoch'})
        if value['profile'] != self.profile:
            raise BridgeError(403, 'scope')
        device = value['device_epoch']
        if type(device) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', device):
            raise BridgeError(400, 'schema')
        with self._lock:
            # Judge liveness BEFORE any mutation: a rejection must not destroy the
            # evidence (lease deadline / scope) that justified it, or an immediate
            # retry would look like a fresh, uncontested connect.
            previous_live = self._device_live()
            if self._poisoned:
                # Parser deadlines are cooperative, not evidence of source closure.
                # No safe public recovery exists for this owner after uncertain cleanup.
                raise BridgeError(423, 'source_cleanup_unproven')
            if self._device is not None and self._device != device and previous_live:
                raise BridgeError(423, 'device_restart_unproven')
            # Checks passed: only now is it safe to tear the previous session down.
            self._revoke()
            if self._epoch >= 2**63 - 1:
                raise BridgeError(423, 'epoch_exhausted')
            previous = self._epoch
            self._epoch += 1
            self._device = device
            self._scope = dict(profile=self.profile, host_epoch=self._host,
                               device_epoch=device, session=secrets.token_hex(16), epoch=self._epoch)
            self._full = self._identity = None
            self._trial_correlation = None
            self.trial_consumed.clear()
            self._enabled, self._acked, self._used = True, False, False
            self._challenge = secrets.token_hex(16)
            self._stopped_epoch = previous
            self._deadline = self._clock() + self._ack_seconds
            self._lease_deadline = self._clock() + self._lease_seconds
            self._stream_started = None
            # New device session = new per-process generation space. Without this the
            # device's restarted counter (1,2,3...) is permanently below the previous
            # session's high-water mark and every stream is refused with 409 admission.
            self._owner.reset_generation()
            # The device's generation space restarts with its process, so the
            # bridge's own arm/descriptor high-water mark restarts with the session.
            self._last_generation = -1
            self.generations_reset_at_connect = getattr(self, 'generations_reset_at_connect', 0) + 1
            self.diagnostics.emit('connect', scope=self._scope, outcome='accepted')
            return dict(self._scope, challenge=self._challenge, stopped_epoch=previous)

    def arm(self, scope, identity, kind='final'):
        """Trusted fixture controller only; HTTP cannot supply a source or arm it."""
        exact(scope, CONNECTION)
        if not isinstance(identity, Identity) or kind not in ('progress', 'final', 'approval_notice'):
            raise ValueError('descriptor')
        with self._lock:
            self._check(scope)
            if self._full is not None or identity.generation <= self._last_generation:
                raise BridgeError(409, 'already_armed_or_generation')
            self._last_generation, self._kind = identity.generation, kind
            self._identity = identity
            self._full = dict(scope, request_id=identity.request_id, audio_id=identity.audio_id,
                              generation=identity.generation)
            self.diagnostics.emit('arm', scope=self._full, outcome='accepted')
            return dict(self._full)

    def stop_ack(self, value):
        exact(value, CONNECTION | {'challenge', 'stopped_epoch', 'closed'})
        with self._lock:
            self._check(value)
            if self._clock() >= self._deadline:
                self._revoke()
                raise BridgeError(410, 'ack_expired')
            if (self._acked or value['challenge'] != self._challenge
                    or type(value['stopped_epoch']) is not int
                    or value['stopped_epoch'] != self._stopped_epoch or value['closed'] is not True):
                raise BridgeError(409, 'stop_ack')
            if self._active is not None:
                raise BridgeError(409, 'host_cleanup_pending')
            self._acked = True
            self.diagnostics.emit('stop_ack', scope=self._scope, outcome='accepted')
            return {'status': 'stopped'}

    def start(self, value):
        exact(value, FULL)
        with self._lock:
            self._check(value, True)
            if not self._acked or self._used or self._active is not None:
                raise BridgeError(409, 'not_ready_or_used')
            if (not self._owner.begin(self._identity.request_id, self._identity.generation)
                    or not self._owner.admit(self._identity, 0, self._kind,
                                             request_render=self._request_render)):
                self._revoke()
                raise BridgeError(409, 'admission')
            cancel = Cancellation()
            marker = object()
            self._used = True
            self._stream_started = self._clock()
            self.diagnostics.emit('admit', scope=self._full, outcome='accepted')
            self._active = (marker, cancel, self._scope['session'])
            # Factory is bounded, nonblocking, synthetic and trusted, NOT a live opener.
            try:
                source = OwnedSource.wrap(self._factory())
            except BaseException as failure:
                self.diagnostics.emit('stream_error', scope=self._full, error=failure, first_error=True)
                self._revoke()
                self._active = None
                raise
            return marker, cancel, source, self._identity

    def current_stream(self, marker, scope):
        with self._lock:
            self._check(scope, True)
            if self._active is None or self._active[0] is not marker:
                raise BridgeError(409, 'stale_stream')

    def first_chunk(self, marker, scope):
        with self._lock:
            self._check(scope, True)
            if self._active is None or self._active[0] is not marker:
                raise BridgeError(409, 'stale_stream')
            self.diagnostics.emit('first_chunk', scope=self._full, elapsed_ms=self._elapsed(), correlation_id=getattr(self, 'correlation', None))

    def eos(self, marker, scope, identity, frames):
        with self._lock:
            self._check(scope, True)
            if (self._active is None or self._active[0] is not marker
                    or not self._owner.eos(identity, frames)):
                raise BridgeError(409, 'eos')
            self.diagnostics.emit('eos', scope=self._full, elapsed_ms=self._elapsed(), correlation_id=getattr(self, 'correlation', None))

    def finish(self, marker, successful, closed):
        with self._lock:
            self._expire_lease_locked()
            if self._active is not None and self._active[0] is marker:
                if not closed:
                    self._poisoned = True
                    self._poisoned_at = self._clock()
                    self._revoke()
                elif self._active[2] == self._scope['session']:
                    if not successful:
                        self._revoke()
                    elif self._enabled:
                        self._completed = True
                self._active = None

    def receipt(self, value):
        exact(value, FULL | {'frame_count', 'status'})
        with self._lock:
            self._check(value, True)
            receipt = {k: value[k] for k in ('request_id', 'audio_id', 'generation', 'frame_count', 'status')}
            if (not self._acked or not self._completed or self._active is not None
                    or not self._owner.accept(receipt)):
                raise BridgeError(409, 'receipt')
            event = self._owner.take_eligibility()
            self._ticket = secrets.token_hex(16) if event is not None else None
            self.diagnostics.emit('receipt', scope=self._full, outcome='accepted', elapsed_ms=self._elapsed())
            return {'status': 'accepted', 'listening_ticket': self._ticket}

    def cancel(self, value):
        exact(value, CONNECTION)
        with self._lock:
            self._check(value)
            self._revoke()
            self.diagnostics.emit('cancel', scope=self._full or self._scope, outcome='revoked')
            return {'status': 'revoked'}

    def set_consent(self, enabled):
        if type(enabled) is not bool:
            raise ValueError('consent')
        with self._lock:
            self._consent_revision += 1
            self._consent, self._ticket = enabled, None
            return self._consent_revision

    def consume_listening(self, scope, ticket, revision, effect):
        """Only bounded nonblocking fixture effect, no microphone implementation.
        Caller never receives a reusable permission boolean; effect runs inside gate.
        """
        exact(scope, FULL)
        with self._lock:
            self._check(scope, True)
            if (not self._consent or type(revision) is not int or revision != self._consent_revision
                    or self._ticket is None or ticket != self._ticket
                    or self._owner.accepted_receipt() is None):
                raise BridgeError(409, 'listening_not_current')
            self._ticket = None
            self.diagnostics.emit('listening', scope=self._full, outcome='accepted')
            return effect()

    def trial(self, path, value):
        """Init-only synthetic wire. Never an arbitrary host-effect/approval RPC."""
        if not self._trial_enabled:
            raise BridgeError(404, 'route')
        if path == '/trial/arm':
            exact(value, CONNECTION)
            # Arm itself serializes generation allocation against every other arm.
            with self._lock:
                self._check(value)
                if not self._acked or self._full is not None:
                    raise BridgeError(409, 'not_ready_or_used')
                generation = self._last_generation + 1
            full = self.arm(value, Identity(secrets.token_hex(12), secrets.token_hex(12), generation))
            with self._lock:
                self._check(full, True)
                self._trial_correlation = secrets.token_hex(16)
                # Synthetic listening consent only; never real user/sensitive approval.
                self._consent_revision += 1
                self._consent = True
                self.diagnostics.emit('arm', scope=full, outcome='accepted',
                                      correlation_id=self._trial_correlation)
                return dict(full, correlation_id=self._trial_correlation,
                            consent_revision=self._consent_revision)
        extra = {'/trial/consumed': set(), '/trial/receipt': {'frame_count', 'status'},
                 '/trial/listening': {'listening_ticket', 'consent_revision'}}
        if path not in extra:
            raise BridgeError(404, 'route')
        exact(value, FULL | {'correlation_id'} | extra[path])
        scope = {k: value[k] for k in FULL}
        with self._lock:
            self._check(scope, True)
            if (self._trial_correlation is None
                    or value['correlation_id'] != self._trial_correlation):
                raise BridgeError(409, 'stale_scope')
            correlation = self._trial_correlation
            if path == '/trial/consumed':
                # A fast stream can finish before the device's first-write report.
                # Successful source cleanup is not revocation or playback completion.
                # _check above still enforces current full scope and live lease.
                if ((self._active is None and not self._completed)
                        or self.trial_consumed.is_set()):
                    raise BridgeError(409, 'not_ready_or_used')
                self.trial_consumed.set()
                self.diagnostics.emit('first_chunk', scope=scope, outcome='accepted',
                                      correlation_id=correlation)
                return dict(status='consumed', correlation_id=correlation)
        # Existing receipt/effect seams revalidate full scope under their own lock.
        if path == '/trial/receipt':
            result = self.receipt(dict(scope, frame_count=value['frame_count'], status=value['status']))
            self.diagnostics.emit('receipt', scope=scope, outcome='accepted', correlation_id=correlation)
            return dict(result, correlation_id=correlation)
        def synthetic_effect():
            self.trial_effects += 1
            self.diagnostics.emit('listening', scope=scope, outcome='accepted', correlation_id=correlation)
            return dict(status='consumed', correlation_id=correlation)
        return self.consume_listening(scope, value['listening_ticket'],
                                      value['consent_revision'], synthetic_effect)

    def shutdown(self):
        with self._lock:
            self._revoke()


def _decode(body):
    failed = False
    try:
        result = json.loads(body, object_pairs_hook=_unique)
    except (ValueError, UnicodeError, RecursionError):
        failed = True
    if failed:
        raise BridgeError(400, 'json')
    return result


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate')
        result[key] = value
    return result


class ConnectionFailure(Exception):
    """Detached allowlisted failure from this handler's socket I/O ONLY."""


def _connection_io(operation, *args):
    try:
        return operation(*args)
    except OSError:
        pass
    # Outside the handler: no raw error, partial data, headers or request locals.
    raise ConnectionFailure('connection_io')


class ConnectionFile:
    """Classify errors at the untrusted socket boundary, not by global type."""
    def __init__(self, stream):
        self._stream = stream

    def read(self, size):
        return _connection_io(self._stream.read, size)

    def readline(self, size):
        return _connection_io(self._stream.readline, size)

    def write(self, data):
        return _connection_io(self._stream.write, data)

    def flush(self):
        return _connection_io(self._stream.flush)

    def close(self):
        return _connection_io(self._stream.close)


class BridgeHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'
    server_version = 'OfflineBridge'
    sys_version = ''

    def setup(self):
        _connection_io(super().setup)
        self.rfile = ConnectionFile(self.rfile)
        self.wfile = ConnectionFile(self.wfile)
        _connection_io(self.connection.settimeout, 2)
        # Bound slow-drip headers/body as well as writes, not merely idle reads.
        self._watchdog = threading.Timer(10, self._expire)
        self._watchdog.start()

    def _expire(self):
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def finish(self):
        self._watchdog.cancel()
        self._watchdog.join(2)
        # Each connection resource still gets cleanup if a flush/close fails.
        try:
            self.wfile.flush()
        except ConnectionFailure:
            pass
        finally:
            try:
                self.wfile.close()
            except ConnectionFailure:
                pass
            finally:
                try:
                    self.rfile.close()
                except ConnectionFailure:
                    pass

    def log_message(self, *args):
        pass

    def send_error(self, code, message=None, explain=None):
        self.reply(code, {'error': 'http'})

    def reply(self, code, value):
        body = json.dumps(value, separators=(',', ':')).encode('ascii')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_POST(self):
        bridge = self.server.bridge
        try:
            auth = self.headers.get_all('Authorization', [])
            if len(auth) != 1 or not bridge.authenticated(auth[0]):
                bridge.diagnostics.emit('auth', outcome='denied')
                raise BridgeError(401, 'authentication')
            if self.path != '/diagnostics':
                bridge.diagnostics.emit('auth', outcome='accepted')
            if self.path not in ('/connect', '/heartbeat', '/stop-ack', '/stream', '/receipt', '/cancel',
                                  '/trial/arm', '/trial/receipt', '/trial/consumed', '/trial/listening',
                                  '/normal/descriptor', '/normal/receipt', '/normal/listening', '/normal/cancel', '/diagnostics',
                                  '/normal/v2/descriptor', '/normal/v2/content-receipt', '/normal/v2/render-closed'):
                raise BridgeError(404, 'route')
            if self.headers.get_all('Transfer-Encoding'):
                raise BridgeError(400, 'framing')
            lengths = self.headers.get_all('Content-Length', [])
            if not lengths:
                raise BridgeError(411, 'length')
            if len(lengths) != 1 or not re.fullmatch('[0-9]{1,8}', lengths[0]):
                raise BridgeError(400, 'length')
            size = int(lengths[0])
            if size > 4096:
                raise BridgeError(413, 'body_limit')
            if self.headers.get_all('Content-Type', []) != ['application/json']:
                raise BridgeError(415, 'content_type')
            body = self.rfile.read(size)
            if len(body) != size:
                raise BridgeError(400, 'truncated_body')
            value = _decode(body)
            if self.path == '/diagnostics':
                exact(value, set())
                self.reply(200, bridge.diagnostics.bounded_export())
            elif self.path.startswith('/normal/'):
                operation = getattr(bridge, 'normal', None)
                if operation is None:
                    raise BridgeError(404, 'route')
                self.reply(200, operation(self.path, value))
            elif self.path.startswith('/trial/'):
                self.reply(200, bridge.trial(self.path, value))
            elif self.path == '/stream':
                self.stream(value)
            else:
                operation = {'/connect': bridge.connect, '/heartbeat': bridge.heartbeat, '/stop-ack': bridge.stop_ack,
                             '/receipt': bridge.receipt, '/cancel': bridge.cancel}[self.path]
                self.reply(200, operation(value))
        except BridgeError as failure:
            # Do not correlate pre-admission requests with attacker-supplied fields.
            bridge.diagnostics.emit('request_error', outcome='denied', code=failure.code)
            self.reply(failure.status, {'error': failure.code})
        except ConnectionFailure:
            self.close_connection = True

    def stream(self, scope):
        bridge = self.server.bridge
        marker, cancel, source, identity = bridge.start(scope)
        correlation = getattr(bridge, 'correlation', None)
        success = False
        first_chunk = True
        primary_failed = False
        try:
            # Only authenticated full-scope admission extends the speech deadline.
            duration = getattr(bridge, 'stream_watchdog_seconds', None)
            if duration is not None:
                self._watchdog.cancel()
                self._watchdog.join(2)
                self._watchdog = threading.Timer(duration, self._expire)
                self._watchdog.start()
            self.send_response(200)
            self.send_header('Content-Type', 'application/x-hps1')
            self.send_header('Connection', 'close')
            self.end_headers()
            with closing(hps1_stream(identity, source, cancel, bridge._limits)) as chunks:
                for chunk in chunks:
                    bridge.current_stream(marker, scope)
                    if chunk[:1] == b'\x02':
                        bridge.eos(marker, scope, identity, struct.unpack('>q', chunk[5:])[0])
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    if first_chunk and chunk[:1] == b'\x01':
                        bridge.first_chunk(marker, scope)
                        first_chunk = False
            success = True
        except BaseException as failure:
            primary_failed = True
            bridge.diagnostics.emit('stream_error', scope=scope, error=failure,
                                    code=(failure.code if type(failure) is BridgeError else
                                          'connection_io' if type(failure) is ConnectionFailure else None),
                                    first_error=True, correlation_id=correlation)
            # Headers may already be sent: truncate, never append JSON or fake EOS.
            if not isinstance(failure, (BridgeError, StreamError, ConnectionFailure)):
                raise
        finally:
            # Covers even a write failure before advancing the framing generator.
            try:
                _close_response(source, unwinding=not success)
            finally:
                bridge.diagnostics.emit('cleanup', scope=scope,
                    outcome='confirmed' if source.close_confirmed else 'unproven',
                    code=None if source.close_confirmed else 'source_cleanup_unproven',
                    first_error=not primary_failed and not source.close_confirmed, correlation_id=correlation)
                bridge.finish(marker, success, source.close_confirmed)
                self.close_connection = True


class OfflineBridgeServer(ThreadingMixIn, HTTPServer):
    """Explicit ephemeral IPv4 loopback only; owned non-daemon workers joined on close."""
    daemon_threads = False
    block_on_close = True
    request_queue_size = 8

    def __init__(self, bridge):
        self.bridge = bridge
        self._slots = threading.BoundedSemaphore(8)
        super().__init__(('127.0.0.1', 0), BridgeHandler)

    def service_actions(self):
        # serve_forever poll only; every admission/effect also checks exact expiry.
        self.bridge.expire_lease()

    def process_request(self, request, client_address):
        if not self._slots.acquire(False):
            try:
                request.settimeout(2)
                request.sendall(b'HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()

    def handle_error(self, request, client_address):
        failure = sys.exc_info()[1]
        if isinstance(failure, ConnectionFailure):
            self.bridge.diagnostics.emit('connection_error', code='connection_io')
            # A failed parse/error reply or watchdog affects only its connection.
            # No raw exception logging; owned stream failures already ran finish.
            return
        # Fail closed without payload-bearing tracebacks/frame locals in HTTP logs.
        self.bridge.diagnostics.emit('internal_error', error=failure, first_error=True)
        self.bridge.shutdown()
        self.unexpected_error = True

    def server_close(self):
        self.bridge.shutdown()
        super().server_close()
