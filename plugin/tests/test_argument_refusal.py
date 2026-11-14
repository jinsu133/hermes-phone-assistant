"""A phone_act argument mistake is explained as such, not as an approval problem."""
import importlib
import sys
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
# Load the plugin as package "plugin" whatever its installed folder is called.
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg
guard = importlib.import_module('plugin.confirmation')


class Ctx:
    def __init__(self, loc):
        self.loc = loc
    def get_config(self, key, default=None):
        return self.loc if key == 'locale' else default


def message(args, loc='en'):
    guard._CTX = Ctx(loc)
    try:
        result = guard.guard_phone_action(tool_name='phone_act', args=args)
    finally:
        guard._CTX = None
    return result and result['message']


class ArgumentRefusal(unittest.TestCase):
    def test_open_app_by_name_gets_fix_hint(self):
        text = message({'action': 'open_app', 'query': 'YouTube'})
        self.assertTrue(text.startswith('INVALID_ARGUMENTS'), text)
        self.assertIn('package', text)
        self.assertIn('list_apps', text)
        self.assertNotIn('PHONE_CONFIRMATION_REQUIRED', text)

    def test_korean_hint(self):
        text = message({'action': 'open_app', 'query': 'YouTube'}, 'ko')
        self.assertTrue(text.startswith('INVALID_ARGUMENTS'), text)
        self.assertIn('승인 문제가 아닙니다', text)

    def test_missing_snapshot_gets_fix_hint(self):
        self.assertTrue(message({'action': 'click', 'ref': 'n1'}).startswith('INVALID_ARGUMENTS'))

    def test_self_approval_keeps_confirmation_message(self):
        for args in ({'action': 'click', 'snapshot': 's', 'ref': 'n1', 'approved': 'yes'},
                     {'action': 'open_app', 'package': 'p', 'confirmation': {'kind': 'general'}},
                     {'action': 'click', 'snapshot': 's', 'ref': 'n1', 'confirmation': {'kind': 'payment'}}):
            self.assertTrue(message(args).startswith('PHONE_CONFIRMATION_REQUIRED'), args)

    def test_unknown_action_keeps_confirmation_message(self):
        self.assertTrue(message({'action': 'approve'}).startswith('PHONE_CONFIRMATION_REQUIRED'))

    def test_valid_arguments_pass(self):
        self.assertIsNone(message({'action': 'open_app', 'package': 'com.google.android.youtube'}))
        self.assertIsNone(message({'action': 'click', 'snapshot': 's', 'ref': 'n1'}))


if __name__ == '__main__':
    unittest.main()
