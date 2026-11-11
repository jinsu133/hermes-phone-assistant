"""Voice-link contract: real loader/hook dispatch, loopback-only, no model/mic/device.

Verifies that an inbound utterance becomes an ordinary turn in THIS CLI conversation and
that replies are captured read-only. Fixture STT replaces the provider so no model or
network call happens; the audit hook refuses every non-loopback connection.
"""
import json
import os
import queue
import shutil
import sqlite3
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
TEMP = tempfile.TemporaryDirectory(prefix='voicelink-')
os.environ['HERMES_HOME'] = TEMP.name
os.environ.pop('HERMES_SAFE_MODE', None)
os.environ.pop('HERMES_ENABLE_PROJECT_PLUGINS', None)
from hermes_cli.plugins import PluginManager  # noqa: E402
import tools.transcription_tools as transcription  # noqa: E402

ALLOWED_PORTS = set()
CONNECTS = []


def _audit(event, args):
    if event == 'socket.connect':
        address = args[1]
        if not (isinstance(address, tuple) and address[0] == '127.0.0.1' and address[1] in ALLOWED_PORTS):
            raise PermissionError('Test prohibits all non-loopback connections')


sys.addaudithook(_audit)
shutil.copytree(ROOT, Path(TEMP.name) / 'plugins' / 'hermes-phone-control',
                ignore=shutil.ignore_patterns('__pycache__'))
(Path(TEMP.name) / 'config.yaml').write_text(json.dumps(
    {'plugins': {'enabled': ['hermes-phone-control'], 'entries': {'hermes-phone-control': {
        'allow_gateway_injection': True,
        'settings': {'connection_file': str(Path(TEMP.name) / 'absent.json'), 'timeout_seconds': 1.0,
                     'voice_documents': {'fixture': str(Path(TEMP.name) / 'document.md')}}}}}}))


