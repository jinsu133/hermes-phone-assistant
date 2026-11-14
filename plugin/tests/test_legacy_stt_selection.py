"""hermes-stt was removed: STT never imports Hermes core; old saved selections stay on-device."""
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

LEGACY = dict(mode='explicit', provider='hermes-stt', credential_ref=None,
              voice=None, model=None, language=None)


class Ctx:
    def __init__(self, config=None):
        self.config = config or {}
    def get_config(self, key, default=None):
        return self.config.get(key, default)
    def dispatch_tool(self, *args, **kwargs):
        return '{}'


class Settings:
    """In-memory stand-in for SpeechSettings (the SQLite store's checks are POSIX-only)."""
    def __init__(self, stt):
        self.payload = dict(schema_version=1, audience='family', tts={'mode': 'inherit'}, stt=stt)
    def current(self):
        return dict(revision=1, payload=settings_mod.validate_speech(self.payload))


class Provider:
    calls = []
    def __init__(self, ctx):
        pass
    def transcribe(self, path, **kw):
        Provider.calls.append((path, kw))
        return dict(success=True, transcript='설정 열어 줘', provider='android-native')


class LegacySttSelection(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        ctx = Ctx(dict(connection_file='/x/pair.json', stt_language='ko-KR'))
        self.runtime = runtime_mod.SpeechRuntime(Path(self.tmp.name).resolve(), ctx,
                                                 settings=Settings(dict(LEGACY)))
        android = importlib.import_module('plugin.android_stt')
        self.saved = android.AndroidTranscriptionProvider
        android.AndroidTranscriptionProvider = Provider
        Provider.calls = []
        self.blocked = sys.modules.get('tools.transcription_tools', 'absent')
        sys.modules['tools.transcription_tools'] = None  # any import now raises ImportError

    def tearDown(self):
        importlib.import_module('plugin.android_stt').AndroidTranscriptionProvider = self.saved
        if self.blocked == 'absent':
            sys.modules.pop('tools.transcription_tools', None)
        else:
            sys.modules['tools.transcription_tools'] = self.blocked
        self.tmp.cleanup()

    def test_catalog_no_longer_offers_hermes_stt(self):
        rows = {(r['kind'], r['provider']) for r in catalog_mod.discover()['providers']}
        self.assertNotIn(('stt', 'hermes-stt'), rows)
        self.assertIn(('stt', 'android-native'), rows)

    def test_old_saved_selection_still_loads(self):
        settings_mod.validate_speech(dict(schema_version=1, audience='family',
                                          tts={'mode': 'inherit'}, stt=dict(LEGACY)))

    def test_old_selection_runs_on_device_with_plugin_options(self):
        result = self.runtime.transcribe('/tmp/a.wav', self.runtime.snapshot())
        self.assertEqual(result['provider'], 'android-native')
        self.assertEqual(Provider.calls, [('/tmp/a.wav', dict(model=None, language='ko-KR'))])

    def test_status_reports_on_device_provider(self):
        stt = self.runtime.runtime_status()['stt']
        self.assertEqual((stt['provider'], stt['dispatch_ready']), ('android-native', True))

    def test_runtime_has_no_core_stt_import(self):
        source = (_PLUGIN_DIR / 'speech_runtime.py').read_text(encoding='utf-8')
        self.assertNotIn('transcription_tools', source)


if __name__ == '__main__':
    unittest.main()
