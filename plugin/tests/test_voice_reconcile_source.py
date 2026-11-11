"""Offline persistence tests. No agent/model construction or live profile reads."""
import copy
from dataclasses import asdict
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
P = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(P))
from voice_reconcile import ReconcileStore, ReconcileError
from voice_history import HistoryStore, HistoryError
from voice_inbox import InboxError

class Persistence(unittest.TestCase):
    def setUp(self):
        self.prepare()
        self.box.enter_reconciliation(self.identity,self.before)
        self.box.capture_candidate(self.owner,self.candidate)
        self.box.bind_start(self.owner,'sid','turn')
        self.result = dict(completed=True,session_id='sid',turn_id='turn',turn_exit_reason='text_response(stop)',current_turn_user_idx=1,final_response='answer',messages=self.candidate+[dict(role='assistant',content='answer')])
    def prepare(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='reconcile-store-', dir=os.environ['TMPDIR'])
        self.home = Path(self.tmp.name)
        self.store = ReconcileStore(self.home, 'fixture-profile', self.home/'approved.sqlite')
        self.box = self.store.open()
        self.box.receive('old','historical request','lane')
        old = self.box.claim('lane'); h = self.box.prepare_history(old)
        self.box.bind_start(old,'old-sid','old-turn')
        self.original = [{'role':'user','content':'historical request','metadata':{'nested':[True,1,1.0]}}, {'role':'assistant','content':'historical answer'}]
        self.box.complete_history(old,'old-sid','old-turn',h,self.original)
        self.box.receive('request','new request','lane')
        self.owner = self.box.claim('lane'); self.h = self.box.prepare_history(self.owner)
        self.box.prepare_reconciliation(self.owner,'sid',self.h)
        self.identity = dict(**asdict(self.owner),session_id='sid',turn_id='turn')
        event,digest = self.box.issue(self.identity,[{'role':'user','content':'normalized only'}])
        self.box.acknowledge(dict(identity=self.identity,event_id=event,content_digest=digest,outcome='played',synthetic=True))
        self.box.consume(event,self.identity,digest)
        self.before = self.original+[dict(role='user',content='new request',timestamp=1)]
        self.candidate = [dict(role='assistant',content='summary',metadata={'nested':[True,1,1.0,0.0]}), {**self.before[-1], '_compaction_tail':True}]
    def tearDown(self):
        self.box.close(); self.tmp.cleanup()
    def finish(self, result=None):
        return self.box.complete_reconciliation(self.owner,'sid','turn',self.h,self.result if result is None else result)
    def test_atomic_owned_completion_and_immutable_ledger(self):
        self.assertTrue(self.finish())
        self.assertEqual(self.box.get('request').state,'completed')
        self.assertEqual(self.box.history('lane').messages,self.result['messages'])
        self.assertEqual(self.box.ledger('lane').messages,self.before+[self.result['messages'][-1]])
        with self.store.open() as other:
            self.assertEqual(other.history('lane').messages,self.result['messages'])
        self.box.receive('next','next request','lane'); nxt=self.box.claim('lane')
        with self.assertRaisesRegex(ReconcileError,'single reconciliation lane'): self.box.prepare_history(nxt)
    def test_bad_suffixes_rollback(self):
        for suffix in (None,False,3,[], 'bad', {'role':'assistant','content':'answer','tool_calls':False}):
            bad=copy.deepcopy(self.result);bad['messages'][-1]=suffix
            with self.assertRaises(ReconcileError): self.finish(bad)
            self.assertEqual(self.box.get('request').state,'started')
            self.assertEqual(self.box.ledger('lane').messages,self.original)
    def test_prefix_and_identity_refused(self):
        for key,value in [('session_id','other'),('turn_id','other'),('completed',False),('error','error'),('current_turn_user_idx',False)]:
            bad=copy.deepcopy(self.result);bad[key]=value
            with self.assertRaises(ReconcileError): self.finish(bad)
        bad=copy.deepcopy(self.result);bad['messages'][0]['content']='rewritten'
        with self.assertRaises(ReconcileError):self.finish(bad)
    def test_unknown_no_replay(self):
        self.box.mark_unknown(self.owner)
        with self.assertRaises(HistoryError):self.finish()
        self.assertIsNone(self.box.claim('lane'))
        self.assertEqual(self.box.ledger('lane').messages,self.original)
    def test_old_store_refused(self):
        with self.assertRaisesRegex(InboxError,'reconciliation-capable store required'):
            HistoryStore(self.home,'fixture-profile',self.home/'approved.sqlite').open()
    def test_duplicate_capture_refused(self):
        with self.assertRaisesRegex(HistoryError,'history attempt mismatch'):self.box.enter_reconciliation(self.identity,self.before)
        with self.assertRaisesRegex(HistoryError,'history attempt mismatch'):self.box.capture_candidate(self.owner,self.candidate)
    def test_ordinary_prefix_guard_unchanged(self):
        with self.assertRaisesRegex(ReconcileError,'reconciliation completion required'):
            self.box.complete_history(self.owner,'sid','turn',self.h,self.result['messages'])
    def test_transaction_rollback_after_ledger_insert(self):
        # Fixture-only SQLite fault, no core interception. The final request UPDATE
        # fails after active+ledger writes; all three must roll back together.
        self.box._db.execute("CREATE TRIGGER fixture_abort BEFORE UPDATE OF state ON requests WHEN NEW.state='completed' BEGIN SELECT RAISE(ABORT,'fixture'); END")
        with self.assertRaisesRegex(InboxError,'store transaction failed'): self.finish()
        self.assertEqual(self.box.get('request').state,'started')
        self.assertEqual(self.box.history('lane').messages,self.original)
        self.assertEqual(self.box.ledger('lane').messages,self.original)
        self.box._db.execute('DROP TRIGGER fixture_abort')
        self.assertTrue(self.finish())
    def test_stale_handoff_and_owner(self):
        from dataclasses import replace
        with self.assertRaisesRegex(ReconcileError,'base CAS'):
            self.box.complete_reconciliation(self.owner,'sid','turn',replace(self.h,turns=99),self.result)
        with self.assertRaisesRegex(HistoryError,'history attempt mismatch'):
            self.box.complete_reconciliation(replace(self.owner,attempt_id='other'),'sid','turn',self.h,self.result)
        self.assertEqual(self.box.get('request').state,'started')
    def test_preopened_legacy_handle_guard(self):
        home=self.home/'other';home.mkdir(mode=0o700)
        legacy=HistoryStore(home,'fixture-profile',home/'db.sqlite').open()
        try:
            with ReconcileStore(home,'fixture-profile',home/'db.sqlite').open():pass
            with self.assertRaisesRegex(InboxError,'reconciliation-capable store required'):
                legacy.receive('r','text','lane')
        finally:legacy.close()
    def test_process_pin_rejects_extension(self):
        from voice_process import _schema, ProcessError, PROFILE
        home=self.home/'process';home.mkdir(mode=0o700)
        with ReconcileStore(home,PROFILE,home/'voice.sqlite').open():pass
        with self.assertRaises(ProcessError):_schema(home)
    def test_completion_duplicate_cannot_rewrite(self):
        self.finish()
        with self.assertRaisesRegex(HistoryError,'history attempt mismatch'):self.finish()
        self.assertFalse(self.box.mark_unknown(self.owner))
        self.assertEqual(self.box.history('lane').messages,self.result['messages'])
    def test_capacity_refusal(self):
        bad=copy.deepcopy(self.result);bad['messages'][-1]['content']='x'*300000;bad['final_response']=bad['messages'][-1]['content']
        with self.assertRaisesRegex(HistoryError,'history capacity exhausted'):self.finish(bad)