class VoiceLinkContract(unittest.TestCase):
    def setUp(self):
        # Import the real dispatcher before registering this test manager; its lazy
        # discovery must not replace our handlers on the first registry call.
        import model_tools
        CONNECTS.clear()
        self.manager = PluginManager()
        self.manager.discover_and_load()
        self.owner = SimpleNamespace(_agent_running=False, session_id='fixture-session',
                                     _pending_input=queue.Queue(), _interrupt_queue=queue.Queue())
        self.manager._cli_ref = self.owner
        hooks = self.manager._hooks.get('post_llm_call') or []
        self.assertEqual(len(hooks), 1, 'exactly one reply observer must be registered')
        self.link = hooks[0].__self__          # the actual VoiceLink instance
        self.command = self.manager._plugin_commands['voicelink']['handler']
        self.voice = self.link.format_voice_message
        module = sys.modules[self.link.__class__.__module__]
        self.port_patch = mock.patch.object(module, 'PORT', 0)
        self.port_patch.start()
        self.addCleanup(self.port_patch.stop)
        self.pairing_path = Path(TEMP.name) / 'fixture-pairing.json'
        self.pairing_token = 'fixture-voice-token-0123456789abcdef0123456789abcdef'
        self.pairing_path.write_text(json.dumps({
            'voice_url': module.VOICE_URL,
            'voice_token': self.pairing_token,
            'instance_id': 'fixture-device'}))
        self.link.import_pairing(str(self.pairing_path))

    def admit(self, **kwargs):
        # Model the host's real turn-start lifecycle, not the removed legacy ID fallback.
        from agent.relay_runtime import ConversationLease, SESSION_COORDINATOR
        lease = ConversationLease('fixture', str(kwargs['session_id']), 'cli', None, None)
        turn = SESSION_COORDINATOR.begin_turn(lease, turn_id=str(kwargs['turn_id']), task_id='fixture-task')
        self.addCleanup(SESSION_COORDINATOR.end_turn, turn, outcome='success')
        return self.manager.invoke_hook('pre_llm_call', **kwargs)

    def tearDown(self):
        self.link.stop()
        self.manager.unload()
        self.manager._cli_ref = None

    def test_public_explanation_real_read_dispatch_http_and_tts(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        import tools.tts_tool as tts
        self.command('on'); ALLOWED_PORTS.add(self.link._port)
        _, accepted = self.link.say('자료를 확인하고 다음 작업의 이유를 설명해 줘')
        request = accepted['request_id']; message = self.owner._pending_input.get_nowait()
        self.admit(session_id='fixture-session', turn_id='explain', user_message=message)
        def call(tool, args, turn='explain'):
            with mock.patch('hermes_cli.plugins._delivery_manager', return_value=self.manager):
                return json.loads(run_tool_execution_middleware(tool, args,
                    lambda a: registry.dispatch(tool, a, session_id='fixture-session'),
                    session_id='fixture-session', turn_id=turn))
        (Path(TEMP.name)/'document.md').write_text('시행일: 2026-10-09. 공식 문서와 비교가 필요합니다.')
        result = call('voice_draft', {'action':'read_document', 'name':'fixture'})
        self.assertTrue(result['ok']); self.assertIn('voice_evidence_id', result)
        args = {'finding':'자료의 시행일을 확인했습니다.', 'basis':'현재 적용되는 기준인지 확인해야 합니다.',
                'next_step':'공식 문서의 시행일과 비교하겠습니다.', 'evidence_ids':[result['voice_evidence_id']]}
        self.assertIsNone(self.link.guard_voice_tool(tool_name='voice_progress', args=args, session_id='fixture-session',turn_id='explain'))
        self.assertFalse(call('voice_progress', args, 'other')['ok'])
        queued = call('voice_progress', args)
        self.assertTrue(queued['queued']); self.assertFalse(queued['spoken'])
        self.assertIsNone(self.link.confirmation.pending)
        code, payload = self._call('/reply?protocol=5&request_id='+request)
        self.assertEqual(code,200); event = payload['event']; self.assertEqual(event['kind'],'explanation')
        expected = ' '.join(args[k] for k in ('finding','basis','next_step'))
        self.assertEqual(event['text'], expected)
        def synth(text, output_path):
            self.assertEqual(text, expected)
            Path(output_path).write_bytes(b'fixture-not-real-speech')
            return json.dumps({'success':True,'file_path':output_path})
        with mock.patch.object(tts,'text_to_speech_tool',side_effect=synth) as provider:
            self.assertEqual(self.link.tts(event['audio_id'], protocol=5)[0],200)
            self.assertEqual(provider.call_count,1)
        self.link.observe_reply(session_id='fixture-session',turn_id='explain',user_message=message,assistant_response='최종 답변')
        self.assertTrue(self.link.reply(request,5)[1]['ready'])
        self.assertEqual(self.link.tts(event['audio_id'],protocol=5)[0],410)
        self.assertFalse(call('voice_progress', args)['ok'])

    def test_public_explanation_no_dispatch_failure_or_foreign_receipts(self):
        self.command('on')
        _, accepted=self.link.say('fixture explain')
        self.admit(session_id='fixture-session',turn_id='explain',user_message=self.voice('fixture explain'))
        for tool,args,result,turn in [
            ('web_extract',{}, {'results':[{'error':'failed'}]}, 'explain'),
            ('phone_act',{'action':'click'}, {'ok':True,'result':{'dispatched':True}}, 'explain'),
            ('phone_observe',{}, {'ok':False,'error':'locked'}, 'explain'),
            ('web_search',{}, {'data':{'web':[{'title':'fixture','url':'https://example.org'}]}}, 'other')]:
            raw=json.dumps(result)
            actual=self.link.bind_tool_voice(args,lambda a:raw,session_id='fixture-session',turn_id=turn,tool_name=tool)
            self.assertEqual(actual,raw)
        self.assertEqual(self.link.events.evidence,{})
        self.link.cancel_turn()
        value=self.link.bind_tool_voice({},lambda a:json.dumps({'ok':True,'result':{'nodes':[]}}),
            session_id='fixture-session',turn_id='explain',tool_name='phone_observe')
        self.assertNotIn('voice_evidence_id',json.loads(value))

    def test_public_explanation_never_arms_pending_confirmation(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        self.command('on')
        _, accepted=self.link.say('fixture approval guard')
        request=accepted['request_id']
        self.admit(session_id='fixture-session',turn_id='explain',user_message=self.voice('fixture approval guard'))
        receipt=self.link.events.record_evidence(request,'web_extract')
        self.link.begin_confirmation(request,{'request_id':'action1','confirmation_prompt':'시험 작업입니다.',
            'voice_phrase':'이 작업 실행 허용','action_digest':'a'*64})
        before=dict(self.link.confirmation.pending)
        args={'finding':'확인했습니다.','basis':'비교가 필요합니다.','next_step':'비교하겠습니다.','evidence_ids':[receipt]}
        with mock.patch('hermes_cli.plugins._delivery_manager',return_value=self.manager):
            result=run_tool_execution_middleware('voice_progress',args,
                lambda a:registry.dispatch('voice_progress',a,session_id='fixture-session'),
                session_id='fixture-session',turn_id='explain')
        self.assertFalse(json.loads(result)['queued'])
        self.assertEqual(self.link.confirmation.pending,before)

    def test_public_events_negotiate_and_final_wins(self):
        self.command('on'); ALLOWED_PORTS.add(self.link._port)
        _, accepted=self.link.say('진행 안내 시험')
        request=accepted['request_id']
        message=self.owner._pending_input.get_nowait()
        self.admit(session_id='fixture-session',turn_id='progress',user_message=message)
        self.assertNotIn('event',self.link.reply(request)[1])
        code,value=self._call('/reply?protocol=4&request_id='+request)
        event=value['event'];self.assertEqual(event['request_id'],request)
        self.assertEqual(self.link.outbox.get(event['audio_id'])['text'],event['text'])
        self.assertNotIn('event',self._call('/reply?protocol=4&after_seq='+str(event['seq'])+'&request_id='+request)[1])
        self.link.events.last-=11
        self.link.bind_tool_voice({},lambda a:'fixture',session_id='fixture-session',turn_id='other',tool_name='web_search')
        self.assertEqual(self.link.events.get(request)['kind'],'accepted')
        self.link.bind_tool_voice({},lambda a:'fixture',session_id='fixture-session',turn_id='progress',tool_name='web_search')
        self.assertEqual(self.link.events.get(request)['kind'],'research')
        self.link.observe_reply(session_id='fixture-session',turn_id='progress',user_message=message,assistant_response='완료')
        value=self._call('/reply?protocol=4&request_id='+request)[1]
        self.assertTrue(value['ready']);self.assertNotIn('event',value)
        self.assertIsNone(self.link.events.get(request))
        self.link.cancel_turn();self.assertEqual(self.link.reply(request)[0],404)

    def test_superseded_progress_never_synthesizes(self):
        import tools.tts_tool as tts
        self.command('on')
        _, accepted=self.link.say('fixture progress')
        request=accepted['request_id']
        message=self.owner._pending_input.get_nowait()
        event=self.link.reply(request,4)[1]['event']
        self.link.observe_reply(session_id='fixture-session',turn_id='t',user_message=message,assistant_response='final')
        with mock.patch.object(tts,'text_to_speech_tool',return_value=json.dumps({'success':False})) as provider:
            self.assertEqual(self.link.tts(event['audio_id'])[0],410)
            provider.assert_not_called()

    def test_late_progress_audio_is_discarded(self):
        import tools.tts_tool as tts
        self.command('on')
        _, accepted=self.link.say('fixture progress')
        request=accepted['request_id']; message=self.owner._pending_input.get_nowait()
        event=self.link.reply(request,4)[1]['event']
        def fixture_synth(text,output_path):
            Path(output_path).write_bytes(b'fixture audio, not real synthesis')
            self.link.observe_reply(session_id='fixture-session',turn_id='t',user_message=message,assistant_response='final')
            return json.dumps({'success':True,'file_path':output_path})
        with mock.patch.object(tts,'text_to_speech_tool',side_effect=fixture_synth):
            self.assertEqual(self.link.tts(event['audio_id'])[0],410)
            self.assertIsNone(self.link._tts_cache)

    def test_tts_failure_is_not_paid_retry(self):
        import tools.tts_tool as tts
        self.command('on')
        self.link.outbox.add(request_id='failure',session_id='s',turn_id='t',text='fixture')
        with mock.patch.object(tts,'text_to_speech_tool',return_value=json.dumps({'success':False})) as provider:
            self.assertEqual(self.link.tts('failure')[0],502)
            self.assertEqual(self.link.tts('failure')[0],410)
            self.assertEqual(provider.call_count,1)

    def test_voice_model_is_scoped_and_cancelled_tools_stay_blocked(self):
        self.command('on')
        status, accepted = self.link.say('헤르메스 지금 상태 알려줘')
        self.assertEqual(status, 200)
        msg = self.owner._pending_input.get_nowait()
        self.admit(session_id='fixture-session', turn_id='voice1', user_message=msg)
        request = {'model': 'gpt-6-astra', 'reasoning': {'effort': 'high'}, 'input': []}
        self.assertIsNone(self.link.route_voice_model(request, session_id='fixture-session', turn_id='other', api_mode='codex_responses'))
        changed = self.link.route_voice_model(request, session_id='fixture-session', turn_id='voice1', api_mode='codex_responses')
        self.assertEqual(changed['request']['model'], 'gpt-6.1-sol')
        self.assertEqual(changed['request']['reasoning']['effort'], 'medium')
        self.assertEqual(request['model'], 'gpt-6-astra')
        self.link.cancel_turn()
        self.assertEqual(self.link.guard_voice_tool(session_id='fixture-session', turn_id='voice1', tool_name='phone_status')['action'], 'block')
        self.assertTrue(self.link.listening)
        self.assertEqual(self.link.say('헤르메스')[1]['no_speech'], True)

    def test_completed_turn_releases_busy_gate(self):
        self.command('on')
        self.admit(session_id='fixture-session',
                                 turn_id='ordinary', user_message='ordinary')
        self.link.auto_start(session_id='fixture-session')
        self.assertEqual(self.link.say('hello')[0],409)
        self.link.observe_reply(session_id='fixture-session', user_message='ordinary', assistant_response='done')
        self.assertEqual(self.link.say('hello')[0],200)

    def test_stop_cancels_without_injection(self):
        self.command('on')
        code, value = self.link.say('스탑')
        self.assertEqual(code,200); self.assertTrue(value['stopped'])
        self.assertTrue(self.owner._pending_input.empty())

    def test_cancel_during_stt_never_injects(self):
        self.command('on')
        def transcribe(*args, **kwargs):
            self.link.cancel_turn()
            return {'success': True, 'transcript': 'do not send this'}
        with mock.patch.object(transcription, 'transcribe_audio', side_effect=transcribe):
            self.assertEqual(self.link.hear(b'fixture', '.wav')[1]['error'], 'voice_cancelled')
        self.assertTrue(self.owner._pending_input.empty())

    def test_document_real_dispatch_is_read_only_scoped_and_digest_bound(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        fixture = Path(TEMP.name) / 'document.md'
        original = ('문서 😀 시험\n' * 900).encode('utf-8')
        fixture.write_bytes(original)
        self.command('on')
        self.assertEqual(self.link.say('기존 문서를 읽어 줘')[0], 200)
        self.admit(session_id='fixture-session', turn_id='document',
                                 user_message=self.owner._pending_input.get_nowait())
        def call(args, turn='document'):
            with mock.patch('hermes_cli.plugins._delivery_manager', return_value=self.manager):
                value = run_tool_execution_middleware('voice_draft', args,
                    lambda a: registry.dispatch('voice_draft', a, session_id='fixture-session'),
                    session_id='fixture-session', turn_id=turn)
                return json.loads(value)
        self.assertEqual(call({'action':'list_documents'})['result']['documents'], ['fixture'])
        self.assertEqual(call({'action':'list_documents'}, turn='other')['error'], 'VOICE_OWNER_REQUIRED')
        self.assertEqual(call({'action':'read_document','name':'missing'})['error'], 'DOCUMENT_NOT_ALLOWED')
        self.assertEqual(call({'action':'read_document','name':'fixture','path':str(fixture)})['error'], 'INVALID_DOCUMENT_ARGUMENTS')
        first = call({'action':'read_document','name':'fixture'})['result']
        page = first; parts = [page['content']]
        while page['has_more']:
            page = call({'action':'read_document', 'name':'fixture', 'offset':page['next_offset'],
                         'expected_sha256':first['sha256']})['result']
            parts.append(page['content'])
        self.assertEqual(''.join(parts).encode('utf-8'), original)
        self.assertEqual(fixture.read_bytes(), original)
        fixture.write_text('changed')
        self.assertEqual(call({'action':'read_document','name':'fixture','offset':1,
                               'expected_sha256':first['sha256']})['error'], 'DOCUMENT_CHANGED')
        self.link.cancel_turn()
        self.assertEqual(call({'action':'read_document','name':'fixture'})['error'], 'VOICE_OWNER_REQUIRED')

    def test_document_errors_do_not_expose_paths_or_file_data(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        fixture = Path(TEMP.name) / 'document.md'
        fixture.write_bytes(b'\xffPRIVATE_FIXTURE')
        self.command('on'); self.link.say('문서를 읽어 줘')
        self.admit(session_id='fixture-session', turn_id='document',
                                 user_message=self.owner._pending_input.get_nowait())
        with mock.patch('hermes_cli.plugins._delivery_manager', return_value=self.manager):
            value = run_tool_execution_middleware('voice_draft', {'action':'read_document','name':'fixture'},
                lambda a: registry.dispatch('voice_draft', a, session_id='fixture-session'),
                session_id='fixture-session', turn_id='document')
        self.assertFalse(json.loads(value)['ok'])
        self.assertNotIn(TEMP.name, value)
        self.assertNotIn('PRIVATE_FIXTURE', value)

    def test_draft_tool_dispatch_scopes_revisions_and_syntax_check(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        self.command('on')
        _, accepted=self.link.say('초안을 작성하고 문법을 검사해 줘')
        self.admit(session_id='fixture-session',turn_id='draft',user_message=self.voice('초안을 작성하고 문법을 검사해 줘'))
        def call(args,turn='draft'):
            with mock.patch('hermes_cli.plugins._delivery_manager',return_value=self.manager):
                result=run_tool_execution_middleware('voice_draft',args,
                    lambda a:registry.dispatch('voice_draft',a,session_id='fixture-session'),session_id='fixture-session',turn_id=turn)
                return json.loads(result)
        self.assertIsNone(self.link.guard_voice_tool(tool_name='voice_draft',args={'action':'list'},session_id='fixture-session',turn_id='draft'))
        one=call({'action':'write','name':'sample.py','content':'value = 2 + 3\n'})
        self.assertTrue(one['ok']);self.assertTrue(one['result']['verified'])
        self.assertFalse(call({'action':'write','name':'sample.py','content':'value = 4\n'})['ok'])
        revised=call({'action':'write','name':'sample.py','content':'value = 4\n','expected_sha256':one['result']['sha256']})
        self.assertTrue(revised['ok'],repr(revised)+repr(Path(one['result']['path']).read_text()))
        check=call({'action':'check','name':'sample.py'})
        self.assertTrue(check['result']['valid']);self.assertFalse(check['result']['executed'])
        self.assertFalse(call({'action':'read','name':'sample.py'},turn='other')['ok'])
        self.link.cancel_turn()
        self.assertFalse(call({'action':'list'})['ok'])

    def test_structured_read_and_search_arguments_do_not_accept_approval_or_roles(self):
        actions=sys.modules[self.link.__class__.__module__.rsplit('.',1)[0]+'.actions']
        actions.validate_action({'action':'read_node','snapshot':'s','ref':'n','offset':2000})
        actions.validate_action({'action':'submit_search','snapshot':'s','ref':'n'})
        for extra in [{'approved':True},{'role':'search_field'},{'offset':True},{'offset':-1}]:
            with self.assertRaises(actions.Denied):
                actions.validate_action({'action':'read_node','snapshot':'s','ref':'n',**extra})

    def test_voice_handler_middleware_continues_same_tool_after_decision(self):
        import asyncio
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        self.command('on')
        code, accepted = self.link.say('확인 시험')
        self.admit(session_id='fixture-session', turn_id='resume', user_message=self.voice('확인 시험'))
        handler_module=sys.modules[self.link.__class__.__module__.rsplit('.',1)[0]+'.handlers']
        seen=[]
        async def backend(operation,args,filename):
            seen.append(dict(args))
            if args.get('action')=='click':
                self.assertEqual(args['_voice_request_id'],accepted['request_id'])
                return {'state':'awaiting_voice_confirmation','request_id':'action1',
                        'confirmation_prompt':'시험 동작입니다','voice_phrase':'이 작업 실행 허용','action_digest':'a'*64}
            if args.get('action')=='approval_status':
                event=self.link.confirmation.get(accepted['request_id'],'action1')
                nonce=self.link.confirmation.arm(accepted['request_id'],'action1')['nonce']
                self.link.confirmation.claim_audio(accepted['request_id'],'action1',nonce)
                self.link.confirmation.finish_audio(accepted['request_id'],'action1','이 작업 실행 허용')
                return {'state':'completed','dispatched':True,'verify_with_observe':True}
            raise AssertionError('Unexpected action')
        original=self.link.ctx.get_config
        # The handler context is independently created? Both objects share register(ctx).
        def config(name,default=None): return True if name=='control_enabled' else original(name,default)
        with mock.patch.object(self.link.ctx,'get_config',side_effect=config), mock.patch.object(handler_module,'query_control',side_effect=backend), mock.patch('hermes_cli.plugins._delivery_manager',return_value=self.manager):
            result=run_tool_execution_middleware('phone_act',{'action':'click','snapshot':'s','ref':'n'},
                lambda args: registry.dispatch('phone_act',args,session_id='fixture-session'),
                session_id='fixture-session',turn_id='resume')
        self.assertTrue(json.loads(result)['result']['dispatched'])
        self.assertEqual([x['action'] for x in seen],['click','approval_status'])
        self.assertIsNone(self.link.confirmation.pending)
        self.assertIsNone(self.link.tool_voice_request.get())

    def test_document_revision_real_dispatch_wait_resume_and_saved_copy(self):
        import hashlib
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        self.command('on')
        _, accepted=self.link.say('문서 수정본을 만들어 줘')
        request=accepted['request_id']
        self.admit(session_id='fixture-session',turn_id='revision',user_message=self.voice('문서 수정본을 만들어 줘'))
        source=Path(TEMP.name)/'document.md';source.write_text('회의는 오전 9시입니다.')
        before=source.read_bytes(); digest=hashlib.sha256(before).hexdigest()
        handler_module=sys.modules[self.link.__class__.__module__.rsplit('.',1)[0]+'.handlers']
        captured={}; seen=[]
        async def backend(operation,args,filename):
            seen.append(operation)
            if operation=='document_revision':
                captured.update(args)
                self.assertEqual(args['voice_request_id'],request)
                self.assertFalse((Path(TEMP.name)/'voice-workspaces'/request).exists())
                return {'state':'awaiting_voice_confirmation','request_id':'revision-action',
                        'confirmation_prompt':'테스트 문서 수정본 저장', 'voice_phrase':'이 문서 수정본 저장 허용',
                        'action_digest':args['action_digest']}
            if operation=='act' and args.get('action')=='approval_status':
                # Fixture decision only. This does not approve a production device action.
                nonce=self.link.confirmation.arm(request,'revision-action')['nonce']
                self.link.confirmation.claim_audio(request,'revision-action',nonce)
                decision=self.link.confirmation.finish_audio(request,'revision-action','이 문서 수정본 저장 허용')
                self.assertTrue(decision['allowed'])
                return {'state':'completed','dispatched':True,'permission_only':True,
                        'voice_request_id':request,'authorized_digest':captured['action_digest']}
            if operation=='status': return {'voice_request_id':request,'handsfree_active':True,'control_allowed':True}
            raise AssertionError('Unexpected backend operation')
        original=self.link.ctx.get_config
        def config(name,default=None): return True if name=='control_enabled' else original(name,default)
        args=dict(action='revise_document',name='fixture',expected_sha256=digest,old_text='오전 9시',new_text='오전 10시')
        with mock.patch.object(self.link.ctx,'get_config',side_effect=config), mock.patch.object(handler_module,'query_control',side_effect=backend), mock.patch('hermes_cli.plugins._delivery_manager',return_value=self.manager):
            result=json.loads(run_tool_execution_middleware('voice_draft',args,
                lambda a:registry.dispatch('voice_draft',a,session_id='fixture-session'),session_id='fixture-session',turn_id='revision'))
        self.assertTrue(result['ok'],result)
        self.assertTrue(result['result']['verified']);self.assertTrue(result['result']['original_preserved'])
        self.assertEqual(Path(result['result']['path']).read_text(),'회의는 오전 10시입니다.')
        self.assertEqual(source.read_bytes(),before)
        self.assertEqual(seen,['document_revision','act','status'])
        self.assertIsNone(self.link.confirmation.pending)
        self.assertEqual(self.link.voice_request('fixture-session','revision'),request)

    def test_real_python_dispatch_denial_then_fixed_approval_executes_once(self):
        from hermes_cli.middleware import run_tool_execution_middleware
        from tools.registry import registry
        self.command('on');_,accepted=self.link.say('작은 Python 코드를 실행해 줘')
        request=accepted['request_id']
        self.admit(session_id='fixture-session',turn_id='python',user_message=self.voice('작은 Python 코드를 실행해 줘'))
        module=sys.modules[self.link.__class__.__module__.rsplit('.',1)[0]+'.handlers']
        def call(args):return json.loads(run_tool_execution_middleware('voice_draft',args,
            lambda a:registry.dispatch('voice_draft',a,session_id='fixture-session'),session_id='fixture-session',turn_id='python'))
        original=self.link.ctx.get_config
        def config(name,default=None):return True if name=='control_enabled' else original(name,default)
        allow=False;captured={}
        async def backend(operation,args,filename):
            if operation=='code_execution':
                captured.update(args)
                return dict(state='awaiting_voice_confirmation',request_id='compute-action',confirmation_prompt='코드 원문 print(sum([2, 4, 6]))',
                    voice_phrase='이 코드 격리 실행 허용',action_digest=args['action_digest'])
            if operation=='status':return dict(voice_request_id=request,handsfree_active=True,control_allowed=True)
            if operation=='act' and args.get('action')=='approval_status':
                nonce=self.link.confirmation.arm(request,'compute-action')['nonce']
                self.link.confirmation.claim_audio(request,'compute-action',nonce)
                decision=self.link.confirmation.finish_audio(request,'compute-action','이 코드 격리 실행 허용' if allow else '취소')
                self.assertEqual(decision['allowed'],allow)
                if not allow:return dict(state='cancelled',dispatched=False)
                return dict(state='completed',dispatched=True,permission_only=True,voice_request_id=request,authorized_digest=captured['action_digest'])
            raise AssertionError('Unexpected operation')
        with mock.patch.object(self.link.ctx,'get_config',side_effect=config), mock.patch.object(module,'query_control',side_effect=backend), mock.patch.object(module,'run_python',wraps=module.run_python) as runner, mock.patch('hermes_cli.plugins._delivery_manager',return_value=self.manager):
            saved=call(dict(action='write',name='compute.py',content='print(sum([2, 4, 6]))'))
            self.assertTrue(saved['ok'])
            args=dict(action='run_python',name='compute.py',expected_sha256=saved['result']['sha256'])
            denied=call(args);self.assertFalse(denied['ok']);self.assertEqual(runner.call_count,0)
            allow=True
            done=call(args);self.assertTrue(done['ok'],done);self.assertEqual(runner.call_count,1)
            self.assertTrue(done['result']['executed']);self.assertEqual(done['result']['exit_code'],0,done)
            self.assertEqual(done['result']['stdout'].strip(),'12')
        self.assertIsNone(self.link.confirmation.pending)
        self.assertEqual(self.link.voice_request('fixture-session','python'),request)

    def test_confirmation_audio_is_not_injected_as_new_request(self):
        self.command('on'); ALLOWED_PORTS.add(self.link._port)
        _, accepted=self.link.say('시험 작업')
        self.owner._pending_input.get_nowait()
        event=self.link.begin_confirmation(accepted['request_id'],{'request_id':'action1','confirmation_prompt':'내용 확인',
            'voice_phrase':'이 작업 실행 허용','action_digest':'a'*64})
        _, armed=self._call('/confirmation/arm',{'request_id':accepted['request_id'],'action_id':'action1'})
        headers={'X-Voice-Request':accepted['request_id'],'X-Voice-Action':'action1','X-Voice-Nonce':armed['nonce']}
        with mock.patch.object(transcription,'transcribe_audio',return_value={'success':True,'transcript':'이 작업 실행 허용'}):
            code,result=self._call('/confirmation/audio',b'fixture',headers=headers)
        self.assertEqual(code,200); self.assertTrue(result['allowed'])
        self.assertTrue(self.owner._pending_input.empty())
        self.assertEqual(self._call('/confirmation/audio',b'fixture',headers=headers)[0],409)
        self.assertEqual(self._call('/confirmation/arm',{'request_id':accepted['request_id'],'action_id':'action1'},token=False)[0],401)

    # ---- helpers ---------------------------------------------------------
    def _url(self, path):
        return f"http://127.0.0.1:{self.link._port}{path}"

    def test_voice_turn_context_and_tool_guard_are_scoped_to_exact_turn(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        status, accepted = self._call('/say', {'text': '화면 상태를 알려줘'})
        self.assertEqual(status, 200)
        message = self.voice('화면 상태를 알려줘')
        self.assertIn('음성은 본인 인증이 아닙니다', message)
        context = self.admit(session_id='fixture-session', turn_id=42, user_message=message,
            conversation_history=[], model='fixture-model', platform='cli')
        self.assertEqual(len(context), 1)
        self.assertIn('phone_act', context[0])
        blocked = self.manager.invoke_hook(
            'pre_tool_call', tool_name='terminal', args={}, session_id='fixture-session', turn_id=42)
        self.assertEqual(len(blocked), 1)
        self.assertEqual(blocked[0]['action'], 'block')
        allowed = self.manager.invoke_hook(
            'pre_tool_call', tool_name='phone_observe', args={}, session_id='fixture-session', turn_id=42)
        self.assertEqual(allowed, [])
        unrelated = self.manager.invoke_hook(
            'pre_tool_call', tool_name='terminal', args={}, session_id='fixture-session', turn_id=43)
        self.assertTrue(any(x.get('action') == 'block' for x in unrelated))
        self.assertTrue(accepted['accepted'])

    def _call(self, path, body=None, token=True, headers=None, method=None):
        data = json.dumps(body).encode() if isinstance(body, dict) else body
        request = urllib.request.Request(self._url(path), data=data, method=method)
        if token:
            request.add_header('Authorization', 'Bearer ' + self.link._token)
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read() or b'{}')
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b'{}')

    def _await_reply(self, request_id, limit=2.0):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            status, payload = self._call(f'/reply?request_id={request_id}')
            self.assertEqual(status, 200)
            if payload.get('ready'):
                return [payload['item']] if payload.get('item') else []
            time.sleep(0.05)
        return []

    def test_confirmation_hook_cannot_be_forged(self):
        self.admit(session_id='any', turn_id=1, user_message='open the map app')
        cases = [
            {"action":"home","confirmed":True},
            {"action":"click","snapshot":"s","ref":"n0","confirmation":{"kind":"payment","merchant":"상점A"}},
            {"action":"click","snapshot":"s","ref":"n0","confirmation":{"kind":"message","recipient":"테스트"}},
        ]
        for args in cases:
            blocked=self.manager.invoke_hook('pre_tool_call',tool_name='phone_act',args=args,session_id='any',turn_id=1)
            self.assertTrue(any(x.get('action')=='block' for x in blocked))
            wrapped=self.manager.invoke_hook('pre_tool_call',tool_name='tool_call',args={'calls':[{'name':'phone_act','arguments':args}]},session_id='any',turn_id=1)
            self.assertTrue(any(x.get('action')=='block' for x in wrapped))
        self.assertEqual(self.manager.invoke_hook('pre_tool_call',tool_name='phone_act',args={'action':'open_app','package':'com.skt.tmap.ku'},session_id='any',turn_id=1),[])

    # ---- tests -----------------------------------------------------------
    def test_registration_is_inert(self):
        """Discovery/registration must not bind a port or create the token file."""
        self.assertFalse(self.link.listening)
        state_file = Path(TEMP.name, 'state', 'voicelink.json')
        state_file.unlink(missing_ok=True)
        self.manager.unload()
        fresh = PluginManager()
        fresh.discover_and_load()
        self.assertFalse(state_file.exists(), 'registration must not write the token file')
        self.assertFalse((fresh._hooks['post_llm_call'][0].__self__).listening)
        fresh.unload()

    def test_explicit_on_off_and_token_hygiene(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        state_file = Path(TEMP.name, 'state', 'voicelink.json')
        self.assertTrue(self.link.listening)
        mode = state_file.stat().st_mode & 0o777
        self.assertEqual(mode, 0o600, 'token file must not be group/other readable')
        status, payload = self._call('/health', token=False, method='GET')
        self.assertEqual((status, payload['ok']), (200, True))
        self.assertEqual(self._call('/say', {'text': 'x'}, token=False, method='POST')[0], 401)
        token, port = self.link._token, self.link._port
        self.command('off')
        self.assertFalse(self.link.listening)
        # Rebind the same isolated test port: pairing survives OFF without re-import.
        with mock.patch.object(sys.modules[self.link.__class__.__module__], 'PORT', port):
            self.command('on')
        self.assertEqual((self.link._token, self.link._port), (token, port))
        self.assertGreater(port, 0)

    def test_utterance_becomes_an_idle_turn(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        status, payload = self._call('/say', {'text': '조명 켜줘'})
        self.assertEqual(status, 200)
        self.assertTrue(payload['accepted'])
        self.assertEqual(payload['delivery'], 'cli_queue')
        self.assertEqual(self.owner._pending_input.get_nowait(), self.voice('조명 켜줘'))
        self.assertTrue(self.owner._interrupt_queue.empty())

    def test_utterance_while_running_is_an_interrupt(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        self.owner._agent_running = True
        _, payload = self._call('/say', {'text': '잠깐 멈춰'})
        self.assertTrue(payload['accepted'])
        self.assertEqual(payload['delivery'], 'cli_queue')
        self.assertEqual(self.owner._interrupt_queue.get_nowait(), self.voice('잠깐 멈춰'))
        self.assertTrue(self.owner._pending_input.empty())

    def test_reply_is_observed_not_rewritten(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        status, accepted = self._call('/say', {'text': '조명 켜줘'})
        self.assertEqual(status, 200)
        returned = self.manager.invoke_hook(
            'post_llm_call', session_id='fixture-session', task_id='fixture-task', turn_id=7,
            user_message=self.voice('조명 켜줘'), assistant_response='조명을 켰습니다.',
            conversation_history=[], model='fixture-model', platform='cli')
        self.assertEqual(returned, [None] if returned else returned,
                         'observer hook must not return a replacement')
        items = self._await_reply(accepted['request_id'])
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]['text'], '조명을 켰습니다.')
        self.assertEqual((items[0]['turn_id'], items[0]['session_id']), (7, 'fixture-session'))
        self.assertEqual(self._call(f"/reply?since={items[0]['seq']}")[1]['items'], [])

    def test_audio_uses_existing_stt_and_deletes_the_temp_file(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        seen = {}

        def fake_transcribe(path, model=None, source=None):
            seen['path'] = path
            seen['source'] = source
            seen['exists_during'] = Path(path).is_file()
            return {'success': True, 'transcript': '춘식아 조명 켜줘'}

        with mock.patch.object(transcription, 'transcribe_audio', fake_transcribe):
            status, payload = self._call('/audio', b'RIFFfixture', headers={'X-Audio-Format': '.wav'})
        self.assertEqual(status, 200)
        self.assertTrue(payload['accepted'])
        self.assertEqual(payload['delivery'], 'cli_queue')
        self.assertEqual(seen['source'], 'voicelink')
        self.assertTrue(seen['exists_during'])
        self.assertFalse(Path(seen['path']).exists(), 'audio must not be retained')
        self.assertEqual(self.owner._pending_input.get_nowait(), self.voice('춘식아 조명 켜줘'))

    def test_audio_failure_and_bounds_fail_closed(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        def failing(path, model=None, source=None):
            return {'success': False, 'error': 'fixture provider down'}
        with mock.patch.object(transcription, 'transcribe_audio', failing):
            status, payload = self._call('/audio', b'RIFFfixture', headers={'X-Audio-Format': '.wav'})
        self.assertEqual(status, 502)
        self.assertEqual(payload['error'], 'transcription_failed')
        self.assertTrue(self.owner._pending_input.empty(), 'a failed transcription injects nothing')
        self.assertEqual(self._call('/audio', b'', headers={'X-Audio-Format': '.wav'})[1]['error'], 'empty_audio')
        self.assertEqual(self._call('/audio', b'x', headers={'X-Audio-Format': '.exe'})[1]['error'],
                         'unsupported_format')
        self.assertEqual(self._call('/say', {'text': '   '})[1]['error'], 'empty_text')
        self.assertEqual(self._call('/say', {'text': 'x' * 4001})[0], 413)
        oversized = urllib.request.Request(self._url('/say'), data=b'', method='POST')
        oversized.add_header('Authorization', 'Bearer ' + self.link._token)
        oversized.add_header('Content-Length', str(5 * 1024 * 1024 + 1))
        try:
            with urllib.request.urlopen(oversized, timeout=5) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        self.assertEqual(status, 413)
        self.assertTrue(self.owner._pending_input.empty())

    def test_no_speech_is_not_a_failure_and_injects_nothing(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        with mock.patch.object(transcription, 'transcribe_audio',
                               lambda path, model=None, source=None: {'success': True, 'transcript': ''}):
            status, payload = self._call('/audio', b'RIFFfixture', headers={'X-Audio-Format': '.wav'})
        self.assertEqual(status, 200)
        self.assertTrue(payload['no_speech'])
        self.assertFalse(payload['accepted'])
        self.assertTrue(self.owner._pending_input.empty())

    def test_off_closes_the_port_and_drops_pending_replies(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        port = self.link._port
        status, accepted = self._call('/say', {'text': 'u'})
        self.assertEqual(status, 200)
        self.manager.invoke_hook('post_llm_call', session_id='fixture-session', task_id='t', turn_id=1,
                                 user_message=self.voice('u'), assistant_response='곧 사라질 답변',
                                 conversation_history=[], model='m', platform='cli')
        self.assertTrue(self._await_reply(accepted['request_id']))
        status, payload = self._call('/off', body=b'', method='POST')
        self.assertEqual((status, payload['listening']), (200, False))
        self.assertEqual(self.link.outbox.since(0), [], 'pending replies must be dropped on OFF')
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3)

    def test_gateway_uses_the_existing_route_and_only_its_reply(self):
        self.manager._cli_ref = None
        session_key = 'agent:main:telegram:dm:fixture'
        config_path = Path(TEMP.name) / 'config.yaml'
        original_config = config_path.read_text()
        self.addCleanup(config_path.write_text, original_config)
        config = json.loads(original_config)
        config['plugins']['entries']['hermes-phone-control']['settings']['session_key'] = session_key
        config_path.write_text(json.dumps(config))

        home = Path(TEMP.name)
        db = sqlite3.connect(home / 'state.db')
        db.execute('CREATE TABLE IF NOT EXISTS gateway_routing (scope TEXT NOT NULL, session_key TEXT NOT NULL, entry_json TEXT NOT NULL, updated_at REAL NOT NULL, PRIMARY KEY(scope, session_key))')
        db.execute('INSERT INTO gateway_routing VALUES (?, ?, ?, ?)',
                   (str((home / 'sessions').resolve()), session_key,
                    json.dumps({'session_id': 'gateway-session-a'}), time.time()))
        db.commit()
        db.close()

        injected = []
        owner = object()
        self.manager.set_gateway_message_injector(
            owner, lambda **kwargs: injected.append(kwargs) is None)
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        status, accepted = self._call('/say', {'text': '춘식아 상태를 알려줘'})
        self.assertEqual(status, 200)
        self.assertTrue(accepted['accepted'])
        self.assertEqual(injected, [{
            'session_key': session_key,
            'content': self.voice('춘식아 상태를 알려줘'),
            'plugin_id': 'hermes-phone-control'}])

        self.manager.invoke_hook(
            'post_llm_call', session_id='another-session', task_id='wrong', turn_id=1,
            user_message=self.voice('춘식아 상태를 알려줘'), assistant_response='다른 세션 응답',
            conversation_history=[], model='fixture-model', platform='telegram')
        status, pending = self._call(f"/reply?request_id={accepted['request_id']}")
        self.assertEqual((status, pending['state']), (200, 'pending'))

        self.manager.invoke_hook(
            'post_llm_call', session_id='gateway-session-a', task_id='right', turn_id=2,
            user_message=self.voice('춘식아 상태를 알려줘'), assistant_response='고정된 세션의 응답입니다.',
            conversation_history=[], model='fixture-model', platform='telegram')
        items = self._await_reply(accepted['request_id'])
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]['text'], '고정된 세션의 응답입니다.')
        self.assertEqual(items[0]['session_id'], 'gateway-session-a')

    def test_tts_multiple_segments_are_complete_ordered_and_generated_once(self):
        import tools.tts_tool as tts
        self.command('on'); ALLOWED_PORTS.add(self.link._port)
        self.link.outbox.add(request_id='parts',session_id='s',turn_id='t',text='fixture reply')
        produced = []
        def synth(text,output_path):
            paths = [Path(output_path).with_name('one.wav'), Path(output_path).with_name('two.mp3')]
            for path,data in zip(paths,(b'first-fixture',b'second-fixture')): path.write_bytes(data)
            produced.extend(paths)
            return json.dumps({'success':True,'file_paths':list(map(str,paths))})
        with mock.patch.object(tts,'text_to_speech_tool',side_effect=synth) as provider:
            for index,data,suffix in [(0,b'first-fixture','.wav'),(1,b'second-fixture','.mp3'),(0,b'first-fixture','.wav')]:
                request=urllib.request.Request(self._url('/tts?request_id=parts&protocol=5&segment='+str(index)),
                    headers={'Authorization':'Bearer '+self.link._token})
                with urllib.request.urlopen(request,timeout=5) as response:
                    self.assertEqual(response.read(),data)
                    self.assertEqual(response.headers['X-Audio-Segment'],str(index))
                    self.assertEqual(response.headers['X-Audio-Segments'],'2')
                    self.assertEqual(response.headers['X-Audio-Format'],suffix)
            self.assertEqual(provider.call_count,1)
            self.assertEqual(self.link.tts('parts')[0],409) # Old clients must not silently play just part one.
            self.assertEqual(self.link.tts('parts',segment=2,protocol=5)[0],400)
            self.link.cancel_turn()
            self.assertEqual(self.link.tts('parts',segment=1,protocol=5)[0],404)
            self.assertEqual(provider.call_count,1)
        self.assertTrue(all(not p.exists() for p in produced))

    def test_tts_noninitial_segment_cannot_trigger_synthesis(self):
        import tools.tts_tool as tts
        self.command('on')
        self.link.outbox.add(request_id='parts',session_id='s',turn_id='t',text='fixture')
        with mock.patch.object(tts,'text_to_speech_tool') as provider:
            self.assertEqual(self.link.tts('parts',segment=1,protocol=5)[0],409)
            for bad in (-1,True,16,'1'):
                self.assertEqual(self.link.tts('parts',segment=bad,protocol=5)[0],400)
            provider.assert_not_called()

    def test_cancel_between_reply_lookup_and_tts_admission_is_denied(self):
        import tools.tts_tool as tts
        self.command('on')
        self.link.outbox.add(request_id='race',session_id='s',turn_id='t',text='fixture')
        original=self.link.outbox.get
        def race(request):
            value=original(request)
            if value is not None: self.link.cancel_turn()
            return value
        with mock.patch.object(self.link.outbox,'get',side_effect=race), mock.patch.object(tts,'text_to_speech_tool') as provider:
            self.assertNotEqual(self.link.tts('race')[0],200)
            provider.assert_not_called()

    def test_tts_returns_matching_reply_once_and_removes_temporary_audio(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        _, accepted = self._call('/say', {'text': '질문'})
        self.manager.invoke_hook('post_llm_call', session_id='fixture-session', task_id='t', turn_id=1,
                                 user_message=self.voice('질문'), assistant_response='맞는 답변',
                                 conversation_history=[], model='m', platform='cli')
        self.assertTrue(self._await_reply(accepted['request_id']))

        import tools.tts_tool as tts_tool
        calls = []

        def fake_tts(text, output_path=None, **kwargs):
            calls.append((text, output_path))
            path = Path(output_path)
            path.write_bytes(b'fixture-mp3')
            return json.dumps({'success': True, 'file_path': str(path), 'file_paths': [str(path)]})

        url = self._url(f"/tts?request_id={accepted['request_id']}")
        request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + self.link._token})
        with mock.patch.object(tts_tool, 'text_to_speech_tool', fake_tts):
            with urllib.request.urlopen(request, timeout=5) as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(response.headers.get_content_type(), 'audio/mpeg')
                self.assertEqual(response.read(), b'fixture-mp3')
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][0], '맞는 답변')
            self.assertFalse(Path(calls[0][1]).exists(), 'temporary TTS output must be removed')
            request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + self.link._token})
            with urllib.request.urlopen(request, timeout=5) as response:
                self.assertEqual(response.read(), b'fixture-mp3')
            self.assertEqual(len(calls), 1, 'retries must reuse cached audio instead of charging twice')

    def test_only_loopback_is_used(self):
        self.command('on')
        ALLOWED_PORTS.add(self.link._port)
        self.assertEqual(self.link._server.server_address[0], '127.0.0.1')
        self._call('/say', {'text': 'hello'})
        self.assertTrue(all(address[0] == '127.0.0.1' for address in CONNECTS if isinstance(address, tuple)))


if __name__ == '__main__':
    unittest.main(verbosity=2)
