"""The voice bridge opens after plugin load and recovers from a run cut off by a restart."""
import importlib
import importlib.util
import json
import os
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg
POSIX = importlib.util.find_spec('fcntl') is not None


class FakeLink:
    def __init__(self, opens_on=1):
        self.listening, self.calls, self.opens_on = False, 0, opens_on

    def auto_start(self):
        self.calls += 1
        if self.calls >= self.opens_on:
            self.listening = True


class Ctx:
    def __init__(self, surface=None):
        self._surface = surface

    def session_surface(self):
        return self._surface


@unittest.skipUnless(POSIX, 'plugin package imports fcntl (Termux/Linux)')
class StartupTest(unittest.TestCase):
    def setUp(self):
        self.mod = importlib.import_module('plugin')

    def run_startup(self, link, ctx):
        self.mod._start_bridge_after_load(ctx, link, delays=(0, 0, 0), sleep=lambda _: None)
        deadline = time.time() + 2
        while time.time() < deadline and not link.listening and link.calls < 3:
            time.sleep(.01)
        time.sleep(.05)

    def test_retries_until_the_bridge_is_listening(self):
        link = FakeLink(opens_on=2)
        self.run_startup(link, Ctx('telegram'))
        self.assertTrue(link.listening)
        self.assertEqual(link.calls, 2)

    def test_local_cli_never_opens_the_bridge(self):
        link = FakeLink()
        self.run_startup(link, Ctx('cli'))
        self.assertEqual(link.calls, 0)


@unittest.skipUnless(POSIX, 'run journal uses flock and POSIX ownership')
class RestoredRunTest(unittest.TestCase):
    RUN = 'run_' + 'a' * 32

    def setUp(self):
        self.owner_mod = importlib.import_module('plugin.voice_runs_owner')
        self.home = tempfile.TemporaryDirectory()
        root = Path(self.home.name) / 'state' / 'phone-runs'
        root.mkdir(parents=True, mode=0o700)
        os.chmod(Path(self.home.name) / 'state', 0o700)
        record = {'version': 1, 'request_id': 'req1', 'session_generation': 'gen1',
                  'session_id': 'phone.family.71e43587e82cc7ea', 'run_id': self.RUN,
                  'idempotency_key': 'idem1', 'state': 'running'}
        path = root / 'active.json'
        fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(record, f)
        self.path = path

    def tearDown(self):
        self.home.cleanup()

    def owner(self, client):
        return self.owner_mod.RunsOwner(self.home.name, client, on_terminal=lambda *a: None,
                                        on_failure=lambda *a: None)

    def client(self, http_status):
        class Err(Exception):
            pass

        class Client:
            def status(self, run_id):
                e = Err('x')
                e.http_status = http_status
                raise e
        return Client()

    def test_run_unknown_after_restart_is_settled_as_interrupted(self):
        os.utime(self.path, (time.time() - 60, self.owner_mod._PROCESS_STARTED - 60))
        owner = self.owner(self.client(404))
        self.assertTrue(owner.blocked())
        self.assertTrue(owner.reconcile_restored_interrupted())
        self.assertFalse(owner.blocked())
        self.assertEqual(json.loads(self.path.read_text())['state'], 'interrupted')

    def test_other_errors_or_a_run_from_this_process_stay_blocked(self):
        os.utime(self.path, (time.time() - 60, self.owner_mod._PROCESS_STARTED - 60))
        self.assertFalse(self.owner(self.client(500)).reconcile_restored_interrupted())

    def test_journal_written_by_this_process_is_not_settled_by_404(self):
        os.utime(self.path, (time.time(), self.owner_mod._PROCESS_STARTED + 1))
        owner = self.owner(self.client(404))
        self.assertFalse(owner.reconcile_restored_interrupted())
        self.assertTrue(owner.blocked())


if __name__ == '__main__':
    unittest.main()
