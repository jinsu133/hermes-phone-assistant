"""Bounded plugin-owned SOURCE diagnostics. Never serialize arbitrary objects.

No file/network sink or upload on import. Export is explicit, detached JSON.
IDs are per-ring keyed pseudonyms, not credentials or cross-restart identifiers.
"""
from collections import deque
import copy
import hashlib
import hmac
from http.client import IncompleteRead
import json
import math
import secrets
import sqlite3
import threading
import time

if __package__:
    from .voice_pcm_stream import StreamError
else:
    from voice_pcm_stream import StreamError

STAGES = frozenset(('auth', 'request_error', 'connect', 'arm', 'heartbeat',
                    'lease_expired', 'stop_ack', 'admit', 'first_chunk', 'eos',
                    'cleanup', 'receipt', 'cancel', 'listening', 'stream_error',
                    'internal_error', 'connection_error', 'provider_open', 'provider_opened', 'provider_error', 'provider_read_error'))
OUTCOMES = frozenset(('accepted', 'denied', 'renewed', 'expired', 'revoked',
                      'confirmed', 'unproven', 'failed'))
CODES = frozenset(('authentication', 'schema', 'scope', 'stale_scope', 'revoked',
    'lease_expired', 'ack_expired', 'stop_ack', 'host_cleanup_pending',
    'source_cleanup_unproven', 'device_restart_unproven', 'already_armed_or_generation',
    'not_ready_or_used', 'admission', 'stale_stream', 'eos', 'receipt',
    'listening_not_current', 'json', 'route', 'framing', 'length', 'body_limit',
    'content_type', 'truncated_body', 'connection_io', 'transport_error',
    'invalid_json', 'cancelled', 'deadline', 'partial_line', 'wire_limit',
    'epoch_exhausted', 'invalid_event', 'sse_event', 'partial_event', 'after_finish',
    'provider_error', 'candidate_shape', 'provider_finish_error', 'parts_shape',
    'pcm_format', 'base64', 'pcm_alignment', 'incomplete', 'redirect_denied',
    'live_not_authorized', 'text_limit', 'model', 'endpoint', 'pcm_limit', 'http_error', 'timeout'))
TYPES = {t: t.__name__ for t in (Exception, RuntimeError, ValueError, TypeError,
    OSError, TimeoutError, BrokenPipeError, ConnectionResetError, AssertionError,
    json.JSONDecodeError, IncompleteRead, sqlite3.Error, sqlite3.OperationalError,
    sqlite3.IntegrityError, StreamError)}
SCOPE_KEYS = ('request_id', 'audio_id', 'session', 'host_epoch', 'device_epoch',
              'epoch', 'generation')


def safe_errors(error):
    """At most 8 distinct nodes and 16 labeled cause/context links.
    Legacy parent/edge fields describe first discovery; links preserve shared and
    cyclic references, with explicit truncation when a target is outside the cap.
    No str/repr, args except exact allowlisted stream code, doc, partial, traceback,
    locals, arbitrary class names, arbitrary attrs or original exception retention.
    """
    pending = [(error, None, 'root')]
    seen, result, objects = {}, [], []
    while pending and len(result) < 8:
        current, parent, edge = pending.pop(0)
        if not isinstance(current, BaseException) or id(current) in seen:
            continue
        seen[id(current)] = len(result)
        objects.append(current)
        node = dict(index=len(result), parent=parent, edge=edge,
                    type=TYPES.get(type(current), 'OtherError'),
                    suppressed=bool(current.__suppress_context__))
        if type(current) is StreamError and len(current.args) == 1:
            code = current.args[0]
            if type(code) is str and code in CODES:
                node['code'] = code
        if type(current) in (sqlite3.Error, sqlite3.OperationalError, sqlite3.IntegrityError):
            code = getattr(current, 'sqlite_errorcode', None)
            if type(code) is int and 0 <= code <= 65535:
                node['sqlite_code'] = code
        result.append(node)
        pending.extend(((current.__cause__, node['index'], 'cause'),
                        (current.__context__, node['index'], 'context')))
    # Resolve every included node's edges after discovery, even at the node cap.
    # Deduplicating nodes must not discard a second edge or a cycle back-reference.
    for node, current in zip(result, objects):
        node['links'] = []
        for edge, target in (('cause', current.__cause__), ('context', current.__context__)):
            if isinstance(target, BaseException):
                ref = seen.get(id(target))
                link = dict(edge=edge, ref=ref)
                if ref is None:
                    link['truncated'] = True
                node['links'].append(link)
    return result


