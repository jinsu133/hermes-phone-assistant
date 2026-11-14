"""Authenticated loopback voice bridge into one existing Hermes conversation.

Registration is inert: no socket, credential read, provider call, or config write. The app
pairs once with a per-device bearer token, then sends bounded audio through Hermes' configured
STT and polls only the final response to the matching injected user turn. In gateway mode the
session_key is fixed by operator configuration; the bridge never creates a second conversation.
"""
from __future__ import annotations

from datetime import datetime
import hashlib
import hmac
import json
import os
import re
import secrets
import shlex
import stat
import tempfile
import threading
import time
import unicodedata
from contextvars import copy_context, ContextVar
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Optional
from urllib.parse import parse_qs, urlsplit
from .voice_confirmation import VoiceConfirmation
from .voice_events import VoiceEvents, TOOL_STATUS
from .voice_progress import evidence_result
from .voice_audio import MAX_SEGMENTS, read_segments, cached_segment
from .voice_tool_policy import arithmetic_command, file_allowed, workspace
from . import messages as _messages

HOST = "127.0.0.1"
PORT = 5001
VOICE_URL = f"http://{HOST}:{PORT}/"
MAX_BODY = 5 * 1024 * 1024
MAX_TEXT = 4000
OUTBOX_LIMIT = 20
PENDING_TTL_SECONDS = 600
TTS_CACHE_TTL_SECONDS = 600
MAX_TTS_BODY = 8 * 1024 * 1024
AUDIO_SUFFIXES = (".wav", ".m4a", ".mp3", ".ogg", ".opus", ".flac", ".aac")
AUDIO_MIME_TYPES = {".wav": "audio/wav", ".m4a": "audio/mp4", ".mp3": "audio/mpeg",
                    ".ogg": "audio/ogg", ".opus": "audio/ogg", ".flac": "audio/flac",
                    ".aac": "audio/aac"}
# Model-facing text lives in messages.py (English default, Korean locale).
VOICE_MESSAGE_PREFIX = _messages.EN["voice.prefix"]
VOICE_HOOK_CONTEXT = _messages.EN["voice.hook"]
VOICE_READ_ONLY_TOOLS = frozenset({"phone_status", "phone_observe", "web_search", "web_extract", "session_search", "skill_view", "skills_list"})


def state_path(home: Optional[Path] = None) -> Path:
    if home is None:
        from hermes_constants import get_hermes_home
        home = get_hermes_home()
    return Path(home) / "state" / "voicelink.json"


LOCAL_SURFACES = frozenset({"cli", "tui", "desktop", "acp", "local"})
REVOKED_OWNER = "__revoked_voice_owner__"
MAX_ADMISSIONS = 256


def _host_surface(ctx) -> Optional[str]:
    """Public ``ctx.session_surface()`` when the host provides it; ``None`` when it cannot tell."""
    surface = getattr(ctx, "session_surface", None)
    if not callable(surface):
        return None
    try:
        value = surface()
    except Exception:
        return None
    return value if isinstance(value, str) else None


def _turn_session_key() -> Optional[str]:
    """Route key of the turn running in this context (public session-context helper)."""
    try:
        from gateway.session_context import get_session_env
    except Exception:
        return None
    try:
        value = get_session_env("HERMES_SESSION_KEY", "")
    except Exception:
        return None
    return value or None


class _Admission:
    """Plugin-owned admission record for one (session_id, turn_id).

    Created at the first ``pre_llm_call`` of a turn and removed at ``post_llm_call`` or
    ``on_session_end``. Identity (``is``) of the record, not its IDs, is what a late async
    callback re-checks, so a released or re-admitted key never revives an old authority.
    """
    __slots__ = ("key", "owner")

    def __init__(self, key: tuple[str, str], owner: Optional[str]) -> None:
        self.key, self.owner = key, owner


def _valid_token(value: Any) -> bool:
    return (isinstance(value, str) and 32 <= len(value) <= 512
            and all(c.isalnum() or c in "-_" for c in value))


def _load_token(home: Optional[Path] = None, connection_file=None) -> Optional[str]:
    from .pairing import canonical_path, read_bundle, PairingError
    if home is not None and connection_file == str(canonical_path(home)):
        try:
            return read_bundle(canonical_path(home))["voice"]["token"]
        except PairingError:
            return None  # An invalid canonical bundle must never fall back to old credentials.
    path = state_path(home)
    try:
        if path.is_symlink() or not path.is_file():
            return None
        info = path.stat()
        if info.st_uid != os.getuid() or info.st_mode & 0o077 or not 0 < info.st_size <= 4096:
            return None
        payload = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    token = payload.get("token") if isinstance(payload, dict) else None
    return token if _valid_token(token) else None


