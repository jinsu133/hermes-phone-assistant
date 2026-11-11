"""Identity-bound admission through real public lifecycle; offline transport spy only."""
import asyncio
from contextlib import contextmanager
from contextvars import copy_context
import json
import unittest
from agent.relay_runtime import ConversationLease, SESSION_COORDINATOR, current_turn
from test_voice_owner_eviction import VoiceOwnerEviction


class AdmissionIdentity(VoiceOwnerEviction):
    @contextmanager
    def lifecycle(self, name='text', session='fixture-session'):
        lease = ConversationLease('fixture', session, 'cli', None, None)
        turn = SESSION_COORDINATOR.begin_turn(lease, turn_id=name, task_id='fixture-task')
        try:
            yield turn
        finally:
            SESSION_COORDINATOR.end_turn(turn, outcome='success')

    def text(self, name='text', message='ordinary text', session='fixture-session'):
        self.manager.invoke_hook('pre_llm_call', session_id=session, turn_id=name, user_message=message)

    def idle(self):
        self.start()
        self.link.cancel_turn()
        self.link.end_turn(session_id='fixture-session')

    def denied(self, name='text'):
        guard = self.link.guard_voice_tool(tool_name='phone_act', args={'action':'home'},
                                          session_id='fixture-session', turn_id=name)
        self.assertEqual(guard['action'], 'block')
        self.assertEqual(self.call({'action':'home'}, turn=name)['error'], 'VOICE_OWNER_REQUIRED')
        self.assertEqual(self.calls, [])

    def test_identity_same_ids_cannot_borrow_text_or_copied_record(self):
        self.idle()
        with self.lifecycle() as admitted:
            self.text()
            record = admitted._phone_voice_admission
        with self.lifecycle() as stranger:
            self.denied()
            # Even copying the record cannot turn equal IDs into object identity.
            stranger._phone_voice_admission = record
            self.denied()

    def test_identity_same_ids_cannot_rebind_live_voice(self):
        with self.lifecycle('task'):
            self.start()
            with self.lifecycle('task'):
                self.text('task', self.link.format_voice_message('티맵에서 서울역 검색해 줘'))
                self.denied('task')
            self.assertTrue(self.call({'action':'home'})['ok'])
            self.assertEqual(self.calls[-1][1]['_voice_request_id'], self.request)

    def test_identity_legacy_reentry_plain_text_after_eviction_denies(self):
        self.start()
        self.churn()
        self.assertIsNone(current_turn())
        self.text('task', 'ordinary-looking replacement')
        self.denied('task')
        self.assertFalse(hasattr(self.link, '_turn_admissions'))

    def test_identity_runtime_reentry_plain_text_after_eviction_denies(self):
        with self.lifecycle('task') as turn:
            self.start()
            original = turn._phone_voice_admission
            self.churn()
            self.text('task', 'ordinary-looking replacement')
            self.assertIs(turn._phone_voice_admission, original)
            self.denied('task')

    def test_identity_invalid_first_hook_cannot_upgrade(self):
        self.idle()
        for message in (None, ['not a supported ingress string']):
            with self.lifecycle():
                self.text(message=message)
                self.text()
                self.denied()

    def test_identity_mismatched_first_hook_cannot_upgrade(self):
        self.idle()
        with self.lifecycle():
            self.text(name='borrowed')
            self.text()
            self.denied()
        with self.lifecycle():
            self.text(session='wrong-session')
            self.text()
            self.denied()

    def test_identity_unadmitted_runtime_cannot_borrow_other_text(self):
        self.idle()
        with self.lifecycle():
            self.text()
        with self.lifecycle('stranger'):
            self.denied()
            self.denied('stranger')

    def test_identity_closed_text_cannot_run_delayed_coroutine(self):
        self.idle()
        with self.lifecycle() as turn:
            self.text()
            value = self.link.bind_tool_voice({'action':'home'}, self.handler.phone_act,
                session_id='fixture-session', turn_id='text', tool_name='phone_act')
            saved = copy_context()
        self.assertTrue(turn.closed)
        result = saved.run(lambda: json.loads(asyncio.run(value)))
        self.assertEqual(result['error'], 'VOICE_OWNER_REQUIRED')
        self.assertEqual(self.calls, [])

    def test_identity_coroutine_cannot_transfer_to_other_admitted_text(self):
        self.idle()
        with self.lifecycle():
            self.text()
            value = self.link.bind_tool_voice({'action':'home'}, self.handler.phone_act,
                session_id='fixture-session', turn_id='text', tool_name='phone_act')
            with self.lifecycle('other'):
                self.text('other')
                self.assertEqual(json.loads(asyncio.run(value))['error'], 'VOICE_OWNER_REQUIRED')
                self.assertEqual(self.calls, [])
                self.assertTrue(self.call({'action':'home'}, turn='other')['ok'])
            self.assertIsNone(self.link.tool_voice_request.get())

    def test_identity_no_middleware_no_runtime_cannot_mutate(self):
        self.idle()
        result = json.loads(asyncio.run(self.handler.phone_act({'action':'home'})))
        self.assertEqual(result['error'], 'VOICE_OWNER_REQUIRED')
        self.assertEqual(self.calls, [])

    def test_identity_survives_a_plugin_reload(self):
        # A reload evicts the plugin module and builds a new instance while earlier turns are
        # still running. Their admission records must keep matching, or every tool in those
        # turns blocks with "Turn admission unavailable".
        self.idle()
        with self.lifecycle() as turn:
            self.text()
            original = turn._phone_voice_admission
            reloaded = type(self.link)(self.link.ctx)
            self.assertIsNot(reloaded, self.link)
            self.assertIs(reloaded._admission_identity, self.link._admission_identity)
            self.assertIs(turn._phone_voice_admission, original)
            self.assertIsNone(reloaded.guard_voice_tool(
                tool_name='phone_act', args={'action': 'home'},
                session_id='fixture-session', turn_id='text'))
            self.assertTrue(self.call({'action': 'home'}, turn='text')['ok'])

    def test_identity_public_lifecycle_text_navigation_and_input_work(self):
        self.idle()
        with self.lifecycle():
            self.text()
            for args in ({'action':'home'}, {'action':'open_app','package':'com.skt.tmap.ku'},
                         {'action':'input_text','snapshot':'s','ref':'n','text':'서울역'},
                         {'action':'submit_search','snapshot':'s2','ref':'n2'}):
                self.assertIsNone(self.link.guard_voice_tool(tool_name='phone_act', args=args,
                    session_id='fixture-session', turn_id='text'))
                self.assertTrue(self.call(args, turn='text')['ok'])
                self.assertNotIn('_voice_request_id', self.calls[-1][1])
                self.text()  # Same runtime's repeat hook is idempotent.
            self.assertIsNone(self.link.tool_voice_request.get())
        self.assertEqual(len(self.calls), 4)

    def test_identity_public_lifecycle_voice_navigation_and_input_work(self):
        with self.lifecycle('task'):
            self.start()
            for args in ({'action':'home'}, {'action':'open_app','package':'com.skt.tmap.ku'},
                         {'action':'input_text','snapshot':'s','ref':'n','text':'서울역'},
                         {'action':'submit_search','snapshot':'s2','ref':'n2'}):
                self.assertIsNone(self.link.guard_voice_tool(tool_name='phone_act', args=args,
                    session_id='fixture-session', turn_id='task'))
                self.assertTrue(self.call(args)['ok'])
                self.assertEqual(self.calls[-1][1]['_voice_request_id'], self.request)
                self.text('task', self.link.format_voice_message('티맵에서 서울역 검색해 줘'))
            self.assertIsNone(self.link.tool_voice_request.get())
        self.assertEqual(len(self.calls), 4)


if __name__ == '__main__':
    suite = unittest.TestSuite(AdmissionIdentity(n) for n in unittest.defaultTestLoader.getTestCaseNames(AdmissionIdentity)
                               if n.startswith('test_identity_'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(not result.wasSuccessful())