class DiagnosticRing:
    def __init__(self, *, capacity=256, retention_seconds=300, clock=time.monotonic,
                 wall_clock=time.time):
        if type(capacity) is not int or not 1 <= capacity <= 1024:
            raise ValueError('diagnostic_capacity')
        if (type(retention_seconds) not in (int, float)
                or not 0 < retention_seconds <= 3600):
            raise ValueError('diagnostic_retention')
        self._events = deque(maxlen=capacity)
        self._failures = deque(maxlen=32) # Independent of heartbeat traffic.
        self._retention = retention_seconds
        self._clock, self._wall = clock, wall_clock
        self._salt = secrets.token_bytes(32)
        self._sequence = 0
        self._lock = threading.Lock()

    def _prune(self, now):
        while self._events and now - self._events[0]['monotonic'] >= self._retention:
            self._events.popleft()
        while self._failures and now - self._failures[0]['monotonic'] >= self._retention:
            self._failures.popleft()

    def emit(self, stage, *, scope=None, outcome=None, code=None, error=None,
             first_error=False, elapsed_ms=None, correlation_id=None, http_status=None):
        # Fixed vocabulary: unknown strings never reach the sink.
        if type(stage) is not str or stage not in STAGES:
            raise ValueError('diagnostic_stage')
        with self._lock:
            now = self._clock()
            self._prune(now)
            self._sequence += 1
            event = dict(sequence=self._sequence, stage=stage, monotonic=now,
                         wall_time=self._wall())
            # Only trusted host-minted opaque IDs; never pass caller values here.
            if (type(correlation_id) is str and len(correlation_id) == 32
                    and all(c in '0123456789abcdef' for c in correlation_id)):
                event['correlation_id'] = correlation_id
            if type(http_status) is int and 100 <= http_status <= 599:
                event['http_status'] = http_status
            if type(outcome) is str and outcome in OUTCOMES:
                event['outcome'] = outcome
            if type(code) is str and code in CODES:
                event['code'] = code
            if type(scope) is dict:
                correlation = {}
                for key in SCOPE_KEYS:
                    value = scope.get(key)
                    if ((type(value) is str and len(value) <= 128) or
                            (type(value) is int and 0 <= value < 2**63)):
                        data = (key + ':' + str(value)).encode('utf-8')
                        correlation[key] = hmac.new(self._salt, data, hashlib.sha256).hexdigest()[:24]
                if correlation:
                    event['correlation'] = correlation
            if error is not None:
                event['errors'] = safe_errors(error)
            if error is not None or stage in ('cleanup', 'stream_error', 'internal_error'):
                event['first_error'] = first_error is True
            if type(elapsed_ms) in (int, float) and math.isfinite(elapsed_ms):
                event['elapsed_ms'] = round(min(86400000, max(0, elapsed_ms)), 3)
            self._events.append(event)
            if first_error is True and 'correlation_id' in event and not any(
                    e['correlation_id'] == event['correlation_id'] for e in self._failures):
                self._failures.append(copy.deepcopy(event))

    def snapshot(self):
        with self._lock:
            self._prune(self._clock())
            return copy.deepcopy(list(self._events))

    def bounded_export(self):
        """Read only: <=128 events, <=64KiB, configured retention <=1h.
        First failures precede recent events. No descriptor or authority effect.
        """
        with self._lock:
            self._prune(self._clock())
            selected = list(self._failures)
            seen = {e['sequence'] for e in selected}
            selected += [e for e in reversed(self._events) if e['sequence'] not in seen]
            result = []
            for event in selected[:128]:
                candidate = result + [event]
                if len(json.dumps({'events': candidate}, separators=(',', ':'), allow_nan=False).encode('ascii')) > 65536:
                    break
                result = candidate
            return copy.deepcopy({'events': result})

    def export_json(self):
        return json.dumps(self.snapshot(), sort_keys=True, separators=(',', ':'), allow_nan=False)
