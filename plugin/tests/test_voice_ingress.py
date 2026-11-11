"""Real ephemeral HTTP in a clean exec child; fixed core middleware, no provider."""
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
sys.path.insert(0, str(P))


def child():
    from voice_ingress import Ingress, Credential
    from voice_worker import bootstrap, Worker
    home = Path(os.environ['HOME'])
    bound = []
    def audit(event, args):
        if event == 'socket.bind':
            assert args[1] == ('127.0.0.1', 0)
        if event == 'socket.connect':
            assert bound and args[1] == ('127.0.0.1', bound[0])
        if event in {'socket.getaddrinfo','subprocess.Popen','os.system'}:
            raise PermissionError('offline fixture')
        if event == 'open' and isinstance(args[0], (str,bytes,os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = (isinstance(args[1],str) and any(c in args[1] for c in 'wax+')) or (isinstance(args[2],int) and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside fixture')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live home')
            if path.is_relative_to(CORE) and (path.name == '.env' or path.name.startswith('auth.json')):
                raise PermissionError('credential')
    sys.addaudithook(audit)
    scope = bootstrap(home,mode='resume' if '--resume' in sys.argv else 'init')
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    manager = get_plugin_manager()
    ctx = PluginContext(PluginManifest(name='voice-ingress-fixture',path=str(P)),manager)
    calls = []
    http_codes,disconnected = [],[]
    entered, release = threading.Event(), threading.Event()
    barrier_expired = threading.Event()
    def model(**kw):
        from copy import deepcopy
        from openai.types.chat import ChatCompletion
        calls.append(deepcopy(kw['request']))
        if kw['request']['messages'][-1]['content'] == 'hold':
            entered.set()
            if not release.wait(15): barrier_expired.set()
        return ChatCompletion.model_validate({'id':'fixture','object':'chat.completion','created':0,'model':'fixture','choices':[{'index':0,'message':{'role':'assistant','content':'synthetic reply'},'finish_reason':'stop'}]})
    ctx.register_middleware('llm_execution',model)
    worker = Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
    token = 'a'*43
    creds = (Credential(token,'voice-worker','alpha'),Credential('b'*43,'voice-worker','beta'))
    prefix = '/v1/profiles/voice-worker/lanes/alpha'
    def body(i='1', text='synthetic original', predecessor=None):
        return {'request_id':i*32,'text':text,'predecessor':predecessor}
    async def request(method='POST', path=None, data=None, auth=token, raw=None, headers=None, drop=False):
        reader, writer = await asyncio.open_connection('127.0.0.1',bound[0],family=socket.AF_INET)
        payload = json.dumps(data if data is not None else body()).encode() if raw is None else raw
        if method == 'GET': payload=b''
        hs={'Host':f'127.0.0.1:{bound[0]}','Authorization':'Bearer '+auth,'Content-Type':'application/json','Content-Length':str(len(payload))}
        if headers: hs.update(headers)
        wire=f'{method} {path or prefix+"/requests"} HTTP/1.1\r\n'+''.join(f'{k}: {v}\r\n' for k,v in hs.items())+'\r\n'
        writer.write(wire.encode()+payload); await writer.drain()
        if drop:
            disconnected.append(1); writer.transport.abort(); return
        response=await asyncio.wait_for(reader.read(),5)
        writer.close(); await writer.wait_closed()
        head, payload=response.split(b'\r\n\r\n',1)
        code=int(head.split()[1]); http_codes.append(code)
        return code, json.loads(payload)
    async def run():
        service=Ingress(worker,lambda:creds,auto_dispatch=False)
        starts=await asyncio.gather(service.start(),service.start(),return_exceptions=True)
        assert sum(isinstance(x,RuntimeError) for x in starts)==1
        assert sum(x is None for x in starts)==1
        bound.append(service.port)
        assert service.port != 5001
        if '--resume' in sys.argv:
            code,ack=await request(); assert code==202
            _,status=await request('GET',prefix+'/receipts/'+ack['receipt'])
            assert status['status']=='engine_turn_verified'
            assert await worker.start('alpha') is None and calls==[]
            code,_=await request(data=body(i='f')); assert code==409
            await service.shutdown()
            return
        # Unauthorized and malformed requests cannot reach storage/engine.
        for kwargs in [dict(auth='x'*43),dict(raw=b'\xff'),dict(raw=b'{'),dict(raw=b'{}'),dict(raw=b'x'*65537),dict(headers={'Content-Type':'text/plain'}),dict(method='PUT'),dict(path='/approve'),dict(data=dict(body(),approval=True)),dict(data=body(i='/')),dict(raw=b'{"request_id":"a","request_id":"b"}'),dict(headers={'Transfer-Encoding':'chunked'}),dict(headers={'Content-Length':'-1'}),dict(headers={'Content-Length':'01'}),dict(headers={'Expect':'100-continue'}),dict(headers={'Origin':'http://untrusted.invalid'}),dict(headers={'Host':'localhost'}),dict(headers={'authorization':'Bearer '+token}),dict(path=prefix+'/requests?x=1'),dict(data=body(text='x'*16385)),dict(data=body(text='\ud800'))]:
            code,_=await request(**kwargs); assert code>=400
        code,_=await request(path='/v1/profiles/other/lanes/alpha/requests'); assert code==403
        code,_=await request(path='/v1/profiles/voice-worker/lanes/beta/requests'); assert code==403
        with worker.store.open() as box:
            assert box._db.execute('SELECT COUNT(*) FROM requests').fetchone()==(0,)
        assert calls==[]
        code,accepted=await request(); assert code==202
        receipt=accepted['receipt']; assert accepted=={'accepted':True,'receipt':receipt}
        with worker.store.open() as box:
            assert box.get(receipt).original_text=='synthetic original'
            assert box.get(receipt).state=='received'
        assert await request()==(202,accepted)
        assert await asyncio.gather(*(request() for _ in range(8)))==[(202,accepted)]*8
        code,_=await request(data=body(text='changed')); assert code==409
        code,_=await request(data=body(i='2')); assert code==409
        code,state=await request('GET',prefix+'/receipts/'+receipt)
        assert code==200 and state['status']=='received' and state['delivery']=='untracked'
        code,_=await request('GET',prefix+'/receipts/'+receipt,auth='b'*43); assert code==403
        assert (await worker.start('alpha')).engine_turn_verified
        assert len(calls)==1
        code,state=await request('GET',prefix+'/receipts/'+receipt)
        assert state['status']=='engine_turn_verified'
        assert await request()==(202,accepted) and len(calls)==1
        code,_=await request(data=body(i='2')); assert code==409
        # Lost ACK followed by same immutable request returns the original receipt.
        follow=body(i='2',text='followup',predecessor=receipt)
        await request(data=follow,drop=True)
        await asyncio.sleep(.1)
        with worker.store.open() as box:
            committed=box._db.execute('SELECT request_id FROM requests WHERE original_text=?',('followup',)).fetchall()
            assert len(committed)==1  # First connection COMMIT, before retry.
        code,second=await request(data=follow); assert code==202 and second['receipt']==committed[0][0]
        assert await request(data=follow)==(202,second)
        code,_=await request(data=body(i='2',text='followup',predecessor=None)); assert code==409
        assert (await worker.start('alpha')).engine_turn_verified
        assert len(calls)==2
        assert any(m.get('content')=='synthetic original' for m in calls[-1]['messages'])
        # Unknown blocks this lane; another authenticated lane can still progress.
        code,_=await request(data=body(i='3',text='stale',predecessor=receipt)); assert code==409
        code,third=await request(data=body(i='3',text='hold',predecessor=second['receipt'])); assert code==202
        task=worker.start('alpha')
        for _ in range(5000):
            if entered.is_set(): break
            await asyncio.sleep(.01)
        assert entered.is_set()
        _,state=await request('GET',prefix+'/receipts/'+third['receipt']); assert state['status']=='inflight'
        task.cancel(); release.set()
        try: await task
        except asyncio.CancelledError: pass
        _,state=await request('GET',prefix+'/receipts/'+third['receipt']); assert state['status']=='unknown'
        count=len(calls)
        assert (await request(data=body(i='3',text='hold',predecessor=second['receipt'])))[0]==202
        code,_=await request(data=body(i='4',predecessor=second['receipt'])); assert code==409
        assert await worker.start('alpha') is None and len(calls)==count
        beta='/v1/profiles/voice-worker/lanes/beta'
        code,_=await request(path=beta+'/requests',auth='b'*43,data=body(i='5',predecessor=receipt)); assert code==409
        # SQLite abort fixture: failed persistence cannot produce an ACK or turn.
        with worker.store.open() as box:
            box._db.execute("CREATE TRIGGER fixture_fail BEFORE INSERT ON requests BEGIN SELECT RAISE(ABORT,'fixture'); END")
        before=len(calls)
        code,error=await request(path=beta+'/requests',auth='b'*43,data=body(i='5',text='beta only'))
        assert code==503 and error=={'error':'request_refused'} and len(calls)==before
        with worker.store.open() as box:
            assert box._db.execute("SELECT COUNT(*) FROM requests WHERE lane='beta'").fetchone()==(0,)
            box._db.execute('DROP TRIGGER fixture_fail')
        code,b=await request(path=beta+'/requests',auth='b'*43,data=body(i='5',text='beta only')); assert code==202
        code,_=await request('GET',beta+'/receipts/'+receipt,auth='b'*43); assert code==404
        assert (await worker.start('beta')).engine_turn_verified
        assert not any(m.get('content')=='synthetic original' for m in calls[-1]['messages'])
        await service.shutdown()
        assert worker.cleanup_state=='closed'
        assert all(not h.active for h in worker.registrations)
        assert not any(t.name.startswith('voice-turn') for t in threading.enumerate())
        try: await asyncio.open_connection('127.0.0.1',bound[0],family=socket.AF_INET)
        except OSError: pass
        else: raise AssertionError('listener survived shutdown')
        # New supported worker/ingress lifecycle, same durable profile, AUTO mode.
        automatic=Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
        auto=Ingress(automatic,lambda:creds,auto_dispatch=True)
        await auto.start(); bound[0]=auto.port
        before=len(calls)
        auto_body=body(i='6',text='automatic lost ack',predecessor=b['receipt'])
        await request(path=beta+'/requests',auth='b'*43,data=auto_body,drop=True)
        for _ in range(5000):
            with automatic.store.open() as box:
                rows=box._db.execute('SELECT request_id,state FROM requests WHERE original_text=?',('automatic lost ack',)).fetchall()
            if rows and rows[0][1]=='completed': break
            await asyncio.sleep(.01)
        assert rows and rows[0][1]=='completed' and len(calls)==before+1
        code,auto_ack=await request(path=beta+'/requests',auth='b'*43,data=auto_body)
        assert code==202 and auto_ack['receipt']==rows[0][0]
        await asyncio.sleep(.05); assert len(calls)==before+1
        # Conflicting concurrent new requests: only one immutable admission wins.
        entered.clear(); release.clear()
        hold=body(i='7',text='hold',predecessor=auto_ack['receipt'])
        responses=await asyncio.gather(request(path=beta+'/requests',auth='b'*43,data=hold),request(path=beta+'/requests',auth='b'*43,data=body(i='8',text='hold',predecessor=auto_ack['receipt'])))
        assert sorted(r[0] for r in responses)==[202,409]
        for _ in range(5000):
            if entered.is_set(): break
            await asyncio.sleep(.01)
        assert entered.is_set()
        stopping=asyncio.create_task(auto.shutdown())
        await asyncio.sleep(.05)
        assert not stopping.done() and all(h.active for h in automatic.registrations)
        try: await asyncio.open_connection('127.0.0.1',bound[0],family=socket.AF_INET)
        except OSError: pass
        else: raise AssertionError('listener not closed before worker join')
        from voice_process import recover_profile
        from voice_inbox import Busy
        try: recover_profile(home)
        except Busy: pass
        else: raise AssertionError('process lease lost before OS exit')
        stopping.cancel(); await asyncio.sleep(.05)
        assert not stopping.done() and all(h.active for h in automatic.registrations)
        release.set()
        try: await stopping
        except asyncio.CancelledError: pass
        else: raise AssertionError('shutdown cancellation not preserved')
        assert automatic.cleanup_state=='closed'
        assert not any(t.name.startswith('voice-turn') for t in threading.enumerate())
        # HTTP admission must fail if a mandatory hook is disposed.
        guarded=Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
        guard_server=Ingress(guarded,lambda:creds)
        await guard_server.start(); bound[0]=guard_server.port
        guarded.registrations[0].dispose()
        with guarded.store.open() as box: count_before=box._db.execute('SELECT COUNT(*) FROM requests').fetchone()[0]
        assert (await request(data=body(i='9')))[0]==503
        with guarded.store.open() as box: assert box._db.execute('SELECT COUNT(*) FROM requests').fetchone()[0]==count_before
        await guard_server.shutdown()
        draining=Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
        slow=Ingress(draining,lambda:creds)
        await slow.start(); bound[0]=slow.port
        reader,writer=await asyncio.open_connection('127.0.0.1',bound[0],family=socket.AF_INET)
        writer.write(b'POST /partial HTTP/1.1\r\n'); await writer.drain()
        await asyncio.sleep(.02)
        stopping=asyncio.create_task(slow.shutdown()); await asyncio.sleep(.05)
        assert not stopping.done() and all(h.active for h in draining.registrations)
        raw=await asyncio.wait_for(reader.read(),5)
        assert raw.startswith(b'HTTP/1.1 408 ')
        writer.close(); await writer.wait_closed(); await stopping
        assert draining.cleanup_state=='closed'
        for cancel_start in (False,True):
            racing=Worker(scope,ctx,model_options={'model':'fixture','provider':'openai','api_key':'offline-placeholder','base_url':'https://offline.invalid/v1','max_iterations':3})
            lifecycle=Ingress(racing,lambda:creds)
            starting=asyncio.create_task(lifecycle.start())
            await asyncio.sleep(0)
            if cancel_start:
                starting.cancel()
                try: await starting
                except asyncio.CancelledError: pass
            else:
                closing=asyncio.create_task(lifecycle.shutdown())
                results=await asyncio.gather(starting,closing,return_exceptions=True)
                assert results[1] is None
            assert racing.cleanup_state=='closed'
            assert lifecycle._server is None or not lifecycle._server.sockets
    asyncio.run(run())
    assert not barrier_expired.is_set()
    manager.unload()
    from collections import Counter
    return {'mode':'resume' if '--resume' in sys.argv else 'init',
            'core_calls':len(calls),'http_status_counts':dict(Counter(http_codes)),
            'dropped_connections':len(disconnected)}


class Tests(unittest.TestCase):
    def test_shutdown_retries_explicit_cleanup_failure(self):
        from types import SimpleNamespace
        from voice_ingress import Ingress, Credential
        class FixtureWorker:
            attempts=0
            def check_admission(self): return SimpleNamespace(profile_id='voice-worker')
            async def shutdown(self,**kw):
                self.attempts+=1
                if self.attempts==1: raise RuntimeError('fixture cleanup failure')
        async def run():
            worker=FixtureWorker()
            service=Ingress(worker,lambda:(Credential('a'*43,'voice-worker','lane'),))
            with self.assertRaises(RuntimeError): await service.shutdown()
            await service.shutdown()
            self.assertEqual(worker.attempts,2)
        asyncio.run(run())

    def test_atomic_retention_and_bounded_history(self):
        from voice_ingress import ReceiptInbox, Refusal
        with tempfile.TemporaryDirectory(prefix='voice-receipts-',dir=SCRATCH) as root:
            home=Path(root)
            kwargs=dict(profile_dir=home,profile_id='voice-worker',db_path=home/'voice.sqlite',max_requests=2,max_history_turns=1)
            with ReceiptInbox(**kwargs) as box:
                first,new=box.admit('a'*32,'one','alpha',None); self.assertTrue(new)
                owner=box.claim('alpha'); handoff=box.prepare_history(owner)
                self.assertTrue(box.bind_start(owner,'sid','turn'))
                self.assertTrue(box.complete_history(owner,'sid','turn',handoff,[{'role':'user','content':'one'},{'role':'assistant','content':'reply'}]))
                with self.assertRaises(Refusal) as ex: box.admit('b'*32,'two','alpha',first)
                self.assertEqual(ex.exception.code,409)
                second,_=box.admit('b'*32,'two','beta',None)
                with self.assertRaises(Refusal) as ex: box.admit('c'*32,'three','gamma',None)
                self.assertEqual(ex.exception.code,429)  # Completed still consumes capacity.
                self.assertEqual(box.admit('a'*32,'one','alpha',None),(first,False))
            with ReceiptInbox(**kwargs) as box:
                self.assertEqual(box.admit('a'*32,'one','alpha',None),(first,False))
                self.assertEqual(box.scoped_status(first,'alpha')['status'],'engine_turn_verified')
                with self.assertRaises(Refusal): box.scoped_status(first,'beta')
                for args in [('wrong','text','delta',None),('c'*32,'text','/bad',None),('c'*32,'text','delta','bad')]:
                    with self.assertRaises(Refusal) as ex: box.admit(*args)
                    self.assertEqual(ex.exception.code,400)

    def test_real_http_core_child(self):
        from voice_worker import clean_environment
        with tempfile.TemporaryDirectory(prefix='voice-ingress-',dir=SCRATCH) as root:
            home=Path(root)
            proc=subprocess.run([str(CORE/'venv/bin/python'),'-B',str(Path(__file__).resolve()),'--child'],cwd=home,env=clean_environment(home,CORE),capture_output=True,timeout=120)
            report=json.loads((home/'result.json').read_text()) if (home/'result.json').exists() else {}
            self.assertEqual(proc.returncode,0,(report,proc.stdout,proc.stderr))
            self.assertEqual(proc.stdout,b''); self.assertEqual(proc.stderr,b'')
            self.assertTrue(report.get('ok'))
            print('FIXTURE',json.dumps(report,sort_keys=True))
            (home/'result.json').unlink()  # Test artifact is not a profile artifact.
            proc=subprocess.run([str(CORE/'venv/bin/python'),'-B',str(Path(__file__).resolve()),'--child','--resume'],cwd=home,env=clean_environment(home,CORE),capture_output=True,timeout=90)
            report=json.loads((home/'result.json').read_text()) if (home/'result.json').exists() else {}
            self.assertEqual(proc.returncode,0,(report,proc.stdout,proc.stderr))
            self.assertTrue(report.get('ok'))
            print('FIXTURE',json.dumps(report,sort_keys=True))
            self.assertEqual(proc.stdout,b''); self.assertEqual(proc.stderr,b'')
            for path in home.rglob('*'):
                if path.is_file() and path.name not in {'voice.sqlite','voice.sqlite-journal','voice.sqlite-wal','voice.sqlite-shm'}:
                    content=path.read_bytes()
                    self.assertNotIn(b'synthetic original',content)
                    self.assertNotIn(b'automatic lost ack',content)
                    self.assertNotIn(b'a'*43,content)


if __name__=='__main__':
    if '--child' in sys.argv:
        try: report={'ok':True,**child()}; code=0
        except BaseException as exc:
            import traceback
            report={'ok':False,'type':type(exc).__name__,'frames':[(Path(f.filename).name,f.lineno,f.name) for f in traceback.extract_tb(exc.__traceback__)]}; code=1
        (Path(os.environ['HOME'])/'result.json').write_text(json.dumps(report))
        raise SystemExit(code)
    unittest.main()
