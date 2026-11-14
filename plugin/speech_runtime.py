"""Exact request-bound speech dispatch; no auto provider/credential fallback.

STT is this plugin's on-device Android provider (``android-native``), registered with
Hermes through ``ctx.register_transcription_provider``. The plugin does not import Hermes
core speech modules; a ``hermes-stt`` selection saved by an older version is treated as
``android-native`` with the plugin's own options. TTS is Hermes' configured
``text_to_speech`` tool, reached through ``ctx.dispatch_tool`` (for example the Nous
Portal subscription via the Tool Gateway). The plugin reads no Hermes config file and
holds no speech credential. Catalog saveability is NOT runtime support. No provider is
opened by snapshot/status. Parent owns request admission, cancellation, ownership and
keeping ONE snapshot for all speech in that request.
"""
from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from .speech_settings import SpeechSettings, validate_speech


# Saved by plugin versions before 0.24.2, which routed it through a Hermes core import.
LEGACY_HERMES_STT = 'hermes-stt'


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
        # Inherit means: this plugin's Android provider with the plugin's own declared
        # options (config_schema stt_model / stt_language). Hermes' config.yaml is not read.
        try:
            choice = dict(mode='explicit', provider='android-native', credential_ref=None,
                          voice=None, model=self.ctx.get_config('stt_model', None),
                          language=self.ctx.get_config('stt_language', None))
            validate_speech(dict(schema_version=1, audience='family',
                                 tts={'mode': 'disabled'}, stt=choice))
            return choice
        except (ValueError, TypeError):
            return None

    def snapshot(self):
        """Return recursively immutable {revision,payload,inherited_stt} mapping.

        A fresh store read for EVERY new request; no process-level selection cache.
        Family inherited Android options are pinned here. No generic legacy STT
        dispatcher is retained: unsupported inherited routes fail closed instead.
        Inherited TTS is whatever Hermes' own text_to_speech tool selects at use time.
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
            if choice['provider'] == LEGACY_HERMES_STT:
                # Removed option: keep old saved settings working, on-device only.
                choice = self._inherited_stt()
                if choice is None:
                    raise SpeechRuntimeError('SPEECH_INHERITED_STT_UNREADY')
            if choice['provider'] != 'android-native':
                raise SpeechRuntimeError('SPEECH_STT_ADAPTER_UNREADY')
            connection = self.ctx.get_config('connection_file')
            if not isinstance(connection, str) or not connection:
                raise SpeechRuntimeError('SPEECH_ANDROID_NOT_CONFIGURED')
            return choice
        if choice['mode'] != 'inherit':
            # Only Hermes' configured TTS tool is a supported speech output. A named
            # provider is never opened directly by the plugin.
            raise SpeechRuntimeError('SPEECH_TTS_ADAPTER_UNREADY')
        if not callable(getattr(self.ctx, 'dispatch_tool', None)):
            raise SpeechRuntimeError('SPEECH_TTS_DISPATCH_UNAVAILABLE')
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

    def check_tts(self, snapshot):
        """Raise SpeechRuntimeError unless this snapshot's TTS route is dispatchable."""
        self._route(self._checked(snapshot), 'tts')

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
                provider = ('hermes-tts' if kind == 'tts' else
                            (value['inherited_stt'] or {}).get('provider'))
            elif provider == LEGACY_HERMES_STT:
                provider = 'android-native'
            try:
                self._route(value, kind)
                ready, reason = True, 'SPEECH_SERVICE_NOT_VERIFIED'
            except SpeechRuntimeError as error:
                ready, reason = False, str(error)
            result[kind] = dict(mode=choice['mode'], provider=provider,
                dispatch_ready=ready, runtime_ready=False, service_verified=False,
                blocked_reason=reason, fallback=False)
        return result
