"""Production ingress/middleware/handler chain, isolated HOME, transport spy only."""
import asyncio
import importlib
import json
import sys
import unittest
from pathlib import Path
from unittest import mock
sys.path.insert(0,str(Path(__file__).parent))
import voicelink_contract as fixture
ALLOWED_PORTS=fixture.ALLOWED_PORTS

class TaskOwnerContract(unittest.TestCase):
    setUp=fixture.VoiceLinkContract.setUp
    tearDown=fixture.VoiceLinkContract.tearDown

    def start(self, runtime=False):
        self.command('on'); ALLOWED_PORTS.add(self.link._port)
        _,accepted=self.link.say('티맵에서 서울역 검색해 줘')
        self.request=accepted['request_id']
        msg=self.owner._pending_input.get_nowait()
        if runtime:
            from agent.relay_runtime import ConversationLease, RelayTurnContext, _CURRENT_TURN
            turn = RelayTurnContext(ConversationLease('fixture', 'fixture-session', 'cli', None, None),
                                    'task', 'fixture-task', relay_enabled=False)
            token = _CURRENT_TURN.set(turn)
            self.addCleanup(_CURRENT_TURN.reset, token)
        self.link.prepare_voice_turn(session_id='fixture-session',turn_id='task',user_message=msg)
        self.module=importlib.import_module(self.link.__class__.__module__.rsplit('.',1)[0]+'.handlers')
        self.handler=self.module.Handlers(self.link.ctx,self.link)
        self.calls=[]
        async def transport(op,args,filename):
            self.calls.append((op,dict(args)))
            return {'dispatched':True,'verify_with_observe':True}
        self.transport=mock.patch.object(self.module,'query_control',transport)
        self.transport.start();self.addCleanup(self.transport.stop)
        self.config=mock.patch.object(self.link.ctx,'get_config',side_effect=lambda key,default=None: True if key=='control_enabled' else default)
        self.config.start();self.addCleanup(self.config.stop)

    def call(self,args,turn='task'):
        value=self.link.bind_tool_voice(args,lambda a:self.handler.phone_act(a),
            session_id='fixture-session',turn_id=turn,tool_name='phone_act')
        return json.loads(asyncio.run(value))

    def test_async_handler_retains_owner_across_tool_steps(self):
        self.start(runtime=True)
        for args in [{'action':'open_app','package':'com.skt.tmap.ku'},
                     {'action':'input_text','snapshot':'s','ref':'n','text':'서울역'},
                     {'action':'submit_search','snapshot':'s2','ref':'n2'}]:
            self.assertTrue(self.call(args)['ok'])
            self.assertEqual(self.calls[-1][1]['_voice_request_id'],self.request)
        self.assertIsNone(self.link.tool_voice_request.get())

    def test_missing_or_cross_turn_identity_cannot_downgrade(self):
        self.start()
        self.assertFalse(self.call({'action':'home'},turn='other')['ok'])
        self.assertEqual(self.calls,[])

    def test_stale_context_denied_after_cancel_completion_and_expiry(self):
        self.start()
        for end in ['cancel','complete','expire']:
            saved=dict(self.link._pending); turns=dict(self.link._voice_turns)
            token=self.link.tool_voice_request.set(self.request)
            try:
                if end=='cancel': self.link.cancel_turn()
                elif end=='complete': self.link._pending=None
                else: self.link._pending['created']-=10000
                result=json.loads(asyncio.run(self.handler.phone_act({'action':'home'})))
                self.assertFalse(result['ok'],end)
                self.assertEqual(self.calls,[])
            finally:
                self.link.tool_voice_request.reset(token)
                self.link._pending=saved;self.link._voice_turns=turns

    def test_live_owner_survives_context_changes_without_transcript_restore(self):
        self.start(runtime=True)
        # No transcript is consulted at the effect boundary. Compaction leaves this live owner intact.
        self.assertTrue(self.call({'action':'home'})['ok'])
        self.link._pending=None
        self.assertIsNone(self.link.voice_request('fixture-session','task'))
        self.assertFalse(self.call({'action':'home'})['ok'])

    def test_model_flags_and_owner_are_rejected_before_transport(self):
        self.start()
        for extra in [{'approved':True},{'_voice_request_id':self.request},{'task_scope':'all'}, {'confirmation':{'kind':'trusted'}}]:
            self.assertFalse(self.call({'action':'home',**extra})['ok'])
        self.assertEqual(self.calls,[])

if __name__=='__main__': unittest.main()
