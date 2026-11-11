"""Bounded synchronous client for the public Hermes API-server runs protocol.

No core/provider imports, configuration discovery, credentials on disk, retries,
redirects, proxies, DNS, SSE, or background threads. Each method is ONE request.
The parent supplies an in-memory bearer token and an exact IPv4 loopback URL.

submit(input_text, *, session_id, idempotency_key, model=None, provider=None, model_options=None)
    -> Admission: the server's 202 run_id/status/replayed, NOT a session receipt.
status(run_id) -> RunStatus: preserves actual session_id/runtime if present.
stop(run_id) -> RunStatus: `stopping` is NOT terminal, cleanup, or billing stop.
model_options() -> ModelOptions: the actual picker payload, not /v1/models.

Raises RunsTransportError with fixed `code`, `reason`, `http_status` (or None).
After any possible POST transmission, lost/invalid acceptance or HTTP 5xx is
submission_unknown / stop_unknown. No effect retry is attempted. A caller must
not replace an unknown submission with a fresh idempotency key. Reconciliation
and any deliberate same-key retry are parent policy, NOT this transport.

Identifiers are validated whole and never trimmed, case-folded, or repaired.
The server may rotate a session and may serve a fallback provider/model; return
those facts unchanged. Optional None model/provider are omitted (host selection). Explicit identifiers
are requested selection, NOT a
per-physical-send route guarantee. Only the parent owns lifecycle policy.
"""
from __future__ import annotations

import json
import math
import re
import socket
import time
from typing import Any, Callable, NoReturn, TypedDict
from .voice_run_ids import valid_session_id


