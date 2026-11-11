"""Opt-in SOURCE prototype. No registration, provider call or listener on import.
Full text -> Vertex SSE incremental PCM -> HPS1 wire. No paid retry/fallback.
"""
import base64
import binascii
import json
import re
import struct
import threading
import time
from dataclasses import dataclass
from http.client import HTTPException
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler


class StreamError(Exception):
    """Only allowlisted codes; never attach provider bodies/credentials."""


@dataclass(frozen=True)
class Limits:
    event_bytes: int = 262144
    wire_bytes: int = 32 * 1024 * 1024
    pcm_bytes: int = 24000 * 2 * 120
    chunk_bytes: int = 8192
    seconds: float = 150.0
    read_timeout: float = 5.0

    def __post_init__(self):
        if not (0 < self.chunk_bytes <= 8192 and self.chunk_bytes % 2 == 0
                and 0 < self.event_bytes <= 1048576 and self.wire_bytes > 0
                and 0 < self.pcm_bytes <= 5760000 and self.seconds > 0
                and 0 < self.read_timeout <= 10):
            raise ValueError('invalid_limits')


@dataclass(frozen=True)
class Identity:
    request_id: str
    audio_id: str
    generation: int

    def __post_init__(self):
        if not all(re.fullmatch(r'[A-Za-z0-9_-]{1,96}', x) for x in (self.request_id, self.audio_id)):
            raise ValueError('invalid_identity')
        if type(self.generation) is not int or not 0 <= self.generation < 2**63:
            raise ValueError('invalid_generation')

    def header(self):
        def field(s):
            b = s.encode('ascii')
            return struct.pack('>H', len(b)) + b
        return b'HPS1' + field(self.request_id) + field(self.audio_id) + struct.pack('>q', self.generation)


def _close_response(response, *, unwinding=False):
    """Never retain network errors or replace an already propagating failure."""
    try:
        response.close()
        return True
    except (OSError, HTTPException):
        return False
    except BaseException:
        # Cleanup-only programmer/process exceptions stay visible on success.
        # During unwinding the original failure (including GeneratorExit) wins.
        if not unwinding:
            raise
        return False


def _close_failure(response):
    """One close attempt while rejecting/abandoning an open response.

    Return only a fresh fixed-vocabulary type, never the caught exception,
    message, traceback or context. Cleanup must not replace the primary error.
    """
    failure_type = None
    try:
        response.close()
    except BaseException as failure:
        failure_type = type(failure)
        if failure_type not in (OSError, TimeoutError, RuntimeError, ValueError, TypeError):
            failure_type = Exception
    return failure_type() if failure_type is not None else None


class OwnedSource:
    """One raw close attempt shared by parser, cancellation and outer cleanup.

    Record uncertainty BEFORE calling close; never retain its exception/payload
    and never let a later cleanup attempt turn uncertainty into success.
    """
    def __init__(self, response):
        self._response = response
        self._close_lock = threading.Lock()
        self._close_state = 'open'

    @classmethod
    def wrap(cls, response):
        return response if isinstance(response, cls) else cls(response)

    def read1(self, size):
        return self._response.read1(size)

    @property
    def close_confirmed(self):
        with self._close_lock:
            return self._close_state == 'closed'

    def close(self):
        with self._close_lock:
            if self._close_state == 'closed':
                return
            if self._close_state == 'uncertain':
                raise OSError('source_cleanup_unproven')
            self._close_state = 'uncertain'
            self._response.close()
            self._close_state = 'closed'


class Cancellation:
    """Close active response on local cancel. This does NOT guarantee billing stops."""
    def __init__(self):
        self.event = threading.Event()
        self.lock = threading.Lock()
        self.response = None

    def bind(self, response):
        with self.lock:
            if self.event.is_set():
                _close_response(response)
                raise StreamError('cancelled')
            self.response = response

    def unbind(self):
        with self.lock:
            self.response = None

    def cancel(self):
        self.event.set()
        with self.lock:
            if self.response is not None:
                _close_response(self.response)

    def check(self):
        if self.event.is_set():
            raise StreamError('cancelled')


