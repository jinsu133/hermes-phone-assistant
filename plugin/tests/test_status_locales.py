"""Spoken status lines ship in every companion-app language; everything else falls back to English."""
import importlib
import sys
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg
messages = importlib.import_module('plugin.messages')

APP_LANGUAGES = ('en', 'ko', 'ja', 'zh-cn', 'de', 'es', 'fr', 'pt-br', 'hi', 'id', 'th', 'vi')
STATUS_KEYS = ('status.accepted', 'status.phone', 'status.research', 'status.document', 'status.calculation')


class Ctx:
    def __init__(self, locale):
        self.value = locale
        self.registered = {}
    def get_config(self, key, default=None):
        return self.value if key == 'locale' else default
    def register_locale(self, code, table, endonym):
        self.registered[code] = (endonym, table)


class StatusLocales(unittest.TestCase):
    def test_every_app_language_has_its_own_status_lines(self):
        seen = set()
        for code in APP_LANGUAGES:
            line = messages.text(Ctx(code), 'status.accepted')
            self.assertNotIn(line, seen, code)
            seen.add(line)
            for key in STATUS_KEYS:
                self.assertTrue(messages.LOCALES[code][key].strip(), (code, key))

    def test_aliases_and_unknown_languages(self):
        self.assertEqual(messages.locale(Ctx('pt_BR')), 'pt-br')
        self.assertEqual(messages.locale(Ctx('in')), 'id')
        self.assertEqual(messages.locale(Ctx('zh')), 'zh-cn')
        self.assertEqual(messages.locale(Ctx('ko_KR')), 'ko')
        self.assertEqual(messages.locale(Ctx('de-AT')), 'de')
        self.assertEqual(messages.locale(Ctx('xx')), 'en')

    def test_untranslated_keys_fall_back_to_english(self):
        self.assertEqual(messages.text(Ctx('ja'), 'voice.blocked'), messages.EN['voice.blocked'])

    def test_register_offers_every_translation(self):
        ctx = Ctx('en')
        messages.register(ctx)
        self.assertEqual(set(ctx.registered), set(APP_LANGUAGES) - {'en'})
        self.assertEqual(ctx.registered['ja'][1]['hermes_phone_control.status.accepted'],
                         messages.LOCALES['ja']['status.accepted'])

    def test_manifest_offers_every_language(self):
        manifest = (_PLUGIN_DIR / 'plugin.yaml').read_text(encoding='utf-8')
        self.assertIn('choices: [' + ', '.join(APP_LANGUAGES) + ']', manifest)


if __name__ == '__main__':
    unittest.main()