class ExactJSON(unittest.TestCase):
    # Every mutation gets a fresh attempt: a RED acceptance must not poison the
    # next subcase. SQL writes below inject only fixture-owned capture faults.
    from contextlib import contextmanager

    @contextmanager
    def fixture(self, prepared=False):
        fixture = Persistence()
        try:
            fixture.prepare() if prepared else fixture.setUp()
            yield fixture
        finally:
            fixture.tearDown()

    def snapshot(self, fixture):
        # Canonical strings/SQL dump, never Python's bool == int equality.
        from voice_history import _encode
        return (tuple(fixture.box._db.iterdump()),
                _encode(asdict(fixture.box.get('request'))),
                fixture.box.ledger('lane').payload,
                fixture.box.history('lane').payload)

    def refused(self, fixture, operation, reason, error=ReconcileError):
        before = self.snapshot(fixture)
        try:
            with self.assertRaisesRegex(error, reason): operation()
        finally:
            self.assertEqual(self.snapshot(fixture), before,
                             'refusal must not alter request, ledger, active or capture')

    def test_final_prefix_preserves_json_types(self):
        mutations = [('marker', 1), ('marker', 1.0), ('timestamp', True),
                     ('timestamp', 1.0), (0, 1), (0, 1.0), (1, True),
                     (1, 1.0), (2, 1), (2, True), (3, -0.0)]
        for field, value in mutations:
            with self.subTest(field=field, value=repr(value)), self.fixture() as f:
                bad = copy.deepcopy(f.result)
                if field == 'marker': bad['messages'][1]['_compaction_tail'] = value
                elif field == 'timestamp': bad['messages'][1]['timestamp'] = value
                else: bad['messages'][0]['metadata']['nested'][field] = value
                self.refused(f, lambda: f.finish(bad), 'candidate final mismatch')

    def test_candidate_anchor_preserves_json_types(self):
        for field, value in [('_compaction_tail', 1), ('_compaction_tail', 1.0),
                             ('timestamp', True), ('timestamp', 1.0)]:
            with self.subTest(field=field, value=repr(value)), self.fixture(prepared=True) as f:
                f.box.enter_reconciliation(f.identity, f.before)
                candidate = copy.deepcopy(f.candidate); candidate[-1][field] = value
                f.box.capture_candidate(f.owner, candidate)
                f.box.bind_start(f.owner, 'sid', 'turn')
                result = dict(completed=True, session_id='sid', turn_id='turn',
                    turn_exit_reason='text_response(stop)', current_turn_user_idx=1,
                    final_response='answer', messages=candidate+[dict(role='assistant', content='answer')])
                self.refused(f, lambda: f.finish(result), 'current request anchor')

    def test_before_prefix_preserves_nested_json_types(self):
        for idx, value in [(0, 1), (1, True), (1, 1.0), (2, 1)]:
            with self.subTest(idx=idx, value=repr(value)), self.fixture(prepared=True) as f:
                before = copy.deepcopy(f.before); before[0]['metadata']['nested'][idx] = value
                self.refused(f, lambda: f.box.enter_reconciliation(f.identity, before),
                             'not a pre-first-model boundary')

    def test_base_original_and_turn_count_preserve_json_types(self):
        from voice_reconcile import digest
        from voice_history import _encode
        for field in ('original', 'turns'):
            with self.subTest(field=field), self.fixture() as f:
                record = f.box._record(f.owner)
                if field == 'original':
                    record['original'][0]['metadata']['nested'][0] = 1
                    record['base_digest'] = digest(record['original'])
                else: record['turns'] = True
                f.box._db.execute('UPDATE voice_reconcile SET payload=?', (_encode(record),))
                self.refused(f, f.finish, 'base CAS')

    def test_handoff_turn_count_preserves_json_types(self):
        from dataclasses import replace
        for value in (True, 1.0):
            with self.subTest(value=repr(value)), self.fixture() as f:
                f.h = replace(f.h, turns=value)
                self.refused(f, f.finish, 'base CAS')

    def test_receipt_synthetic_integer_not_boolean(self):
        from voice_history import _encode
        with self.fixture(prepared=True) as f:
            receipt = f.box.notices(f.owner)[0]['receipt']; receipt['synthetic'] = 1
            f.box._db.execute('UPDATE voice_checkpoints SET receipt=?', (_encode(receipt),))
            self.refused(f, lambda: f.box.enter_reconciliation(f.identity, f.before), 'permission')

    def test_invalid_json_numbers_are_rejected_without_writes(self):
        for value in (float('nan'), float('inf'), -float('inf')):
            with self.subTest(value=repr(value)), self.fixture() as f:
                bad = copy.deepcopy(f.result); bad['messages'][0]['metadata'] = value
                self.refused(f, lambda: f.finish(bad), 'invalid history encoding', HistoryError)

    def test_json_object_key_order_is_not_a_change(self):
        with self.fixture() as f:
            result = copy.deepcopy(f.result)
            result['messages'] = [dict(reversed(list(m.items()))) for m in result['messages']]
            self.assertTrue(f.finish(result))
            from voice_history import _encode
            self.assertEqual(f.box.history('lane').payload, _encode(f.result['messages']))

