"""Bounded actual-worker tests, separate private clean exec children."""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = CORE.parent/'cache/scratch'
sys.path.insert(0,str(P))

class Contract(unittest.TestCase):
    def test_explicit_mode_api(self):
        import inspect
        from voice_worker import bootstrap
        self.assertIn('task_state_url',inspect.signature(bootstrap).parameters)

def child(case):
    from voice_worker import bootstrap, Worker
    from voice_task_worker import GOAL, SCREEN, FOLLOWUP, PREFIX, TOOLS, TOOLSETS
    from http.server import HTTPServer, BaseHTTPRequestHandler
    home=Path(os.environ['HOME'])
    requests=[]; summaries=[]; errors=[]
    entered=threading.Event(); release=threading.Event(); stopped=threading.Event()
    worker=None
    def audit(event,args):
        if event in ('subprocess.Popen','os.system'): raise PermissionError('offline')
        if event=='socket.getaddrinfo' and args[0] not in ('127.0.0.1',b'127.0.0.1'): raise PermissionError('DNS')
        if event in ('socket.connect','socket.bind') and args[1][0]!='127.0.0.1': raise PermissionError('remote')
        if event=='open' and isinstance(args[0],(str,bytes,os.PathLike)):
            path=Path(os.fsdecode(args[0])).resolve()
            write=(isinstance(args[1],str) and any(c in args[1] for c in 'wax+')) or (isinstance(args[2],int) and args[2]&(os.O_WRONLY|os.O_RDWR|os.O_CREAT))
            if write and not path.is_relative_to(home) and str(path)!='/dev/null': raise PermissionError('outside write')
            if path.name in ('.env','auth.json','auth.json.lock'): raise PermissionError('credential')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home): raise PermissionError('live profile')
    sys.addaudithook(audit)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            try:
                request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                summary=len(request['messages'])==1
                if summary:
                    summaries.append(request)
                    worker.task_adapter.events.append(dict(event='stock_summary'))
                    msg=dict(role='assistant',content='Synthetic context condensed; consult verified task state.')
                    finish='stop';usage=100
                else:
                    requests.append(request);n=len(requests)
                    if (n==1 and case=='cancel') or (n==2 and case=='cancel_after_open'):
                        entered.set()
                        if not release.wait(15): raise RuntimeError('barrier timeout')
                    if n==2 and case=='wrong_owner':
                        worker.task_adapter.state.owner['attempt_id']='foreign'
                    if n==2 and case=='unavailable':
                        worker.task_adapter.state.close()
                    if n==2 and case=='missing_hook':
                        worker.task_adapter.handles[0].dispose()
                    tool='voice_task_open' if n==1 or (n==4 and case=='followup_replay') else ('voice_task_read' if n==2 else None)
                    msg=dict(role='assistant',content=SCREEN)
                    finish='stop';usage=100
                    if tool:
                        msg=dict(role='assistant',content=None,tool_calls=[dict(id='call-'+str(n),type='function',function=dict(name=tool,arguments='{}'))])
                        finish='tool_calls';usage=60000 if n==1 else 100
                payload=dict(id='fixture',object='chat.completion',created=0,model='fixture',choices=[dict(index=0,message=msg,finish_reason=finish)],usage=dict(prompt_tokens=usage,completion_tokens=30,total_tokens=usage+30))
                if request.get('stream'):
                    delta={k:v for k,v in msg.items() if v is not None}
                    if 'tool_calls' in delta: delta['tool_calls']=[dict(index=0,**delta['tool_calls'][0])]
                    chunk=dict(id='fixture',object='chat.completion.chunk',created=0,model='fixture',choices=[dict(index=0,delta=delta,finish_reason=None)])
                    end=dict(id='fixture',object='chat.completion.chunk',created=0,model='fixture',choices=[dict(index=0,delta={},finish_reason=finish)],usage=payload['usage'])
                    body=('data: '+json.dumps(chunk)+'\n\ndata: '+json.dumps(end)+'\n\ndata: [DONE]\n\n').encode();typ='text/event-stream'
                else: body=json.dumps(payload).encode();typ='application/json'
                self.send_response(200);self.send_header('Content-Type',typ);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            except BaseException as exc:
                errors.append(graph(exc));raise
    server=HTTPServer(('127.0.0.1',0),Handler)
    url='http://127.0.0.1:%d/v1'%server.server_address[1]
    scope=bootstrap(home,task_state_url=url)
    server_thread=threading.Thread(target=server.serve_forever,name='owned-loopback')
    server_thread.start()
    import hermes_cli.main
    from hermes_cli.plugins import PluginContext,PluginManifest,get_plugin_manager
    from voice_process import validate_profile,ProcessError
    manager=get_plugin_manager()
    ctx=PluginContext(PluginManifest(name='task-worker-offline',path=str(P)),manager)
    worker=Worker(scope,ctx,model_options=dict(model='fixture',provider='openai',api_key='offline-placeholder',base_url=url,api_mode='chat_completions',max_iterations=4),enabled_toolsets=TOOLSETS,allowed_tools=TOOLS)
    adapter=worker.task_adapter
    if case in ('missing_injection','missing_final'):
        def remove_state(request,**kw):
            if adapter.state is not None and adapter.state.snapshot()['revision'] >= (4 if case=='missing_final' else 2):
                request=dict(request,messages=[m for m in request['messages'] if not (isinstance(m.get('content'),str) and m['content'].startswith(PREFIX))])
                return {'request':request}
            return None
        ctx.register_middleware('llm_request',remove_state)
    def ack():
        try:
            while not stopped.wait(.005):
                notice=adapter.notice
                if notice:
                    if case=='missing_ack': return
                    receipt=dict(identity=notice['identity'],event_id=notice['event_id'],content_digest=notice['content_digest'],outcome='played',synthetic=True)
                    assert adapter.acknowledge(receipt)
                    return
        except BaseException as exc: errors.append(graph(exc))
    ack_thread=threading.Thread(target=ack,name='owned-synthetic-ack');ack_thread.start()
    results=[]
    async def run():
        try:
            worker.receive('request',GOAL,'lane')
            task=worker.start('lane')
            if case in ('cancel','cancel_after_open'):
                for _ in range(8000):
                    if entered.is_set(): break
                    await asyncio.sleep(.01)
                assert entered.is_set()
                task.cancel()
                for _ in range(200):
                    if adapter.steps.cancelled: break
                    await asyncio.sleep(.01)
                assert adapter.steps.cancelled and not task.done()
                assert not adapter.state.closed
                release.set()
                try: await task
                except asyncio.CancelledError: pass
                else: raise AssertionError('cancel not raised')
            else:
                result=await task;results.append(result.engine_turn_verified)
                assert result.engine_turn_verified==(case in ('success','followup_replay')),(case,adapter.events)
                if case in ('success','followup_replay'):
                    worker.receive('next',FOLLOWUP,'lane')
                    result=await worker.start('lane');results.append(result.engine_turn_verified)
                    assert result.engine_turn_verified==(case=='success'),adapter.events
            with worker.store.open() as box:
                assert box.get('request').state==('completed' if case in ('success','followup_replay') else 'unknown_outcome')
                assert worker.store.history_enabled is False  # no transcript reconciliation
            try: validate_profile(home)
            except ProcessError: pass
            else: raise AssertionError('legacy resume accepted task schema')
        finally:
            release.set();stopped.set();ack_thread.join(5)
            assert not ack_thread.is_alive()
            await worker.shutdown(cancel=True)
    primary=None
    try: asyncio.run(run())
    except BaseException as exc: primary=graph(exc)
    finally:
        stopped.set();release.set();ack_thread.join(5)
        server.shutdown();server.server_close();server_thread.join(5)
        manager.unload()
    effects=(home/'effects.log').read_text().splitlines() if (home/'effects.log').exists() else []
    report=dict(case=case,primary=primary,errors=errors,results=results,events=adapter.events,
                summaries=len(summaries),model_requests=len(requests),effects=effects,
                joined=not server_thread.is_alive() and not ack_thread.is_alive(),closed=adapter.state.closed,
                requests=requests)
    (home/'result.json').write_text(json.dumps(report,indent=2))
    assert not primary and not errors,(primary,errors)
    assert report['joined'] and report['closed']
    if case=='success':
        assert len(summaries)==1 and len(requests)==4
        assert effects==['open']
        revisions=[e['revision'] for e in adapter.events if e['event']=='request']
        assert revisions==[0,2,4,4],revisions
        names=[e['event'] for e in adapter.events]
        assert names.index('checkpoint_permitted')<names.index('stock_summary')
    elif case=='cancel': assert effects==[] and not summaries
    elif case=='cancel_after_open': assert effects==['open'] and len(summaries)==1
    elif case=='followup_replay':
        assert effects==['open'] and results==[True,False]
    elif case=='missing_final':
        assert effects==['open'] and any(e['event']=='final' and not e['accepted'] for e in adapter.events)
    else:
        assert effects==['open'] and not any(e.get('step')=='read' and e['event']=='effect' for e in adapter.events)
        if case=='missing_ack': assert not summaries


