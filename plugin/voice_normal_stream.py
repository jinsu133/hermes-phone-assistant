"""Normal VoiceLink streaming. Inert/default-off; no agent, retries or buffered fallback.
Reuses plugin-owned authenticated transport/PCM ownership, not synthetic authority.
Lock order is VoiceLink -> bridge. Provider opening and all I/O are outside both gates.
"""
import hashlib
import importlib.util
import json
import os
import re
import threading
import secrets
import time
from pathlib import Path

from .voice_pcm_bridge import OfflineBridge, BridgeError, CONNECTION, FULL, exact
from .voice_pcm_stream import Identity, open_vertex, StreamError, _close_failure
from .voice_fixed_prompt_cache import FixedPromptCache, FIXED_TEXTS


class LazyVertexSource:
    """One lazy open on the stream worker. Cancellation/closure never retries an open."""
    def __init__(self, opener, text, diagnostic=lambda *a, **k: None):
        self.opener, self.text = opener, text
        self.diagnostic = diagnostic
        self.response = None
        self._lock = threading.Lock()
        self.attempted = self.closed = self.opening = False
        self._cleanup_unproven = False

    def _diagnostic(self, stage, **values):
        # Observers confer no authority and must not interrupt resource bookkeeping.
        try:
            self.diagnostic(stage, **values)
        except Exception:
            pass

    def _cleanup_diagnostic(self, error):
        if error is not None:
            self._diagnostic('cleanup', outcome='unproven',
                             code='source_cleanup_unproven', error=error)

    def read1(self, size):
        with self._lock:
            if self.closed:
                raise OSError('source_closed')
            opening = not self.attempted
            self.opening = self.opening or opening
            self.attempted = True
            text, self.text = self.text, None
        if opening:
            self._diagnostic('provider_open')
            try:
                response = self.opener(text)
            except BaseException as failure:
                cleanup_error = getattr(failure, 'cleanup_error', None) if type(failure) is StreamError else None
                with self._lock:
                    self._cleanup_unproven |= cleanup_error is not None
                    self.opening = False
                self._diagnostic('provider_error', error=failure, first_error=True,
                    http_status=getattr(failure, 'http_status', None) if type(failure) is StreamError else None)
                self._cleanup_diagnostic(cleanup_error)
                raise
            self._diagnostic('provider_opened', http_status=getattr(response, 'status', None))
            with self._lock:
                self.opening = False
                closed = self.closed
                if not closed:
                    self.response = response
            if closed:
                # Cancellation already marked cleanup uncertain; a late success
                # cannot retroactively certify the earlier unjoined close.
                cleanup_error = _close_failure(response)
                self._cleanup_diagnostic(cleanup_error)
                raise OSError('source_closed')
        if self.response is None:
            raise OSError('source_open_failed')
        try:
            return self.response.read1(size)
        except BaseException as failure:
            self._diagnostic('provider_read_error', error=failure, first_error=True)
            raise

    def close(self):
        with self._lock:
            if self._cleanup_unproven:
                raise OSError('source_cleanup_unproven')
            if self.closed:
                return
            self.closed = True
            self.text = None
            response = self.response
            opening = self.opening
            # Set before I/O; retries must never certify a failed or pending close.
            self._cleanup_unproven = opening or response is not None
        if opening:
            # A late response is closed by the opener worker, but that has not joined yet.
            # Do not let OwnedSource certify cleanup before a possibly failing late close.
            raise OSError('source_open_pending')
        if response is not None:
            cleanup_error = _close_failure(response)
            self._cleanup_diagnostic(cleanup_error)
            if cleanup_error is not None:
                raise OSError('source_cleanup_unproven')
            with self._lock:
                self._cleanup_unproven = False


