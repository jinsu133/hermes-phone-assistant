"""Durable runtime provenance across real admission-cache eviction; transport spy only."""
import asyncio
from contextlib import contextmanager
from contextvars import copy_context
import gc
import json
import unittest
import weakref
import test_task_owner_contract as fixture
from agent.relay_runtime import ConversationLease, RelayTurnContext, _CURRENT_TURN


class VoiceOwnerEviction(fixture.TaskOwnerContract):
    @contextmanager
    def runtime(self, name):
        lease = ConversationLease('fixture', 'fixture-session', 'cli', None, None)
        turn = RelayTurnContext(lease, name, 'fixture-task', relay_enabled=False)
        token = _CURRENT_TURN.set(turn)
        try:
            yield turn
        finally:
            _CURRENT_TURN.reset(token)

    def churn(self):
        for i in range(65):
            self.link.cancel_turn()
            self.link.end_turn(session_id='fixture-session')
            with self.runtime(f'new-{i}'):
                _, result = self.link.say('티맵에서 서울역 검색해 줘')
                self.assertTrue(result.get('request_id'))
                self.manager.invoke_hook('pre_llm_call', session_id='fixture-session',
                    turn_id=f'new-{i}', user_message=self.owner._pending_input.get_nowait())
        self.link.cancel_turn()
        self.link.end_turn(session_id='fixture-session')
        self.assertNotIn(('fixture-session', 'task'), self.link._voice_turns)
        self.assertFalse(hasattr(self.link, '_turn_admissions'))
        self.assertLessEqual(len(self.link._voice_turns), 64)


    def assert_denied(self):
        guard = self.manager.invoke_hook('pre_tool_call', tool_name='phone_act',
            args={'action': 'home'}, session_id='fixture-session', turn_id='task')
        self.assertTrue(any(x.get('action') == 'block' for x in guard))
        self.assertEqual(self.call({'action': 'home'})['error'], 'VOICE_OWNER_REQUIRED')
        self.assertEqual(self.calls, [])
        self.assertIsNone(self.link.tool_voice_request.get())

    def test_runtime_owner_survives_cancelled_history_eviction(self):
        with self.runtime('task') as turn:
            self.start()
            self.churn()
            self.assert_denied()
            self.assertEqual(turn._phone_voice_admission[2], self.request)

    def test_completed_and_expired_runtime_owner_survives_eviction(self):
        with self.runtime('task') as turn:
            self.start()
            for ending in ('complete', 'expire', 'close'):
                saved = dict(self.link._pending)
                if ending == 'complete': self.link._pending = None
                elif ending == 'expire': self.link._pending['created'] -= 10000
                else: turn.closed = True
                self.assert_denied()
                turn.closed = False
                self.link._pending = saved
            self.churn()
            self.assert_denied()

    def test_async_creation_before_eviction_and_await_after(self):
        with self.runtime('task'):
            self.start()
            value = self.link.bind_tool_voice({'action': 'home'}, self.handler.phone_act,
                session_id='fixture-session', turn_id='task', tool_name='phone_act')
            self.assertIsNone(self.link.tool_voice_request.get())
            self.churn()
            self.assertEqual(json.loads(asyncio.run(value))['error'], 'VOICE_OWNER_REQUIRED')
            self.assertEqual(self.calls, [])
            self.assertIsNone(self.link.tool_voice_request.get())

    def test_copied_context_keeps_provenance_and_does_not_leak(self):
        with self.runtime('task'):
            self.start()
            old = copy_context()
        self.churn()
        old.run(self.assert_denied)
        with self.runtime('text'):
            self.manager.invoke_hook('pre_llm_call', session_id='fixture-session',
                turn_id='text', user_message='ordinary text')
            self.assertTrue(self.call({'action': 'home'}, turn='text')['ok'])
            self.assertNotIn('_voice_request_id', self.calls[-1][1])

    def test_ordinary_runtime_survives_cache_eviction(self):
        self.start()
        self.link.cancel_turn()
        with self.runtime('text'):
            self.manager.invoke_hook('pre_llm_call', session_id='fixture-session',
                turn_id='text', user_message='ordinary text')
            self.churn()
            self.assertTrue(self.call({'action': 'home'}, turn='text')['ok'])
            self.assertNotIn('_voice_request_id', self.calls[-1][1])

    def test_cross_turn_and_repeated_transcript_cannot_restore(self):
        with self.runtime('task'):
            self.start()
            self.churn()
            self.manager.invoke_hook('pre_llm_call', session_id='fixture-session',
                turn_id='task', user_message=self.link.format_voice_message('티맵에서 서울역 검색해 줘'))
            self.assert_denied()
            with self.runtime('text'):
                self.link.prepare_voice_turn(session_id='fixture-session', turn_id='text', user_message='text')
            self.assertFalse(self.call({'action': 'home'}, turn='text')['ok'])
            self.assertEqual(self.calls, [])

    def test_legacy_unknown_and_legacy_text_both_denied(self):
        self.start()
        self.churn()
        self.assertFalse(self.call({'action': 'home'})['ok'])
        self.assertFalse(self.call({'action': 'home'}, turn='unadmitted')['ok'])
        self.assertEqual(self.calls, [])
        self.link.prepare_voice_turn(session_id='fixture-session', turn_id='fresh-text', user_message='text')
        self.assertFalse(self.call({'action': 'home'}, turn='fresh-text')['ok'])
        self.assertEqual(self.calls, [])

    def test_runtime_provenance_not_retained_by_global_history(self):
        self.start()  # Start the long-lived fixture server outside the measured turn context.
        with self.runtime('task') as turn:
            self.link.prepare_voice_turn(session_id='fixture-session', turn_id='task',
                user_message=self.link.format_voice_message('티맵에서 서울역 검색해 줘'))
            ref = weakref.ref(turn)
        del turn
        gc.collect()
        self.assertIsNone(ref())


if __name__ == '__main__':
    unittest.main(verbosity=2)