def _write_private(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    data = json.dumps(payload, separators=(",", ":")).encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(data)
    os.chmod(path, 0o600)


def _text_digest(text: str) -> str:
    normalized = unicodedata.normalize("NFC", " ".join(text.split()))
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _read_pairing(path_text: str) -> dict:
    """Read only the voice token from an explicitly selected companion pairing file."""
    source = Path(path_text).expanduser()
    if source.is_symlink():
        raise ValueError("symlinked pairing files are not accepted")
    try:
        fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as exc:
        raise ValueError("pairing file is not readable") from exc
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or not 1 <= info.st_size <= 16 * 1024:
            raise ValueError("pairing file must be a small regular file")
        raw = stream.read(16 * 1024 + 1)
    if len(raw) > 16 * 1024:
        raise ValueError("pairing file is too large")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ValueError("pairing file is invalid JSON") from exc
    if not isinstance(payload, dict) or payload.get("voice_url") != VOICE_URL:
        raise ValueError("pairing file does not target the fixed loopback voice bridge")
    token = payload.get("voice_token")
    if not _valid_token(token):
        raise ValueError("pairing file has no valid voice credential")
    instance_id = payload.get("instance_id")
    if not isinstance(instance_id, str) or len(instance_id) > 80:
        raise ValueError("pairing file has no valid device identity")
    return {"version": 2, "kind": "hermes-voicelink", "token": token,
            "instance_id": instance_id, "url": VOICE_URL}


class Outbox:
    """Bounded in-memory replies keyed to a single injected voice request."""
    def __init__(self, limit: int = OUTBOX_LIMIT) -> None:
        self._lock = threading.Lock()
        self._items: list[dict] = []
        self._seq = 0
        self._limit = limit

    def add(self, *, request_id: str, session_id: Any, turn_id: Any, text: str, progress_owner: Optional[str] = None) -> int:
        with self._lock:
            self._seq += 1
            self._items.append({"seq": self._seq, "request_id": request_id,
                                "session_id": session_id, "turn_id": turn_id,
                                "text": text, "at": round(time.time(), 3)})
            if progress_owner is not None:
                self._items[-1]["progress_owner"] = progress_owner
            if len(self._items) > self._limit:
                del self._items[:-self._limit]
            return self._seq

    def get(self, request_id: str) -> Optional[dict]:
        with self._lock:
            for item in reversed(self._items):
                if item["request_id"] == request_id:
                    return dict(item)
        return None

    def since(self, seq: int) -> list[dict]:
        with self._lock:
            return [dict(item) for item in self._items if item["seq"] > seq]

    def clear(self) -> None:
        with self._lock:
            self._items.clear()


def serialized_lifecycle(method):
    from functools import wraps
    @wraps(method)
    def guarded(self, *args, **kwargs):
        with self._lifecycle_lock:
            return method(self, *args, **kwargs)
    return guarded


class VoiceLink:
    def __init__(self, ctx) -> None:
        self.ctx = ctx
        self.outbox = Outbox()
        self.confirmation = VoiceConfirmation()
        self.events = VoiceEvents()
        self._tts_attempted = set()
        self.tool_voice_request = ContextVar("phone_voice_request", default=None)
        # Set once by authenticated POST dispatch, before body I/O; never refreshed
        # from current ownership. Internal non-HTTP callers retain their own gates.
        self._http_session: ContextVar[Optional[tuple[str, Optional[str]]]] = ContextVar(
            "phone_http_session", default=None)
        self._lock = threading.RLock()
        self._lifecycle_lock = threading.RLock()
        self._pending: Optional[dict] = None
        self._runs = None
        self._speech_runtime = None
        self._speech_requests = {}  # Only current request; never persisted or authority.
        self._run_errors = {}
        self._server: Optional[ThreadingHTTPServer] = None
        self._thread: Optional[threading.Thread] = None
        self._token: Optional[str] = None
        self._port: Optional[int] = None
        self._home: Optional[Path] = None
        self._generation = 0
        # A host-lifetime nonce prevents delayed STOPs from crossing reconnect/process epochs.
        self._session_generation = secrets.token_urlsafe(18)
        self._latest_request_scope = None
        self._ingress_operations = {}  # Bounded, never evict live-epoch revocation tombstones.
        self._ingress_active = set()
        self._confirmation_active = None  # One audio-lock owner: (validated request, session epoch).
        self._cancel_cleanup_blocked = False
        self._request_cleanup_pending = set()  # Missing pending IDs / TTL expiry are not worker completion.
        self._sample_collection = None  # No persisted/configured/automatic consent.
        self._sample_collection_timer = None
        self._latest_request_id = None
        self._tts_cache: Optional[dict] = None
        self._tts_inflight: set[str] = set()
        self._voice_turns: dict[tuple[str, str], bool] = {}
        # Plugin-owned admission state keyed by the hook's (session_id, turn_id); see _Admission.
        self._admissions: dict[tuple[str, str], _Admission] = {}
        # The tool middleware publishes the calling turn's key to its handlers.
        self.tool_turn_key: ContextVar[Optional[tuple[str, str]]] = ContextVar(
            "phone_tool_turn_key", default=None)
        # Session the configured gateway route resolved to, learned from a matched voice turn.
        self._route_session_id: Optional[str] = None
        self._active_sessions: dict[str, tuple] = {}  # Exact pre-turn busy marker; not authority.
        self._audio_lock = threading.Lock()
        self._auto_attempted = False
        self._startup_epoch = 0
        self._last_model = None
        self._metrics = {"audio_requests": 0, "voice_requests": 0, "model_requests": 0, "cancellations": 0}

    @property
    def listening(self) -> bool:
        return self._server is not None

    def startup_epoch(self):
        with self._lock:
            return self._startup_epoch

    def auto_start(self, **kwargs):
        import asyncio
        with self._lock:
            if self._auto_attempted or self.ctx.get_config("voice_enabled", False) is not True:
                return
            self._auto_attempted = True
            epoch = self._startup_epoch
        def attempt():
            try:
                self.start(expected_epoch=epoch)
            except RuntimeError:
                with self._lock:
                    if epoch == self._startup_epoch:
                        self._auto_attempted = False
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            attempt()
        else:
            self.ctx.spawn_task(asyncio.to_thread(attempt), name="phone-voice-start")

    def end_turn(self, **kwargs):
        sid = str(kwargs.get("session_id") or "")
        with self._lock:
            self._active_sessions.pop(sid, None)
            for key in [key for key in self._admissions if key[0] == sid]:
                self._admissions.pop(key, None)

    def route_voice_model(self, request, **kwargs):
        # Kept inert for legacy callers. This is no longer registered as middleware.
        # Rewriting a late request is not a route guarantee and must not choose a model.
        return None

    def voice_admission(self):
        if self._runs is not None:
            return self._runs.admission()
        from .voice_route import snapshot
        return snapshot(self.ctx)

    def execution_capabilities(self):
        from .voice_route import capabilities
        value = capabilities()
        state = self.voice_admission()
        value['voice_execution'] = {
            'mode': state.get('mode', 'strict'),
            'ready': self.listening and state.get('mode') == 'runs' and state.get('ok') is True,
            'contract': 'hermes-runs-v1' if self._runs is not None else None,
            'scoped_cancel': self._runs is not None,
            'strict_physical_route': False, 'settings_cas_scope': 'plugin',
            'error': state.get('error')}
        value['model_catalog'] = self._runs is not None
        value['execution_settings_command'] = True
        value['blocked_reason'] = state.get('error')
        return value

    def _prepare_runs(self):
        from .voice_execution_settings import ExecutionSettings
        from .voice_runs_integration import RunsBinding
        if self._runs is not None:
            return
        settings = ExecutionSettings(self._home)
        selected = settings.current()
        if selected['payload']['mode'] == 'strict':
            return
        client = settings.create_client(selected)
        runtime = self.speech_runtime()
        speech = runtime.snapshot()
        local = runtime.runtime_status(speech)
        if not all(local[k]['dispatch_ready'] for k in ('tts', 'stt')):
            raise RuntimeError('SPEECH_DISPATCH_UNAVAILABLE')
        client.probe()
        self._runs = RunsBinding(self, selected, client)
        # Retain the binding/flock even if durable recovery fails; never deliver or replay.
        self._runs.owner.reconcile_restored_interrupted()


    def speech_runtime(self):
        from .speech_runtime import SpeechRuntime
        from .speech_control import settings
        if self._home is None:
            from .speech_runtime import SpeechRuntimeError
            raise SpeechRuntimeError('SPEECH_HOME_UNAVAILABLE')
        with self._lock:
            if self._speech_runtime is None:
                store = settings(self.ctx, self._home)
                self._speech_runtime = SpeechRuntime(self._home, self.ctx, settings=store,
                    audience=self.ctx.get_config('distribution_audience', 'family'))
            return self._speech_runtime

    def _speech_request(self, request):
        from .speech_runtime import SpeechRuntimeError
        with self._lock:
            if (not self.listening or request != self._latest_request_id
                    or request not in self._speech_requests):
                raise SpeechRuntimeError('SPEECH_REQUEST_STALE')
            return self._speech_requests[request]

    def speech_snapshot(self, request):
        return self._speech_request(request)

    def arm_sample_collection(self, *, consent: bool = False) -> dict:
        """Fresh operator consent for one sample of each fixed phrase, default OFF.
        Storage is fixed under the existing private plugin home; no APK marker.
        """
        from .voice_upload_collection import FixedPhraseUploadCollection
        if consent is not True:
            raise ValueError("fresh_explicit_collection_consent_required")
        if not self._audio_lock.acquire(blocking=False):
            raise ValueError("audio_busy")
        try:
            with self._lock:
                if not self.listening or self._pending or self.confirmation.pending is not None:
                    raise ValueError("collection_requires_idle_live_bridge")
                if self._sample_collection is not None:
                    raise ValueError("collection_already_exists_purge_first")
                collection = FixedPhraseUploadCollection(self._home, self._generation)
                self._sample_collection = collection
                try:
                    self._schedule_collection_expiry(collection, collection.deadline)
                except Exception:
                    self.disarm_sample_collection()
                    raise
                return {"armed": True, "limit": 2, "expires_seconds": collection.ARM_SECONDS,
                        "remaining": collection.remaining}
        finally:
            self._audio_lock.release()

    def sample_collection_status(self):
        with self._lock:
            collection = self._sample_collection
            if collection is not None and (collection.generation != self._generation
                    or not self.listening or time.monotonic() >= collection.retention_deadline
                    or (collection.remaining > 0 and time.monotonic() >= collection.deadline)):
                self.disarm_sample_collection()
                collection = self._sample_collection
            return {"armed": bool(collection and collection.eligible(self._generation)),
                    "remaining": collection.remaining if collection else 0,
                    "copies": len(collection.files) if collection else 0,
                    "cleanup_blocked": bool(collection and collection.error)}

    def _schedule_collection_expiry(self, collection, deadline):
        def expire():
            with self._lock:
                if self._sample_collection is not collection:
                    return
                if (collection.remaining > 0 or collection.error
                        or collection.generation != self._generation
                        or time.monotonic() >= collection.retention_deadline):
                    self.disarm_sample_collection()
                else:
                    self._schedule_collection_expiry(collection, collection.retention_deadline)
        timer = threading.Timer(max(0, deadline - time.monotonic()), expire)
        timer.daemon = True
        self._sample_collection_timer = timer
        timer.start()

    def disarm_sample_collection(self):
        """Revoke and purge the bounded test copies. Failed purge blocks re-arm."""
        with self._lock:
            if self._sample_collection_timer is not None:
                self._sample_collection_timer.cancel()
                self._sample_collection_timer = None
            collection = self._sample_collection
            if collection is None or collection.purge():
                self._sample_collection = None

    def _capture_sample_upload(self, audio, suffix, generation, transcript):
        with self._lock:
            collection = self._sample_collection
            if collection is None:
                return
            if (generation != collection.generation or generation != self._generation
                    or time.monotonic() >= collection.deadline or not self.listening):
                # Exhausted copies retain only under the same owner until retention TTL.
                if collection.remaining > 0 or generation != collection.generation or not self.listening:
                    self.disarm_sample_collection()
                return
            try:
                collection.capture(audio, suffix, generation, transcript)
            except Exception:
                # An optional diagnostic copy must never change configured STT/injection.
                collection.error = "capture_failed"
                collection.remaining = 0
            if collection.error:
                self.disarm_sample_collection()

    @staticmethod
    def _scope_id(value):
        return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value) is not None

    def _http_session_error(self):
        """Validate the original POST authentication at an owner-locked effect gate.

        A request's body/headers cannot supply this capability. The ContextVar keeps
        it fixed through synchronous dispatch/STT and is reset after each POST (also
        on keep-alive connections). Token rotation revokes an already authenticated
        handler even when its claimed session generation still matches.
        """
        scope = self._http_session.get()
        if scope is None:  # Internal calls are not authenticated HTTP requests.
            return None
        if not self.listening:
            return 503, {"ok": False, "error": "bridge_off"}
        if scope != (self._session_generation, self._token):
            return 409, {"ok": False, "error": "http_session_stale"}
        return None

    def cancel_scoped(self, payload):
        """Revoke only an immutable request or pre-accept ingress scope, never current-by-default.

        This revokes plugin authority, not the core model/provider worker. If that worker
        may still be running, acknowledge revocation but explicitly withhold cleanup.
        """
        if not isinstance(payload, dict):
            return 400, {"ok": False, "error": "cancel_scope_required"}
        request = payload.get("request_id")
        operation = payload.get("ingress_operation_id")
        generation = payload.get("session_generation")
        if (not self._scope_id(generation) or bool(request) == bool(operation)
                or not self._scope_id(request or operation)
                or set(payload) != {"session_generation", "request_id" if request else "ingress_operation_id"}):
            return 400, {"ok": False, "error": "cancel_scope_required"}
        with self._lock:
            if error := self._http_session_error():
                return error
            if generation != self._session_generation:
                return 409, {"ok": False, "error": "cancel_session_stale"}
            if operation:
                record = self._ingress_operations.get(operation)
                if record is None:
                    if len(self._ingress_operations) >= 256:
                        return 409, {"ok": False, "error": "ingress_scope_capacity"}
                    record = self._ingress_operations[operation] = {"request_id": None, "revoked": False}
                record["revoked"] = True
                request = record["request_id"]
            matches = request is not None and self._latest_request_scope == (request, generation)
            # Cleanup follows the target, not whichever request is current now.
            worker_pending = request is not None and (
                request in self._request_cleanup_pending or request in self._tts_inflight
                or (self._runs is not None and self._runs.owns(request) and self._runs.pending_cleanup()))
            if matches:
                # Retain uncertainty: clearing pending IDs cannot prove a core worker stopped.
                self._cancel_cleanup_blocked |= (bool(self._tts_inflight) or
                    (self._runs is None and (self._pending is not None
                     or request in self._request_cleanup_pending)))
                self.cancel_turn()
            active = operation in self._ingress_active if operation else any(
                key in self._ingress_active and value["request_id"] == request
                for key, value in self._ingress_operations.items())
            # Confirmation has no upload-operation ID of its own. Follow the
            # resolved request, but never turn a joined ingress into sticky poison.
            active |= request is not None and self._confirmation_active == (request, generation)
            return 200, {"ok": True, "revoked": True, "matched": matches,
                         "session_generation": generation,
                         "request_id": payload.get("request_id"),
                         "ingress_operation_id": operation,
                         "cleanup_confirmed": (not active and not worker_pending
                                               and not self._cancel_cleanup_blocked),
                         "remote_generation_stopped": False}

    def cancellation_status(self, payload):
        # Read-only POST uses the existing authenticated pre-body session fence.
        if (type(payload) is not dict or set(payload) != {'request_id', 'session_generation'}
                or not all(self._scope_id(v) for v in payload.values())):
            return 400, {"ok": False, "error": "cancel_scope_required"}
        request, generation = payload['request_id'], payload['session_generation']
        with self._lock:
            if error := self._http_session_error():
                return error
            if not self.listening or generation != self._session_generation:
                return 409, {"ok": False, "error": "cancel_session_stale"}
            evidence = ({"run_id": None, "terminal": "UNKNOWN"} if self._runs is None else
                        self._runs.owner.terminal_snapshot(request, generation))
            # Exact run settlement alone cannot certify ingress/TTS/stream cleanup.
            clean = (evidence['terminal'] == 'CLEAN' and not self._cancel_cleanup_blocked
                     and not self._worker_cleanup_pending() and not self._ingress_active
                     and self._confirmation_active is None)
            return 200, {"ok": True, **payload, **evidence, "cleanup_confirmed": clean}

    def _worker_cleanup_pending(self):
        # Called under the VoiceLink gate. TTL expiry/descriptor reset are not joins.
        return (bool(self._request_cleanup_pending) or bool(self._tts_inflight)
                or (self._runs is not None and self._runs.pending_cleanup()))

    def cancel_turn(self):
        with self._lock:
            if self._runs is not None:
                self._runs.cancel(self._latest_request_id)
            self._run_errors.clear()
            self._speech_requests.clear()
            self.disarm_sample_collection()
            self.confirmation.clear()
            self.events.clear()
            self._tts_attempted.clear()
            self._metrics["cancellations"] += 1
            self._generation += 1
            for key in self._voice_turns:
                self._voice_turns[key] = True
            self._pending = None
            self._tts_cache = None
            self.outbox.clear()
        return {"ok": True, "cancelled": True}

    @serialized_lifecycle
    def import_phone_pairing(self, path_text: str) -> dict:
        from hermes_constants import get_hermes_home
        from .pairing import store_initial, canonical_path, PairingError
        if self.listening:
            raise PairingError("PAIRING_STOP_REQUIRED")
        home = get_hermes_home()
        result = store_initial(Path(path_text).expanduser(), home)
        destination = str(canonical_path(home))
        self.ctx.set_config("connection_file", destination)
        if self.ctx.get_config("connection_file") != destination:
            raise PairingError("PAIRING_CONFIG_NOT_APPLIED")
        # This commits configuration only. Runtime application and MCP challenge
        # verification are distinct future operations, never implied by import.
        return result

    @serialized_lifecycle
    def import_pairing(self, path_text: str) -> dict:
        from hermes_constants import get_hermes_home
        if self.listening:
            raise ValueError("PAIRING_STOP_REQUIRED")
        from .pairing import canonical_path
        if self.ctx.get_config("connection_file") == str(canonical_path(get_hermes_home())):
            raise ValueError("UNIFIED_PAIRING_ACTIVE")
        self.disarm_sample_collection()
        self._home = get_hermes_home()
        pairing = _read_pairing(path_text)
        _write_private(state_path(self._home), pairing)
        return {"paired": True}

    @serialized_lifecycle
    def start(self, *, expected_epoch=None) -> dict:
        """Bind only after explicit ON and a private, one-time companion pairing."""
        with self._lock:
            if expected_epoch is not None and expected_epoch != self._startup_epoch:
                raise RuntimeError('STARTUP_REVOKED')
            if (self._cancel_cleanup_blocked or self._ingress_active
                    or self._worker_cleanup_pending()):
                raise RuntimeError("cancel_cleanup_unconfirmed")
            listening = self.listening
        if listening:
            return self.status()
        from hermes_constants import get_hermes_home
        self._home = get_hermes_home()
        token = _load_token(self._home, self.ctx.get_config("connection_file"))
        if token is None:
            raise RuntimeError("companion pairing required; import its connection file first")
        session_key = str(self.ctx.get_config("session_key", "") or "").strip()
        try:
            self._prepare_runs()
        except Exception as error:
            raise RuntimeError('RUNS_CONFIGURATION_OR_HOST_UNAVAILABLE') from None
        if self._runs is None:
            # Public surface only: a local client surface with a gateway route configured is
            # ambiguous, and the gateway route itself is proven by inject_message at use time.
            surface = _host_surface(self.ctx)
            if session_key and surface in LOCAL_SURFACES:
                raise RuntimeError("refusing CLI mode when a gateway session_key is configured")
            if not session_key and surface is not None and surface not in LOCAL_SURFACES:
                raise RuntimeError("gateway session_key is not configured")
        self._route_session_id = None
        # Keep the production port by default; 0 requests an isolated OS-assigned
        # port. The bind address remains loopback and is not configurable.
        bind_port = self.ctx.get_config("voice_bind_port", PORT)
        if type(bind_port) is not int or not 0 <= bind_port <= 65535:
            raise RuntimeError("voice_bind_port must be an integer from 0 to 65535")
        try:
            server = ContextThreadingHTTPServer((HOST, bind_port), _make_handler(self), copy_context())
        except OSError as exc:
            raise RuntimeError(f"loopback port {bind_port} is unavailable: {exc}") from exc
        with self._lock:
            # Recheck after setup: a late cleanup failure must not publish a new ON.
            blocked = (self._cancel_cleanup_blocked or bool(self._ingress_active)
                       or self._worker_cleanup_pending())
            if not blocked:
                self.disarm_sample_collection()
                self._generation += 1
                self._session_generation = secrets.token_urlsafe(18)
                self._ingress_operations.clear()
                self._latest_request_scope = None
                server.daemon_threads = True
                self._token, self._port, self._server = token, server.server_address[1], server
        if blocked:
            server.server_close()  # Never perform blocking cleanup under the owner gate.
            raise RuntimeError("cancel_cleanup_unconfirmed")
        self._thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2},
                                        name="voicelink", daemon=True)
        self._thread.start()
        return self.status()

    @serialized_lifecycle
    def stop(self) -> dict:
        with self._lock:
            if error := self._http_session_error():
                return error[1]
            # Invalidate queued startup workers before a later worker can bind.
            self._startup_epoch += 1
            self._auto_attempted = True
            # This is OFF's admission boundary, shared by audio, text, TTS and
            # stream ownership. Socket shutdown below is not an admission gate.
            server, self._server = self._server, None
            self._cancel_cleanup_blocked |= (bool(self._tts_inflight) or
                (self._runs is None and (self._pending is not None or self._worker_cleanup_pending())))
            for record in self._ingress_operations.values():
                record["revoked"] = True
            self.cancel_turn()
        try:
            # Provider/source cleanup stays worker-owned, outside the owner gate.
            if self._runs is not None:
                if not self._runs.stop():
                    with self._lock:
                        self._cancel_cleanup_blocked = True
                elif not self._runs.pending_cleanup():
                    self._runs = None
            if server is not None:
                server.shutdown()
                server.server_close()
        except BaseException:
            with self._lock:
                self._cancel_cleanup_blocked = True
            raise
        with self._lock:
            # Workers can publish cleanup evidence during shutdown. Preserve it
            # before dropping the bridge, not merely the initial snapshot.
            self._cancel_cleanup_blocked |= (self._pending is not None
                or self._worker_cleanup_pending())
            self._token = self._port = self._thread = None
        return self.status()

    def status(self) -> dict:
        from hermes_constants import get_hermes_home
        home = self._home or get_hermes_home()
        session_key = str(self.ctx.get_config("session_key", "") or "").strip()
        return {"session_generation": self._session_generation,
                "cancel_cleanup_blocked": self._cancel_cleanup_blocked or bool(self._ingress_active),
                "listening": self.listening, "paired": _load_token(home, self.ctx.get_config("connection_file")) is not None,
                "session_configured": bool(session_key) or self._runs is not None,
                "url": f"http://{HOST}:{self._port}/" if self._port else None,
                "streaming": None,  # Speech is buffered through Hermes' own text_to_speech tool.
                "document_reading": True, "public_explanations": True,
                "voice_request_pending": bool(self._pending and time.monotonic() - self._pending["created"] <= PENDING_TTL_SECONDS),
                "outbox": len(self.outbox.since(0)), "protocol": 5, "tts_segments": True, "voice_events": True, "voice_confirmation": True, "voice_model": None,
                "voice_admission": self.voice_admission(), "voice_effort": None, "last_voice_request": self._last_model, "metrics": dict(self._metrics)}

    def observe_reply(self, **kwargs: Any) -> None:
        """Read the completed-turn hook and capture only the exact injected user turn."""
        with self._lock:
            sid = str(kwargs.get("session_id") or "")
            marker = self._active_sessions.get(sid)
            if marker is not None and marker[0] == str(kwargs.get("turn_id") or ""):
                self._active_sessions.pop(sid, None)
            # The turn is over: its admission (and any authority a late callback could
            # still present) ends here, before the reply is published.
            self._admissions.pop((sid, str(kwargs.get("turn_id") or "")), None)
        if not self.listening:
            return
        user_message = kwargs.get("user_message")
        if not isinstance(user_message, str):
            return
        digest = _text_digest(user_message)
        platform = str(kwargs.get("platform") or "")
        session_key = str(self.ctx.get_config("session_key", "") or "").strip()
        target_platform = session_key.split(":")[2] if session_key.startswith("agent:") and len(session_key.split(":")) > 2 else ""
        if self._runs is None and target_platform and platform and platform != target_platform:
            return
        with self._lock:
            pending = self._pending
            if not pending:
                return
            if time.monotonic() - pending["created"] > PENDING_TTL_SECONDS:
                self._pending = None
                return
            expected_session_id = pending.get("session_id")
            actual_session_id = kwargs.get("session_id")
            if expected_session_id and actual_session_id != expected_session_id:
                return
            if not hmac.compare_digest(pending["digest"], digest):
                return
            if self._runs is not None:
                self._runs.stage_reply(pending, kwargs)
                return
            self._request_cleanup_pending.discard(pending["request_id"])
            self.events.finish(pending["request_id"])
            response = kwargs.get("assistant_response")
            if not isinstance(response, str) or not response.strip():
                self._pending = None
                self.outbox.add(request_id=pending["request_id"], session_id=kwargs.get("session_id"),
                                turn_id=kwargs.get("turn_id"), text="")
                return
            self.outbox.add(request_id=pending["request_id"], session_id=kwargs.get("session_id"),
                            turn_id=kwargs.get("turn_id"), text=response[:MAX_TEXT])
            self._pending = None

    def format_voice_message(self, spoken_text: str) -> str:
        return f"{self._voice_prefix()}{spoken_text.strip()}"

    def _voice_prefix(self) -> str:
        return _messages.text(self.ctx, "voice.prefix")

    def _admit_turn(self, key, request_id):
        """First ingress wins for one (session_id, turn_id); called under ``self._lock``.

        A key without both IDs has no trustworthy lifetime boundary and is never admitted.
        The table is bounded: an evicted turn loses its record and then fails closed.
        """
        if not (isinstance(key, tuple) and len(key) == 2 and all(key)):
            return None
        record = self._admissions.get(key)
        if record is not None:
            return record
        record = _Admission(key, request_id)
        self._admissions[key] = record
        while len(self._admissions) > MAX_ADMISSIONS:
            self._admissions.pop(next(iter(self._admissions)))
        return record

    def _admission_record(self, key):
        if not (isinstance(key, tuple) and len(key) == 2 and all(key)):
            return None
        return self._admissions.get((str(key[0]), str(key[1])))

    def _admission_owner(self, key):
        """('none'|'record', owner) — a voice record is positive evidence only.

        'none' means this turn has no admission record at all (never bound by the turn-start
        hook, already finished, or evicted). 'record' with a None owner is a text turn that
        WAS bound; with a request id it is a live voice turn; with the revoked sentinel it is
        a voice envelope whose identity did not match.
        """
        record = self._admission_record(key)
        return ('record', record.owner) if record is not None else ('none', None)

    def _route_matches(self) -> bool:
        """The turn running now belongs to the configured route, as far as public context tells.

        The injected text also carries a per-request nonce, so a matching digest alone already
        requires that exact request. These checks reject a known-different route outright.
        """
        session_key = str(self.ctx.get_config("session_key", "") or "").strip()
        turn_key = _turn_session_key()
        if session_key and turn_key and turn_key != session_key:
            return False
        surface = _host_surface(self.ctx)
        parts = session_key.split(":")
        platform = parts[2] if session_key.startswith("agent:") and len(parts) > 2 else ""
        if surface and platform and surface != platform:
            return False
        if surface in LOCAL_SURFACES and session_key:
            return False
        return True

    def _admitted_owner(self, key):
        """None is positive text admission; every unknown/closed identity denies."""
        state, owner = self._admission_owner(key)
        return owner if state == 'record' else '__revoked_voice_owner__'

    def prepare_voice_turn(self, **kwargs: Any) -> Optional[str]:
        """Bind the exact injected voice utterance to its live turn and add a bounded safety note."""
        user_message = kwargs.get("user_message")
        with self._lock:
            sid = str(kwargs.get("session_id") or "")
            marker = (str(kwargs.get("turn_id") or ""), object())
            self._active_sessions[sid] = marker
            if self._runs is not None:
                self._runs.note_turn(user_message, sid, marker)
        if not isinstance(user_message, str):
            with self._lock:
                self._admit_turn((str(kwargs.get("session_id") or ""), str(kwargs.get("turn_id") or "")),
                                 '__revoked_voice_owner__')
            return None
        digest = _text_digest(user_message)
        session_id = str(kwargs.get("session_id") or "")
        turn_id = str(kwargs.get("turn_id") or "")
        with self._lock:
            pending = self._pending
            if not pending or not hmac.compare_digest(pending["digest"], digest):
                # A replayed voice envelope with no live request is not text ingress.
                owner = (REVOKED_OWNER if user_message.startswith(_messages.all_texts("voice.prefix"))
                         else None)
                self._admit_turn((session_id, turn_id), owner)
                return None
            expected_session_id = pending.get("session_id")
            if expected_session_id and str(expected_session_id) != session_id:
                self._admit_turn((session_id, turn_id), REVOKED_OWNER)
                return None
            if self._runs is None and not self._route_matches():
                self._admit_turn((session_id, turn_id), REVOKED_OWNER)
                return None
            record = self._admission_record((session_id, turn_id))
            if record is not None and record.owner != pending['request_id']:
                return None
            if pending.get("turn_id") is not None:
                if ((pending.get("session_id"), pending["turn_id"]) != (session_id, turn_id)
                        or record is None or pending.get("admission") is not record):
                    self._admit_turn((session_id, turn_id), REVOKED_OWNER)
                    return None
            else:
                record = self._admit_turn((session_id, turn_id), pending['request_id'])
                if record is None:
                    return None
                pending["admission"] = record
            pending["session_id"] = session_id
            pending["turn_id"] = turn_id
            if self._runs is None:
                self._route_session_id = session_id
            self._voice_turns[(session_id, turn_id)] = False
            while len(self._voice_turns) > 64:
                self._voice_turns.pop(next(iter(self._voice_turns)))
        root=workspace(self._home, pending["request_id"]) if self._home else None
        # Voice turns may not run shell commands, so the clock is given, not looked up.
        now = datetime.now().astimezone().strftime("%Y-%m-%d (%a) %H:%M %Z")
        context = (_messages.text(self.ctx, "voice.hook")
                   + _messages.text(self.ctx, "voice.now", now=now)
                   + _messages.text(self.ctx, "voice.progress"))
        if root:
            context += _messages.text(self.ctx, "voice.documents", root=root)
        return context

    def guard_voice_tool(self, **kwargs: Any) -> Optional[dict]:
        """Block every non-read-only tool only during the exact matched phone voice turn."""
        tool_name = str(kwargs.get("tool_name") or "")
        session_id = str(kwargs.get("session_id") or "")
        turn_id = str(kwargs.get("turn_id") or "")
        with self._lock:
            key = (session_id, turn_id)
            state, owner = self._admission_owner(key)
            # No record and no phone request in flight: this is not a voice turn (for example a
            # nested execute_code call, which carries no turn IDs). Ordinary tools run as they
            # would without the plugin; phone handlers still require their own admission.
            if state == 'none' and not self._pending:
                return None
            # A text turn the hook admitted carries state='record' with owner=None and passes.
            # Any voice-shaped turn without a usable record yields state='none' and must fail
            # closed, so this guard never becomes an owner-bypass.
            admitted_owner = owner if state == 'record' else "__revoked_voice_owner__"
            if admitted_owner is not None and self.voice_request(session_id, turn_id) != admitted_owner:
                if tool_name not in VOICE_READ_ONLY_TOOLS:
                    return {"action": "block", "message": "Turn admission unavailable; do not continue."}
            if admitted_owner is None:
                return None
            if self._voice_turns.get(key):
                return {"action": "block", "message": "Voice request cancelled; do not continue."}
        allowed = set(VOICE_READ_ONLY_TOOLS)
        if self.ctx.get_config("control_enabled", False) is True:
            allowed.add("phone_act")
            # A plan is one deterministic call instead of a click per step, so it is no wider
            # than phone_act. The handlers still enforce admission and the device still judges
            # every step, so allowing it here does not confer any extra authority.
            allowed.add("phone_run_plan")
            allowed.add("phone_profiles")
        args = kwargs.get("args") or {}
        request_id = self.voice_request(session_id,turn_id)
        if request_id and self._home:
            if tool_name in {"voice_draft", "voice_progress", "phone_speech"}: return None
            if tool_name == "terminal" and arithmetic_command(args): return None
            if file_allowed(tool_name,args,workspace(self._home,request_id)): return None
        if tool_name in allowed:
            return None
        if tool_name == "tool_describe":
            names = args.get("names", [])
            if isinstance(names, str): names = [names]
            if names and all(n in allowed | {"read_file","write_file","terminal","voice_draft","voice_progress","phone_speech"} for n in names): return None
        if tool_name == "tool_call":
            calls = args.get("calls", [])
            if isinstance(calls, list) and calls and all(isinstance(c, dict) and c.get("name") not in {"tool_call", "execute_code"}
                    and self.guard_voice_tool(tool_name=c.get("name"), args=c.get("arguments"), session_id=session_id, turn_id=turn_id) is None for c in calls): return None
        if tool_name in {"tool_search", "hermes_tool_search"}:
            return None
        return {"action": "block", "message": _messages.text(self.ctx, "voice.blocked")}

    def _inject(self, text: str, *, _collection_ingress: bool = False,
                _ingress_operation_id=None, _speech_snapshot=None) -> tuple[int, dict]:
        with self._lock:
            if error := self._http_session_error():
                return error
            generation = self._generation
        if not (text or "").strip():
            return 400, {"ok": False, "error": "empty_text"}
        spoken_text = re.sub(r"^(?:헤르메스|hermes)[\s,!.?，。]*", "", (text or "").strip(), flags=re.I).strip()
        # Recognising STOP or a bare wake word injects nothing, so it does not depend on the
        # conversation route being available.
        if spoken_text.lower().strip(" .!?") in {"스탑", "스톱", "stop"}:
            # STOP must arrive at /cancel with the captured request/ingress scope.
            # An uploaded transcript has no authority over another accepted request.
            return 200, {"ok": True, "accepted": False, "command_reason": "stop_only"}
        if not spoken_text:
            return 200, {"ok": True, "accepted": False, "command_reason": "wake_only"}
        admission = self.voice_admission()
        if not admission["ok"]:
            return 503, {"ok": False, "error": admission["error"]}
        if self._cancel_cleanup_blocked:
            return 409, {"ok": False, "error": "cancel_cleanup_unconfirmed"}
        # A user-confirmed mapping repairs recurring recognizer mistakes before the turn
        # exists. It never guesses: only pairs the user stated are applied, and a damaged
        # store leaves the utterance untouched.
        if self._home is not None:
            from .voice_corrections import apply as apply_corrections
            spoken_text, _ = apply_corrections(self._home, spoken_text)
        if len(spoken_text) + len(self._voice_prefix()) > MAX_TEXT:
            return 413, {"ok": False, "error": "text_too_long"}
        text = self.format_voice_message(spoken_text)
        if not self.listening:
            return 503, {"ok": False, "error": "bridge_off"}
        session_key = str(self.ctx.get_config("session_key", "") or "").strip() or None
        home = self._home
        if home is None:
            return 503, {"ok": False, "error": "profile_scope_unavailable"}
        # Gateway/CLI: the session is bound later from the turn-start hook's own session_id
        # (matched by this request's nonce); nothing is resolved from core state here.
        expected_session_id = self._runs.session_id if self._runs is not None else None
        with self._lock:
            busy_ids = ({expected_session_id, self._runs.window.current} if self._runs is not None
                        else {self._route_session_id})
            if any(busy and str(busy) in self._active_sessions for busy in busy_ids):
                return 409, {"ok": False, "error": "conversation_busy"}
        speech_snapshot = None
        if self._runs is not None:
            from .speech_runtime import SpeechRuntimeError
            from .speech_settings import SettingsError
            try:
                runtime = self.speech_runtime()
                speech_snapshot = runtime.snapshot() if _speech_snapshot is None else _speech_snapshot
                runtime.check_tts(speech_snapshot)
            except (SpeechRuntimeError, SettingsError) as error:
                return 503, {'ok': False, 'error': str(error)}
        request_id = secrets.token_urlsafe(18)
        # Distinguish equal utterances without persisting their text or restoring approval;
        # the nonce is also what binds the reply to this request's own session_id.
        text += "\n[phone_request_id=" + request_id + "]"
        with self._lock:
            if error := self._http_session_error():
                return error
            if (self._cancel_cleanup_blocked or not self.listening
                    or generation != self._generation
                    or self._worker_cleanup_pending()
                    or self._ingress_active - ({_ingress_operation_id}
                                               if _collection_ingress else set())):
                return 409, {"ok": False, "error": "cancel_cleanup_unconfirmed"}
            if self._pending and time.monotonic() - self._pending["created"] <= PENDING_TTL_SECONDS:
                return 409, {"ok": False, "error": "voice_turn_in_progress"}
            collection = self._sample_collection
            if collection is not None and not (_collection_ingress is True
                    and collection.generation == self._generation
                    and time.monotonic() < collection.deadline):
                self.disarm_sample_collection()
                collection = None
            self._generation += 1
            if collection is not None:
                collection.generation = self._generation
            self._latest_request_id = request_id
            self._speech_requests.clear()
            if speech_snapshot is not None:
                self._speech_requests[request_id] = speech_snapshot
            self._latest_request_scope = (request_id, self._session_generation)
            if _ingress_operation_id is not None:
                self._ingress_operations[_ingress_operation_id]["request_id"] = request_id
            self._pending = {"request_id": request_id, "digest": _text_digest(text),
                             "session_id": expected_session_id, "created": time.monotonic()}
            self._request_cleanup_pending.add(request_id)
            self.events.begin(request_id)
        try:
            if self._runs is not None:
                with self._lock:
                    pending = self._pending
                    if not pending or pending['request_id'] != request_id:
                        self._request_cleanup_pending.discard(request_id)
                        return 409, {'ok': False, 'error': 'voice_cancelled'}
                    self._runs.submit(text, pending)
                accepted = True
            else:
                accepted = bool(self.ctx.inject_message(text, role="user", session_key=session_key))
        except Exception:
            accepted = False
        if not accepted:
            with self._lock:
                self._request_cleanup_pending.discard(request_id)
                if self._pending and self._pending["request_id"] == request_id:
                    self._pending = None
                    self.events.finish(request_id)
            return 503, {"ok": False, "error": "gateway_rejected_injection"}
        with self._lock:
            self._metrics["voice_requests"] += 1
        return 200, {"ok": True, "accepted": True, "request_id": request_id,
                     "session_generation": self._session_generation,
                     "ingress_operation_id": _ingress_operation_id,
                     "delivery": "runs" if self._runs is not None else "queued"}

    def _transcribe(self, path: str, snapshot) -> dict:
        """Speech-to-text on the phone through this plugin's Android provider.

        Runs delivery routes through the request's speech snapshot (see speech_runtime);
        queued delivery has no snapshot and uses the plugin's stt_model/stt_language.
        """
        if self._runs is not None:
            return self.speech_runtime().transcribe(path, snapshot)
        from .android_stt import AndroidTranscriptionProvider
        return AndroidTranscriptionProvider(self.ctx).transcribe(
            path, model=self.ctx.get_config("stt_model", None),
            language=self.ctx.get_config("stt_language", None))

    def _synthesize(self, text: str, output_path: str) -> Any:
        """Text-to-speech through Hermes' public tool dispatch.

        The provider is whatever Hermes' ``tts.provider`` selects (for example the Nous
        Portal subscription through the Tool Gateway); the plugin holds no TTS credential.
        """
        raw = self.ctx.dispatch_tool("text_to_speech", {"text": text, "output_path": output_path})
        return json.loads(raw) if isinstance(raw, str) else raw

    def say(self, text: str) -> tuple[int, dict]:
        return self._inject(text)

    def bind_tool_voice(self, args, next_call, **kwargs):
        key = (str(kwargs.get("session_id") or ""), str(kwargs.get("turn_id") or ""))
        request = self.voice_request(kwargs.get("session_id"), kwargs.get("turn_id"))
        with self._lock:
            record = self._admission_record(key)
            owner = self._admitted_owner(key)
        # Keep the admission owner, not a fresh owner reconstructed from current pending state.
        # A turn with no live voice request has no voice owner at all: publish None so tool
        # handlers can tell "not a voice turn" apart from "a voice turn that lost its identity".
        if not request:
            owner = None
        elif owner is None or owner != request:
            owner = "__revoked_voice_owner__"
        def with_evidence(result):
            if request:
                value = evidence_result(kwargs.get("tool_name"), args, result)
                if value is not None:
                    receipt = self.events.record_evidence(request, kwargs.get("tool_name"))
                    if receipt:
                        value["voice_evidence_id"] = receipt
                        return json.dumps(value, ensure_ascii=False) if isinstance(result, str) else value
            return result

        token = self.tool_voice_request.set(owner)
        key_token = self.tool_turn_key.set(key)
        phone_span = None
        async_handoff = False
        try:
            if request:
                kind = TOOL_STATUS.get(kwargs.get("tool_name"), "")
                if kind == "phone":
                    phone_span = self.events.begin_phone_span(request)
                else:
                    self.events.emit(request, kind)
            result = next_call(args)
            # Public middleware can wrap an async handler: keep owner during its actual execution,
            # not merely during coroutine creation. Never leak it into the calling context.
            import inspect
            if inspect.isawaitable(result):
                async def awaited():
                    # Recheck at execution, not only coroutine creation. Context
                    # transfer to another runtime must not transfer its authority.
                    # The same record object must still be live: a finished/released turn,
                    # or a later turn that reused the IDs, does not carry this authority.
                    with self._lock:
                        valid = (record is not None and self._admission_record(key) is record
                                 and self._admitted_owner(key) == owner)
                    inner = self.tool_voice_request.set(owner if valid else REVOKED_OWNER)
                    inner_key = self.tool_turn_key.set(key if valid else None)
                    try:
                        return with_evidence(await result)
                    finally:
                        self.events.end_phone_span(phone_span)
                        self.tool_turn_key.reset(inner_key)
                        self.tool_voice_request.reset(inner)
                async_handoff = True
                return awaited()
            return with_evidence(result)
        finally:
            if not async_handoff:
                self.events.end_phone_span(phone_span)
            self.tool_turn_key.reset(key_token)
            self.tool_voice_request.reset(token)

    def voice_request(self, session_id, turn_id):
        with self._lock:
            key = (str(session_id or ""), str(turn_id or ""))
            p = self._pending
            if (self._voice_turns.get(key) is not False or not p
                    or time.monotonic() - p["created"] > PENDING_TTL_SECONDS
                    or p.get("session_id") != key[0] or str(p.get("turn_id")) != key[1]):
                return None
            return p["request_id"]

    def begin_confirmation(self, request_id, value):
        with self._lock:
            if not self._pending or self._pending["request_id"] != request_id:
                raise ValueError("voice_request_cancelled")
            event = self.confirmation.begin(request_id, value["request_id"],
                value["confirmation_prompt"], value["voice_phrase"], value["action_digest"])
            self.outbox.add(request_id=event["audio_id"], session_id=self._pending["session_id"],
                turn_id=self._pending.get("turn_id"), text=event["prompt"])
            return event

    def confirmation_metadata(self, request_id, action_id):
        try:
            event = self.confirmation.get(request_id, action_id)
            return 200, {"ok": True, "audio_id": event["audio_id"], "action_id": action_id,
                         "digest": event["digest"], "state": event["state"]}
        except ValueError as exc:
            return 409, {"ok": False, "error": str(exc)}

    def hear_confirmation(self, audio, suffix, request_id, action_id, nonce):
        if not audio or len(audio) > MAX_BODY or suffix not in AUDIO_SUFFIXES:
            return 400, {"ok": False, "error": "invalid_confirmation_audio"}
        with self._lock:
            if error := self._http_session_error():
                return error
            if not self.listening:
                return 503, {"ok": False, "error": "bridge_off"}
            if self._cancel_cleanup_blocked:
                return 409, {"ok": False, "error": "cancel_cleanup_unconfirmed"}
            if not self._audio_lock.acquire(blocking=False):
                return 409, {"ok": False, "error": "audio_busy"}
            reserved = False
            try:
                claimed = self.confirmation.claim_audio(request_id, action_id, nonce)
                self._confirmation_active = (claimed["request_id"], self._session_generation)
                self._ingress_active.add(None)
                generation = self._generation
                reserved = True
            except ValueError as exc:
                code = exc.args[0] if len(exc.args) == 1 else None
                if isinstance(code, str) and code in (
                        "confirmation_unknown", "confirmation_expired",
                        "confirmation_replayed_or_unarmed"):
                    return 409, {"ok": False, "error": code}
                return 502, {"ok": False, "error": "confirmation_stt_failed"}
            except Exception:
                return 502, {"ok": False, "error": "confirmation_stt_failed"}
            finally:
                # Transfer ownership only after atomic claim + reservation setup.
                # Failed setup (including BaseException) must never strand a mutex
                # or publish a worker that never reached provider/file I/O.
                if not reserved:
                    try:
                        self._confirmation_active = None
                        self._ingress_active.discard(None)
                    finally:
                        self._audio_lock.release()
        try:
            failed = False
            try:
                snapshot = self.speech_snapshot(request_id) if self._runs is not None else None
                # Own deletion explicitly: a contextmanager's unlink exception must
                # not be mistaken for an ordinary STT failure with successful purge.
                handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
                path = handle.name
                try:
                    with handle:
                        handle.write(audio)
                    result = self._transcribe(path, snapshot)
                    result = self._validated_stt_result(result)
                    transcript = result.get("transcript", "") if result.get("success") else ""
                finally:
                    try:
                        os.unlink(path)
                    except OSError:
                        with self._lock:
                            self._cancel_cleanup_blocked = True
            except Exception:
                # Provider/upload ValueError payloads are not confirmation protocol errors.
                failed = True
            with self._lock:
                if not self.listening or generation != self._generation:
                    return 409, {"ok": False, "error": "voice_cancelled"}
                if error := self._http_session_error():
                    return error
                if failed or self._cancel_cleanup_blocked:
                    self.confirmation.clear(action_id)
                    return 502, {"ok": False, "error": "confirmation_stt_failed"}
                try:
                    return 200, {"ok": True, **self.confirmation.finish_audio(request_id, action_id, transcript)}
                except ValueError as exc:
                    return 409, {"ok": False, "error": str(exc)}
        finally:
            with self._lock:
                self._confirmation_active = None
                self._ingress_active.discard(None)
                self._audio_lock.release()

    def hear(self, audio: bytes, suffix: str, *, ingress_operation_id=None,
             session_generation=None) -> tuple[int, dict]:
        # Legacy internal fixtures may omit scope; HTTP always requires both fields.
        with self._lock:
            if error := self._http_session_error():
                return error
            if not self.listening:
                return 503, {"ok": False, "error": "bridge_off"}
            if self._cancel_cleanup_blocked or self._worker_cleanup_pending():
                return 409, {"ok": False, "error": "cancel_cleanup_unconfirmed"}
            if ingress_operation_id is not None or session_generation is not None:
                if not self._scope_id(ingress_operation_id) or not self._scope_id(session_generation):
                    return 400, {"ok": False, "error": "ingress_scope_required"}
                if session_generation != self._session_generation:
                    return 409, {"ok": False, "error": "ingress_session_stale"}
                if ingress_operation_id in self._ingress_operations:
                    return 409, {"ok": False, "error": "ingress_revoked_or_reused"}
                if len(self._ingress_operations) >= 256:
                    return 409, {"ok": False, "error": "ingress_scope_capacity"}
            if not self._audio_lock.acquire(blocking=False):
                return 409, {"ok": False, "error": "audio_busy"}
            if ingress_operation_id is not None:
                self._ingress_operations[ingress_operation_id] = {"request_id": None, "revoked": False}
            # Also account for legacy unscoped callers until their STT/purge exits.
            self._ingress_active.add(ingress_operation_id)
        try:
            return self._hear(audio, suffix, ingress_operation_id=ingress_operation_id)
        finally:
            with self._lock:
                self._ingress_active.discard(ingress_operation_id)
                self._audio_lock.release()

    def _validated_stt_result(self, result):
        """Keep validated native cleanup uncertainty after the HTTP worker exits.

        This fact is independent of the recognition reason and transcript.
        An invalid/legacy envelope is refused by the existing validator but is
        not invented evidence of a native cleanup failure.
        """
        if result.get("provider") != "android-native":
            return result
        from .stt_outcome import validated
        checked = validated(result)
        if checked.get("stt_cleanup_confirmed") is False:
            # The HTTP/STT call returning cannot certify joined native cleanup.
            # Scoped cancellation and a later ON must not erase this fact.
            with self._lock:
                self._cancel_cleanup_blocked = True
        return checked

    def _hear(self, audio: bytes, suffix: str, *, ingress_operation_id=None) -> tuple[int, dict]:
        """Use only Hermes' configured STT; delete the temporary upload in every outcome."""
        admission = self.voice_admission()
        if not admission["ok"]:
            return 503, {"ok": False, "error": admission["error"]}
        if not audio:
            return 400, {"ok": False, "error": "empty_audio"}
        if suffix not in AUDIO_SUFFIXES:
            return 400, {"ok": False, "error": "unsupported_format"}
        if len(audio) > MAX_BODY:
            return 413, {"ok": False, "error": "body_too_large"}
        with self._lock:
            if error := self._http_session_error():
                return error
            generation = self._generation
            self._metrics["audio_requests"] += 1
        snapshot = self.speech_runtime().snapshot() if self._runs is not None else None
        handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        path = handle.name
        try:
            with handle:
                handle.write(audio)
            result = self._transcribe(path, snapshot)
        finally:
            try:
                os.unlink(path)
            except OSError:
                # A failed private-upload purge is sticky cleanup uncertainty, not success.
                with self._lock:
                    self._cancel_cleanup_blocked = True
        stt_fields = {}
        result = self._validated_stt_result(result)
        if result.get("provider") == "android-native":
            from .stt_outcome import bridge_fields
            stt_fields = bridge_fields(result)
        if not result.get("success"):
            return 502, {"ok": False, "error": "transcription_failed", **stt_fields}
        transcript = (result.get("transcript") or "").strip()
        if not transcript:
            return 200, {"ok": True, "accepted": False, "no_speech": True}
        with self._lock:
            if (not self.listening
                    or (ingress_operation_id is None and generation != self._generation)
                    or (ingress_operation_id is not None
                        and self._ingress_operations[ingress_operation_id]["revoked"])):
                return 409, {"ok": False, "error": "voice_cancelled"}
            if error := self._http_session_error():
                return error
            # Exact FULL provider transcript, before stripping/wake-prefix handling.
            self._capture_sample_upload(audio, suffix, generation, result.get("transcript"))
            code, response = self._inject(transcript, _collection_ingress=True,
                                         _ingress_operation_id=ingress_operation_id, _speech_snapshot=snapshot)
            if code == 200:
                response.update(stt_fields)
            return code, response

    def reply(self, request_id: str, protocol: int = 3, after: int = 0) -> tuple[int, dict]:
        if not request_id or len(request_id) > 80:
            return 400, {"ok": False, "error": "invalid_request_id"}
        with self._lock:
            error = self._run_errors.get(request_id)
        if error is not None:
            return 200, {'ok': True, 'ready': True, 'error': error}
        item = self.outbox.get(request_id)
        if item is not None:
            if not item["text"]:
                return 200, {"ok": True, "ready": True, "error": "empty_assistant_response"}
            return 200, {"ok": True, "ready": True, "item": item}
        with self._lock:
            pending = self._pending
            if pending and pending["request_id"] == request_id:
                if time.monotonic() - pending["created"] <= PENDING_TTL_SECONDS:
                    result = {"ok": True, "ready": False, "state": "pending"}
                    if protocol >= 4:
                        current = self.events.get(request_id) if self.confirmation.pending is None else None
                        # A cursor suppresses duplicates, not proof that an old event is still live.
                        # Let the actual device invalidate already downloaded stale progress.
                        result["progress_seq"] = current["seq"] if current else 0
                        event = current if current and current["seq"] > after else None
                        if event:
                            if self.outbox.get(event["audio_id"]) is None:
                                self.outbox.add(request_id=event["audio_id"],session_id=pending.get("session_id"),
                                                turn_id=(pending.get("turn_id") or
                                                    ('acceptance-' + request_id if event['kind'] == 'accepted' else None)),
                                                text=event["text"],progress_owner=request_id)
                            result["event"] = event
                    return 200, result
                self._pending = None
        return 404, {"ok": False, "error": "request_expired_or_unknown"}

    def _stale_progress(self, item):
        owner = item.get("progress_owner")
        if owner is None:
            return False
        event = self.events.get(owner)
        return (not event or event["audio_id"] != item["request_id"]
                or self.confirmation.pending is not None)

    def audio_segment_count(self, request_id):
        with self._lock:
            cache = self._tts_cache
            return len(cache['parts']) if cache and cache['request_id'] == request_id else 0

    def tts(self, request_id: str, segment: int = 0, protocol: int = 4) -> tuple[int, Any, str]:
        """Synthesize once, then serve every bounded part in provider order."""
        if not isinstance(request_id, str) or not request_id or len(request_id) > 80:
            return 400, {"ok": False, "error": "invalid_request_id"}, ""
        if type(segment) is not int or not 0 <= segment < MAX_SEGMENTS or type(protocol) is not int:
            return 400, {"ok": False, "error": "invalid_audio_segment"}, ""
        now = time.monotonic()
        with self._lock:
            generation = self._generation
            item = self.outbox.get(request_id)
            if item is None or not item.get("text"):
                return 404, {"ok": False, "error": "reply_unavailable"}, ""
            if generation != self._generation or not self.listening:
                return 410, {"ok": False, "error": "voice_cancelled"}, ""
            if self._stale_progress(item):
                return 410, {"ok": False, "error": "progress_superseded"}, ""
            cached = self._tts_cache
            if cached and now - cached["at"] > TTS_CACHE_TTL_SECONDS:
                self._tts_cache = None
                cached = None
            if cached and cached["request_id"] == request_id:
                return cached_segment(cached['parts'], segment, protocol)
            if request_id in self._tts_inflight or self._tts_inflight:
                return 202, {"ok": True, "ready": False, "state": "generating"}, ""
            if segment != 0:
                return 409, {"ok": False, "error": "tts_initial_segment_required"}, ""
            if request_id in self._tts_attempted:
                return 410, {"ok": False, "error": "tts_not_retried"}, ""
            self._tts_attempted.add(request_id)
            if len(self._tts_attempted) > 64:
                retained = {v["request_id"] for v in self.outbox.since(0)}
                self._tts_attempted.intersection_update(retained)
                self._tts_attempted.add(request_id)
            self._tts_inflight.add(request_id)
        try:
            with tempfile.TemporaryDirectory(prefix="voicelink-") as temp_dir:
                root = Path(temp_dir).resolve()
                output_path = root / "assistant.mp3"
                try:
                    result = self._synthesize(item["text"], str(output_path))
                except (TypeError, ValueError):
                    return 502, {"ok": False, "error": "tts_failed"}, ""
                if not isinstance(result, dict) or result.get("success") is not True:
                    return 502, {"ok": False, "error": "tts_failed"}, ""
                paths = result.get("file_paths")
                if paths is None:
                    paths = [result.get("file_path")]
                try:
                    parts = read_segments(root, paths, MAX_TTS_BODY)
                except ValueError as exc:
                    code = 'tts_audio_too_large' if str(exc) == 'tts_audio_too_large' else 'tts_output_invalid'
                    return (413 if code == 'tts_audio_too_large' else 502), {"ok": False, "error": code}, ""
                except OSError:
                    return 502, {"ok": False, "error": "tts_output_invalid"}, ""
            with self._lock:
                if not self.listening or generation != self._generation:
                    return 503, {"ok": False, "error": "bridge_off"}, ""
                if self._stale_progress(item):
                    return 410, {"ok": False, "error": "progress_superseded"}, ""
                self._tts_cache = {"request_id": request_id, "parts": parts, "at": time.monotonic()}
            return cached_segment(parts, segment, protocol)
        except Exception:
            return 502, {"ok": False, "error": "tts_failed"}, ""
        finally:
            with self._lock:
                self._tts_inflight.discard(request_id)