class Coordinator(unittest.TestCase):
    def test_dispatch_capability_success_missing_capture_and_tool_denial(self):
        import asyncio
        import logging
        from voice_dispatch import Dispatcher, DispatchHooks, CURRENT
        from test_voice_dispatch import Registry
        from voice_inbox import Busy
        logging.disable(logging.CRITICAL)
        for mode in ('success','missing','tool','close_failure'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
                home=Path(tmp);store=ReconcileStore(home,'fixture-profile',home/'db.sqlite')
                registry=Registry();hooks=DispatchHooks(registry);closed=[]
                with store.open() as box:
                    box.receive('old','old','lane');old=box.claim('lane');h=box.prepare_history(old)
                    box.bind_start(old,'old-sid','old-turn')
                    box.complete_history(old,'old-sid','old-turn',h,[dict(role='user',content='old'),dict(role='assistant',content='old answer')])
                test=self
                def factory(attempt):
                    class Agent:
                        def run_conversation(self,text,conversation_history):
                            ident=dict(**asdict(attempt.owner),session_id=attempt.sid,turn_id='turn')
                            before=conversation_history+[dict(role='user',content=text)]
                            candidate=[dict(role='assistant',content='summary'),{**before[-1],'_compaction_tail':True}]
                            with store.open() as box:
                                if mode!='missing':
                                    event,digest=box.issue(ident,[dict(role='user',content=text)])
                                    box.acknowledge(dict(identity=ident,event_id=event,content_digest=digest,outcome='played',synthetic=True))
                                    box.consume(event,ident,digest);box.enter_reconciliation(ident,before)
                                    box.capture_candidate(attempt.owner,candidate)
                            hooks.pre_llm_call(session_id=attempt.sid,turn_id='turn')
                            if mode=='tool':
                                test.assertEqual(hooks.pre_tool_call(session_id=attempt.sid,turn_id='turn')['action'],'block')
                            hooks.post_llm_call(session_id=attempt.sid,turn_id='turn')
                            return dict(completed=True,session_id=attempt.sid,turn_id='turn',turn_exit_reason='text_response(stop)',current_turn_user_idx=1,final_response='answer',messages=candidate+[dict(role='assistant',content='answer')])
                        def close(self):
                            with test.assertRaises(Busy):store.open(exclusive_startup=True)
                            closed.append(True)
                            if mode=='close_failure':raise RuntimeError('fixture close')
                    return Agent()
                dispatcher=Dispatcher(store,hooks,factory);dispatcher.receive('r','new','lane')
                outcome=asyncio.run(dispatcher.dispatch('lane'))
                self.assertEqual(closed,[True]);self.assertEqual(outcome.engine_turn_verified,mode=='success')
                with store.open() as box:
                    self.assertEqual(box.get('r').state,'completed' if mode=='success' else 'unknown_outcome')
                    self.assertIsNone(box.claim('lane'))
                self.assertIsNone(asyncio.run(dispatcher.dispatch('lane')))

if __name__ == '__main__': unittest.main()
