"""OFFLINE feasibility only: real public adapter lifecycle + disposable SQLite fixtures.

Not a production adapter/inbox. No core patching, discovery, runner/model creation,
transport, STT/TTS, production config or credentials. Each case is a fresh child.
Run with core/venv/bin/python -B this-file; parent supplies scrubbed temp HOME.
"""
import asyncio
from contextlib import closing
import ast
import hashlib
import importlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import types
import unittest

CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
PLUGIN = Path(__file__).resolve().parents[1]
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
CASES = (
    'registration_lifecycle', 'event_receipts_and_delivery', 'empty_is_success_not_execution',
    'start_receipt_failure_is_not_a_gate', 'completion_receipt_failure_is_unknown',
    'cancel_is_not_rollback', 'durable_acceptance_and_single_consumer', 'single_consumer_delivery',
    'recovery_never_blind_replays', 'approval_one_use_expiry', 'identity_and_followup',
    'sid_rebind_current_route', 'sid_rebind_unavailable_or_mismatched_route',
)


def isolate():
    home = Path(os.environ['HOME']).resolve()
    assert home == Path(os.environ['HERMES_HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-adapter-probe-')
    sys.dont_write_bytecode = True

    # Watched Python audit events only; not an OS-level sandbox.
    def audit(event, args):
        if event in {'socket.connect', 'socket.bind', 'socket.getaddrinfo', 'subprocess.Popen', 'os.system'}:
            raise PermissionError('offline probe forbids network/child process')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            p = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(x in args[1] for x in 'wax+')) or (
                isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT))
            if writing and not p.is_relative_to(home):
                raise PermissionError('probe writes restricted to isolated HOME')
            if p.is_relative_to(CORE.parent) and not p.is_relative_to(CORE) and not p.is_relative_to(home):
                raise PermissionError('production profile access forbidden')
            if p.is_relative_to(CORE) and (p.name == '.env' or p.name.startswith('auth.json')):
                raise PermissionError('credentials forbidden')
    sys.addaudithook(audit)
    sys.path.insert(0, str(CORE))
    return home


class JournalFixture:
    """Minimal SQL contract experiment, NOT an implementation proposal/API."""
    def __init__(self, path):
        self.path = path
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS inbox (id TEXT PRIMARY KEY, text TEXT NOT NULL, lane TEXT NOT NULL, parent TEXT, state TEXT NOT NULL)')
        self.db.execute("CREATE UNIQUE INDEX IF NOT EXISTS single_owner ON inbox(lane) WHERE state IN ('dispatching','started','unknown_outcome')")
        self.db.commit()

    def accept(self, rid, text='fixture text', parent=None, abort=False):
        with self.db:
            old = self.db.execute('SELECT text,parent FROM inbox WHERE id=?', (rid,)).fetchone()
            if old:
                if old != (text, parent):
                    raise ValueError('id conflict')
            else:
                self.db.execute('INSERT INTO inbox VALUES (?,?,?,?,?)', (rid, text, 'voice-lane', parent, 'received'))
            if abort:
                raise RuntimeError('fixture transaction abort before acknowledgement')
        # A separate connection sees commit BEFORE this receipt is returned.
        with closing(sqlite3.connect(self.path)) as reader:
            assert reader.execute('SELECT text FROM inbox WHERE id=?', (rid,)).fetchone() == (text,)
        return {'request_id': rid, 'accepted': True, 'state': self.state(rid)}

    def state(self, rid):
        row = self.db.execute('SELECT state FROM inbox WHERE id=?', (rid,)).fetchone()
        return row[0] if row else None

    def transition(self, rid, old, new):
        with self.db:
            cur = self.db.execute('UPDATE inbox SET state=? WHERE id=? AND state=?', (new, rid, old))
        return cur.rowcount == 1

    def recover(self):
        with self.db:
            self.db.execute("UPDATE inbox SET state='unknown_outcome' WHERE state IN ('dispatching','started')")


