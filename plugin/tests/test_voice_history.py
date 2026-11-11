"""Source-only, sequential offline history contracts; real public AIAgent fixture."""
import asyncio
from contextlib import closing
from dataclasses import replace
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from test_voice_dispatch import P, CORE, SCRATCH, Registry, isolate
sys.path.insert(0, str(P))


class Tests(unittest.TestCase):
    def setUp(self):
        from voice_history import HistoryStore, HistoryError
        from voice_dispatch import Dispatcher, DispatchHooks
        self.Error = HistoryError
        self.store = HistoryStore(profile_dir=Path(os.environ['HOME']), profile_id='a',
                                  db_path=Path(os.environ['HOME'])/'inbox.sqlite')
        self.hooks = DispatchHooks(Registry())
        self.Dispatcher = Dispatcher

    def start(self, rid='r1', text='remember amber', lane='lane'):
        with self.store.open() as box:
            box.receive(rid, text, lane)
            owner = box.claim(lane)
            snap = box.prepare_history(owner)
            self.assertTrue(box.bind_start(owner, 'sid-'+rid, 'turn-'+rid))
        return owner, snap

    def finish(self, owner, snap, answer='amber'):
        messages = snap.messages + [{'role':'user','content':self.row(owner.request_id).original_text},
                                    {'role':'assistant','content':answer}]
        with self.store.open() as box:
            return box.complete_history(owner, 'sid-'+owner.request_id, 'turn-'+owner.request_id, snap, messages)

    def row(self, rid='r1'):
        with self.store.open() as box: return box.get(rid)

    def view(self, lane='lane'):
        with self.store.open() as box: return box.history(lane)

    def test_atomic_reopen_parent_and_no_delivery_claim(self):
        owner, snap = self.start()
        self.assertTrue(self.finish(owner, snap))
        v = self.view()
        self.assertEqual(v.parent_request_id, 'r1')
        self.assertEqual(v.messages, [{'role':'user','content':'remember amber'}, {'role':'assistant','content':'amber'}])
        self.assertEqual(v.delivery_status, 'untracked')
        self.assertEqual(self.row().state, 'completed')
        second, follow = self.start('r2', 'what color?')
        self.assertEqual(follow.parent_request_id, 'r1')
        self.assertTrue(self.finish(second, follow))
        self.assertEqual(len(self.view().messages), 4)
        self.assertNotIn('amber', repr(self.view()))

    def test_rollback_history_on_completion_failure(self):
        owner, snap = self.start()
        with closing(sqlite3.connect(self.store.db_path)) as db, db:
            db.execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON requests WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        with self.assertRaises(Exception): self.finish(owner, snap)
        self.assertEqual(self.view().messages, [])
        self.assertEqual(self.row().state, 'started')

    def test_rollback_completion_on_history_failure(self):
        owner, snap = self.start()
        with closing(sqlite3.connect(self.store.db_path)) as db, db:
            db.execute("CREATE TRIGGER refuse_history BEFORE INSERT ON voice_history BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        with self.assertRaises(Exception): self.finish(owner, snap)
        self.assertEqual(self.row().state, 'started')
        self.assertEqual(self.view().messages, [])

    def test_wrong_profile_lane_attempt_and_stale_snapshot(self):
        owner, snap = self.start()
        for wrong in (replace(owner, profile_id='b'), replace(owner, lane='other'), replace(owner, attempt_id='old')):
            with self.assertRaises(self.Error): self.finish(wrong, snap)
        with self.store.open() as box:
            with self.assertRaises(self.Error):
                box.complete_history(owner, 'wrong', 'turn-r1', snap, [])
        self.assertTrue(self.finish(owner, snap))
        second, follow = self.start('r2', 'again')
        with self.assertRaises(self.Error): self.finish(second, snap)
        with self.assertRaises(self.Error): self.finish(second, replace(follow, parent_request_id=None))
        self.assertEqual(self.row('r2').state, 'started')

    def test_plain_complete_refused(self):
        owner, snap = self.start()
        with self.store.open() as box:
            with self.assertRaises(self.Error): box.complete(owner, 'sid-r1', 'turn-r1')
        self.assertEqual(self.view().messages, [])

    def test_downgrade_to_plain_store_refused(self):
        from voice_dispatch import Store
        plain = Store(self.store.profile_dir, self.store.profile_id, self.store.db_path)
        with plain.open() as stale:
            with self.store.open(): pass
            with self.assertRaises(Exception): stale.claim('lane')
            with self.assertRaises(Exception): stale.receive('bypass', 'text', 'lane')
        with self.assertRaises(Exception): plain.open()

    def test_history_cancel_close_never_commits(self):
        import threading
        from voice_inbox import Busy
        entered, release = threading.Event(), threading.Event()
        test = self
        class Agent:
            def __init__(self, identity): self.identity = identity
            def run_conversation(self, text, conversation_history):
                kw = dict(session_id=self.identity.sid, turn_id='t')
                test.hooks.pre_llm_call(**kw); test.hooks.post_llm_call(**kw)
                return dict(kw, completed=True, turn_exit_reason='text_response(stop)', messages=conversation_history+[{'role':'user','content':text},{'role':'assistant','content':'amber'}])
            def close(self):
                entered.set()
                if not release.wait(10): raise TimeoutError('close barrier')
        d = self.Dispatcher(self.store, self.hooks, Agent)
        d.receive('r1','remember amber','lane')
        async def exercise():
            task = asyncio.create_task(d.dispatch('lane'))
            try:
                while not entered.is_set(): await asyncio.sleep(.01)
                task.cancel(); await asyncio.sleep(.03)
                self.assertFalse(task.done())
                self.assertEqual(self.view().messages, [])
                d.receive('r2','what color?','lane')
                self.assertIsNone(await d.dispatch('lane'))
                with self.assertRaises(Busy): self.store.open(exclusive_startup=True)
            finally:
                release.set()
                with self.assertRaises(asyncio.CancelledError): await task
        asyncio.run(exercise())
        self.assertEqual(self.row().state, 'unknown_outcome')
        self.assertEqual(self.view().messages, [])
        self.assertIsNone(asyncio.run(d.dispatch('lane')))

    def test_dispatch_oversize_reply_blocks_next(self):
        test = self
        self.store = replace(self.store, max_history_bytes=128)
        class Agent:
            def __init__(self, identity): self.identity=identity
            def run_conversation(self, text, conversation_history):
                kw=dict(session_id=self.identity.sid,turn_id='t')
                test.hooks.pre_llm_call(**kw); test.hooks.post_llm_call(**kw)
                return dict(kw,completed=True,turn_exit_reason='text_response(stop)',messages=[{'role':'user','content':text},{'role':'assistant','content':'x'*129}])
            def close(self): pass
        d=self.Dispatcher(self.store,self.hooks,Agent)
        d.receive('r1','remember amber','lane'); d.receive('r2','again','lane')
        self.assertFalse(asyncio.run(d.dispatch('lane')).engine_turn_verified)
        self.assertEqual(self.row().state,'unknown_outcome')
        self.assertEqual(self.row().original_text,'remember amber')
        self.assertEqual(self.view().messages,[])
        self.assertIsNone(asyncio.run(d.dispatch('lane')))

    def test_message_limit_and_private_path(self):
        self.store = replace(self.store, max_history_messages=2)
        owner, snap = self.start(); self.finish(owner,snap)
        with self.store.open() as box:
            box.receive('r2','again','lane'); owner=box.claim('lane')
            with self.assertRaises(self.Error): box.prepare_history(owner)
        self.assertEqual(self.store.db_path.stat().st_mode & 0o777, 0o600)
        self.store.db_path.chmod(0o644)
        with self.assertRaises(Exception): self.store.open()

    def test_size_and_count_fail_closed_preserve_originals(self):
        self.store = replace(self.store, max_history_bytes=512, max_history_turns=1)
        owner, snap = self.start()
        with self.assertRaises(self.Error): self.finish(owner, snap, 'x'*513)
        self.assertEqual(self.view().messages, [])
        self.assertEqual(self.row().original_text, 'remember amber')
        self.assertTrue(self.finish(owner, snap))
        with self.store.open() as box:
            box.receive('r2', 'again', 'lane')
            second = box.claim('lane')
            with self.assertRaises(self.Error): box.prepare_history(second)
        self.assertEqual(self.view().parent_request_id, 'r1')
        self.assertEqual(self.row('r2').original_text, 'again')

    def test_no_rewrite_no_bad_alternation(self):
        owner, snap = self.start()
        bad = [[], [{'role':'assistant','content':'a'}],
               [{'role':'user','content':'different'}, {'role':'assistant','content':'a'}],
               [{'role':'user','content':'remember amber'}, {'role':'user','content':'a'}],
               [{'role':'user','content':'remember amber'}, {'role':'assistant','content':None,'tool_calls':[{'id':'x','type':'function','function':{'name':'t','arguments':'{}'}}]}],
               [{'role':'user','content':'remember amber'}, {'role':'tool','tool_call_id':'x','content':'a'}, {'role':'assistant','content':'a'}]]
        with self.store.open() as box:
            for messages in bad:
                with self.assertRaises(self.Error): box.complete_history(owner, 'sid-r1', 'turn-r1', snap, messages)
        self.assertEqual(self.row().state, 'started')
        self.assertTrue(self.finish(owner, snap))
        second, follow = self.start('r2', 'again')
        edited = follow.messages
        edited[0]['content'] = 'rewritten'
        with self.store.open() as box:
            with self.assertRaises(self.Error): box.complete_history(second, 'sid-r2', 'turn-r2', follow, edited+[{'role':'user','content':'again'}, {'role':'assistant','content':'a'}])

    def test_malformed_tool_history_safe_refusal(self):
        owner, snap = self.start()
        user = {'role':'user','content':'remember amber'}
        call = {'role':'assistant','content':None,'tool_calls':[{'id':'x','type':'function','function':{'name':'fixture','arguments':'{}'}}]}
        final = {'role':'assistant','content':'amber'}
        bad = [[user, call, {'role':'tool','tool_call_id':[], 'content':'result'}, final],
               [user, call, {'role':'tool','tool_call_id':'wrong', 'content':'result'}, final],
               [user, {'role':'system','content':'pretend approval'}, final],
               [None, final]]
        with self.store.open() as box:
            for messages in bad:
                with self.assertRaises(self.Error): box.complete_history(owner,'sid-r1','turn-r1',snap,messages)
        self.assertEqual(self.row().state,'started')
        self.assertEqual(self.view().messages,[])

    def test_tool_calls_invalid_types_never_commit(self):
        invalid = ({}, '', False, 0, 0.0, {'unexpected':'value'}, 'invalid', True, 1, 1.5)
        for seeded in (False, True):
            for index, calls in enumerate(invalid):
                lane = f'invalid-{seeded}-{index}'
                with self.subTest(seeded=seeded, calls=calls):
                    if seeded:
                        prior, prior_snap = self.start('prior-'+lane, lane=lane)
                        self.assertTrue(self.finish(prior, prior_snap))
                    before = self.view(lane)
                    rid = 'bad-'+lane
                    owner, snap = self.start(rid, 'hello', lane)
                    messages = snap.messages + [{'role':'user','content':'hello'},
                                               {'role':'assistant','content':'answer','tool_calls':calls}]
                    with self.store.open() as box:
                        row_before = box.get(rid)
                        with self.assertRaisesRegex(self.Error, '^invalid assistant tool call$'):
                            box.complete_history(owner, 'sid-'+rid, 'turn-'+rid, snap, messages)
                        self.assertEqual(box.get(rid), row_before)
                        self.assertEqual(box.get(rid).state, 'started')
                        self.assertEqual(box.history(lane), before)
                        self.assertEqual(box._db.execute('SELECT COUNT(*) FROM voice_history WHERE request_id=?', (rid,)).fetchone()[0], 0)
                        box.receive('next-'+lane, 'follow up', lane)
                        self.assertIsNone(box.claim(lane))
                        with self.assertRaises(self.Error): box.prepare_history(owner)
                    # A fresh handle must still see the exact canonical predecessor.
                    self.assertEqual(self.view(lane), before)
                    self.assertEqual(self.row('next-'+lane).state, 'received')

    def test_tool_calls_absent_none_empty_preserved_in_handoff(self):
        expected = []
        for index, fields in enumerate(({}, {'tool_calls':None}, {'tool_calls':[]})):
            with self.subTest(fields=fields):
                rid = f'accepted-{index}'
                owner, snap = self.start(rid, 'hello')
                self.assertEqual(snap.messages, expected)
                assistant = {'role':'assistant','content':'answer', **fields}
                expected += [{'role':'user','content':'hello'}, assistant]
                with self.store.open() as box:
                    self.assertTrue(box.complete_history(owner, 'sid-'+rid, 'turn-'+rid, snap, expected))
                    self.assertEqual(box.get(rid).state, 'completed')
                self.assertEqual(self.view().messages, expected)
                self.assertEqual(self.view().turns, index+1)
        _, next_snap = self.start('next', 'follow up')
        self.assertEqual(next_snap.messages, expected)
        self.assertEqual(next_snap.payload, self.view().payload)
        self.assertNotIn('tool_calls', next_snap.messages[1])
        self.assertIsNone(next_snap.messages[3]['tool_calls'])
        self.assertEqual(next_snap.messages[5]['tool_calls'], [])

    def test_tool_calls_nonempty_list_requires_matched_results(self):
        owner, snap = self.start('tools', 'hello')
        messages = [{'role':'user','content':'hello'},
                    {'role':'assistant','content':None,'tool_calls':[
                        {'id':cid,'type':'function','function':{'name':'fixture','arguments':'{}'}}
                        for cid in ('first', 'second')]},
                    {'role':'tool','tool_call_id':'second','content':'second result'},
                    {'role':'tool','tool_call_id':'first','content':'first result'},
                    {'role':'assistant','content':'answer','tool_calls':[]}]
        with self.store.open() as box:
            for bad in (messages[:2]+messages[3:], messages[:3]+messages[4:]):
                with self.subTest(missing_result=bad):
                    with self.assertRaises(self.Error):
                        box.complete_history(owner, 'sid-tools', 'turn-tools', snap, bad)
                    self.assertEqual(box.history('lane').messages, [])
                    self.assertEqual(box.get('tools').state, 'started')
            self.assertTrue(box.complete_history(owner, 'sid-tools', 'turn-tools', snap, messages))
            self.assertEqual(box.get('tools').state, 'completed')
        _, next_snap = self.start('next', 'follow up')
        self.assertEqual(next_snap.messages, messages)
        self.assertEqual(next_snap.payload, self.view().payload)

    def test_dispatch_invalid_tool_calls_blocks_followup(self):
        test = self
        class Agent:
            def __init__(self, identity): self.identity = identity
            def run_conversation(self, text, conversation_history):
                kw = dict(session_id=self.identity.sid, turn_id='t')
                test.hooks.pre_llm_call(**kw); test.hooks.post_llm_call(**kw)
                return dict(kw, completed=True, turn_exit_reason='text_response(stop)',
                            messages=conversation_history+[{'role':'user','content':text},
                            {'role':'assistant','content':'answer','tool_calls':{}}])
            def close(self): pass
        d = self.Dispatcher(self.store, self.hooks, Agent)
        d.receive('r1', 'hello', 'lane'); d.receive('r2', 'follow up', 'lane')
        self.assertFalse(asyncio.run(d.dispatch('lane')).engine_turn_verified)
        self.assertEqual(self.row().state, 'unknown_outcome')
        self.assertEqual(self.row().original_text, 'hello')
        self.assertEqual(self.view().messages, [])
        self.assertIsNone(asyncio.run(d.dispatch('lane')))
        self.assertEqual(self.row('r2').state, 'received')

    def test_unknown_blocks_followup_after_recovery(self):
        owner, snap = self.start()
        with self.store.open() as box: box.receive('r2', 'again', 'lane')
        with self.store.open(exclusive_startup=True) as box:
            self.assertEqual(box.recover_inflight(), 1)
        with self.store.open() as box:
            self.assertIsNone(box.claim('lane'))
            with self.assertRaises(self.Error): box.prepare_history(owner)
        self.assertEqual(self.view().messages, [])
        self.assertEqual(self.row('r2').state, 'received')

    def test_legacy_gap_and_settings_mismatch(self):
        from voice_dispatch import Store
        with Store(self.store.profile_dir, self.store.profile_id, self.store.db_path).open() as box:
            box.receive('old', 'legacy', 'lane'); owner = box.claim('lane')
            box.bind_start(owner, 's', 't'); box.complete(owner, 's', 't')
        with self.store.open() as box:
            box.receive('r1', 'new', 'lane'); owner = box.claim('lane')
            with self.assertRaises(self.Error): box.prepare_history(owner)
        with self.assertRaises(self.Error): replace(self.store, max_history_turns=1).open()
        with self.assertRaises(Exception): replace(self.store, profile_id='b').open()

    def test_actual_core_two_turn_reopen_no_replay(self):
        self.actual_core_two_turns(expired=False)

    def test_actual_core_expired_placeholder_no_authorization(self):
        self.actual_core_two_turns(expired=True)

    def actual_core_two_turns(self, *, expired):
        from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
        from run_agent import AIAgent
        from voice_dispatch import DispatchHooks
        manager = get_plugin_manager(); self.addCleanup(manager.unload)
        ctx = PluginContext(PluginManifest(name='history-fixture', path=str(P)), manager)
        hooks = DispatchHooks(ctx)
        calls, effects, denials = [], [], []
        approval = {'state': 'synthetic-approved'}
        def approval_guard(**kw):
            if approval['state'] == 'synthetic-expired':
                denials.append(1)
                return {'action':'block', 'message':'Synthetic approval expired'}
        ctx.register_hook('pre_tool_call', approval_guard)
        def model(**kw):
            from openai.types.chat import ChatCompletion
            calls.append(kw)
            msg = {'role':'assistant','content':'amber'}; finish='stop'
            if len(calls) == 1 or expired and len(calls) == 3:
                msg = {'role':'assistant','content':None,'tool_calls':[{'id':'fixture-call','type':'function','function':{'name':'tool_call','arguments':json.dumps({'calls':[{'name':'history_fixture','arguments':{}}]})}}]}; finish='tool_calls'
            if len(calls) == 3:
                text = json.dumps(kw['request'])
                self.assertIn('remember amber', text)
                self.assertIn('what color?', text)
                self.assertIn('fixture-call', text)
            return ChatCompletion.model_validate({'id':'fixture','object':'chat.completion','created':0,'model':'fixture','choices':[{'index':0,'message':msg,'finish_reason':finish}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}})
        ctx.register_middleware('llm_execution', model)
        ctx.register_tool(name='history_fixture', toolset='history_fixture', schema={'name':'history_fixture','description':'Synthetic counter','parameters':{'type':'object','properties':{}}}, handler=lambda args, **kw: effects.append(1) or '{"success":true}')
        def factory(identity):
            return AIAgent(model='fixture', provider='openai', api_key='offline-placeholder', base_url='https://offline.invalid/v1', enabled_toolsets=['history_fixture'], max_iterations=3, quiet_mode=True, skip_memory=True, skip_context_files=True, skip_background_review=True, platform='history_fixture', session_id=identity.sid, cwd=os.environ['HOME'])
        d = self.Dispatcher(self.store, hooks, factory)
        d.receive('r1','remember amber','lane')
        self.assertTrue(asyncio.run(d.dispatch('lane')).engine_turn_verified)
        self.assertEqual(effects, [1])
        original = self.view().messages
        d = self.Dispatcher(replace(self.store), hooks, factory)
        d.receive('r1','remember amber','lane')
        self.assertIsNone(asyncio.run(d.dispatch('lane')))
        if expired: approval['state'] = 'synthetic-expired'
        d.receive('r2','what color?','lane')
        self.assertTrue(asyncio.run(d.dispatch('lane')).engine_turn_verified)
        self.assertEqual(len(calls), 4 if expired else 3)
        self.assertEqual(effects, [1])
        self.assertEqual(bool(denials), expired)
        if expired: self.assertEqual(approval['state'], 'synthetic-expired')
        self.assertEqual(self.view().messages[:len(original)], original)
        self.assertEqual(self.view().parent_request_id, 'r2')
        self.assertEqual(self.view().delivery_status, 'untracked')
        for path in Path(os.environ['HOME']).rglob('*'):
            if path.is_file() and path != self.store.db_path:
                self.assertNotIn(b'remember amber', path.read_bytes(), str(path))
        print(f'ACTUAL_CORE two turns; reopen; history prefix preserved; model calls={len(calls)}; tool effects=1; expired_placeholder={expired}; delivery=untracked')


def main():
    if len(sys.argv)==3 and sys.argv[1]=='--case':
        isolate()
        return not unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([Tests(sys.argv[2])])).wasSuccessful()
    cases=unittest.defaultTestLoader.getTestCaseNames(Tests); failed=[]
    for case in cases:
        with tempfile.TemporaryDirectory(prefix='voice-dispatch-test-',dir=SCRATCH) as home:
            env={'HOME':home,'HERMES_HOME':home,'TMPDIR':home,'PATH':'/data/data/com.termux/files/usr/bin','LANG':'C.UTF-8','PYTHONDONTWRITEBYTECODE':'1','PYTHONPATH':str(CORE)}
            try:
                p=subprocess.run([sys.executable,'-B',str(Path(__file__).resolve()),'--case',case],cwd=home,env=env,text=True,capture_output=True,timeout=75)
                print(p.stdout,end=''); print(p.stderr,end='')
                if p.returncode: failed.append(case)
            except subprocess.TimeoutExpired: failed.append(case); print('TIMEOUT',case)
    print(json.dumps({'cases':len(cases),'passed':len(cases)-len(failed),'failed':failed}))
    return bool(failed)

if __name__=='__main__': raise SystemExit(main())
