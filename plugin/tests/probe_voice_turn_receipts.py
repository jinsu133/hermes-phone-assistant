"""Offline source-only spike: public adapter -> ContextVar -> real AIAgent turn.

No runtime monkeypatching. llm_execution middleware supplies fixture responses;
public hooks and tool_execution middleware receive actual core-generated turn IDs.
The dedicated fixture handler is NOT GatewayRunner._handle_message integration.
Audit hooks cover watched events, not an OS security sandbox.
"""
import asyncio
from contextlib import closing
from contextvars import ContextVar
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest

CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
OWNER = ContextVar('offline_voice_owner', default=None)
CASES = ('happy', 'missing_owner', 'lost_executor_context', 'start_observer_failure',
         'durable_start_failure', 'guard_storage_failure', 'metadata_mismatch', 'payload_mismatch',
         'session_mismatch', 'handler_none', 'post_receipt_failure', 'completion_commit_failure',
         'replay', 'stale_post', 'followup', 'unknown_restart', 'interrupted_after_effect', 'budget_exhaustion', 'policy_happy', 'policy_missing_owner', 'policy_missing_start',
         'policy_exception', 'policy_timeout', 'middleware_exception_failopen')


def isolate():
    home = Path(os.environ['HOME']).resolve()
    assert home == Path(os.environ['HERMES_HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-turn-probe-')
    sys.dont_write_bytecode = True
    def audit(event, args):
        if event in {'socket.connect', 'socket.bind', 'socket.getaddrinfo', 'subprocess.Popen', 'os.system'}:
            raise PermissionError('offline probe forbids network/processes')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            p = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1], str) and any(x in args[1] for x in 'wax+')) or (
                isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT))
            if writing and not p.is_relative_to(home):
                raise PermissionError('write outside isolated HOME')
            if p.is_relative_to(CORE.parent) and not p.is_relative_to(CORE) and not p.is_relative_to(home):
                raise PermissionError('production profile access')
            if p.is_relative_to(CORE) and (p.name == '.env' or p.name.startswith('auth.json')):
                raise PermissionError('credentials forbidden')
    sys.addaudithook(audit)
    sys.path.insert(0, str(CORE))
    # Only disposable configuration: disable unrelated background features.
    (home / 'config.yaml').write_text('plugins:\n  hook_callback_timeout: 0.2\nmemory:\n  memory_enabled: false\n  user_profile_enabled: false\ncompression:\n  enabled: false\n')
    return home


