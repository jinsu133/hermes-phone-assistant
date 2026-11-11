"""Exact request-bound speech dispatch; no auto provider/credential fallback.

Only family Android STT and the existing opt-in Vertex SSE route are implemented.
Catalog saveability is NOT runtime support. External execution and explicit cloud
credential UUIDs remain pending: this module has no principal-bound vault resolver.
No provider is imported/opened by snapshot/status. Parent owns request admission,
cancellation, ownership and keeping ONE snapshot for all speech in that request.
"""
from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from .speech_settings import SpeechSettings, validate_speech


class SpeechRuntimeError(RuntimeError):
    """Fixed nonsecret runtime refusal code."""


def _freeze(value):
    if isinstance(value, dict):
        return MappingProxyType({key: _freeze(item) for key, item in value.items()})
    return value


def _plain(value):
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    return value


class SpeechRuntime:
    def __init__(self, home, ctx, *, settings=None, audience='family'):
        """home is the active Hermes profile, NOT an ambient HOME fallback.

        Pass the SAME SpeechSettings instance/directory used by the settings UI.
        Default store: home/plugin-data/hermes-phone-control/settings.
        audience is trusted deployment policy, never a request-controlled field.
        """
        self.home = Path(home)
        if not self.home.is_absolute() or '..' in self.home.parts:
            raise SpeechRuntimeError('SPEECH_HOME_INVALID')
        if audience not in ('family', 'external'):
            raise SpeechRuntimeError('SPEECH_AUDIENCE_INVALID')
        self.ctx, self.audience = ctx, audience
        self.settings = settings if settings is not None else SpeechSettings(
            self.home/'plugin-data/hermes-phone-control/settings', audience=audience)

    def _inherited_stt(self):
        # Read only in family inherit, once per new request. Do NOT use the generic
        # dispatcher, public registry globals, ambient API keys or auto detection.
        # Retain only the reviewed Android options, never the full host config.
        import yaml
        try:
            with (self.home/'config.yaml').open('rb') as stream:
                raw = stream.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                return None
            config = yaml.safe_load(raw)
            stt = config.get('stt') if type(config) is dict else None
            if (type(stt) is not dict or stt.get('enabled', True) is not True
                    or stt.get('provider') != 'android-native'):
                return None
            # A command declaration could shadow the plugin. Never reinterpret it
            # as native Android merely because its name happens to match.
            declarations = stt.get('providers', {})
            if type(declarations) is not dict or 'android-native' in declarations:
                return None
            options = stt.get('android-native', {})
            if type(options) is not dict or set(options) - {'model', 'language'}:
                return None
            choice = dict(mode='explicit', provider='android-native', credential_ref=None,
                          voice=None, model=options.get('model'), language=options.get('language'))
            validate_speech(dict(schema_version=1, audience='family',
                                 tts={'mode': 'disabled'}, stt=choice))
            return choice
        except (OSError, ValueError, yaml.YAMLError):
            return None

    def snapshot(self):
        """Return recursively immutable {revision,payload,inherited_stt} mapping.

        A fresh store read for EVERY new request; no process-level selection cache.
        Family inherited Android config is pinned here. No generic legacy STT
        dispatcher is retained: unsupported inherited routes fail closed instead.
        Vertex inherits its existing wrapper's model/voice/auth at actual open,
        unchanged; those external configuration values are not a frozen vault.
        """
        current = self.settings.current()
        clean = validate_speech(current['payload'])
        self._audience(clean)
        inherited = (self._inherited_stt() if clean['audience'] == 'family'
                     and clean['stt']['mode'] == 'inherit' else None)
        return _freeze(dict(revision=current['revision'], payload=clean, inherited_stt=inherited))

    def _audience(self, payload):
        if self.audience == 'external' and payload['audience'] != 'external':
            raise SpeechRuntimeError('SPEECH_AUDIENCE_FORBIDDEN')

    def _checked(self, snapshot):
        # Trusted in-process snapshot, not an authorization capability or a wire
        # format. Validate again before any effect; do not reread current selection.
        try:
            value = _plain(snapshot)
            if type(value) is not dict or set(value) != {'revision', 'payload', 'inherited_stt'}:
                raise ValueError()
            if type(value['revision']) is not int or not 0 <= value['revision'] < 2**63:
                raise ValueError()
            payload = validate_speech(value['payload'])
            inherited = value['inherited_stt']
            if inherited is not None:
                if payload['audience'] != 'family' or payload['stt']['mode'] != 'inherit':
                    raise ValueError()
                validate_speech(dict(schema_version=1, audience='family',
                                    tts={'mode': 'disabled'}, stt=inherited))
                if inherited.get('provider') != 'android-native':
                    raise ValueError()
        except (ValueError, TypeError, KeyError, RecursionError):
            raise SpeechRuntimeError('SPEECH_SNAPSHOT_INVALID') from None
        self._audience(payload)
        return value

    def _route(self, value, kind):
        payload = value['payload']
        choice = payload[kind]
        if choice['mode'] == 'disabled':
            raise SpeechRuntimeError('SPEECH_DISABLED')
        # Includes native Android: external deployment cannot borrow family pairing.
        if payload['audience'] != 'family':
            raise SpeechRuntimeError('SPEECH_EXTERNAL_RUNTIME_PENDING')
        if kind == 'stt':
            if choice['mode'] == 'inherit':
                choice = value['inherited_stt']
                if choice is None:
                    raise SpeechRuntimeError('SPEECH_INHERITED_STT_UNREADY')
            if choice['provider'] != 'android-native':
                raise SpeechRuntimeError('SPEECH_STT_ADAPTER_UNREADY')
            connection = self.ctx.get_config('connection_file')
            if not isinstance(connection, str) or not connection:
                raise SpeechRuntimeError('SPEECH_ANDROID_NOT_CONFIGURED')
            return choice
        if choice['mode'] != 'inherit':
            if choice['provider'] == 'family-vertex':
                if any(choice[key] is not None for key in ('model', 'voice', 'language')):
                    raise SpeechRuntimeError('SPEECH_VERTEX_OVERRIDE_UNSUPPORTED')
                # UUID syntax / scope=family is NOT sharing authorization. Never
                # discard that exact UUID and use the HOME wrapper's credentials.
                raise SpeechRuntimeError('SPEECH_CREDENTIAL_RESOLVER_PENDING')
            raise SpeechRuntimeError('SPEECH_TTS_ADAPTER_UNREADY')
        if self.ctx.get_config('voice_streaming', False) is not True:
            raise SpeechRuntimeError('SPEECH_STREAMING_DISABLED')
        if not (self.home/'bin/hermes-vertex-tts-portable.py').is_file():
            raise SpeechRuntimeError('SPEECH_VERTEX_WRAPPER_MISSING')
        return choice

    def transcribe(self, path, snapshot):
        """Return the exact Android provider envelope; refusal returns success=False.

        Requires the provider's existing mono PCM16/16kHz WAV contract (<=30s).
        No decoding/resampling/downloads, retry, legacy hooks, or provider fallback.
        Unexpected provider exceptions propagate; caller owns purge/cancellation.
        """
        try:
            value = self._checked(snapshot)
            choice = self._route(value, 'stt')
        except SpeechRuntimeError as error:
            return dict(success=False, transcript='', provider=None, error=str(error))
        from .android_stt import AndroidTranscriptionProvider
        return AndroidTranscriptionProvider(self.ctx).transcribe(
            path, model=choice['model'], language=choice['language'])

    def streaming_opener(self, snapshot):
        """Return existing opener(text), including its fixed_prompt(text) helper.

        Lazily opens the existing wrapper unchanged. Raises SpeechRuntimeError on
        disabled/unready selection; NEVER substitute buffered TTS or another engine.
        Do not invoke under VoiceLink/bridge gates. Parent retains SSE ownership.
        """
        value = self._checked(snapshot)
        self._route(value, 'tts')
        from .voice_normal_stream import existing_vertex_opener
        required = self.ctx.get_config('voice_require_pregenerated', False)
        if type(required) is not bool:
            raise SpeechRuntimeError('SPEECH_PREGENERATED_POLICY_INVALID')
        return (existing_vertex_opener(self.home, require_pregenerated=True) if required
                else existing_vertex_opener(self.home))

    def runtime_status(self, snapshot=None):
        """Nonsecret local dispatch evidence, NOT cloud/device health verification.

        dispatch_ready means a real adapter can be invoked with current local
        prerequisites. runtime_ready is conservatively false: credentials, device,
        installed Korean model and remote availability were not probed. No catalog
        entry gets a working-provider claim just because its name is recognized.
        """
        value = self._checked(self.snapshot() if snapshot is None else snapshot)
        result = dict(revision=value['revision'], audience=value['payload']['audience'])
        for kind in ('tts', 'stt'):
            choice = value['payload'][kind]
            provider = choice.get('provider')
            if choice['mode'] == 'inherit':
                provider = ('family-vertex' if kind == 'tts' else
                            (value['inherited_stt'] or {}).get('provider'))
            try:
                self._route(value, kind)
                ready, reason = True, 'SPEECH_SERVICE_NOT_VERIFIED'
            except SpeechRuntimeError as error:
                ready, reason = False, str(error)
            result[kind] = dict(mode=choice['mode'], provider=provider,
                dispatch_ready=ready, runtime_ready=False, service_verified=False,
                blocked_reason=reason, fallback=False)
        return result
