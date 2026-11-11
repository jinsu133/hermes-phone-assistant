"""Offline real manager/dispatcher tests for omitted nested RPC identity fields.

No production manager, runtime, core method, or tool dispatch is patched.
The ordinary child context is core's public delegated_child_context; this is
not an end-to-end delegate_task/model test.
"""
import json
import unittest
from contextlib import contextmanager
from contextvars import copy_context
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

# Existing fixture makes a private HOME and prohibits non-loopback network.
import voicelink_contract as fixture
from hermes_cli.plugins import get_plugin_manager
from hermes_cli.lifecycle import invoke_hook
from agent.relay_runtime import ConversationLease, SESSION_COORDINATOR, current_turn
from agent.delegation_context import delegated_child_context
from model_tools import handle_function_call
from tools.code_execution_rpc import _default_dispatch


class NestedAdmission(unittest.TestCase):
    def setUp(self):
        self.manager = get_plugin_manager()
        self.manager.discover_and_load()
        self.link = self.manager._hooks['pre_llm_call'][0].__self__
        self.addCleanup(self.manager.unload)

    @contextmanager
    def turn(self, name='child', session='nested-fixture'):
        lease = ConversationLease('fixture', session, 'subagent', None, None)
        turn = SESSION_COORDINATOR.begin_turn(lease, turn_id=name, task_id='nested-task')
        try:
            yield turn
        finally:
            SESSION_COORDINATOR.end_turn(turn, outcome='success')

    def admit(self):
        turn = current_turn()
        invoke_hook('pre_llm_call', session_id=turn.lease.session_id,
                    turn_id=turn.turn_id, user_message='source-only development')

    def dispatch(self, **ids):
        # Unknown fixture tool has no handler and therefore no external effect;
        # its unknown-tool result proves the admission hook allowed dispatch.
        return handle_function_call('offline_admission_probe', {}, task_id='nested-task', **ids)

    def allowed(self, result):
        self.assertNotIn('Turn admission unavailable', result)
        self.assertIn('offline_admission_probe', result)

    def denied(self, result):
        self.assertIn('Turn admission unavailable', result)

    def test_nested_rpc_preserves_child_runtime_but_omits_wire_ids(self):
        with delegated_child_context('nested-fixture'), self.turn() as turn:
            self.admit()
            self.allowed(self.dispatch(session_id='nested-fixture', turn_id='child'))
            saved = copy_context()
            def work():
                self.assertIs(current_turn(), turn)
                return _default_dispatch('nested-task')('offline_admission_probe', {})
            with ThreadPoolExecutor(max_workers=1) as pool:
                result = pool.submit(saved.run, work).result()
            print(json.dumps({'case':'nested_rpc','runtime_same':True,'closed':turn.closed,
                              'admission_present':hasattr(turn,'_phone_voice_admission'),
                              'wire_ids_present':False,'blocked':'Turn admission unavailable' in result}))
            self.allowed(result)

    def test_nested_rpc_real_patch_effect_in_private_home(self):
        from pathlib import Path
        target = Path(fixture.TEMP.name) / 'nested-patch-fixture.txt'
        target.write_text('before nested patch\n')
        with delegated_child_context('nested-fixture'), self.turn():
            self.admit()
            result = _default_dispatch('nested-task')('patch', {
                'mode':'replace', 'path':str(target),
                'old_string':'before nested patch', 'new_string':'after nested patch'})
            self.assertNotIn('Turn admission unavailable', result)
            self.assertTrue(json.loads(result)['success'], result)
            self.assertEqual(target.read_text(), 'after nested patch\n')

    def test_missing_metadata_middleware_keeps_text_owner(self):
        import asyncio
        with self.turn():
            self.admit()
            async def handler(args):
                return self.link.tool_voice_request.get()
            value = self.link.bind_tool_voice({}, handler, tool_name='fixture')
            self.assertIsNone(asyncio.run(value))
            self.assertIsNone(self.link.tool_voice_request.get())

    def test_partial_missing_ids_resolve_only_against_current_runtime(self):
        with self.turn():
            self.admit()
            self.allowed(self.dispatch(session_id='nested-fixture'))
            self.allowed(self.dispatch(turn_id='child'))
            self.denied(self.dispatch(session_id='foreign'))
            self.denied(self.dispatch(turn_id='foreign'))

    def test_unadmitted_foreign_same_ids_and_missing_runtime_deny(self):
        with self.turn() as admitted:
            self.admit()
            with self.turn() as foreign:
                self.denied(self.dispatch())
                foreign._phone_voice_admission = admitted._phone_voice_admission
                self.denied(self.dispatch())
        self.denied(self.dispatch())

    def test_closed_context_is_not_permission(self):
        with self.turn():
            self.admit()
            saved = copy_context()
        self.denied(saved.run(self.dispatch))

    def test_skipped_child_ingress_is_not_inherited_from_parent(self):
        with self.turn('parent', 'parent-fixture'):
            self.admit()
            with delegated_child_context('nested-fixture'), self.turn():
                self.denied(self.dispatch())

    def test_text_admission_has_no_voice_ttl(self):
        with self.turn():
            self.admit()
            self.link._pending = {'created':0, 'request_id':'expired-fixture'}
            self.allowed(self.dispatch())

    def test_revoked_voice_ingress_remains_denied_with_missing_ids(self):
        with self.turn():
            invoke_hook('pre_llm_call', session_id='nested-fixture', turn_id='child',
                        user_message=self.link.format_voice_message('stale voice'))
            self.denied(self.dispatch())
            self.admit()
            self.denied(self.dispatch())


if __name__ == '__main__':
    unittest.main(verbosity=2)
