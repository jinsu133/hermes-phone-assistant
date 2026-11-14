"""An app that is still loading is observed again instead of being reported as unreadable."""
import asyncio
import sys
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg

from plugin.backend import Denied  # noqa: E402
from plugin.observe_compact import LOADING_HINT, OBSERVE_SETTLE_DELAYS, observe_settled  # noqa: E402

EMPTY = {'snapshot': 's', 'package': 'com.example.app', 'nodes': []}
READY = {'snapshot': 't', 'package': 'com.example.app', 'nodes': [{'ref': 'n0', 'text': 'Close'}]}


def run(results):
    calls, waits = [], []

    async def query(operation, args, filename):
        calls.append(operation)
        item = results[min(len(calls), len(results)) - 1]
        if isinstance(item, Exception):
            raise item
        return item

    async def sleep(delay):
        waits.append(delay)

    value = asyncio.run(observe_settled({}, 'f', query=query, sleep=sleep))
    return value, calls, waits


class ObserveSettle(unittest.TestCase):
    def test_ready_screen_is_returned_at_once(self):
        value, calls, waits = run([READY])
        self.assertEqual((value, len(calls), waits), (READY, 1, []))

    def test_splash_then_screen(self):
        value, calls, waits = run([EMPTY, Denied('SCREEN_UNAVAILABLE'), READY])
        self.assertEqual(value, READY)
        self.assertEqual(waits, list(OBSERVE_SETTLE_DELAYS[:2]))

    def test_still_empty_says_so(self):
        value, calls, waits = run([EMPTY])
        self.assertEqual(len(calls), len(OBSERVE_SETTLE_DELAYS) + 1)
        self.assertLessEqual(sum(waits), 5.0)
        self.assertEqual(value['screen_hint'], LOADING_HINT)
        self.assertEqual(value['nodes'], [])

    def test_still_unavailable_raises_the_same_code(self):
        with self.assertRaises(Denied) as caught:
            run([Denied('SCREEN_UNAVAILABLE')])
        self.assertEqual(caught.exception.code, 'SCREEN_UNAVAILABLE')

    def test_other_errors_and_states_are_not_retried(self):
        with self.assertRaises(Denied):
            run([Denied('PHONE_LOCKED_OR_UNAVAILABLE'), READY])
        value, calls, _ = run([{'state': 'awaiting_user_confirmation'}])
        self.assertEqual(len(calls), 1)


if __name__ == '__main__':
    unittest.main()