MAX_REQUEST_BYTES = 256 * 1024
MAX_BODY_BYTES = 1024 * 1024
MAX_HEADER_BYTES = 32 * 1024  # Includes status, chunk framing, and trailers.
MAX_HEADER_COUNT = 64
MAX_CHUNKS = 4096
TERMINAL_STATUSES = frozenset({'completed', 'failed', 'cancelled', 'interrupted'})
RUN_STATUSES = frozenset({'queued', 'running', 'waiting_for_approval', 'stopping'}) | TERMINAL_STATUSES
_ADMISSION_STATUSES = RUN_STATUSES | {'started'}
_URL = re.compile(r'http://127\.0\.0\.1:([1-9][0-9]{0,4})', re.ASCII)
_RUN = re.compile(r'run_[0-9a-f]{32}', re.ASCII)
_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/@+~\-]*', re.ASCII)
_HEADER_NAME = re.compile(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+")
_EFFORTS = frozenset({'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'})
_MESSAGES = {
    'invalid_configuration': 'Invalid loopback transport configuration.',
    'invalid_argument': 'Invalid transport argument.',
    'request_too_large': 'Request exceeds transport limit.',
    'headers_too_large': 'Response headers exceed transport limit.',
    'response_too_large': 'Response exceeds transport limit.',
    'invalid_response': 'Invalid host response.',
    'deadline_exceeded': 'Host request deadline exceeded.',
    'transport_unavailable': 'Loopback transport unavailable.',
    'redirect_blocked': 'Host redirect refused.',
    'http_rejected': 'Host rejected request.',
    'submission_unknown': 'Run submission outcome is unknown; do not duplicate it.',
    'stop_unknown': 'Run stop outcome is unknown; poll the exact run.',
}


class Admission(TypedDict):
    run_id: str
    status: str
    replayed: bool


class RuntimeAttribution(TypedDict, total=False):
    provider: str
    model: str
    route_source: str
    requested: dict[str, str]
    model_lock: str


class RunStatus(TypedDict, total=False):
    object: str
    run_id: str
    status: str
    session_id: str
    model: str
    output: str
    error: str
    completed: bool
    partial: bool
    interrupted: bool
    runtime: RuntimeAttribution
    usage: dict[str, Any]
    created_at: float
    updated_at: float


class ModelOptions(TypedDict):
    providers: list[dict[str, Any]]
    provider: str
    model: str


class RunsError(Exception):
    """Fixed public errors only: never contains token, body, headers, or URL.

    reason is a fixed underlying code (e.g. deadline_exceeded), useful when code
    is submission_unknown/stop_unknown. http_status is available after parsing
    a valid HTTP status. Errors are not a declaration of worker termination.
    """
    def __init__(self, code: str, *, reason: str | None = None, http_status: int | None = None):
        self.code = code
        self.reason = reason or code
        self.http_status = http_status
        self.status = http_status
        self.submission_unknown = code == 'submission_unknown'
        super().__init__(_MESSAGES[code])


RunsTransportError = RunsError  # Backwards-compatible descriptive alias.


class _Failure(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _fail(code: str = 'invalid_response') -> NoReturn:
    raise _Failure(code)


def _identifier(value: Any, *, session: bool = False, empty: bool = False) -> bool:
    if not isinstance(value, str):
        return False
    if empty and value == '':
        return True
    return valid_session_id(value) if session else len(value) <= 512 and bool(_ID.fullmatch(value))


def _run_id(value: Any) -> bool:
    return isinstance(value, str) and bool(_RUN.fullmatch(value))


def _visible_ascii(value: Any, limit: int) -> bool:
    return isinstance(value, str) and 0 < len(value) <= limit and all(33 <= ord(c) <= 126 for c in value)


def _options(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, dict) or set(value) - {'reasoning', 'reasoning_effort', 'fast', 'service_tier'}:
        _fail('invalid_argument')
    if 'fast' in value and (type(value['fast']) is not bool or 'service_tier' in value):
        _fail('invalid_argument')
    if 'service_tier' in value and value['service_tier'] not in (None, 'auto', 'default', 'priority', 'flex'):
        _fail('invalid_argument')
    if 'reasoning_effort' in value:
        if not isinstance(value['reasoning_effort'], str) or value['reasoning_effort'] not in _EFFORTS or 'reasoning' in value:
            _fail('invalid_argument')
    result = dict(value)
    if 'reasoning' in value:
        reasoning = value['reasoning']
        if not isinstance(reasoning, dict) or not reasoning or set(reasoning) - {'enabled', 'effort'}:
            _fail('invalid_argument')
        if 'enabled' in reasoning and type(reasoning['enabled']) is not bool:
            _fail('invalid_argument')
        if 'effort' in reasoning and (not isinstance(reasoning['effort'], str) or reasoning['effort'] not in _EFFORTS):
            _fail('invalid_argument')
        if reasoning.get('enabled') is False and reasoning.get('effort', 'none') != 'none':
            _fail('invalid_argument')
        if reasoning.get('enabled') is True and reasoning.get('effort') == 'none':
            _fail('invalid_argument')
        result['reasoning'] = dict(reasoning)
    return result


class _Reader:
    """Small bounded socket reader with a monotonically shrinking deadline."""
    def __init__(self, sock: socket.socket, deadline: float):
        self.sock, self.deadline = sock, deadline
        self.buffer = bytearray()
        self.header_bytes = 0

    def remaining(self) -> float:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            _fail('deadline_exceeded')
        return remaining

    def recv(self, count: int = 8192) -> bytes:
        self.sock.settimeout(self.remaining())
        return self.sock.recv(min(count, 8192))

    def line(self) -> bytes:
        while True:
            end = self.buffer.find(b'\r\n')
            used = end + 2 if end >= 0 else len(self.buffer)
            if self.header_bytes + used > MAX_HEADER_BYTES:
                _fail('headers_too_large')
            if end >= 0:
                result = bytes(self.buffer[:end])
                del self.buffer[:end + 2]
                self.header_bytes += end + 2
                return result
            part = self.recv()
            if not part:
                _fail()
            self.buffer.extend(part)

    def exact(self, count: int) -> bytes:
        while len(self.buffer) < count:
            part = self.recv(count - len(self.buffer))
            if not part:
                _fail()
            self.buffer.extend(part)
        result = bytes(self.buffer[:count])
        del self.buffer[:count]
        return result

    def headers(self) -> dict[bytes, bytes]:
        headers: dict[bytes, bytes] = {}
        count = 0
        while True:
            line = self.line()
            if not line:
                return headers
            count += 1
            if count > MAX_HEADER_COUNT:
                _fail('headers_too_large')
            key, sep, value = line.partition(b':')
            if not sep or not _HEADER_NAME.fullmatch(key) or any(c < 32 and c != 9 or c == 127 for c in value):
                _fail()
            key = key.lower()
            if key in headers:
                _fail()
            headers[key] = value.strip(b' \t')

    def body(self, headers: dict[bytes, bytes]) -> bytes:
        if headers.get(b'content-encoding', b'identity').lower() != b'identity':
            _fail()
        if headers.get(b'content-type', b'').split(b';', 1)[0].strip().lower() != b'application/json':
            _fail()
        length, transfer = headers.get(b'content-length'), headers.get(b'transfer-encoding')
        if length is not None and transfer is not None:
            _fail()
        if length is not None:
            if not re.fullmatch(rb'[0-9]{1,10}', length):
                _fail()
            size = int(length)
            if size > MAX_BODY_BYTES:
                _fail('response_too_large')
            return self.exact(size)
        body = bytearray()
        if transfer is not None:
            if transfer.lower() != b'chunked':
                _fail()
            for _ in range(MAX_CHUNKS):
                line = self.line()
                size_text = line.split(b';', 1)[0]
                if not re.fullmatch(rb'[0-9A-Fa-f]{1,8}', size_text):
                    _fail()
                size = int(size_text, 16)
                if not size:
                    trailers = self.headers()
                    if any(k in trailers for k in (b'content-length', b'transfer-encoding', b'content-encoding')):
                        _fail()
                    return bytes(body)
                if len(body) + size > MAX_BODY_BYTES:
                    _fail('response_too_large')
                body.extend(self.exact(size))
                if self.exact(2) != b'\r\n':
                    _fail()
                self.header_bytes += 2
            _fail('response_too_large')
        body.extend(self.buffer)
        self.buffer.clear()
        while True:
            if len(body) > MAX_BODY_BYTES:
                _fail('response_too_large')
            part = self.recv(min(8192, MAX_BODY_BYTES + 1 - len(body)))
            if not part:
                return bytes(body)
            body.extend(part)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail()
        result[key] = value
    return result


def _decode(body: bytes, token: str) -> dict[str, Any]:
    def reject_constant(_):
        _fail()
    try:
        result = json.loads(body.decode('utf-8'), object_pairs_hook=_unique_object, parse_constant=reject_constant)
    except (ValueError, UnicodeError, RecursionError):
        _fail()
    if not isinstance(result, dict):
        _fail()
    # Reject credential echoes, including JSON-escaped text and object keys.
    # Reject rather than edit a payload: never silently repair an identity.
    stack: list[tuple[Any, int]] = [(result, 0)]
    nodes = 0
    while stack:
        value, depth = stack.pop()
        nodes += 1
        if depth > 32 or nodes > 100000:
            _fail()
        if isinstance(value, str):
            if token in value or any(0xD800 <= ord(c) <= 0xDFFF for c in value):
                _fail()
        elif isinstance(value, dict):
            stack.extend((k, depth + 1) for k in value)
            stack.extend((v, depth + 1) for v in value.values())
        elif isinstance(value, list):
            stack.extend((v, depth + 1) for v in value)
        elif isinstance(value, float) and not math.isfinite(value):
            _fail()
    return result


def _validate_run(payload: dict[str, Any], *, run_id: str | None = None, admission: bool = False, stop: bool = False) -> None:
    if not _run_id(payload.get('run_id')) or (run_id is not None and payload['run_id'] != run_id):
        _fail()
    status = payload.get('status')
    allowed = _ADMISSION_STATUSES if admission else (TERMINAL_STATUSES | {'stopping'} if stop else RUN_STATUSES)
    if not isinstance(status, str) or status not in allowed:
        _fail()
    if admission and type(payload.get('replayed')) is not bool:
        _fail()
    if 'object' in payload and payload['object'] != 'hermes.run':
        _fail()
    if not admission and not stop and 'session_id' not in payload:
        _fail()
    if 'session_id' in payload and not _identifier(payload['session_id'], session=True):
        _fail()
    if 'model' in payload and not _identifier(payload['model'], empty=True):
        _fail()
    for key in ('replayed', 'completed', 'partial', 'interrupted'):
        if key in payload and type(payload[key]) is not bool:
            _fail()
    if status == 'completed' and (payload.get('completed') is False or payload.get('partial') is True or payload.get('interrupted') is True):
        _fail()
    for key in ('output', 'error', 'turn_exit_reason', 'last_event'):
        if key in payload and not isinstance(payload[key], str):
            _fail()
    for key in ('created_at', 'updated_at', 'shutdown_requested_at'):
        if key in payload and (type(payload[key]) not in (int, float) or payload[key] < 0):
            _fail()
    if 'usage' in payload and not isinstance(payload['usage'], dict):
        _fail()
    if 'runtime' in payload:
        runtime = payload['runtime']
        if not isinstance(runtime, dict):
            _fail()
        for key in ('model', 'provider', 'route_source', 'model_lock'):
            if key in runtime and not _identifier(runtime[key], empty=True):
                _fail()
        if 'requested' in runtime:
            requested = runtime['requested']
            if not isinstance(requested, dict):
                _fail()
            for key in ('model', 'provider'):
                if key in requested and not _identifier(requested[key], empty=True):
                    _fail()


def _validate_catalog(payload: dict[str, Any]) -> None:
    if not isinstance(payload.get('providers'), list):
        _fail()
    if not _identifier(payload.get('model'), empty=True) or not _identifier(payload.get('provider'), empty=True):
        _fail()
    for row in payload['providers']:
        if not isinstance(row, dict) or not _identifier(row.get('slug')) or not isinstance(row.get('models'), list):
            _fail()
        if any(not _identifier(model) for model in row['models']):
            _fail()


class RunsClient:
    """In-memory bearer client; timeout is a total deadline per method (0, 60]s.

    base_url MUST exactly match http://127.0.0.1:<canonical port>, without slash,
    credentials, query, fragment, profile prefix, or whitespace. No env lookup.
    A fresh socket is closed after every operation. This object owns no poller.
    """
    __slots__ = ('_port', '__token', '_timeout')

    def __init__(self, base_url: str, token: str, timeout: float = 10.0):
        match = _URL.fullmatch(base_url) if isinstance(base_url, str) else None
        if (not match or not 1 <= int(match[1]) <= 65535 or not _visible_ascii(token, 4096)
                or type(timeout) not in (int, float) or not 0 < timeout <= 60):
            raise RunsTransportError('invalid_configuration')
        self._port, self.__token, self._timeout = int(match[1]), token, float(timeout)

    def submit(self, input_text: str, *, session_id: str, idempotency_key: str,
               model: str | None = None, provider: str | None = None,
               model_options: dict[str, Any] | None = None) -> Admission:
        if (not isinstance(input_text, str) or not input_text
                or (model is not None and not _identifier(model))
                or (provider is not None and not _identifier(provider))
                or not _identifier(session_id, session=True) or not _visible_ascii(idempotency_key, 255)):
            raise RunsTransportError('invalid_argument')
        if len(input_text) > MAX_REQUEST_BYTES:
            raise RunsTransportError('request_too_large')
        try:
            options = _options(model_options)
        except _Failure as exc:
            raise RunsTransportError(exc.code) from None
        body = {'input': input_text, 'session_id': session_id, 'model_options': options}
        if model is not None:
            body['model'] = model
        if provider is not None:
            body['provider'] = provider
        return self._request('POST', '/v1/runs', body=body, key=idempotency_key, mode='submit',
                             validate=lambda p: _validate_run(p, admission=True))

    def status(self, run_id: str) -> RunStatus:
        if not _run_id(run_id):
            raise RunsTransportError('invalid_argument')
        return self._request('GET', f'/v1/runs/{run_id}', validate=lambda p: _validate_run(p, run_id=run_id))

    def stop(self, run_id: str) -> RunStatus:
        if not _run_id(run_id):
            raise RunsTransportError('invalid_argument')
        return self._request('POST', f'/v1/runs/{run_id}/stop', body={}, mode='stop',
                             validate=lambda p: _validate_run(p, run_id=run_id, stop=True))

    def probe(self) -> dict:
        """Authenticate the real runs schema without admitting a run/model request.

        Empty input must be rejected before concurrency, session, or provider work.
        A healthy unrelated HTTP server or an auth denial is not readiness.
        """
        def validate(payload):
            error = payload.get('error')
            if not isinstance(error, dict) or error.get('message') != "Missing 'input' field":
                _fail()
        self._request('POST', '/v1/runs', body={'input': ''}, validate=validate, expected_status=400)
        return {'ok': True, 'contract': 'hermes-runs-v1', 'model_called': False}

    def model_options(self) -> ModelOptions:
        return self._request('GET', '/api/model/options', validate=_validate_catalog)

    def _request(self, method: str, path: str, *, validate: Callable[[dict[str, Any]], None],
                 body: dict[str, Any] | None = None, key: str | None = None, mode: str = 'read',
                 expected_status: int | None = None) -> Any:
        deadline = time.monotonic() + self._timeout
        sent = False
        http_status = None
        sock = None
        try:
            try:
                raw = json.dumps(body, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8') if body is not None else b''
            except (ValueError, TypeError, UnicodeError, RecursionError):
                _fail('invalid_argument')
            if len(raw) > MAX_REQUEST_BYTES:
                _fail('request_too_large')
            headers = [f'{method} {path} HTTP/1.1', f'Host: 127.0.0.1:{self._port}',
                       'Authorization: Bearer ' + self.__token, 'Accept: application/json',
                       'Accept-Encoding: identity', 'Connection: close']
            if key is not None:
                headers.append('Idempotency-Key: ' + key)
            if body is not None:
                headers.extend(['Content-Type: application/json', f'Content-Length: {len(raw)}'])
            request = ('\r\n'.join(headers) + '\r\n\r\n').encode('ascii') + raw
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            reader = _Reader(sock, deadline)
            sock.settimeout(reader.remaining())
            sock.connect(('127.0.0.1', self._port))  # Numeric address: no DNS/proxy/env path.
            sock.settimeout(reader.remaining())
            sent = True  # sendall can fail after a partial write: conservatively unknown.
            sock.sendall(request)
            status_line = reader.line()
            match = re.fullmatch(rb'HTTP/1\.[01] ([1-5][0-9]{2})(?: [\x20-\x7e]*)?', status_line)
            if not match:
                _fail()
            http_status = int(match[1])
            if 300 <= http_status < 400:
                _fail('redirect_blocked')
            if http_status >= 400 and http_status != expected_status:
                # Do not read/return/log arbitrary server error bodies or headers.
                _fail('http_rejected')
            if http_status != (expected_status if expected_status is not None else (202 if mode == 'submit' else 200)):
                _fail()
            response_headers = reader.headers()
            payload = _decode(reader.body(response_headers), self.__token)
            validate(payload)
            reader.remaining()  # Include bounded decode/validation in total deadline.
            return payload
        except (_Failure, OSError) as exc:
            reason = (exc.code if isinstance(exc, _Failure) else
                      'deadline_exceeded' if isinstance(exc, TimeoutError) else 'transport_unavailable')
            code = reason
            definitive = reason == 'redirect_blocked' or (reason == 'http_rejected' and http_status is not None and 400 <= http_status < 500)
            if sent and mode != 'read' and not definitive:
                code = 'submission_unknown' if mode == 'submit' else 'stop_unknown'
            raise RunsTransportError(code, reason=reason, http_status=http_status) from None
        finally:
            if sock is not None:
                sock.close()