def _lines(response, cancel, limits, deadline):
    # Cooperative parser deadline, not a hard interrupt of blocked transport I/O.
    # Emit CR immediately (no lookahead read). None accounts for a following LF
    # without interpreting that CRLF continuation as a second line/event.
    pending = bytearray()
    cr = False
    total = 0
    while True:
        cancel.check()
        if time.monotonic() > deadline:
            raise StreamError('deadline')
        failed = False
        try:
            chunk = response.read1(min(4096, limits.event_bytes + 1))
        except TimeoutError:
            failed = 'timeout'
        except (OSError, HTTPException):
            failed = 'transport_error'
        # Outside the except block: do not retain a partial HTTP payload through
        # __context__. Check every blocking read outcome, including EOF/errors.
        cancel.check()
        if time.monotonic() > deadline:
            raise StreamError('deadline')
        if failed:
            raise StreamError(failed)
        if not chunk:
            if pending:
                raise StreamError('partial_line')
            return
        total += len(chunk)
        if total > limits.wire_bytes:
            raise StreamError('wire_limit')
        for byte in chunk:
            if cr:
                cr = False
                if byte == 10:
                    yield None
                    continue
            pending.append(byte)
            if len(pending) > limits.event_bytes:
                raise StreamError('wire_limit')
            if byte in (13, 10):
                cr = byte == 13
                yield bytes(pending)
                pending.clear()


def sse_json(response, cancel, limits):
    """Bound every line, entire event and total wire bytes, including comments."""
    total = size = completed_size = 0
    data = []
    deadline = time.monotonic() + limits.seconds
    first_line = True
    for line in _lines(response, cancel, limits, deadline):
        cancel.check()
        if time.monotonic() > deadline:
            raise StreamError('deadline')
        if line is None:
            # CR already dispatched its line. Charge LF to that same event,
            # even when CR was the blank line that completed it.
            total += 1
            if size:
                size += 1
                accounted_size = size
            else:
                completed_size += 1
                accounted_size = completed_size
            if accounted_size > limits.event_bytes or total > limits.wire_bytes:
                raise StreamError('wire_limit')
            continue
        total += len(line)
        size += len(line)
        if size > limits.event_bytes or total > limits.wire_bytes:
            raise StreamError('wire_limit')
        if first_line:
            line = line.removeprefix(b'\xef\xbb\xbf')
            first_line = False
        line = line.removesuffix(b'\n').removesuffix(b'\r')
        if not line:
            completed_size = size
            if data:
                invalid = False
                try:
                    obj = json.loads(b'\n'.join(data))
                except (ValueError, UnicodeError, RecursionError):
                    invalid = True
                # `from None` hides display, not JSONDecodeError.doc/context.
                # Leave the handler before constructing the safe replacement.
                if invalid:
                    raise StreamError('invalid_json')
                if not isinstance(obj, dict):
                    raise StreamError('invalid_event')
                yield obj
            data, size = [], 0
        elif line.startswith(b'data:'):
            data.append(line[5:].removeprefix(b' '))
        elif not line.startswith(b':'):
            # Vertex uses data-only SSE; fail closed on explicit SSE error events.
            if line.startswith(b'event:') and line[6:].strip() != b'message':
                raise StreamError('sse_event')
    if size or data:
        raise StreamError('partial_event')


def vertex_pcm(response, cancel=None, limits=Limits()):
    """Yield raw PCM before provider completion; STOP + clean EOF required."""
    response = OwnedSource.wrap(response)
    cancel = cancel or Cancellation()
    total = 0
    finished = False
    unwinding = False
    try:
        cancel.bind(response)
        for obj in sse_json(response, cancel, limits):
            if finished:
                # Usage-only metadata after STOP is permitted, not more audio.
                if obj.get('candidates') or obj.get('error') or obj.get('promptFeedback'):
                    raise StreamError('after_finish')
                continue
            feedback = obj.get('promptFeedback') or {}
            if not isinstance(feedback, dict):
                raise StreamError('invalid_event')
            if obj.get('error') or feedback.get('blockReason'):
                raise StreamError('provider_error')
            candidates = obj.get('candidates', [])
            if not isinstance(candidates, list) or len(candidates) > 1:
                raise StreamError('candidate_shape')
            if not candidates:
                continue
            c = candidates[0]
            if not isinstance(c, dict) or c.get('index', 0) != 0:
                raise StreamError('candidate_shape')
            reason = c.get('finishReason')
            if reason and reason != 'STOP':
                raise StreamError('provider_finish_error')
            content = c.get('content') or {}
            if not isinstance(content, dict):
                raise StreamError('invalid_event')
            parts = content.get('parts', [])
            if not isinstance(parts, list):
                raise StreamError('parts_shape')
            for part in parts:
                if not isinstance(part, dict):
                    raise StreamError('parts_shape')
                blob = part.get('inlineData')
                if blob is None:
                    continue
                if (not isinstance(blob, dict) or not isinstance(blob.get('mimeType', ''), str)
                        or blob.get('mimeType', '').lower().replace(' ', '') not in (
                    'audio/pcm;rate=24000', 'audio/l16;codec=pcm;rate=24000',
                    'audio/pcm;rate=24000;channels=1')):
                    raise StreamError('pcm_format')
                try:
                    pcm = base64.b64decode(blob['data'], validate=True)
                except (KeyError, ValueError, TypeError, binascii.Error):
                    raise StreamError('base64') from None
                if not pcm or len(pcm) % 2:
                    raise StreamError('pcm_alignment')
                total += len(pcm)
                if total > limits.pcm_bytes:
                    raise StreamError('pcm_limit')
                for start in range(0, len(pcm), limits.chunk_bytes):
                    cancel.check()
                    yield pcm[start:start + limits.chunk_bytes]
            if reason == 'STOP':
                finished = True
        if not finished or not total:
            raise StreamError('incomplete')
    except BaseException:
        unwinding = True
        raise
    finally:
        try:
            closed = _close_response(response, unwinding=unwinding)
        finally:
            cancel.unbind()
        if not closed and not unwinding:
            raise StreamError('transport_error')