def _authorized(header: Optional[str], token: Optional[str]) -> bool:
    return bool(token and header and header.startswith("Bearer ")
                and hmac.compare_digest(header[7:], token))


class ContextThreadingHTTPServer(ThreadingHTTPServer):
    """Carry Hermes' active profile context into each concurrent HTTP request."""
    def __init__(self, server_address, RequestHandlerClass, request_context):
        self._request_context = request_context
        super().__init__(server_address, RequestHandlerClass)

    def process_request_thread(self, request, client_address):
        context = self._request_context.copy()
        context.run(super().process_request_thread, request, client_address)


def _make_handler(link: VoiceLink):
    class Handler(BaseHTTPRequestHandler):
        server_version = "hermes-voicelink/4"
        protocol_version = "HTTP/1.1"

        def log_message(self, *args: Any) -> None:
            return

        def _send(self, code: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _send_audio(self, code: int, data: bytes, suffix: str, segment: int = 0, count: int = 1) -> None:
            self.send_response(code)
            self.send_header("Content-Type", AUDIO_MIME_TYPES[suffix])
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Audio-Format", suffix)
            self.send_header("X-Audio-Segment", str(segment))
            self.send_header("X-Audio-Segments", str(count))
            self.end_headers()
            self.wfile.write(data)

        def _deny(self) -> bool:
            # Authentication and identity capture are one owner-locked operation.
            # No body/socket I/O under this gate, including unauthorized responses.
            with link._lock:
                authorized = _authorized(self.headers.get("Authorization"), link._token)
                self._authenticated_session = ((link._session_generation, link._token)
                                               if authorized else None)
            if not authorized:
                self._send(401, {"ok": False, "error": "unauthorized"})
                return True
            return False

        def _read_body(self, limit=MAX_BODY) -> Optional[bytes]:
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                self._send(400, {"ok": False, "error": "bad_length"})
                return None
            if length < 0 or length > limit:
                self._send(413, {"ok": False, "error": "body_too_large"})
                return None
            if not length:
                return b""
            body = self.rfile.read(length)
            if len(body) != length:
                self._send(400, {"ok": False, "error": "incomplete_body"})
                return None
            return body

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlsplit(self.path)
            if parsed.path == "/health":
                self._send(200, {"ok": True, "listening": link.listening})
                return
            if self._deny():
                return
            if parsed.path == "/reply":
                query = parse_qs(parsed.query, keep_blank_values=True)
                request_id = (query.get("request_id") or [""])[0]
                if request_id:
                    try:
                        protocol = int((query.get("protocol") or ["3"])[0])
                        after = int((query.get("after_seq") or ["0"])[0])
                    except ValueError:
                        self._send(400, {"ok": False, "error": "invalid_event_cursor"})
                        return
                    code, payload = link.reply(request_id, protocol, max(0, after))
                    self._send(code, payload)
                    return
                try:
                    since = max(0, int((query.get("since") or ["0"])[0]))
                except ValueError:
                    since = 0
                self._send(200, {"ok": True, "items": link.outbox.since(since)})
                return
            if parsed.path == "/tts":
                query = parse_qs(parsed.query, keep_blank_values=True)
                request_id = (query.get("request_id") or [""])[0]
                try:
                    protocol = int((query.get("protocol") or ["4"])[0])
                    segment = int((query.get("segment") or ["0"])[0])
                except ValueError:
                    self._send(400, {"ok": False, "error": "invalid_audio_segment"})
                    return
                code, payload, suffix = link.tts(request_id, segment=segment, protocol=protocol)
                if isinstance(payload, bytes):
                    count = link.audio_segment_count(request_id)
                    if count == 0:
                        self._send(410, {"ok": False, "error": "reply_unavailable"})
                        return
                    self._send_audio(code, payload, suffix, segment, count)
                else:
                    self._send(code, payload)
                return
            if parsed.path == "/confirmation":
                query = parse_qs(parsed.query, keep_blank_values=True)
                code, payload = link.confirmation_metadata((query.get("request_id") or [""])[0],
                                                          (query.get("action_id") or [""])[0])
                self._send(code, payload)
                return
            if parsed.path == "/v1/capabilities":
                self._send(200, link.execution_capabilities())
                return
            if parsed.path == "/v1/learning-status":
                from . import phone_learning as learning
                from .backend import load_connection
                try:
                    device = load_connection(link.ctx.get_config("connection_file"))[0]["instance_id"]
                    rows = learning.list_routes(link._home, device)
                    self._send(200, {"ok": True, "schema_version": 1,
                        "enabled": link.ctx.get_config("learning_enabled", True) is True,
                        "routes": len(rows), "paused": sum(r["status"] == "paused" for r in rows),
                        "repeatedly_verified": sum(r["status"] == "repeatedly_verified" for r in rows)})
                except Exception:
                    self._send(503, {"ok": False, "error": "LEARNING_STATUS_UNAVAILABLE"})
                return
            if parsed.path == '/v1/model-picker':
                # This route is authenticated by do_GET/_deny and read-only. Never
                # bridge arbitrary commands or infer operator authority from UI text.
                from .voice_execution_settings import ExecutionSettings
                from .voice_model_picker import picker_snapshot
                from .speech_settings import SettingsError
                try:
                    self._send(200, picker_snapshot(ExecutionSettings(link._home), link))
                except SettingsError as error:
                    self._send(409 if str(error) == 'SETTINGS_CONFLICT' else 503,
                               {'ok': False, 'error': str(error)})
                except Exception:
                    self._send(503, {'ok': False, 'error': 'EXECUTION_CATALOG_FAILED'})
                return
            if parsed.path == "/v1/voice-route":
                self._send(200, link.voice_admission())
                return
            if parsed.path == '/v1/model-catalog' and link._runs is not None:
                try:
                    self._send(200, {'ok': True, **link._runs.owner.client.model_options()})
                except Exception:
                    self._send(503, {'ok': False, 'error': 'RUNS_CATALOG_UNAVAILABLE'})
                return
            if parsed.path in {"/v1/model-catalog", "/v1/conversations"}:
                self._send(503, {"ok": False, "schema_version": 1,
                    "error": "HOST_MODEL_CATALOG_UNAVAILABLE" if parsed.path.endswith("model-catalog")
                             else "HOST_CONVERSATION_CATALOG_UNAVAILABLE"})
                return
            if parsed.path == "/status":
                self._send(200, {"ok": True, **link.status()})
                return
            self._send(404, {"ok": False, "error": "unknown_path"})

        def do_POST(self) -> None:  # noqa: N802
            if self._deny():
                return
            token = link._http_session.set(self._authenticated_session)
            try:
                self._post_authenticated()
            finally:
                link._http_session.reset(token)

        def _post_authenticated(self) -> None:
            parsed = urlsplit(self.path)
            operator = parsed.path == "/sample-collection"
            if operator and (self.client_address[0] != "127.0.0.1" or parsed.query):
                self._send(403, {"ok": False, "error": "local_operator_only"})
                return
            body = self._read_body(limit=256 if operator else MAX_BODY)
            if body is None:
                return
            with link._lock:
                error = link._http_session_error()
            if error:
                self._send(*error)
                return
            if operator:
                try:
                    payload = json.loads(body.decode("utf-8"))
                    with link._lock:
                        error = link._http_session_error()
                        if error:
                            code, result = error
                        elif payload == {"action": "arm", "consent": "fixed-two-phrase-120s"}:
                            code, result = 200, {"ok": True, **link.arm_sample_collection(consent=True)}
                        elif payload == {"action": "disarm"}:
                            link.disarm_sample_collection()
                            status = link.sample_collection_status()
                            code = 409 if status["cleanup_blocked"] else 200
                            result = {"ok": code == 200, **status}
                        elif payload == {"action": "status"}:
                            code, result = 200, {"ok": True, **link.sample_collection_status()}
                        else:
                            code, result = 400, {"ok": False, "error": "fixed_collection_schema_required"}
                    self._send(code, result)
                except (ValueError, TypeError, OSError):
                    self._send(409, {"ok": False, "error": "collection_not_armable"})
                return
            if parsed.path == "/confirmation/arm":
                try:
                    payload = json.loads(body.decode("utf-8"))
                    with link._lock:
                        error = link._http_session_error()
                        if error:
                            code, result = error
                        else:
                            code, result = 200, {"ok": True, **link.confirmation.arm(
                                payload["request_id"], payload["action_id"])}
                    self._send(code, result)
                except (ValueError, KeyError, TypeError):
                    self._send(409, {"ok": False, "error": "confirmation_not_armable"})
                return
            if parsed.path == "/confirmation/audio":
                code, result = link.hear_confirmation(body, ".wav",
                    self.headers.get("X-Voice-Request", ""), self.headers.get("X-Voice-Action", ""),
                    self.headers.get("X-Voice-Nonce", ""))
                self._send(code, result)
                return
            if parsed.path == "/say":
                try:
                    payload = json.loads(body.decode("utf-8"))
                    text = payload.get("text", "") if isinstance(payload, dict) else ""
                except (ValueError, UnicodeDecodeError):
                    self._send(400, {"ok": False, "error": "bad_json"})
                    return
                code, result = link.say(text)
                self._send(code, result)
                return
            if parsed.path == "/audio":
                suffix = (self.headers.get("X-Audio-Format") or ".wav").lower()
                operation = self.headers.get("X-Voice-Ingress-Operation", "")
                generation = self.headers.get("X-Voice-Session-Generation", "")
                code, result = link.hear(body, suffix, ingress_operation_id=operation,
                                         session_generation=generation)
                self._send(code, result)
                return
            if parsed.path in {"/cancel", "/cancel/status"}:
                try:
                    payload = json.loads(body.decode("utf-8"))
                except (ValueError, UnicodeDecodeError):
                    payload = None
                code, result = (link.cancel_scoped(payload) if parsed.path == "/cancel"
                                else link.cancellation_status(payload))
                self._send(code, result)
                return
            if parsed.path == "/off":
                # Keep ON serialized through the acknowledgement, not only stop().
                # Transport I/O may block, so hold no request-owner gate here.
                with link._lifecycle_lock:
                    stopped = link.stop()
                    if stopped.get("error"):
                        self._send(503 if stopped["error"] == "bridge_off" else 409, stopped)
                        return
                    with link._lock:
                        link._cancel_cleanup_blocked |= link._worker_cleanup_pending()
                        clean = (not link.listening and not link._cancel_cleanup_blocked
                                 and not link._ingress_active)
                    self._send(200 if clean else 409, {"ok": clean, "listening": False,
                        "cleanup_confirmed": clean, "error": None if clean else "cancel_cleanup_unconfirmed"})
                return
            self._send(404, {"ok": False, "error": "unknown_path"})

    return Handler