class Probe(unittest.TestCase):
    def setUp(self):
        from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
        self.manager = get_plugin_manager()
        self.ctx = PluginContext(PluginManifest(name='voice-turn-fixture', path=str(Path(__file__).parent)), self.manager)
        self.addCleanup(self.manager.unload)
        self.trace, self.effects, self.results = [], [], []
        self.ctx.register_hook('pre_llm_call', self.pre)
        self.ctx.register_hook('post_llm_call', self.post)
        self.ctx.register_middleware('llm_execution', self.model)
        self.ctx.register_middleware('tool_execution', self.guard)
        self.ctx.register_tool(name='voice_fixture_mutate', toolset='voice_fixture',
                               schema={'name': 'voice_fixture_mutate', 'description': 'Offline counter only',
                                       'parameters': {'type': 'object', 'properties': {}}},
                               handler=self.effect)
        self.path = Path(os.environ['HOME']) / 'receipts.sqlite'
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE receipts (rid TEXT PRIMARY KEY, state TEXT, sid TEXT, tid TEXT, text TEXT NOT NULL DEFAULT \'Exercise offline counter.\')')
            db.execute("INSERT INTO receipts (rid,state,sid,tid) VALUES ('r1','dispatching',NULL,NULL)")
        self.mode = 'happy'
        self.calls = 0
        self.rid = 'r1'
        self.denied = False
        self.last_agent = None
        self.policy_release = threading.Event()
        self.addCleanup(self.policy_release.set)

    def row(self, rid=None):
        with closing(sqlite3.connect(self.path)) as db:
            return db.execute('SELECT state,sid,tid FROM receipts WHERE rid=?', (rid or self.rid,)).fetchone()

    def accepted_text(self):
        with closing(sqlite3.connect(self.path)) as db:
            return db.execute('SELECT text FROM receipts WHERE rid=?', (self.rid,)).fetchone()[0]

    def update(self, sql, args=()):
        with closing(sqlite3.connect(self.path)) as db, db:
            return db.execute(sql, args).rowcount

    def pre(self, **kw):
        self.trace.append(('pre', OWNER.get(), kw['session_id'], kw['turn_id']))
        if OWNER.get() != (self.rid, 'fixture-attempt', 'fixture-sid') or kw['session_id'] != 'fixture-sid':
            return
        if self.mode in ('durable_start_failure', 'policy_missing_start'):
            raise OSError('injected durable start write failure')
        self.update("UPDATE receipts SET state='started',sid=?,tid=? WHERE rid=? AND state='dispatching'",
                    (kw['session_id'], kw['turn_id'], self.rid))

    def post(self, **kw):
        self.trace.append(('post', OWNER.get(), kw['session_id'], kw['turn_id']))
        if self.mode == 'post_receipt_failure':
            raise OSError('injected post receipt write failure')
        if OWNER.get() == (self.rid, 'fixture-attempt', 'fixture-sid'):
            self.update("UPDATE receipts SET state='post_turn' WHERE rid=? AND state='started' AND sid=? AND tid=?",
                        (self.rid, kw['session_id'], kw['turn_id']))

    def policy(self, **kw):
        self.trace.append(('policy', OWNER.get(), kw['session_id'], kw['turn_id']))
        if self.mode == 'policy_exception':
            raise OSError('injected unhandled policy storage exception')
        if self.mode == 'policy_timeout':
            self.policy_release.wait(5)
        if (OWNER.get() != (self.rid, 'fixture-attempt', 'fixture-sid')
                or self.row() != ('started', kw['session_id'], kw['turn_id'])):
            return {'action': 'block', 'message': 'owner or durable start receipt missing'}
        return None

    def guard(self, next_call, **kw):
        if self.mode.startswith('policy_'):
            # Prove the supported POLICY hook is a separate fail-closed gate even
            # when execution middleware itself crashes and core skips that middleware.
            self.trace.append(('middleware_fault',))
            raise OSError('injected broken execution middleware')
        # Catch validation/storage errors and RETURN a denial. Raising here is fail-open in core.
        try:
            if self.mode == 'guard_storage_failure':
                raise sqlite3.OperationalError('injected reader failure')
            row = self.row()
            allowed = (OWNER.get() == (self.rid, 'fixture-attempt', 'fixture-sid')
                       and row == ('started', kw['session_id'], kw['turn_id']))
        except Exception:
            allowed = False
        self.trace.append(('guard', allowed, OWNER.get(), kw['session_id'], kw['turn_id']))
        if not allowed:
            self.denied = True
            return json.dumps({'error': 'owner/start receipt missing', 'blocked': True})
        return next_call()

    @staticmethod
    def has_fixture_effect_receipt(result):
        # Fixture tool protocol, not assistant prose. Production needs operation-specific receipts.
        tools = [m for m in result.get('messages', []) if m.get('role') == 'tool']
        try:
            return bool(tools) and all(m.get('name') == 'voice_fixture_mutate' and
                                      json.loads(m.get('content', '')) == {'success': True} for m in tools)
        except (TypeError, ValueError):
            return False

    def effect(self, args, **kw):
        self.effects.append('mutation')
        if self.mode == 'interrupted_after_effect':
            self.last_agent.interrupt('offline fixture interruption')
        return json.dumps({'success': True})

    def model(self, **kw):
        from openai.types.chat import ChatCompletion
        self.calls += 1
        msg = {'role': 'assistant', 'content': 'Fixture final response.'}
        finish = 'stop'
        if self.calls % 2 == 1:
            msg = {'role': 'assistant', 'content': None, 'tool_calls': [
                {'id': 'fixture-call', 'type': 'function', 'function': {'name': 'tool_call', 'arguments': json.dumps({'calls': [{'name': 'voice_fixture_mutate', 'arguments': {}}]})}}]}
            finish = 'tool_calls'
        return ChatCompletion.model_validate({'id': 'fixture-response', 'object': 'chat.completion', 'created': 0,
                    'model': 'fixture-model', 'choices': [{'index': 0, 'message': msg, 'finish_reason': finish}],
                    'usage': {'prompt_tokens': 10, 'completion_tokens': 3, 'total_tokens': 13}})

    async def drive(self):
        from gateway.config import Platform, PlatformConfig
        from gateway.platforms.base import BasePlatformAdapter, SendResult
        from gateway.platforms.event import MessageEvent
        from gateway.session import SessionSource
        from run_agent import AIAgent
        owner = self
        if self.mode.startswith('policy_'):
            self.ctx.register_hook('pre_tool_call', self.policy)
        class Adapter(BasePlatformAdapter):
            def __init__(self, config):
                super().__init__(config, Platform('voice_turn_fixture'))
                self.done = asyncio.Event()
                self.outcome = None
            async def connect(self, *, is_reconnect=False): return True
            async def disconnect(self): await self.cancel_background_tasks()
            async def get_chat_info(self, chat_id): return {'id': chat_id, 'type': 'dm'}
            async def send(self, chat_id, content, reply_to=None, metadata=None):
                return SendResult(success=True, message_id='fixture-output')
            async def on_processing_start(self, event):
                OWNER.set(None)  # never reuse a previous task's owner
                if owner.mode == 'start_observer_failure':
                    raise OSError('injected adapter observer failure')
                if (owner.mode not in ('missing_owner', 'policy_missing_owner') and event.message_id == owner.rid
                        and event.metadata.get('voice_request_id') == owner.rid
                        and event.text == owner.accepted_text()
                        and owner.row()[0] == 'dispatching'):
                    OWNER.set((event.message_id, 'fixture-attempt', 'fixture-sid'))
                owner.trace.append(('adapter_start', OWNER.get()))
            async def on_processing_complete(self, event, outcome):
                self.outcome = outcome.value
                owner.trace.append(('adapter_complete', OWNER.get(), outcome.value))
                self.done.set()
        self.ctx.register_platform(name='voice_turn_fixture', label='Offline', adapter_factory=Adapter, check_fn=lambda: True)
        from gateway.platform_registry import platform_registry
        adapter = platform_registry.create_adapter('voice_turn_fixture', PlatformConfig(enabled=True))
        agent = AIAgent(model='fixture-model', provider='openai', api_key='offline-placeholder',
                        base_url='https://offline.invalid/v1', enabled_toolsets=['voice_fixture'],
                        max_iterations=(1 if self.mode == 'budget_exhaustion' else 3), quiet_mode=True, skip_memory=True, skip_context_files=True,
                        skip_background_review=True, platform='voice_turn_fixture', session_id=('wrong-sid' if self.mode == 'session_mismatch' else 'fixture-sid'),
                        cwd=os.environ['HOME'])
        self.last_agent = agent
        async def handler(event):
            owner.trace.append(('handler', OWNER.get()))
            if owner.mode == 'handler_none':
                return None
            def run():
                return agent.run_conversation(event.text, system_message='Offline fixture.', task_id='fixture-task')
            if owner.mode == 'lost_executor_context':
                result = await asyncio.get_running_loop().run_in_executor(None, run)
            else:
                result = await asyncio.to_thread(run)
            owner.results.append(result)
            # A structured core envelope + exact committed pre/post tuple, NOT model prose.
            row = owner.row()
            if (OWNER.get() == (owner.rid, 'fixture-attempt', 'fixture-sid')
                    and row == ('post_turn', result.get('session_id'), result.get('turn_id'))
                    and result.get('completed') is True
                    and not any(result.get(k) for k in ('failed', 'partial', 'interrupted'))
                    and str(result.get('turn_exit_reason', '')).startswith('text_response(')
                    and owner.has_fixture_effect_receipt(result)
                    and not owner.denied):
                if owner.mode == 'completion_commit_failure':
                    raise OSError('injected completion commit failure')
                owner.update("UPDATE receipts SET state='turn_completed' WHERE rid=? AND state='post_turn' AND sid=? AND tid=?",
                             (owner.rid, result['session_id'], result['turn_id']))
            return result.get('final_response')
        adapter.set_message_handler(handler)
        try:
            await adapter.handle_message(MessageEvent(text=('Changed payload' if self.mode == 'payload_mismatch' else 'Exercise offline counter.'), message_id=self.rid,
                    source=SessionSource(platform=Platform('voice_turn_fixture'), chat_id='voice-lane', user_id='fixture'),
                    metadata={'voice_request_id': ('forged-rid' if self.mode == 'metadata_mismatch' else self.rid)}, allow_gateway_control=False))
            await asyncio.wait_for(adapter.done.wait(), 35)
        finally:
            await adapter.disconnect()
            self.policy_release.set()
            agent.close()
        # Unknown never authorizes replay. Lane-wide exclusion is a separate inbox contract.
        self.update("UPDATE receipts SET state='unknown_outcome' WHERE rid=? AND state IN ('dispatching','started','post_turn')", (self.rid,))
        return adapter

    def test_happy(self):
        a = asyncio.run(self.drive())
        print('TRACE', json.dumps(self.trace))
        print('ENVELOPE', json.dumps([{k:r.get(k) for k in ('session_id','turn_id','completed','failed','partial','interrupted','turn_exit_reason')} for r in self.results]))
        self.assertEqual(self.effects, ['mutation'])
        result = self.results[0]
        self.assertEqual(self.row(), ('turn_completed', result['session_id'], result['turn_id']))
        for label in ('pre', 'post'):
            observed = [x for x in self.trace if x[0] == label]
            self.assertEqual(observed, [(label, ('r1', 'fixture-attempt', 'fixture-sid'), result['session_id'], result['turn_id'])])
        self.assertEqual(a.outcome, 'success')
        self.assertIsNone(OWNER.get())

    def assert_blocked(self, mode):
        self.mode = mode
        adapter = asyncio.run(self.drive())
        self.assertEqual(self.effects, [])
        self.assertEqual(self.accepted_text(), 'Exercise offline counter.')
        self.assertEqual(self.row()[0], 'unknown_outcome')
        self.assertTrue(any(t[0] == 'guard' and t[1] is False for t in self.trace))
        self.assertIsNone(OWNER.get())
        print('EVIDENCE', mode, 'effects=0; unknown_outcome; adapter=' + str(adapter.outcome), json.dumps(self.trace))

    def test_missing_owner(self): self.assert_blocked('missing_owner')
    def test_lost_executor_context(self): self.assert_blocked('lost_executor_context')
    def test_start_observer_failure(self): self.assert_blocked('start_observer_failure')
    def test_durable_start_failure(self): self.assert_blocked('durable_start_failure')
    def test_guard_storage_failure(self): self.assert_blocked('guard_storage_failure')
    def test_metadata_mismatch(self): self.assert_blocked('metadata_mismatch')
    def test_payload_mismatch(self): self.assert_blocked('payload_mismatch')
    def test_session_mismatch(self): self.assert_blocked('session_mismatch')

    def test_handler_none(self):
        self.mode = 'handler_none'
        a = asyncio.run(self.drive())
        self.assertEqual(a.outcome, 'success')
        self.assertEqual(self.row()[0], 'unknown_outcome')
        self.assertEqual(self.results, [])
        self.assertEqual(self.effects, [])
        print('EVIDENCE handler None: adapter SUCCESS; no actual turn; NOT completed')

    def test_post_receipt_failure(self):
        self.mode = 'post_receipt_failure'
        a = asyncio.run(self.drive())
        self.assertEqual(a.outcome, 'success')
        self.assertEqual(self.effects, ['mutation'])
        self.assertTrue(self.results[0]['completed'])
        self.assertEqual(self.row()[0], 'unknown_outcome')
        print('EVIDENCE mutation happened; structured completed=true + adapter SUCCESS insufficient without committed post receipt; unknown preserved')

    def test_completion_commit_failure(self):
        self.mode = 'completion_commit_failure'
        a = asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'])
        self.assertEqual(self.row()[0], 'unknown_outcome')
        self.assertNotEqual(a.outcome, 'success')
        print('EVIDENCE completion commit failed after mutation; unknown_outcome; adapter=' + a.outcome)

    def test_replay(self):
        asyncio.run(self.drive())
        before = self.row()
        asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'])
        self.assertEqual(self.row(), before)
        second_pre = [x for x in self.trace if x[0] == 'pre'][1]
        self.assertIsNone(second_pre[1])
        self.assertNotEqual(second_pre[3], before[2])
        print('EVIDENCE same accepted request replay: new real agent turn unbound; tool denied; prior tuple unchanged', json.dumps(self.trace))

    def test_stale_post(self):
        asyncio.run(self.drive())
        original = self.row()
        # Fault injection at public lifecycle boundary: a stale/mismatched delivery must not bind.
        self.update("UPDATE receipts SET state='started' WHERE rid='r1'")
        from hermes_cli.lifecycle import invoke_hook
        token = OWNER.set(('r1', 'fixture-attempt', 'fixture-sid'))
        try:
            for sid, tid in [('other-sid', original[2]), (original[1], 'other-turn')]:
                invoke_hook('post_llm_call', session_id=sid, turn_id=tid)
                self.assertEqual(self.row(), ('started', original[1], original[2]))
            OWNER.set(('other-request', 'fixture-attempt', 'fixture-sid'))
            invoke_hook('post_llm_call', session_id=original[1], turn_id=original[2])
            self.assertEqual(self.row()[0], 'started')
        finally:
            OWNER.reset(token)
        print('EVIDENCE stale post hook SID/turn/request mismatches never finish or rebind')

    def test_followup(self):
        asyncio.run(self.drive())
        first = self.row()
        # Explicit parent association, separately committed; not an inferred Telegram history link.
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE parents (rid TEXT PRIMARY KEY,parent TEXT)')
            db.execute("INSERT INTO receipts (rid,state,sid,tid) VALUES ('r2','dispatching',NULL,NULL)")
            db.execute("INSERT INTO parents VALUES ('r2','r1')")
        self.rid = 'r2'
        asyncio.run(self.drive())
        second = self.row()
        self.assertEqual(self.effects, ['mutation', 'mutation'])
        self.assertEqual(first[:2], ('turn_completed', 'fixture-sid'))
        self.assertEqual(second[:2], ('turn_completed', 'fixture-sid'))
        self.assertNotEqual(first[2], second[2])
        self.assertEqual(self.row('r1'), first)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(db.execute("SELECT parent FROM parents WHERE rid='r2'").fetchone(), ('r1',))
        print('EVIDENCE followup r2 -> r1: same dedicated SID, distinct real turn IDs; parent commit visible; approval not tested by this case')

    def test_unknown_restart(self):
        self.mode = 'post_receipt_failure'
        asyncio.run(self.drive())
        self.assertEqual(self.row()[0], 'unknown_outcome')
        self.mode = 'happy'
        asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'])
        self.assertEqual(self.row()[0], 'unknown_outcome')
        print('EVIDENCE reopen durable unknown + attempted replay: effects still 1; unknown never replay-authorized')

    def test_interrupted_after_effect(self):
        self.mode = 'interrupted_after_effect'
        asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'])
        self.assertTrue(self.results[0]['interrupted'])
        self.assertEqual(self.row()[0], 'unknown_outcome')
        print('EVIDENCE actual agent.interrupt after effect:', {k:self.results[0].get(k) for k in ('completed','interrupted','turn_exit_reason')}, 'unknown preserved')

    def test_budget_exhaustion(self):
        self.mode = 'budget_exhaustion'
        asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'])
        self.assertFalse(str(self.results[0].get('turn_exit_reason', '')).startswith('text_response('))
        self.assertEqual(self.row()[0], 'unknown_outcome')
        print('EVIDENCE real iteration budget exhaustion:', {k:self.results[0].get(k) for k in ('completed','interrupted','turn_exit_reason')}, 'post observed=', any(x[0]=='post' for x in self.trace), 'unknown preserved')

    def check_policy(self, mode, happy=False):
        self.mode = mode
        asyncio.run(self.drive())
        self.assertEqual(self.effects, ['mutation'] if happy else [])
        self.assertEqual(self.row()[0], 'turn_completed' if happy else 'unknown_outcome')
        self.assertTrue(any(x[0] == 'policy' for x in self.trace))
        if happy:
            self.assertIn(('middleware_fault',), self.trace)
        tool_results = [m.get('content') for m in self.results[0]['messages'] if m.get('role') == 'tool']
        print('EVIDENCE', mode, 'effects=', len(self.effects), 'state=', self.row()[0], 'tool_results=', tool_results, 'trace=', json.dumps(self.trace))

    def test_policy_happy(self): self.check_policy('policy_happy', happy=True)
    def test_policy_missing_owner(self): self.check_policy('policy_missing_owner')
    def test_policy_missing_start(self): self.check_policy('policy_missing_start')
    def test_policy_exception(self): self.check_policy('policy_exception')
    def test_policy_timeout(self): self.check_policy('policy_timeout')

    def test_middleware_exception_failopen(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        # Remove fixture registrations through the public unload API.
        self.manager.unload()
        def broken(**kw):
            raise OSError('injected uncaught guard error')
        self.ctx.register_middleware('tool_execution', broken)
        calls = []
        result = run_tool_execution_middleware('fixture_only', {}, lambda args: calls.append('ran') or 'executed',
                                                session_id='fixture-sid', turn_id='fixture-turn')
        self.assertEqual(calls, ['ran'])
        self.assertEqual(result, 'executed')
        print('EVIDENCE CORE LIMIT: unhandled middleware exception before next_call FAILS OPEN; guarded fixture catches storage/validation errors and returns denial')


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--case':
        isolate()
        result = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([Probe('test_' + sys.argv[2])]))
        return not result.wasSuccessful()
    failures = []
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-turn-probe-', dir=SCRATCH) as home:
            env = {'HOME': home, 'HERMES_HOME': home, 'TMPDIR': home,
                   'PATH': '/data/data/com.termux/files/usr/bin', 'LANG': 'C.UTF-8',
                   'PYTHONDONTWRITEBYTECODE': '1', 'PYTHONPATH': str(CORE)}
            p = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--case', case],
                               cwd=home, env=env, text=True, capture_output=True, timeout=90)
            print(p.stdout, end='')
            print(p.stderr, end='')
            if p.returncode: failures.append(case)
    print(json.dumps({'cases': len(CASES), 'passed': len(CASES)-len(failures), 'failed': failures}))
    return bool(failures)

if __name__ == '__main__':
    raise SystemExit(main())
