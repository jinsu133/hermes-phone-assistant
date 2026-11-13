"""Per-app learning with control-label anchors (screens without resource ids, e.g. WebView)."""
import importlib
import importlib.util
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg
# phone_learning imports fcntl at load (Linux/Termux). Store locking is not exercised here, so
# on hosts without it a stub is visible only while this module loads, never to other tests.
_stubbed = 'fcntl' not in sys.modules and importlib.util.find_spec('fcntl') is None
if _stubbed:
    sys.modules['fcntl'] = types.ModuleType('fcntl')
try:
    learning = importlib.import_module('plugin.phone_learning')
finally:
    if _stubbed:
        del sys.modules['fcntl']

PKG = 'com.skt.tmap.ku'
DEVICE = '12345678-1234-5678-1234-567812345678'


def plan(*steps, task=None):
    out = {'package': PKG, 'version_code': 10, 'steps': [{'op': 'launch', 'package': PKG}, *steps]}
    if task:
        out['task'] = task
    return out


def entry(p, successes, paused=False):
    return {'device': DEVICE, 'plan': p, 'successes': successes, 'failures': 0,
            'streak': successes, 'total_ms': 100 * successes, 'paused': paused}


class ControlLabelTemplateTest(unittest.TestCase):
    def test_short_button_word_is_learned_in_any_app(self):
        p = plan({'op': 'input', 'role': 'search_field', 'value': '강남역'},
                 {'op': 'click', 'role': 'search_suggestion'},
                 {'op': 'click', 'text': '도착'},
                 {'op': 'assert', 'text': '안내 시작'}, task='강남역으로 안내해줘')
        t = learning.template(p)
        self.assertIsNotNone(t)
        self.assertNotIn('task', t)
        self.assertEqual(t['steps'][1]['value'], '{q}')
        self.assertEqual(t['steps'][3], {'op': 'click', 'text': '도착'})
        other = learning.template({**p, 'package': 'com.example.app',
                                   'steps': [{'op': 'launch', 'package': 'com.example.app'}, *p['steps'][1:]]})
        self.assertIsNotNone(other)

    def test_content_and_risk_are_never_anchors(self):
        for label in ('강남역 2번 출구 앞', '010-1234-5678', 'a@b.com', 'https://x.y', '결제하기', '보내기',
                      '로그인', '전화 걸기', '이 문장은 화면 내용이라서 너무 깁니다 정말로', ' 도착', '도착\n'):
            with self.subTest(label=label):
                self.assertIsNone(learning.template(plan({'op': 'click', 'text': label},
                                                         {'op': 'assert', 'text': '안내 시작'})))

    def test_label_carrying_the_spoken_query_is_not_learned(self):
        p = plan({'op': 'input', 'role': 'search_field', 'value': '스타벅스'},
                 {'op': 'click', 'text': '스타벅스'}, {'op': 'assert', 'text': '도착'})
        self.assertIsNone(learning.template(p))

    def test_fuzzy_or_positional_selectors_stay_out(self):
        self.assertIsNone(learning.template(plan({'op': 'click', 'text_contains': '도착'},
                                                 {'op': 'assert', 'text': '안내 시작'})))
        self.assertIsNone(learning.template(plan({'op': 'click', 'text': '도착', 'index': 1},
                                                 {'op': 'assert', 'text': '안내 시작'})))
        self.assertIsNone(learning.template(plan({'op': 'input', 'text': '검색', 'value': 'x'},
                                                 {'op': 'assert', 'text': '안내 시작'})))


class LabelVerificationTest(unittest.TestCase):
    def test_final_label_must_be_unique_on_screen(self):
        p = learning.template(plan({'op': 'click', 'text': '도착'}, {'op': 'assert', 'text': '안내 시작'}))
        ok = {'ok': True, 'completed': 3, 'total': 3}
        screen = {'package': PKG, 'truncated': False, 'snapshot': 's',
                  'nodes': [{'text': '안내 시작'}, {'label': '닫기'}]}
        self.assertTrue(learning.verified(p, ok, screen))
        screen['nodes'].append({'label': '안내 시작'})
        self.assertFalse(learning.verified(p, ok, screen))


class LabelReuseTest(unittest.TestCase):
    def setUp(self):
        self.p = learning.template(plan({'op': 'click', 'text': '도착'}, {'op': 'assert', 'text': '안내 시작'}))
        self.rid = learning._id(DEVICE, self.p)

    def store(self, successes, paused=False):
        return mock.patch.object(learning, '_read', return_value={
            'schema': 1, 'entries': {self.rid: entry(self.p, successes, paused)}})

    def test_label_route_needs_repeated_verification_before_reuse(self):
        with self.store(1):
            with self.assertRaisesRegex(learning.LearningError, 'LEARNING_UNCONFIRMED'):
                learning.resolve('home', self.rid, DEVICE)
            row, = learning.list_routes('home', DEVICE)
            self.assertEqual((row['anchors'], row['reusable']), ('label', False))
        with self.store(learning.LABEL_CONFIRMATIONS):
            self.assertEqual(learning.resolve('home', self.rid, DEVICE)['steps'][1], {'op': 'click', 'text': '도착'})
            row, = learning.list_routes('home', DEVICE)
            self.assertTrue(row['reusable'])

    def test_paused_label_route_is_not_reusable(self):
        with self.store(5, paused=True):
            with self.assertRaisesRegex(learning.LearningError, 'LEARNING_REPLAN_REQUIRED'):
                learning.resolve('home', self.rid, DEVICE)
            self.assertFalse(learning.list_routes('home', DEVICE)[0]['reusable'])


if __name__ == '__main__':
    unittest.main()