def hps1_stream(identity, response, cancel=None, limits=Limits()):
    """Consume under contextlib.closing. Truncation/error never produces EOS."""
    response = OwnedSource.wrap(response)
    cancel = cancel or Cancellation()
    chunks = vertex_pcm(response, cancel, limits)
    frames = 0
    unwinding = False
    try:
        yield identity.header()
        for pcm in chunks:
            frames += len(pcm) // 2
            yield b'\x01' + struct.pack('>I', len(pcm)) + pcm
        cancel.check()
        yield b'\x02' + struct.pack('>I', 8) + struct.pack('>q', frames)
    except BaseException:
        unwinding = True
        raise
    finally:
        chunks.close()
        # Also closes when abandoned after header; preserve any primary error.
        _close_response(response, unwinding=unwinding)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise StreamError('redirect_denied')


def open_vertex(text, wrapper, *, authorized=False, model=None, voice=None, limits=Limits()):
    """Explicitly gated seam reuses existing wrapper auth/payload without copying keys.
    Caller supplies the trusted, already-loaded portable wrapper module. Never
    import a path from an HTTP request. No registration/default-provider changes.
    Limits.seconds starts only when the returned response is parsed: wrapper
    authentication and connection establishment are outside that budget.
    read_timeout is a transport timeout, not a total authentication/connect bound.
    This function is NOT exercised against Vertex in offline tests.
    """
    if not authorized:
        raise StreamError('live_not_authorized')
    if not isinstance(text, str) or not text.strip() or len(text) > 12000:
        raise StreamError('text_limit')
    import os
    model = model or os.environ.get('VERTEX_TTS_MODEL', '').strip() or wrapper.DEFAULT_MODEL
    voice = voice or os.environ.get('VERTEX_TTS_VOICE', '').strip() or wrapper.DEFAULT_VOICE
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,96}', model):
        raise StreamError('model')
    url = wrapper.build_url(model).removesuffix(':generateContent') + ':streamGenerateContent?alt=sse'
    from urllib.parse import urlsplit
    host = urlsplit(url)
    if host.scheme != 'https' or not re.fullmatch(r'(?:[a-z0-9-]+-)?aiplatform\.googleapis\.com', host.netloc):
        raise StreamError('endpoint')
    # Credentials/token stay inside the existing resolver and this local scope.
    status = None
    cleanup_error = None
    try:
        prompt = wrapper.compose_prompt(text, wrapper.load_style_prompt())
        token = wrapper.mint_access_token(wrapper.resolve_credential_path())
        req = Request(url, data=json.dumps(wrapper.build_payload(prompt, voice)).encode(),
                      headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                               'Accept': 'text/event-stream'}, method='POST')
        response = build_opener(ProxyHandler({}), NoRedirect()).open(req, timeout=limits.read_timeout)
        if response.headers.get_content_type() != 'text/event-stream':
            error_code = 'content_type'
            cleanup_error = _close_failure(response)
        else:
            return response
    except StreamError:
        raise
    except HTTPError as exc:
        status = exc.code if type(exc.code) is int and 100 <= exc.code <= 599 else None
        error_code = 'http_error'
        cleanup_error = _close_failure(exc)
    except TimeoutError:
        error_code = 'timeout'
    except (OSError, HTTPException):
        error_code = 'transport_error'
    # Do not attach network exception context (which may hold partial bodies).
    failure = StreamError(error_code)
    failure.http_status = status
    failure.cleanup_error = cleanup_error
    raise failure