def existing_vertex_opener(home, *, require_pregenerated=False):
    """Private local acceptance asset first; default misses retain approved streaming.
    Construction does no I/O. Readiness/generation are explicit operator APIs, never
    invoked by descriptor allocation. Strict mode fails closed for acceptance only.
    """
    from .voice_fixed_prompt_asset import PrivatePromptAsset, ACCEPTANCE_TEXT
    from .voice_fixed_prompt_cache import PromptSource
    cache = FixedPromptCache()
    asset = PrivatePromptAsset(home)

    def load_wrapper():
        path = Path(home) / 'bin' / 'hermes-vertex-tts-portable.py'
        spec = importlib.util.spec_from_file_location('voicelink_existing_vertex', path)
        module = importlib.util.module_from_spec(spec)
        # Execute the same bytes we hash; avoid stale timestamp-based pyc reuse.
        source = path.read_bytes()
        exec(compile(source, str(path), 'exec'), module.__dict__)
        return module, hashlib.sha256(source).hexdigest()

    def resolve(text):
        module, wrapper_hash = load_wrapper()
        model = os.environ.get('VERTEX_TTS_MODEL', '').strip() or module.DEFAULT_MODEL
        voice = os.environ.get('VERTEX_TTS_VOICE', '').strip() or module.DEFAULT_VOICE
        style = module.load_style_prompt()
        module.load_style_prompt = lambda: style
        prompt = module.compose_prompt(text, style)
        payload = json.dumps(module.build_payload(prompt, voice), sort_keys=True, separators=(',', ':'))
        identity = ('vertex', module.build_url(model), model, voice, wrapper_hash,
                    hashlib.sha256(payload.encode()).hexdigest())
        return identity, lambda: open_vertex(text, module, authorized=True, model=model, voice=voice)

    def open_source(text, fixed=False):
        if not fixed or text not in FIXED_TEXTS:
            module, _ = load_wrapper()
            return open_vertex(text, module, authorized=True)
        identity, provider = resolve(text)
        if text == ACCEPTANCE_TEXT:
            pcm = asset.load(identity)
            if pcm is not None:
                # New facade only. No old descriptors/receipts/tickets exist in asset.
                return PromptSource(cache, None, pcm=pcm)
            if require_pregenerated:
                raise ValueError('pregenerated_not_ready')
        return cache.source(text, identity, provider)

    def readiness():
        try:
            identity, _ = resolve(ACCEPTANCE_TEXT)
            ready = asset.load(identity) is not None
        except Exception:
            ready = False
        return {'ready': ready, 'status': 'ready' if ready else 'pregenerated_not_ready'}

    open_source.fixed_prompt = lambda text: open_source(text, fixed=True)
    open_source.fixed_prompt_readiness = readiness
    open_source.pregenerate_fixed_prompt = lambda *, authorized=False: asset.generate_once(
        lambda: resolve(ACCEPTANCE_TEXT), authorized=authorized)
    return open_source


