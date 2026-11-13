"""Phone STT can use Hermes core's own STT routing (the operator's stt.provider)."""
import importlib
import sys
import tempfile
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
# Load the plugin as package "plugin" whatever its installed folder is called.
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg
runtime_mod = importlib.import_module('plugin.speech_runtime')
settings_mod = importlib.import_module('plugin.speech_settings')
catalog_mod = importlib.import_module('plugin.speech_catalog')

HERMES_STT = dict(mode='explicit', provider='hermes-stt', credential_ref=None,
                  voice=None, model=None, language=None)


class Ctx:
    def __init__(self, config=None):
        self.config = config or {}
    def get_config(self, key, default=None):
        return self.config.get(key, default)
    def dispatch_tool(self, *args, **kwargs):
        return '{}'


class CoreStub:
    """Stand-in for Hermes' tools.transcription_tools for the duration of a test."""
    def __init__(self, result=None, error=None):
        self.result, self.error, self.calls = result, error, []
        self.saved = {name: sys.modules.get(name) for name in ('tools', 'tools.transcription_tools')}
    def transcribe_audio(self, file_path, model=None, source=None):
        self.calls.append((file_path, model, source))
        if self.error is not None:
            raise self.error
        return self.result
    def __enter__(self):
        package = types.ModuleType('tools'); package.__path__ = []
        module = types.ModuleType('tools.transcription_tools')
        module.transcribe_audio = self.transcribe_audio
        package.transcription_tools = module
        sys.modules['tools'], sys.modules['tools.transcription_tools'] = package, module
        return self
    def __exit__(self, *exc):
        for name, value in self.saved.items():
            if value is None: sys.modules.pop(name, None)
            else: sys.modules[name] = value


class Settings:
    """In-memory stand-in for SpeechSettings (the SQLite store's checks are POSIX-only)."""
    def __init__(self):
        self.payload = dict(schema_version=1, audience='family', tts={'mode': 'inherit'}, stt={'mode': 'inherit'})
        self.revision = 1
    def current(self):
        return dict(revision=self.revision, payload=settings_mod.validate_speech(self.payload))


class HermesStt(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.settings = Settings()
        self.runtime = runtime_mod.SpeechRuntime(Path(self.tmp.name).resolve(), Ctx(), settings=self.settings)

    def tearDown(self):
        self.tmp.cleanup()

    def select_hermes(self):
        self.settings.payload = dict(self.settings.payload, stt=dict(HERMES_STT))
        self.settings.revision += 1
        return self.runtime.snapshot()

    def test_catalog_offers_hermes_stt_without_credentials(self):
        rows = {(r['kind'], r['provider']): r for r in catalog_mod.discover()['providers']}
        self.assertIn(('stt', 'hermes-stt'), rows)
        self.assertIn(('stt', 'android-native'), rows)
        self.assertEqual(rows[('stt', 'hermes-stt')]['adapter_id'], 'hermes:transcribe_audio')

    def test_hermes_stt_rejects_credential_reference(self):
        bad = dict(HERMES_STT, credential_ref={'scope': 'family', 'id': '11111111-1111-4111-8111-111111111111'})
        with self.assertRaises(ValueError):
            settings_mod.validate_speech(dict(schema_version=1, audience='family', tts={'mode': 'inherit'}, stt=bad))

    def test_default_stays_on_device(self):
        with CoreStub(result=dict(success=True, transcript='x')) as core:
            status = self.runtime.runtime_status()
        self.assertEqual(status['stt']['provider'], 'android-native')
        self.assertEqual(core.calls, [])

    def test_routes_to_core_with_operator_provider(self):
        snapshot = self.select_hermes()
        with CoreStub(result=dict(success=True, transcript='설정 열어 줘', provider='groq')) as core:
            result = self.runtime.transcribe('/tmp/a.wav', snapshot)
        self.assertEqual(result, dict(success=True, transcript='설정 열어 줘', provider='hermes-stt'))
        self.assertEqual(core.calls, [('/tmp/a.wav', None, 'voice_mode')])

    def test_core_failure_is_reported_not_fallen_back(self):
        snapshot = self.select_hermes()
        with CoreStub(result=dict(success=False, transcript='', error='No STT provider available.')):
            result = self.runtime.transcribe('/tmp/a.wav', snapshot)
        self.assertEqual(result['success'], False)
        self.assertEqual(result['provider'], 'hermes-stt')
        self.assertEqual(result['error'], 'No STT provider available.')

    def test_core_exception_never_escapes(self):
        snapshot = self.select_hermes()
        with CoreStub(error=RuntimeError('boom')):
            result = self.runtime.transcribe('/tmp/a.wav', snapshot)
        self.assertEqual(result['error'], 'SPEECH_STT_PROVIDER_FAILED')

    def test_missing_core_blocks_dispatch(self):
        snapshot = self.select_hermes()
        saved = {n: sys.modules.pop(n, None) for n in ('tools.transcription_tools',)}
        sys.modules['tools.transcription_tools'] = None  # import now raises ImportError
        try:
            result = self.runtime.transcribe('/tmp/a.wav', snapshot)
        finally:
            sys.modules.pop('tools.transcription_tools', None)
            for name, value in saved.items():
                if value is not None: sys.modules[name] = value
        self.assertEqual(result['error'], 'SPEECH_STT_DISPATCH_UNAVAILABLE')


if __name__ == '__main__':
    unittest.main()