class Contract(unittest.TestCase):
    def setUp(self):
        from gateway.config import Platform, PlatformConfig
        from gateway.platforms.base import BasePlatformAdapter, SendResult
        from gateway.platforms.event import MessageEvent
        from gateway.session import SessionSource
        self.Platform, self.Config = Platform, PlatformConfig
        self.Event, self.Source = MessageEvent, SessionSource
        self.journal = JournalFixture(Path(os.environ['HOME']) / 'fixture.sqlite')
        self.addCleanup(self.journal.db.close)
        owner = self

        class ProbeAdapter(BasePlatformAdapter):
            def __init__(self, config):
                super().__init__(config, Platform('voice_contract_fixture'))
                self.trace, self.sends = [], []
                self.done, self.entered = asyncio.Event(), asyncio.Event()
                self.fail_start = self.fail_complete = False

            async def connect(self, *, is_reconnect=False):
                self.trace.append(('connect', is_reconnect))
                return True

            async def disconnect(self):
                await self.cancel_background_tasks()
                self.trace.append(('disconnect',))

            async def send(self, chat_id, content, reply_to=None, metadata=None):
                self.sends.append((chat_id, content, reply_to, metadata))
                return SendResult(success=True, message_id='fixture-output')

            async def get_chat_info(self, chat_id):
                return {'id': chat_id, 'name': 'Offline fixture', 'type': 'dm'}

            async def on_processing_start(self, event):
                self.trace.append(('start', event.message_id, dict(event.metadata)))
                if self.fail_start:
                    raise OSError('fixture start receipt storage failure')
                owner.journal.transition(event.message_id, 'dispatching', 'started')
                self.entered.set()

            async def on_processing_complete(self, event, outcome):
                self.trace.append(('complete', event.message_id, outcome.value))
                self.done.set()
                if self.fail_complete:
                    raise OSError('fixture completion receipt storage failure')
                # Do not equate base SUCCESS with business completion.
                # Keep started until a separate verified execution contract exists.

        self.Adapter = ProbeAdapter
        from hermes_cli.plugins import PluginContext, PluginManager, PluginManifest
        self.manager = PluginManager(scope_key=os.environ['HERMES_HOME'])
        self.ctx = PluginContext(PluginManifest(name='voice-contract-fixture', path=str(PLUGIN)), self.manager)
        self.addCleanup(self.manager.unload)
        self.registration = self.ctx.register_platform(
            name='voice_contract_fixture', label='Offline fixture',
            adapter_factory=self.Adapter, check_fn=lambda: True)

    def event(self, rid='r1', parent=None):
        return self.Event(text='fixture request', message_id=rid,
                          source=self.Source(platform=self.Platform('voice_contract_fixture'),
                                             chat_id='voice-lane', user_id='fixture-user'),
                          metadata={'voice_request_id': rid, 'parent_request_id': parent},
                          internal=False, allow_gateway_control=False)

    def stage(self, rid='r1', parent=None):
        self.journal.accept(rid, parent=parent)
        assert self.journal.transition(rid, 'received', 'dispatching')
        return self.event(rid, parent)

    def test_registration_lifecycle(self):
        from gateway.platform_registry import platform_registry
        manager = self.manager
        self.assertIsNotNone(self.registration)
        adapter = platform_registry.create_adapter('voice_contract_fixture', self.Config(enabled=True))
        self.assertIsInstance(adapter, self.Adapter)
        async def run():
            self.assertTrue(await adapter.connect(is_reconnect=False))
            await adapter.disconnect()
            self.assertTrue(await adapter.connect(is_reconnect=True))
            await adapter.disconnect()
        asyncio.run(run())
        manager.unload()
        self.assertFalse(platform_registry.is_registered('voice_contract_fixture'))
        print('EVIDENCE registry: official register/create/unload; fixture connect/reconnect/disconnect')

    async def lifecycle(self, response=None, fail_start=False, fail_complete=False, cancel=False):
        a = self.Adapter(self.Config(enabled=True))
        a.fail_start, a.fail_complete = fail_start, fail_complete
        hold = asyncio.Event()
        async def handler(event):
            a.trace.append(('handler', event.message_id, dict(event.metadata)))
            if cancel:
                await hold.wait()
            return response
        a.set_message_handler(handler)
        await a.handle_message(self.stage())
        if cancel:
            await asyncio.wait_for(a.entered.wait(), 3)
            await a.cancel_background_tasks()
        else:
            await asyncio.wait_for(a.done.wait(), 3)
        await a.disconnect()
        return a

    def test_event_receipts_and_delivery(self):
        a = asyncio.run(self.lifecycle(response='offline answer'))
        self.assertEqual([r[0] for r in a.trace[:3]], ['start', 'handler', 'complete'])
        self.assertEqual(a.trace[0][1:], ('r1', {'voice_request_id': 'r1', 'parent_request_id': None}))
        self.assertEqual(a.trace[1][1:], ('r1', {'voice_request_id': 'r1', 'parent_request_id': None}))
        self.assertEqual(a.trace[2], ('complete', 'r1', 'success'))
        self.assertEqual(a.sends[0][1], 'offline answer')
        self.assertNotIn('voice_request_id', a.sends[0][3] or {})
        print('EVIDENCE IDs: message_id+metadata reach start/handler/complete; send metadata does NOT forward request ID')

    def test_empty_is_success_not_execution(self):
        a = asyncio.run(self.lifecycle())
        self.assertIn(('complete', 'r1', 'success'), a.trace)
        self.assertEqual(a.sends, [])
        self.assertEqual(self.journal.state('r1'), 'started')
        print('EVIDENCE ambiguity: handler None => start + SUCCESS with no agent execution or send')

    def test_start_receipt_failure_is_not_a_gate(self):
        a = asyncio.run(self.lifecycle(fail_start=True))
        self.assertIn('handler', [r[0] for r in a.trace])
        self.assertEqual(self.journal.state('r1'), 'dispatching')
        self.journal.recover()
        self.assertEqual(self.journal.state('r1'), 'unknown_outcome')
        print('EVIDENCE start hook exception swallowed; handler STILL RUNS; failed durable receipt is not fail-closed')

    def test_completion_receipt_failure_is_unknown(self):
        a = asyncio.run(self.lifecycle(response='offline answer', fail_complete=True))
        self.assertTrue(a.sends)
        self.assertEqual(self.journal.state('r1'), 'started')
        self.journal.recover()
        self.assertEqual(self.journal.state('r1'), 'unknown_outcome')
        print('EVIDENCE delivered fixture answer + lost completion receipt => unknown_outcome, NOT retry')

    def test_cancel_is_not_rollback(self):
        a = asyncio.run(self.lifecycle(cancel=True))
        self.assertIn(('complete', 'r1', 'cancelled'), a.trace)
        self.assertEqual(self.journal.state('r1'), 'started')
        self.journal.recover()
        self.assertEqual(self.journal.state('r1'), 'unknown_outcome')
        print('EVIDENCE public cancellation hook correlated; no evidence prior effects rolled back')

    def test_durable_acceptance_and_single_consumer(self):
        j = self.journal
        with self.assertRaises(RuntimeError):
            j.accept('aborted', abort=True)
        self.assertIsNone(j.state('aborted'))
        self.assertTrue(j.accept('r1')['accepted'])
        self.assertTrue(j.accept('r1')['accepted'])
        with self.assertRaises(ValueError):
            j.accept('r1', text='different payload')
        j.accept('r2', parent='r1')
        other = JournalFixture(j.path)
        self.addCleanup(other.db.close)
        self.assertTrue(j.transition('r1', 'received', 'dispatching'))
        with self.assertRaises(sqlite3.IntegrityError):
            other.transition('r2', 'received', 'dispatching')
        self.assertFalse(other.transition('r1', 'received', 'dispatching'))
        self.assertTrue(j.transition('r1', 'dispatching', 'started'))
        with self.assertRaises(sqlite3.IntegrityError):
            other.transition('r2', 'received', 'dispatching')
        # Fixture explicit result, not inferred from ProcessingOutcome.SUCCESS.
        self.assertTrue(j.transition('r1', 'started', 'completed'))
        self.assertTrue(other.transition('r2', 'received', 'dispatching'))
        print('EVIDENCE SQL fixture: commit-before-ACK, rollback-no-ACK, dedupe/conflict, two connections one lane owner')

    def test_single_consumer_delivery(self):
        async def run():
            a = self.Adapter(self.Config(enabled=True))
            released = asyncio.Event()
            deliveries = []
            async def handler(event):
                deliveries.append(event.message_id)
                await released.wait()
                return None
            a.set_message_handler(handler)
            j = self.journal
            j.accept('r1')
            j.accept('r2', parent='r1')
            other = JournalFixture(j.path)
            self.addCleanup(other.db.close)
            async def attempt(db, rid):
                try:
                    owned = db.transition(rid, 'received', 'dispatching')
                except sqlite3.IntegrityError:
                    return False
                if not owned:
                    return False
                await a.handle_message(self.event(rid))
                return True
            self.assertEqual(await asyncio.gather(attempt(j, 'r1'), attempt(other, 'r2')), [True, False])
            await asyncio.wait_for(a.entered.wait(), 3)
            self.assertEqual(deliveries, ['r1'])
            self.assertFalse(await attempt(other, 'r2'))
            released.set()
            await asyncio.wait_for(a.done.wait(), 3)
            await a.disconnect()
            # Handler completion is insufficient: uncertain owner blocks further dispatch.
            j.recover()
            self.assertEqual(j.state('r1'), 'unknown_outcome')
            self.assertFalse(await attempt(other, 'r2'))
            self.assertEqual(deliveries, ['r1'])
        asyncio.run(run())
        print('EVIDENCE two fixture claimers + real handle_message: exactly one handler delivery; unknown owner blocks next request')

    def test_recovery_never_blind_replays(self):
        j = self.journal
        for state in ('received', 'dispatching', 'started', 'completed', 'blocked', 'unknown_outcome'):
            rid = 'state-' + state
            j.accept(rid)
            # Use distinct lanes solely to test all crash-window states in one DB.
            with j.db:
                j.db.execute('UPDATE inbox SET lane=?,state=? WHERE id=?', (rid, state, rid))
        restored = JournalFixture(j.path)
        self.addCleanup(restored.db.close)
        restored.recover()
        for old in ('received', 'dispatching', 'started', 'completed', 'blocked', 'unknown_outcome'):
            expected = 'unknown_outcome' if old in ('dispatching', 'started') else old
            self.assertEqual(restored.state('state-' + old), expected)
        self.assertEqual(restored.db.execute("SELECT id FROM inbox WHERE state='received'").fetchall(), [('state-received',)])
        print('EVIDENCE reopened SQL fixture: dispatching/started => unknown; only received eligible; no automatic replay')

    def test_approval_one_use_expiry(self):
        spec = importlib.util.spec_from_file_location('fixture_confirmation', PLUGIN / 'voice_confirmation.py')
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        now = [10.0]
        c = mod.VoiceConfirmation(clock=lambda: now[0])
        digest = hashlib.sha256(b'explicit fixture target and draft').hexdigest()
        c.begin('r1', 'action1', 'fixture prompt', 'allow fixture', digest)
        with self.assertRaises(ValueError):
            c.get('r2', 'action1')
        armed = c.arm('r1', 'action1')
        self.assertEqual(armed['digest'], digest)
        c.claim_audio('r1', 'action1', armed['nonce'])
        with self.assertRaises(ValueError):
            c.claim_audio('r1', 'action1', armed['nonce'])
        result = c.finish_audio('r1', 'action1', 'allow fixture')
        self.assertTrue(result['allowed'])
        self.assertEqual(result['digest'], digest)
        with self.assertRaises(ValueError):
            c.finish_audio('r1', 'action1', 'allow fixture')
        now[0] = 250.0
        with self.assertRaisesRegex(ValueError, 'expired'):
            c.get('r1', 'action1')
        with self.assertRaisesRegex(ValueError, 'unknown'):
            mod.VoiceConfirmation().get('r1', 'action1')
        print('EVIDENCE existing confirmation: request/action/digest + nonce single-use; expires; new instance restores NO approval')

    def test_identity_and_followup(self):
        from gateway.session import build_session_key
        e1, e2 = self.event('r1'), self.event('r2', 'r1')
        self.assertEqual(build_session_key(e1.source), build_session_key(e2.source))
        telegram = self.Source(platform=self.Platform.TELEGRAM, chat_id='voice-lane', user_id='fixture-user')
        self.assertNotEqual(build_session_key(e1.source), build_session_key(telegram))
        # Source-contract check: public pre_llm kwargs carry SID/turn/platform, NOT inbound ID.
        tree = ast.parse((CORE / 'agent/turn_context.py').read_text())
        fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == '_collect_pre_llm_call_context')
        call = next(n for n in ast.walk(fn) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == '_invoke_hook')
        keys = {k.arg for k in call.keywords}
        self.assertTrue({'session_id', 'turn_id', 'platform'} <= keys)
        self.assertFalse({'request_id', 'message_id', 'inbound_message_id', 'metadata'} & keys)
        # Import source package modules without executing plugin registration/startup.
        package = types.ModuleType('voice_contract_source')
        package.__path__ = [str(PLUGIN)]
        sys.modules[package.__name__] = package
        mod = importlib.import_module('voice_contract_source.voicelink')
        link = mod.VoiceLink(types.SimpleNamespace(get_config=lambda key, default=None: default))
        text = 'fixture voice content'
        link._pending = {'request_id': 'r1', 'session_id': 'old-sid', 'digest': mod._text_digest(text)}
        self.assertIsNotNone(link.prepare_voice_turn(session_id='old-sid', turn_id='t1', user_message=text))
        self.assertEqual(link.guard_voice_tool(session_id='old-sid', turn_id='t1', tool_name='terminal', args={})['action'], 'block')
        self.assertIsNone(link.prepare_voice_turn(session_id='new-sid', turn_id='t2', user_message=text))
        self.assertIsNone(link.guard_voice_tool(session_id='new-sid', turn_id='t2', tool_name='terminal', args={}))
        print('EVIDENCE follow-up lane stable and distinct from Telegram; pre_llm lacks request ID; unstarted link WITHOUT _home cannot rebind SID')

    def route_link_fixture(self, label):
        # Load unchanged source; never register/start a bridge or read real config.
        package = types.ModuleType('voice_contract_source')
        package.__path__ = [str(PLUGIN)]
        sys.modules[package.__name__] = package
        mod = importlib.import_module('voice_contract_source.voicelink')
        route = 'agent:main:voice_contract_fixture:dm:fixture'
        config = {'session_key': route}
        link = mod.VoiceLink(types.SimpleNamespace(get_config=config.get))
        home = Path(os.environ['HOME']) / label
        home.mkdir()
        link._home = home
        text = 'fixture voice content'
        link._pending = {'request_id': 'r1', 'session_id': 'old-sid',
                         'digest': mod._text_digest(text)}
        self.assertIsNotNone(link.prepare_voice_turn(session_id='old-sid', turn_id='t1', user_message=text))
        self.assertEqual(link.guard_voice_tool(session_id='old-sid', turn_id='t1',
                                              tool_name='terminal', args={})['action'], 'block')
        return mod, link, home, route, text

    def write_route_fixture(self, home, route, sid, *, scope=None):
        # Same routing schema/scope as existing voicelink_contract.py fixture.
        with closing(sqlite3.connect(home / 'state.db')) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS gateway_routing (scope TEXT NOT NULL, session_key TEXT NOT NULL, entry_json TEXT NOT NULL, updated_at REAL NOT NULL, PRIMARY KEY(scope, session_key))')
            db.execute('INSERT OR REPLACE INTO gateway_routing VALUES (?, ?, ?, ?)',
                       (scope if scope is not None else str((home / 'sessions').resolve()),
                        route, json.dumps({'session_id': sid}), 1.0))

    def test_sid_rebind_current_route(self):
        mod, link, home, route, text = self.route_link_fixture('current-route')
        self.write_route_fixture(home, route, 'old-sid')
        self.assertEqual(mod._current_route_session_id(home, route), 'old-sid')
        # Simulate committed route rollover, not a running gateway or injection.
        self.write_route_fixture(home, route, 'new-sid')
        self.assertEqual(mod._current_route_session_id(home, route), 'new-sid')
        # A current route alone is insufficient: exact message digest must match.
        self.assertIsNone(link.prepare_voice_turn(session_id='new-sid', turn_id='wrong-text',
                                                 user_message='different fixture content'))
        self.assertIsNone(link.guard_voice_tool(session_id='new-sid', turn_id='wrong-text',
                                               tool_name='terminal', args={}))
        self.assertEqual(link._pending['session_id'], 'old-sid')
        self.assertIsNotNone(link.prepare_voice_turn(session_id='new-sid', turn_id='t2', user_message=text))
        self.assertEqual((link._pending['request_id'], link._pending['session_id'],
                          link._pending['turn_id']), ('r1', 'new-sid', 't2'))
        self.assertEqual(link.voice_request('new-sid', 't2'), 'r1')
        self.assertEqual(link.guard_voice_tool(session_id='new-sid', turn_id='t2',
                                              tool_name='terminal', args={})['action'], 'block')
        self.assertIsNone(link.guard_voice_tool(session_id='new-sid', turn_id='other-turn',
                                               tool_name='terminal', args={}))
        print('EVIDENCE real SQLite route old-sid -> new-sid + matching digest: pending r1 rebinds to new-sid/t2; terminal BLOCKED; wrong text/turn stays unmatched')

    def test_sid_rebind_unavailable_or_mismatched_route(self):
        for condition in ('missing-db', 'wrong-scope', 'wrong-key', 'different-sid'):
            with self.subTest(condition=condition):
                mod, link, home, route, text = self.route_link_fixture(condition)
                if condition == 'wrong-scope':
                    self.write_route_fixture(home, route, 'new-sid', scope=str(home / 'other-sessions'))
                elif condition == 'wrong-key':
                    self.write_route_fixture(home, route + '-other', 'new-sid')
                elif condition == 'different-sid':
                    self.write_route_fixture(home, route, 'third-sid')
                resolved = 'third-sid' if condition == 'different-sid' else None
                self.assertEqual(mod._current_route_session_id(home, route), resolved)
                pending_before = dict(link._pending)
                self.assertIsNone(link.prepare_voice_turn(session_id='new-sid', turn_id='t2', user_message=text))
                self.assertEqual(link._pending, pending_before)
                self.assertIsNone(link.guard_voice_tool(session_id='new-sid', turn_id='t2',
                                                       tool_name='terminal', args={}))
                self.assertEqual(link.guard_voice_tool(session_id='old-sid', turn_id='t1',
                                                      tool_name='terminal', args={})['action'], 'block')
                print(f'EVIDENCE {condition}: actual route reader={resolved!r}; pending unchanged; new SID guard unmatched, old guard BLOCKED')


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--case':
        isolate()
        suite = unittest.TestSuite([Contract('test_' + sys.argv[2])])
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    failed = []
    for case in CASES:  # Sequential, no production environment forwarding.
        with tempfile.TemporaryDirectory(prefix='voice-adapter-probe-', dir=SCRATCH) as home:
            env = {'HOME': home, 'HERMES_HOME': home, 'TMPDIR': home,
                   'PATH': '/data/data/com.termux/files/usr/bin', 'LANG': 'C.UTF-8',
                   'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONPATH': str(CORE)}
            try:
                result = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--case', case],
                                        env=env, cwd=home, text=True, capture_output=True, timeout=60)
                print(result.stdout, end='')
                print(result.stderr, end='')
                if result.returncode:
                    failed.append(case)
            except subprocess.TimeoutExpired:
                failed.append(case)
                print('TIMEOUT', case)
    print(json.dumps({'cases': len(CASES), 'passed': len(CASES) - len(failed),
                      'failed': failed, 'deployment_gate': 'NO-GO'}))
    return bool(failed)


if __name__ == '__main__':
    raise SystemExit(main())