class NormalStreamBridge(OfflineBridge):
    stream_watchdog_seconds = 160
    render_contract = 'request-render-v1'
    render_limit = 1024

    def __init__(self, link, credential, opener, *, request_opener=None):
        self.link, self.opener = link, opener
        # Callback must only validate/read an already-created in-memory snapshot.
        self.request_opener = request_opener
        self.binding = None
        # Retain worker identity independently of the descriptor reset on reconnect.
        self._active_request_scope = None
        self._unclean_request_scope = None
        self.attempted_audio = set()
        self.correlation = None
        # One current R/C; bounded non-evicting tombstones prevent ID reuse.
        self._render = None
        self._closed_render_ids = set()
        super().__init__('voicelink', credential, self._source)

    def connect(self, value):
        with self.link._lock:
            with self._lock:
                if self._render is not None and not self._render['closed']:
                    raise BridgeError(409, 'render_close_required')
            result = super().connect(value)
            with self._lock:
                # Generation belongs to this fresh controller epoch, never another ON.
                self._last_generation = -1
                self.binding = None
                self.correlation = None
                self._request_render = False
            return result

    def _source(self):
        # Called only after admission; allocation only, never provider I/O in gate.
        text = self.binding['text']
        opener = (self.request_opener(self.binding['request'])
                  if self.request_opener is not None else self.opener)
        if self.binding['kind'] == 'progress' and text in FIXED_TEXTS:
            opener = getattr(opener, 'fixed_prompt', opener)
        scope, correlation = dict(self._full or {}), self.correlation
        started = time.monotonic()
        def diagnostic(stage, **values):
            self.diagnostics.emit(stage, scope=scope, correlation_id=correlation,
                                  elapsed_ms=(time.monotonic()-started)*1000, **values)
        return LazyVertexSource(opener, text, diagnostic)

    def _item(self, request, audio, kind):
        link = self.link
        item = link.outbox.get(audio)
        if (not link.listening or request != link._latest_request_id or not item
                or not item.get('text') or not item.get('session_id') or not item.get('turn_id')
                or time.time() - item['at'] > 600):
            raise BridgeError(409, 'stale_scope')
        if kind == 'progress':
            valid = item.get('progress_owner') == request and not link._stale_progress(item)
        elif kind == 'approval_notice':
            p = link.confirmation.pending
            valid = bool(p and p.get('audio_id') == audio and p.get('request_id') == request
                         and p.get('state') in ('prompt', 'listening')
                         and self.link.confirmation.clock() < p['expires'])
        else:
            valid = kind == 'final' and audio == request and link._pending is None
        if not valid:
            raise BridgeError(409, 'stale_scope')
        return item

    def _binding_current(self):
        b = self.binding
        if b is None:
            raise BridgeError(409, 'stale_scope')
        item = self._item(b['request'], b['audio'], b['kind'])
        if (b['voice_generation'] != self.link._generation
                or b['session_generation'] != self.link._session_generation or item != b['item']):
            raise BridgeError(409, 'stale_scope')
        return b

    def descriptor(self, value, *, request_render=False):
        extra = {'render_owner_id', 'contract'} if request_render else set()
        exact(value, FULL | {'kind'} | extra)
        if (any(type(value[k]) is not str or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', value[k])
                for k in ('request_id', 'audio_id')) or type(value['kind']) is not str):
            raise BridgeError(400, 'schema')
        if request_render:
            self._render_schema(value)
            if value['kind'] not in ('progress', 'final'):
                raise BridgeError(409, 'unsupported_render_kind')
        with self.link._lock, self._lock:
            self._check(value)
            item = self._item(value['request_id'], value['audio_id'], value['kind'])
            generation = value['generation']
            self._render_admission(value, item, request_render)
            key = (self.link._generation, item['seq'])
            if (type(generation) is not int or not 0 <= generation < 2**63 or generation <= self._last_generation
                    or not self._acked or self._active is not None or key in self.attempted_audio):
                raise BridgeError(409, 'not_ready_or_used')
            # Retain only actual bounded outbox identities; old entries cannot be selected again.
            retained = {(self.link._generation, x['seq']) for x in self.link.outbox.since(0)}
            self.attempted_audio.intersection_update(retained)
            self.attempted_audio.add(key)
            self._owner.cancel()
            self._ticket = None
            self._completed = self._used = False
            self._last_generation, self._kind = generation, value['kind']
            self._request_render = request_render
            self._identity = Identity(value['request_id'], value['audio_id'], generation)
            self._full = {k: value[k] for k in FULL}
            self.binding = dict(request=value['request_id'], audio=value['audio_id'], kind=value['kind'],
                                voice_generation=self.link._generation,
                                session_generation=self.link._session_generation,
                                item=dict(item), text=item['text'])
            if request_render:
                self._bind_render(value, item)
            self.correlation = secrets.token_hex(16)
            self.diagnostics.emit('arm', scope=self._full, outcome='accepted', correlation_id=self.correlation)
            result = dict(self._full, correlation_id=self.correlation, kind=self._kind,
                          voice_session=str(item['session_id']), turn_id=str(item['turn_id']),
                          voice_generation=self.link._generation,
                          text_sha256=hashlib.sha256(item['text'].encode()).hexdigest())
            if request_render:
                result.update(render_owner_id=value['render_owner_id'], contract=self.render_contract)
            return result

    def _revoke(self):
        if self._render is not None:
            self._render['failed'] = True
        super()._revoke()

    def _render_schema(self, value):
        if (type(value['contract']) is not str or value['contract'] != self.render_contract
                or type(value['render_owner_id']) is not str
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,96}', value['render_owner_id'])):
            raise BridgeError(400, 'schema')

    def _render_admission(self, value, item, enabled):
        """Called under link -> bridge gates, before changing any descriptor state."""
        r = self._render
        if r is not None and r['closed'] and r['request'] == value['request_id']:
            raise BridgeError(409, 'render_request_sealed')
        if r is not None and not r['closed']:
            if not enabled or not self._request_render:
                raise BridgeError(409, 'render_close_required')
            if (r['owner'] != value['render_owner_id']
                    or r['request'] != value['request_id'] or r['scope'] != self._scope
                    or r['voice_generation'] != self.link._generation
                    or r['session_generation'] != self.link._session_generation
                    or r['voice_session'] != item['session_id']
                    or r['failed'] or r['sealed'] or r['count'] >= self.render_limit
                    or not (r['accepted'] or r['content_cancelled'])):
                raise BridgeError(409, 'render_not_current')
        elif enabled:
            if (value['render_owner_id'] in self._closed_render_ids
                    or len(self._closed_render_ids) >= self.render_limit):
                raise BridgeError(409, 'render_used_or_exhausted')
            # A v1 descriptor is not evidence that its renderer closed.
            if (self.binding is not None and not self._request_render
                    and self._owner.accepted_receipt() is None):
                raise BridgeError(409, 'legacy_completion_required')

    def _bind_render(self, value, item):
        r = self._render
        if r is None or r['closed']:
            r = self._render = dict(owner=value['render_owner_id'], request=value['request_id'],
                scope=dict(self._scope), voice_generation=self.link._generation,
                session_generation=self.link._session_generation, voice_session=item['session_id'],
                count=0, failed=False, closed=False)
        r.update(count=r['count'] + 1, accepted=False, content_cancelled=False,
                 sealed=value['kind'] == 'final')

    def _current_render(self, value):
        self._render_schema(value)
        r = self._render
        if (not self._request_render or r is None or r['owner'] != value['render_owner_id']
                or r['request'] != value['request_id']):
            raise BridgeError(409, 'render_not_current')
        return r

    def content_receipt(self, value):
        exact(value, FULL | {'correlation_id', 'render_owner_id', 'contract', 'frame_count',
                            'status', 'cleanup_scope', 'renderer_closed'})
        if (type(value['frame_count']) is not int or type(value['status']) is not str
                or value['status'] != 'content_presented' or type(value['cleanup_scope']) is not str
                or value['cleanup_scope'] != 'content' or value['renderer_closed'] is not False
                or type(value['correlation_id']) is not str):
            raise BridgeError(400, 'schema')
        with self.link._lock, self._lock:
            self._check(value, True)
            self._binding_current()
            r = self._current_render(value)
            if (value['correlation_id'] != self.correlation or r['closed'] or r['failed']
                    or r['accepted'] or r['content_cancelled'] or not self._completed
                    or self._active is not None or self._poisoned or not self._acked
                    or not self._owner.content_presented(self._identity, value['frame_count'])):
                raise BridgeError(409, 'content_receipt')
            r['accepted'] = True
            # Echo only exact validated fields. Not a legacy completion or ticket.
            return dict(value, status='content_accepted')

    def render_closed(self, value):
        exact(value, CONNECTION | {'request_id', 'render_owner_id', 'contract', 'closed'})
        self._render_schema(value)
        if value['closed'] is not True or type(value['request_id']) is not str:
            raise BridgeError(400, 'schema')
        with self.link._lock, self._lock:
            r = self._current_render(value)
            if (r['closed'] or any(type(value[k]) is not type(v) or value[k] != v
                                  for k, v in r['scope'].items())):
                raise BridgeError(409, 'render_not_current')
            # Exact cancelled/OFF R may settle its client assertion, but never heal
            # host cleanup uncertainty or restore played/listening authority.
            self._expire_lease_locked()
            current = False
            try:
                self._binding_current()
                current = True
            except BridgeError:
                pass
            success = (current and self._enabled and self._acked and not self._poisoned
                       and self._active is None and self._completed and r['accepted']
                       and r['sealed'] and not r['failed'] and not r['content_cancelled'])
            r['closed'] = True
            self._closed_render_ids.add(r['owner'])
            if not success or not self._owner.render_closed(self._identity):
                r['failed'] = True
                self._owner.cancel()
                self._ticket = None
                self._completed = False
                self._used = True
                if self._active is not None:
                    self._active[1].event.set()
            return dict(value, status='render_closed')

    def receipt(self, value):
        # Generic /receipt must cross the same host/outbox fence as /normal/receipt.
        # The owner itself additionally enforces v2 prerequisites on every accept.
        with self.link._lock:
            self._binding_current()
            return super().receipt(value)

    def cleanup_pending(self):
        """Read cleanup evidence only; revocation and elapsed time prove no join."""
        with self._lock:
            return self._active is not None or self._poisoned

    def cancel_request(self, request, session_generation):
        """Revoke only the captured VoiceLink request; source I/O stays worker-owned.

        Caller holds VoiceLink's gate. Keep the controller lease and never cancel a
        different request, including after connect() has reset the descriptor.
        """
        target = (request, session_generation)
        with self._lock:
            active = self._active is not None and self._active_request_scope == target
            bound = self.binding is not None and (
                self.binding['request'], self.binding['session_generation']) == target
            if bound:
                if self._request_render:
                    self._render['failed'] = True
                self._owner.cancel()
                self._used = True
                self._completed = False
                self._ticket = None
            if active and self._active is not None:
                self._active[1].event.set()
            return not active and self._unclean_request_scope != target

    def start(self, value):
        with self.link._lock:
            binding = self._binding_current()
            result = super().start(value)
            with self._lock:
                self._active_request_scope = (binding['request'], binding['session_generation'])
            return result

    def current_stream(self, marker, scope):
        with self.link._lock:
            self._binding_current()
            return super().current_stream(marker, scope)

    def finish(self, marker, successful, closed):
        # Failed/superseded utterance != widget OFF. Uncertain source cleanup still poisons.
        with self._lock:
            self._expire_lease_locked()
            if self._active is not None and self._active[0] is marker:
                if not closed:
                    self._poisoned = True
                    self._unclean_request_scope = self._active_request_scope
                    self._poisoned_at = self._clock()
                    self._revoke()
                elif self._enabled and self._scope is not None and self._active[2] == self._scope['session']:
                    self._completed = successful and not self._active[1].event.is_set()
                    if not self._completed:
                        if self._request_render and not self._render['content_cancelled']:
                            self._render['failed'] = True
                        self._owner.cancel()
                        self._ticket = None
                self._active = None
                self._active_request_scope = None

    def normal(self, path, value):
        if path == '/normal/v2/descriptor':
            return self.descriptor(value, request_render=True)
        if path == '/normal/v2/content-receipt':
            return self.content_receipt(value)
        if path == '/normal/v2/render-closed':
            return self.render_closed(value)
        if path == '/normal/descriptor':
            return self.descriptor(value)
        if path == '/normal/cancel':
            exact(value, FULL)
            with self._lock:
                self._check(value, True)
                self._owner.cancel()
                self._used = True
                self._completed = False
                self._ticket = None
                if self._request_render:
                    self._render['content_cancelled'] = True
                    if self._kind != 'progress':
                        self._render['failed'] = True
                if self._active is not None:
                    self._active[1].event.set()
                return {'status': 'cancelled'}
        extras = {'/normal/receipt': {'frame_count', 'status'},
                  '/normal/listening': {'listening_ticket'}}
        if path not in extras:
            raise BridgeError(404, 'route')
        exact(value, FULL | {'correlation_id'} | extras[path])
        scope = {k: value[k] for k in FULL}
        with self.link._lock:
            self._binding_current()
            with self._lock:
                self._check(scope, True)
                if value['correlation_id'] != self.correlation:
                    raise BridgeError(409, 'stale_scope')
            if path == '/normal/receipt':
                result = self.receipt(dict(scope, frame_count=value['frame_count'], status=value['status']))
                return dict(result, listening_ticket=result['listening_ticket'] or '', correlation_id=self.correlation)
            with self._lock:
                self._check(scope, True)
                if (not self._completed or self._active is not None or self._ticket is None
                        or value['listening_ticket'] != self._ticket or self._kind == 'progress'):
                    raise BridgeError(409, 'listening_not_current')
                # One-use host validation only. NO host microphone/counter/approval effect.
                self._ticket = None
                self.diagnostics.emit('listening', scope=scope, outcome='accepted', correlation_id=self.correlation)
                return dict(status='consumed', correlation_id=self.correlation)
