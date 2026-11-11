"""Source-only delivery protocol: no providers, audio or live phone."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(P))


def ack(view, event='read_reported'):
    return {**{k: view[k] for k in ('delivery_id', 'attempt_id', 'response_hash', 'version')}, 'event': event}


class Tests(unittest.TestCase):
    def test_bound_monotonic_receipts_and_canonical_projection(self):
        from voice_delivery import DeliveryInbox
        from voice_ingress import Refusal
        with tempfile.TemporaryDirectory(dir=SCRATCH) as root:
            kwargs = dict(profile_dir=Path(root), profile_id='fixture', db_path=Path(root)/'voice.sqlite', max_requests=2)
            with DeliveryInbox(**kwargs) as box:
                rid, _ = box.admit('a'*32, 'private request', 'alpha', None)
                with self.assertRaises(Refusal): box.read_response(rid, 'alpha')
                owner = box.claim('alpha'); handoff = box.prepare_history(owner)
                box.bind_start(owner, 'sid', 'turn')
                with self.assertRaises(Refusal): box.read_response(rid, 'alpha')
                messages = [{'role':'user','content':'private request'}, {'role':'assistant','content':None,'tool_calls':[{'id':'call','type':'function','function':{'name':'hidden','arguments':'{}'}}]}, {'role':'tool','tool_call_id':'call','content':'private tool result'}, {'role':'assistant','content':'public final 한글','reasoning':'private reasoning'}]
                box.complete_history(owner, 'sid', 'turn', handoff, messages)
                ready = box.read_response(rid, 'alpha')
                self.assertEqual(ready['delivery'], 'response_ready')
                self.assertEqual(ready['response'], {'content_type':'text/plain; charset=utf-8', 'text':'public final 한글'})
                self.assertEqual(ready['response_hash'], hashlib.sha256('public final 한글'.encode()).hexdigest())
                self.assertEqual(ready['audio'], {'state':'not_integrated','ready':False,'downloaded':False,'acoustic_verified':False})
                self.assertFalse(ready['auto_play'])
                self.assertNotIn('private', json.dumps(ready))
                self.assertEqual(box.read_response(rid, 'alpha'), ready)
                with self.assertRaises(Refusal): box.ack_response(rid, 'alpha', ack(ready, 'played_reported'))
                for key, value in [('version', True), ('version',2), ('attempt_id','0'*32), ('response_hash','0'*64), ('delivery_id','0'*64), ('event','audio_downloaded')]:
                    bad=ack(ready); bad[key]=value
                    with self.assertRaises(Refusal): box.ack_response(rid, 'alpha', bad)
                with self.assertRaises(Refusal): box.ack_response(rid, 'beta', ack(ready))
                read = box.ack_response(rid, 'alpha', ack(ready))
                self.assertEqual(read['delivery'], 'read_reported')
                self.assertEqual(box.ack_response(rid, 'alpha', ack(ready)), read)
                played=box.ack_response(rid, 'alpha', ack(ready, 'played_reported'))
                self.assertEqual(played['delivery'], 'played_reported')
                self.assertFalse(played['audio']['acoustic_verified'])
                self.assertEqual(box.ack_response(rid, 'alpha', ack(ready)), played)
                second,_=box.admit('b'*32, 'pending', 'alpha', rid)
                with self.assertRaises(Refusal): box.ack_response(second, 'alpha', ack(ready))
                owner2=box.claim('alpha'); box.mark_unknown(owner2)
                with self.assertRaises(Refusal): box.read_response(second, 'alpha')
                with self.assertRaises(Refusal): box.ack_response(second, 'alpha', ack(ready))
                self.assertEqual(box.get(second).state, 'unknown_outcome')
                self.assertIsNone(box.claim('alpha'))
                self.assertEqual(box._db.execute('SELECT COUNT(*) FROM voice_delivery').fetchone(), (1,))
                for _ in range(100): box.ack_response(rid, 'alpha', ack(ready))
                self.assertEqual(box._db.execute('SELECT COUNT(*) FROM voice_delivery').fetchone(), (1,))
            with DeliveryInbox(**kwargs) as box:
                self.assertEqual(box.read_response(rid, 'alpha'), played)
                self.assertEqual(box.ack_response(rid, 'alpha', ack(ready, 'played_reported')), played)
                self.assertEqual(box.get(rid).state, 'completed')

    def test_payload_limit_and_cross_request_replay(self):
        from voice_delivery import DeliveryInbox, MAX_RESPONSE_BYTES
        from voice_ingress import Refusal
        with tempfile.TemporaryDirectory(dir=SCRATCH) as root:
            with DeliveryInbox(profile_dir=Path(root),profile_id='fixture',db_path=Path(root)/'voice.sqlite') as box:
                views=[]
                for i,text in enumerate(['same','same','x'*(MAX_RESPONSE_BYTES+1)]):
                    lane='lane'+str(i); rid,_=box.admit(str(i)*32,'q',lane,None)
                    o=box.claim(lane); h=box.prepare_history(o); box.bind_start(o,'sid'+str(i),'turn'+str(i))
                    box.complete_history(o,'sid'+str(i),'turn'+str(i),h,[{'role':'user','content':'q'},{'role':'assistant','content':text}])
                    if i==2:
                        with self.assertRaises(Refusal) as e: box.read_response(rid,lane)
                        self.assertEqual(e.exception.code,409)
                    else: views.append((rid,lane,box.read_response(rid,lane)))
                self.assertNotEqual(views[0][2]['delivery_id'],views[1][2]['delivery_id'])
                with self.assertRaises(Refusal): box.ack_response(views[1][0],views[1][1],ack(views[0][2]))
                with self.assertRaises(Refusal): box.read_response('v1.'+'f'*32+'.'+'0'*32,'lane0')
                self.assertEqual(box._db.execute('SELECT COUNT(*) FROM voice_delivery').fetchone(),(2,))

    def test_real_http_core_and_new_process_resume(self):
        from voice_worker import clean_environment
        with tempfile.TemporaryDirectory(prefix='voice-delivery-',dir=SCRATCH) as root:
            home=Path(root)
            for mode in ('init','resume'):
                cmd=[str(CORE/'venv/bin/python'),'-B',str(Path(__file__).resolve()),'--child',mode]
                proc=subprocess.run(cmd,cwd=home,env=clean_environment(home,CORE),capture_output=True,timeout=120)
                report=json.loads((home/'result.json').read_text()) if (home/'result.json').exists() else {}
                self.assertEqual(proc.returncode,0,(report,proc.stdout,proc.stderr))
                self.assertEqual(proc.stdout,b''); self.assertEqual(proc.stderr,b'')
                self.assertTrue(report['ok'])
                print('DELIVERY_FIXTURE',json.dumps(report,sort_keys=True))
                (home/'result.json').unlink()
            for path in home.rglob('*'):
                if path.is_file() and not path.name.startswith('voice.sqlite'):
                    for private in (b'a'*43,b'delivery final',b'delivery request'):
                        self.assertNotIn(private,path.read_bytes())


def child():
    from voice_worker import bootstrap, Worker
    from voice_ingress import Ingress, Credential
    home=Path(os.environ['HOME']); bound=[]
    def audit(event,args):
        if event=='socket.bind': assert args[1]==('127.0.0.1',0)
        if event=='socket.connect': assert bound and args[1]==('127.0.0.1',bound[0])
        if event in {'socket.getaddrinfo','subprocess.Popen','os.system'}: raise PermissionError('offline fixture')
        if event=='open' and isinstance(args[0],(str,bytes,os.PathLike)):
            path=Path(os.fsdecode(args[0])).resolve()
            writing=(isinstance(args[1],str) and any(c in args[1] for c in 'wax+')) or (isinstance(args[2],int) and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT))
            if writing and not path.is_relative_to(home) and str(path)!='/dev/null': raise PermissionError('outside fixture')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home): raise PermissionError('live home')
            if path.is_relative_to(CORE) and (path.name=='.env' or path.name.startswith('auth.json')): raise PermissionError('credential')
    sys.addaudithook(audit)
    mode=sys.argv[-1]; scope=bootstrap(home,mode=mode)
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    manager=get_plugin_manager(); ctx=PluginContext(PluginManifest(name='voice-delivery-fixture',path=str(P)),manager)
    calls=[]
    def model(**kw):
        from openai.types.chat import ChatCompletion
        calls.append(1)
        return ChatCompletion.model_validate({'id':'fixture','object':'chat.completion','created':0,'model':'fixture','choices':[{'index':0,'message':{'role':'assistant','content':'delivery final'},'finish_reason':'stop'}]})
    ctx.register_middleware('llm_execution',model)
    worker=Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
    token='a'*43; creds=(Credential(token,'voice-worker','alpha'),Credential('b'*43,'voice-worker','beta'))
    prefix='/v1/profiles/voice-worker/lanes/alpha'; codes=[]
    async def request(method,path,data=None,auth=token,drop=False):
        reader,writer=await asyncio.open_connection('127.0.0.1',bound[0],family=socket.AF_INET)
        payload=json.dumps(data).encode() if data is not None else b''
        wire=f'{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{bound[0]}\r\nAuthorization: Bearer {auth}\r\nContent-Type: application/json\r\nContent-Length: {len(payload)}\r\n\r\n'.encode()+payload
        writer.write(wire); await writer.drain()
        if drop: writer.transport.abort(); return
        raw=await asyncio.wait_for(reader.read(),5); writer.close(); await writer.wait_closed()
        header,body=raw.split(b'\r\n\r\n',1); code=int(header.split()[1]); codes.append(code)
        assert b'Cache-Control: no-store' in header
        assert token.encode() not in raw
        return code,json.loads(body)
    async def run():
        service=Ingress(worker,lambda:creds,delivery=True,auto_dispatch=True)
        await service.start(); bound.append(service.port); assert service.port!=5001
        body={'request_id':'1'*32,'text':'delivery request','predecessor':None}
        code,accepted=await request('POST',prefix+'/requests',body); assert code==202
        rid=accepted['receipt']; path=prefix+'/receipts/'+rid
        if mode=='init':
            for _ in range(5000):
                code,ready=await request('GET',path+'/response')
                if code==200: break
                assert code==409
                await asyncio.sleep(.01)
            assert code==200
        code,ready=await request('GET',path+'/response'); assert code==200
        assert ready['response']['text']=='delivery final'
        if mode=='init':
            assert ready['delivery']=='response_ready'
            # Authenticate and scope before any DB open, including delivery operations.
            import voice_ingress
            original=voice_ingress._open
            def forbidden(*a,**kw): raise AssertionError('unauthorized DB open')
            voice_ingress._open=forbidden
            try:
                assert (await request('GET',path+'/response',auth='x'*43))[0]==401
                assert (await request('POST',path+'/ack',ack(ready),auth='b'*43))[0]==403
            finally: voice_ingress._open=original
            unknown=prefix+'/receipts/v1.'+'f'*32+'.'+'0'*32
            assert (await request('GET',unknown+'/response'))[0]==404
            assert (await request('POST',unknown+'/ack',ack(ready)))[0]==404
            assert (await request('POST',path+'/ack',ack(ready,'played_reported')))[0]==409
            bad=ack(ready); bad['response_hash']='0'*64
            assert (await request('POST',path+'/ack',bad))[0]==409
            # Disconnect before ACK response. Readback proves commit before retry.
            await request('POST',path+'/ack',ack(ready),drop=True)
            for _ in range(100):
                code,current=await request('GET',path+'/response')
                if current['delivery']=='read_reported': break
                await asyncio.sleep(.01)
            assert current['delivery']=='read_reported'
            code,retried=await request('POST',path+'/ack',ack(ready)); assert code==200 and retried==current
            played_ack=ack(ready,'played_reported')
            code,played=await request('POST',path+'/ack',played_ack); assert code==200 and played['delivery']=='played_reported'
            assert (await request('POST',path+'/ack',played_ack))==(200,played)
        else:
            assert ready['delivery']=='played_reported'
            assert (await request('POST',path+'/ack',ack(ready,'played_reported')))==(200,ready)
        status=(await request('GET',path))[1]
        assert status['delivery']=='played_reported' and status['status']=='engine_turn_verified'
        assert 'response' not in status
        assert (await request('POST',prefix+'/requests',body))==(202,accepted)
        assert await worker.start('alpha') is None
        assert len(calls)==(1 if mode=='init' else 0)
        assert not ready['audio']['ready'] and not ready['audio']['acoustic_verified'] and not ready['auto_play']
        await service.shutdown(); manager.unload()
    asyncio.run(run())
    from collections import Counter
    return {'mode':mode,'core_calls':len(calls),'http_status_counts':dict(Counter(codes)),'acoustic_verified':False}


if __name__=='__main__':
    if '--child' in sys.argv:
        try: report={'ok':True,**child()}; code=0
        except BaseException as exc:
            import traceback
            report={'ok':False,'type':type(exc).__name__,'frames':[(Path(f.filename).name,f.lineno,f.name) for f in traceback.extract_tb(exc.__traceback__)]}; code=1
        (Path(os.environ['HOME'])/'result.json').write_text(json.dumps(report))
        raise SystemExit(code)
    unittest.main()
