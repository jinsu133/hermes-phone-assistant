"""Voice context stays bounded: conversation windows and compact screen snapshots."""
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg

from plugin.voice_session_window import SessionWindow  # noqa: E402
from plugin.observe_compact import compact_observation  # noqa: E402


class Clock:
    def __init__(self, now=1_800_000_000):
        self.now = now

    def __call__(self):
        return self.now


class Ctx:
    def __init__(self, **config):
        self.config = config

    def get_config(self, key, default=None):
        return self.config.get(key, default)


class SessionWindowTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp()
        (Path(self.home) / 'state' / 'phone-runs').mkdir(parents=True)
        self.clock = Clock()

    def window(self, **config):
        return SessionWindow.from_ctx(Ctx(**config), 'phone.family.abc', self.home, clock=self.clock)

    def test_follow_ups_share_a_session(self):
        w = self.window()
        first = w.peek(); w.commit(first)
        self.clock.now += 120
        self.assertEqual(w.peek(), first)
        self.assertTrue(first.startswith('phone.family.abc.w'))

    def test_idle_gap_starts_a_new_session(self):
        w = self.window(voice_session_idle_minutes=10)
        first = w.peek(); w.commit(first)
        self.clock.now += 11 * 60
        self.assertNotEqual(w.peek(), first)

    def test_idle_counts_from_the_end_of_the_request(self):
        w = self.window(voice_session_idle_minutes=10)
        first = w.peek(); w.commit(first)
        self.clock.now += 9 * 60; w.touch()
        self.clock.now += 9 * 60
        self.assertEqual(w.peek(), first)

    def test_turn_budget_starts_a_new_session(self):
        w = self.window(voice_session_max_turns=2)
        first = w.peek(); w.commit(first); w.commit(w.peek())
        self.assertNotEqual(w.peek(), first)

    def test_window_survives_a_restart(self):
        w = self.window()
        first = w.peek(); w.commit(first)
        self.clock.now += 30
        self.assertEqual(self.window().peek(), first)

    def test_other_base_or_bad_settings_do_not_carry_over(self):
        w = self.window()
        w.commit(w.peek())
        other = SessionWindow.from_ctx(Ctx(voice_session_idle_minutes='x'), 'phone.other', self.home, clock=self.clock)
        self.assertTrue(other.peek().startswith('phone.other.w'))
        self.assertEqual(other.idle_seconds, 600)

    def test_peek_alone_records_nothing(self):
        w = self.window()
        w.peek()
        self.assertFalse((Path(self.home) / 'state' / 'phone-runs' / 'session-window.json').exists())


class CompactObservationTest(unittest.TestCase):
    def test_defaults_are_dropped_and_stated_once(self):
        value = {'snapshot': 's', 'nodes': [
            {'ref': 'n1', 'text': '닫기', 'label': '', 'clickable': True, 'enabled': True, 'focused': False,
             'resource_id': 'x:id/close', 'role': ''},
            {'ref': 'n2', 'text': '', 'clickable': False, 'enabled': False}],
            'detail_diagnosis': {'detail_reason': '', 'pages': 0}}
        out = compact_observation(value)
        self.assertEqual(out['nodes'][0], {'ref': 'n1', 'text': '닫기', 'clickable': True, 'resource_id': 'x:id/close'})
        self.assertEqual(out['nodes'][1], {'ref': 'n2', 'enabled': False})
        self.assertNotIn('detail_diagnosis', out)
        self.assertIs(out['node_defaults']['clickable'], False)
        self.assertEqual(value['nodes'][0]['label'], '')  # input untouched

    def test_diagnosis_with_a_reason_is_kept(self):
        out = compact_observation({'nodes': [], 'detail_diagnosis': {'detail_reason': 'no_button'}})
        self.assertIn('detail_diagnosis', out)

    def test_zero_is_not_mistaken_for_false(self):
        out = compact_observation({'nodes': [{'ref': 'n0', 'clickable': 0}]})
        self.assertEqual(out['nodes'][0]['clickable'], 0)

    def test_non_observation_is_unchanged(self):
        self.assertEqual(compact_observation({'state': 'awaiting_user_confirmation'}),
                         {'state': 'awaiting_user_confirmation'})


if __name__ == '__main__':
    unittest.main()