def graph(exc,seen=None):
    import traceback
    seen=set() if seen is None else seen
    if exc is None: return None
    if id(exc) in seen: return {'ref':id(exc)}
    seen.add(id(exc))
    return dict(id=id(exc),type=type(exc).__name__,frames=[dict(file=x.filename,line=x.lineno,function=x.name) for x in traceback.extract_tb(exc.__traceback__)],suppress_context=exc.__suppress_context__,sqlite_errorcode=getattr(exc,'sqlite_errorcode',None),sqlite_errorname=getattr(exc,'sqlite_errorname',None),cause=graph(exc.__cause__,seen),context=graph(exc.__context__,seen))


def suite():
    from voice_worker import clean_environment
    out=Path(tempfile.mkdtemp(prefix='voice-task-worker-runs-',dir=SCRATCH))
    for case in ('success','cancel','cancel_after_open','wrong_owner','missing_hook','unavailable','missing_injection','missing_final','missing_ack','followup_replay'):
        home=out/case;home.mkdir(mode=0o700)
        env=clean_environment(home,CORE)
        cmd=[str(CORE/'venv/bin/python'),'-B',str(Path(__file__).resolve()),'--child',case]
        proc=subprocess.run(cmd,cwd=home,env=env,capture_output=True,timeout=180)
        (out/(case+'-process.json')).write_text(json.dumps(dict(argv=cmd,returncode=proc.returncode,stdout=proc.stdout.decode(),stderr=proc.stderr.decode())))
        print(case,proc.returncode,home,flush=True)
        if proc.returncode: raise SystemExit(proc.returncode)

if __name__ == '__main__':
    if len(sys.argv)>1 and sys.argv[1]=='--child':
        try: child(sys.argv[2])
        except BaseException as exc:
            (Path(os.environ['HOME'])/'failure.json').write_text(json.dumps(graph(exc),indent=2));raise
    elif '--suite' in sys.argv: suite()
    else: unittest.main()
