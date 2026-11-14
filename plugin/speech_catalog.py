"""Read-only catalog of the speech adapters this plugin can actually dispatch.

None of them is opened here:

- STT ``android-native``: this plugin's own registered transcription provider, which asks
  the paired companion app to run Android's on-device recognizer.
- TTS ``hermes-tts``: Hermes' own ``text_to_speech`` tool, reached only through the public
  ``ctx.dispatch_tool``. Which engine speaks (for example the Nous Portal subscription via
  the Tool Gateway) is the operator's ``tts.provider`` choice in Hermes, not the plugin's.

The catalog does not read Hermes' config, scan Hermes' source, or resolve credentials.
``selectable`` means a selection can be saved; ``runtime_ready`` is always false because
nothing here probes a device or a remote service.
"""
import re

_IDENTIFIER = re.compile(r'[a-z][a-z0-9_-]{0,63}\Z')


def _identifier(value):
    return type(value) is str and _IDENTIFIER.fullmatch(value) is not None


def _row(kind, provider, adapter_id, *, streaming, languages, auth, note):
    return dict(kind=kind, provider=provider, adapter_id=adapter_id, origin='phone_plugin',
                configured=False, registered=True, installed='source_present',
                dependencies='none', auth=auth, languages=languages, streaming=streaming,
                selectable=True, adapter_compatible=True, runtime_ready=False,
                evidence=[], blocked_reason=note)


def discover(*, profile=None, scope=None, core_root=None):
    """Return the fixed adapter catalog. Arguments are accepted for compatibility only."""
    if profile is not None and type(profile) is not dict:
        raise ValueError('SPEECH_PROFILE_INVALID')
    if scope is not None and (type(scope) is not str or not scope or '\x00' in scope):
        raise ValueError('SPEECH_SCOPE_INVALID')
    rows = [
        _row('stt', 'android-native', 'phone:android-native', streaming='file_transcription',
             languages=['en-US','ko-KR','ja-JP','zh-CN','es-ES','fr-FR','de-DE','pt-BR','vi-VN','id-ID','th-TH','hi-IN'], auth='paired_device_required',
             note='paired_device_and_installed_model_required'),
        _row('tts', 'hermes-tts', 'hermes:text_to_speech', streaming='buffered_file',
             languages=None, auth='hermes_tts_provider',
             note='hermes_tts_provider_configuration_required'),
    ]
    return dict(schema_version=1, catalog_kind='available_adapter_catalog',
                readiness_checked=False, providers=rows, warnings=[])
