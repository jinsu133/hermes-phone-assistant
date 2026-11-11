"""First bounded public-API task-state contract. Only local fixed model responses.
Stock AIAgent + ContextCompressor; no core patch, private override or subclass.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = CORE.parent/'cache/scratch'
CASES = ('success','middleware_raise','middleware_omit','final_missing_state',
         'playback_missing','playback_failed','stale_ack','missing_provider',
         'duplicate_open','unknown_effect','state_controls', 'final_missing_after_read',
         'observer_raise','hook_disposed','duplicate_ack')


def exception_graph(exc, seen=None):
    seen = set() if seen is None else seen
    if exc is None:
        return None
    if id(exc) in seen:
        return {'ref':id(exc)}
    seen.add(id(exc))
    import traceback
    return dict(id=id(exc),type=type(exc).__name__, message=str(exc),
                frames=[dict(file=x.filename,line=x.lineno,function=x.name)
                        for x in traceback.extract_tb(exc.__traceback__)],
                suppress_context=exc.__suppress_context__,
                sqlite_errorcode=getattr(exc,'sqlite_errorcode',None),
                sqlite_errorname=getattr(exc,'sqlite_errorname',None),
                cause=exception_graph(exc.__cause__,seen),context=exception_graph(exc.__context__,seen))


def cleanup_graph(exc):
    """Keep diagnostic structure without cleanup messages or request/audio payloads."""
    graph = exception_graph(exc)
    def scrub(node):
        if node is not None:
            node.pop('message', None)
            scrub(node.get('cause'))
            scrub(node.get('context'))
    scrub(graph)
    return graph


class OwnedResources:
    """Fixture-only teardown. Failed cleanup never grants an early owner release."""
    def __init__(self, home, join_timeout=5):
        self.home = Path(home)
        self.join_timeout = join_timeout
        self.runtime = self.agent = self.server = None
        self.server_thread = self.ack_thread = None
        self.cleanup_errors = []
        self.outcomes = {}

    def attempt(self, stage, action):
        try:
            action()
        except BaseException as exc:
            self.cleanup_errors.append(dict(stage=stage, **cleanup_graph(exc)))
            self.outcomes[stage] = False
            return False
        self.outcomes[stage] = True
        return True

    def join(self, thread):
        # Thread.start itself can fail; joining an unstarted thread is invalid.
        if thread is not None and thread.ident is not None:
            thread.join(self.join_timeout)
            if thread.is_alive():
                raise RuntimeError('owned thread not joined')

    def finish(self, primary, evidence, *, observed_primary=None):
        # Expected negative-case conversation errors are recorded, but only raised
        # when cleanup also fails; an unexpected primary is always re-raised.
        effective_primary = primary if primary is not None else observed_primary
        initial_errors = list(getattr(effective_primary, 'cleanup_errors', []))
        self.cleanup_errors.extend(initial_errors)
        runtime = self.runtime
        if runtime is not None:
            runtime.sealed = True
            runtime.notice_ready.set()
        playback_ok = self.attempt('playback_join', lambda: self.join(self.ack_thread))
        agent_ok = self.agent is None or self.attempt('agent_close', self.agent.close)
        registrations_ok = True
        if runtime is not None:
            for index in reversed(range(len(runtime.registrations))):
                handle = runtime.registrations[index]
                if handle is not None:
                    def dispose(handle=handle):
                        handle.dispose()
                        if handle.active:
                            raise RuntimeError('registration still active')
                    registrations_ok = self.attempt('registration_'+str(index), dispose) and registrations_ok
        shutdown_ok = close_ok = True
        if self.server is not None:
            if self.server_thread is not None and self.server_thread.is_alive():
                shutdown_ok = self.attempt('server_shutdown', self.server.shutdown)
            close_ok = self.attempt('server_close', self.server.server_close)
        server_ok = self.attempt('server_join', lambda: self.join(self.server_thread))
        threads_joined = playback_ok and server_ok
        can_release = threads_joined and agent_ok and registrations_ok and shutdown_ok and close_ok and not initial_errors
        state_ok = runtime is None
        if runtime is not None:
            if can_release:
                state_ok = self.attempt('state_close', runtime.state.close)
            else:
                def deferred():
                    raise RuntimeError('state lease retained until owned resources stop')
                self.attempt('state_close_deferred', deferred)
            import task_continuity_fixture as f
            if f.ACTIVE is runtime:
                f.ACTIVE = None
        for stage, writer in evidence:
            self.attempt(stage, writer)
        report = dict(primary_error=exception_graph(effective_primary), cleanup_errors=self.cleanup_errors,
                      outcomes=self.outcomes, owned_threads_joined=threads_joined,
                      resources_released=bool(can_release and state_ok))
        # This write is independent of requests/trace/engine-result writes.
        self.attempt('lifecycle_evidence', lambda: (self.home/'lifecycle.json').write_text(
            json.dumps(report, indent=2)))
        if primary is not None or (self.cleanup_errors and observed_primary is not None):
            effective_primary.cleanup_errors = self.cleanup_errors
            raise effective_primary.with_traceback(effective_primary.__traceback__)
        if self.cleanup_errors:
            failure = RuntimeError('fixture cleanup failed; see lifecycle evidence')
            failure.cleanup_errors = self.cleanup_errors
            raise failure from None
        return report


def isolate():
    home = Path(os.environ['HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-task-run-')
    assert Path.cwd().resolve() == home and not home.stat().st_mode & 0o077
    assert all(os.environ.get(k) == str(home) for k in ('HOME','HERMES_HOME','TMPDIR'))
    assert set(os.environ) <= {'HOME','HERMES_HOME','TMPDIR','PATH','LANG','LC_CTYPE','PYTHONDONTWRITEBYTECODE','PYTHONPATH'}
    os.umask(0o077)
    def audit(event,args):
        if event in ('subprocess.Popen','os.system'):
            raise PermissionError('fixture subprocess denied')
        if event == 'socket.getaddrinfo' and args[0] not in ('127.0.0.1',b'127.0.0.1'):
            raise PermissionError('remote DNS denied')
        if event in ('socket.connect','socket.bind') and args[1][0] != '127.0.0.1':
            raise PermissionError('remote socket denied')
        if event == 'open' and isinstance(args[0],(str,bytes,os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = ((isinstance(args[1],str) and any(c in args[1] for c in 'wax+'))
                       or (isinstance(args[2],int) and args[2] & (os.O_WRONLY|os.O_RDWR|os.O_CREAT)))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside private fixture write')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile read')
            if path.name in ('.env','auth.json','auth.json.lock'):
                raise PermissionError('credential read')
    # Defense in depth, not an OS sandbox (native SQLite is not Python open).
    sys.addaudithook(audit)
    sys.path[:0] = [str(P/'tests'),str(P),str(CORE)]
    import hermes_cli.main  # normal entry import, no standalone checkout recovery
    return home


def controls(home):
    from task_continuity_fixture import State,StateError,canonical,SCREEN
    owner = {'profile':'fixture','attempt':'one'}
    s = State(home,owner)
    checks=[]
    def refused(name,call):
        before=s.snapshot()
        try: call()
        except (StateError,BlockingIOError): pass
        else: raise AssertionError(name+' was not refused')
        assert s.snapshot() == before
        checks.append(name)
    refused('stale_revision',lambda:s.begin('open',5))
    refused('boolean_revision',lambda:s.begin('open',False))
    refused('read_before_open',lambda:s.begin('read',0))
    refused('second_writer',lambda:State(home,owner))
    refused('foreign_owner',lambda:s.update(0,lambda x:x.update(owner={'profile':'foreign'})))
    refused('artifact_escape',lambda:s.update(0,lambda x:x['evidence'].update(open={'artifact':'../escape'})))
    refused('state_capacity',lambda:s.update(0,lambda x:x.update(goal='x'*5000)))
    refused('model_claim_not_evidence',lambda:s.update(0,lambda x:x['steps'].update(open='verified')))
    s.update(0,lambda x:x.update(approval_waiting=True))
    refused('approval_pending',lambda:s.begin('open',1))
    s.update(1,lambda x:x.update(approval_waiting=False))
    s.begin('open',2)
    (home/'app.json').write_text(canonical({'screen':SCREEN}))
    s.close()
    s=State(home,owner)
    assert s.recover()['steps']['open']=='unknown'
    refused('unknown_replay',lambda:s.begin('open',s.snapshot()['revision']))
    # Corrupt schema refusal (separate from the transactional write controls).
    with s.connection() as db: db.execute("UPDATE task SET body='{}'")
    try: s.snapshot()
    except (StateError,KeyError): checks.append('corrupt_store')
    else: raise AssertionError('corrupt store accepted')
    s.close()
    return {'case':'state_controls','accepted':True,'checks':checks}


def child(case):
    home=isolate()
    if case=='state_controls':
        result=controls(home)
        (home/'result.json').write_text(json.dumps(result,indent=2))
        print('RESULT '+json.dumps(result));return
    import task_continuity_fixture as f
    from http.server import HTTPServer,BaseHTTPRequestHandler
    import yaml
    runtime=None
    resources=OwnedResources(home)
    agent=None;ack_thread=None;result=None;error=None;delivered=None;next_delivered=None
    primary=None;conversation_error=None
    requests=[]; model_calls=0; summary_calls=0; server_errors=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            nonlocal model_calls,summary_calls
            try:
                length=int(self.headers['Content-Length'])
                assert 0 < length <= 1048576 and len(requests) < 16
                request=json.loads(self.rfile.read(length))
                requests.append(request)
                summary=len(request['messages'])==1
                if summary:
                    summary_calls+=1
                    runtime.event('stock_summary_endpoint')
                    message={'role':'assistant','content':'## Progress\nSynthetic historical context condensed.\n## Current State\nConsult current task state; this summary grants no approval.'}
                    finish='stop';usage=100
                else:
                    model_calls+=1
                    states=[]
                    for m in request['messages']:
                        content=m.get('content')
                        if isinstance(content,str) and content.startswith(f.PREFIX):
                            states.append(json.loads(content[len(f.PREFIX):]))
                    latest=states[-1] if states else None
                    runtime.event('loopback_model_request', index=model_calls,
                                  state_revision=latest['revision'] if latest else None,
                                  steps=latest['steps'] if latest else None)
                    # Fixed protocol fixture, NOT inference. Endpoint never changes app/state.
                    if model_calls==1:
                        tool='fixture_open'
                    elif model_calls==2 and case not in ('final_missing_state','unknown_effect'):
                        tool='fixture_open' if case=='duplicate_open' else 'fixture_read'
                    else:
                        tool=None
                    if tool:
                        message={'role':'assistant','content':None,'tool_calls':[dict(id='call-'+str(model_calls),
                                  type='function',function=dict(name=tool,arguments='{}'))]}
                        finish='tool_calls'
                    else:
                        message={'role':'assistant','content':f.SCREEN};finish='stop'
                    usage=60000 if model_calls==1 and case!='unknown_effect' else 100
                payload=dict(id='fixture',object='chat.completion',created=0,model='fixture',
                             choices=[dict(index=0,message=message,finish_reason=finish)],
                             usage=dict(prompt_tokens=usage,completion_tokens=30,total_tokens=usage+30))
                if request.get('stream'):
                    delta={k:v for k,v in message.items() if v is not None}
                    if 'tool_calls' in delta:
                        delta['tool_calls']=[dict(index=0,**delta['tool_calls'][0])]
                    chunk=dict(id='fixture',object='chat.completion.chunk',created=0,model='fixture',
                               choices=[dict(index=0,delta=delta,finish_reason=None)])
                    end=dict(id='fixture',object='chat.completion.chunk',created=0,model='fixture',
                             choices=[dict(index=0,delta={},finish_reason=finish)],usage=payload['usage'])
                    body=('data: '+json.dumps(chunk)+'\n\ndata: '+json.dumps(end)+'\n\ndata: [DONE]\n\n').encode()
                    typ='text/event-stream'
                else: body=json.dumps(payload).encode();typ='application/json'
                self.send_response(200);self.send_header('Content-Type',typ)
                self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            except BaseException as exc:
                server_errors.append(exception_graph(exc));raise
    try:
        runtime=resources.runtime=f.Runtime(home,case); f.ACTIVE=runtime
        server=resources.server=HTTPServer(('127.0.0.1',0),Handler)
        server_thread=resources.server_thread=threading.Thread(target=server.serve_forever,name='owned-loopback')
        server_thread.start()
        url='http://127.0.0.1:%d/v1'%server.server_address[1]
        config=dict(memory=dict(memory_enabled=False,user_profile_enabled=False,provider='task-state-fixture' if case!='missing_provider' else 'none'),
                    model=dict(context_length=131072), tools=dict(tool_search=dict(enabled='off')),
                    compression=dict(enabled=True,checkpoint_required=True,threshold_tokens=50000,
                                     protect_first_n=1,protect_last_n=2,min_tail_user_messages=1,
                                     proactive_prune_tokens=0,micro_compact=False,in_place=True,
                                     context_timeout_seconds=10,max_attempts=1),
                    auxiliary=dict(compression=dict(provider='custom',model='fixture',base_url=url,
                                                    api_key='offline-placeholder',context_length=131072)),
                    checkpoints=dict(enabled=False),sessions=dict(write_json_snapshots=False))
        (home/'config.yaml').write_text(yaml.safe_dump(config))
        plugin=home/'plugins/task-state-fixture';plugin.mkdir(parents=True)
        (plugin/'__init__.py').write_text('from task_continuity_fixture import register # register_memory_provider\n')
        from hermes_cli.plugins import PluginContext,PluginManifest,get_plugin_manager
        from agent.context_compressor import ContextCompressor
        from run_agent import AIAgent
        ctx=PluginContext(PluginManifest(name='task-tools-fixture',path=str(plugin)),get_plugin_manager())
        runtime.register(ctx)
        agent=resources.agent=AIAgent(model='fixture',provider='openai',api_mode='chat_completions',
                      api_key='offline-placeholder',base_url=url,enabled_toolsets=['task_fixture'],
                      session_id='fixture-session',session_db=None,save_trajectories=False,
                      verbose_logging=False,quiet_mode=True,skip_memory=False,skip_context_files=True,
                      load_soul_identity=False,skip_background_review=True,checkpoints_enabled=False,
                      max_iterations=4,event_callback=lambda event,data:runtime.event('core_event',name=event))
        assert type(agent.context_compressor) is ContextCompressor
        assert not {'voice_reconcile','voice_reconcile_engine'} & set(sys.modules)
        assert {x['function']['name'] for x in agent.tools}=={'fixture_open','fixture_read'}, agent.tools
        history=[{'role':'user' if i%2==0 else 'assistant',
                  'content':('Synthetic historical detail '+str(i)+' ')*180} for i in range(20)]
        if case not in ('unknown_effect','missing_provider'):
            ack_thread=resources.ack_thread=threading.Thread(target=runtime.ack,name='owned-synthetic-playback');ack_thread.start()
        try:
            result=agent.run_conversation(f.GOAL,conversation_history=history)
        except BaseException as exc:
            conversation_error=exc
            error=exception_graph(exc)
        delivered=runtime.final(result)
        if case in ('success','duplicate_ack'):
            assert delivered==f.SCREEN,(result,error,runtime.events)
            next_result=agent.run_conversation('Report the already verified synthetic screen; do not reopen.',
                                               conversation_history=result['messages'])
            next_delivered=runtime.final(next_result)
            assert next_delivered==f.SCREEN,next_result
    except BaseException as exc:
        primary=exc
    finally:
        lifecycle=resources.finish(primary, [
            ('requests_evidence', lambda: (home/'requests.json').write_text(json.dumps(requests,indent=2))),
            ('trace_evidence', lambda: (home/'trace.json').write_text(json.dumps(runtime.events if runtime else [],indent=2))),
            ('engine_evidence', lambda: (home/'engine-result.json').write_text(json.dumps(
                dict(result=result,error=error,primary_error=exception_graph(primary),
                     cleanup_errors=resources.cleanup_errors,server_errors=server_errors),indent=2,default=str)))],
            observed_primary=conversation_error)
    assert not server_errors,server_errors
    assert runtime.ack_failure is None,exception_graph(runtime.ack_failure)
    effects=(home/'effects.log').read_text().splitlines() if (home/'effects.log').exists() else []
    state=f.State(home,runtime.identity)
    snapshot=state.recover()
    state.close()
    names=[event['event'] for event in runtime.events]
    if case in ('success','duplicate_ack'):
        assert summary_calls==1 and model_calls==4,(summary_calls,model_calls)
        assert effects==['open'] and snapshot['steps']=={'open':'verified','read':'verified'}
        assert names.index('fixture_open_effect')<names.index('checkpoint_state_committed')<names.index('synthetic_playback_ack')<names.index('checkpoint_permitted')<names.index('stock_summary_endpoint')<names.index('fixture_read_observation')
        post_summary=[e for e in runtime.events[names.index('stock_summary_endpoint')+1:] if e['event']=='loopback_model_request']
        assert post_summary[0]['state_revision']==2 and post_summary[0]['steps']=={'open':'verified','read':'pending'}
        assert any(e['event']=='core_event' and e['name']=='session:compress' for e in runtime.events)
        assert len([e for e in runtime.events if e['event']=='pre_llm'])==2
    else:
        assert delivered is None,'false success delivered'
        if case != 'final_missing_after_read':
            assert 'fixture_read_observation' not in names,'next step was executed'
        else:
            assert snapshot['steps']=={'open':'verified','read':'verified'}
            assert any(e['event']=='effective_request' and not e['present'] and e['revision']==4 for e in runtime.events)
        if case in ('playback_missing','playback_failed','stale_ack','missing_provider'):
            assert summary_calls==0,(case,summary_calls)
            assert error and error['type']=='CompressionCheckpointUnavailable',error
        elif case=='unknown_effect':
            assert effects==['open'] and snapshot['steps']['open']=='unknown'
        else:
            assert summary_calls==1
            if case not in ('final_missing_state','final_missing_after_read','hook_disposed'):
                assert 'pre_tool_blocked' in names,names
        assert len(effects)<=1
    report=dict(case=case,accepted=True,summary_calls=summary_calls,model_calls=model_calls,
                effects=effects,state=snapshot,delivered=delivered,next_turn_delivered=next_delivered,
                error=error,events=runtime.events,owned_threads_joined=lifecycle['owned_threads_joined'],
                real_phone=False,real_audio=False,paid_calls=0)
    (home/'result.json').write_text(json.dumps(report,indent=2))
    print('RESULT '+json.dumps(report))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--child',choices=CASES)
    parser.add_argument('--case',choices=CASES);parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.child:child(args.child);return
    out=args.output or Path(tempfile.mkdtemp(prefix='voice-task-contract-results-',dir=SCRATCH))
    out.mkdir(parents=True,exist_ok=True)
    for case in (args.case,) if args.case else CASES:
        home=Path(tempfile.mkdtemp(prefix='voice-task-run-',dir=out))
        # Child ownership check allows nested evidence directories under scratch.
        env=dict(HOME=str(home),HERMES_HOME=str(home),TMPDIR=str(home),
                 PATH='/data/data/com.termux/files/usr/bin',LANG='C.UTF-8',
                 PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(CORE))
        command=[sys.executable,'-B',str(Path(__file__).resolve()),'--child',case]
        (home/'command.json').write_text(json.dumps(dict(argv=command,cwd=str(home),env=env)))
        proc=subprocess.run(command,cwd=home,env=env,text=True,capture_output=True,timeout=90)
        (home/'stdout.log').write_text(proc.stdout);(home/'stderr.log').write_text(proc.stderr)
        if proc.returncode:
            print('FAIL',case,'evidence',home,proc.stdout,proc.stderr);raise SystemExit(proc.returncode)
        lines=[line for line in proc.stdout.splitlines() if line.startswith('RESULT ')]
        assert len(lines)==1,proc.stdout
        print(lines[0])
    print('EVIDENCE '+str(out))

if __name__=='__main__':main()
