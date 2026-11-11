"""Additive source-only public-output reconciliation probe. No runtime activation.
Uses fixed loopback model/summary; never treats a candidate/observer as host commit.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = CORE.parent/'cache/scratch'
CASES = ('success', 'cancel_candidate', 'missing', 'crash_candidate', 'crash_before_commit', 'crash_after_commit')

def child(case):
    assert case in CASES, case
    home = Path(os.environ['HOME']).resolve()
    assert home.is_relative_to(SCRATCH) and home.name.startswith('voice-reconcile-')
    assert Path.cwd().resolve() == home and not home.stat().st_mode & 0o077
    assert all(os.environ.get(k) == str(home) for k in ('HOME','HERMES_HOME','TMPDIR'))
    assert set(os.environ) <= {'HOME','HERMES_HOME','TMPDIR','PATH','LANG','LC_CTYPE','PYTHONDONTWRITEBYTECODE','PYTHONPATH'}
    os.umask(0o077)
    # Defense in depth for audited Python operations, NOT an OS sandbox.
    # Native sqlite writes do not emit Python 'open'; private HOME/owned paths
    # and inspected serial entrypoints remain required. No universal write fence.
    # Permit only the local fixed-response endpoint for audited DNS/connections.
    def audit(event, args):
        if event in ('subprocess.Popen', 'os.system'):
            raise PermissionError('offline subprocess denied')
        if event == 'socket.getaddrinfo' and args[0] not in ('127.0.0.1', b'127.0.0.1'):
            raise PermissionError('remote DNS denied')
        if event in ('socket.connect', 'socket.bind') and args[1][0] != '127.0.0.1':
            raise PermissionError('remote socket denied')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = ((isinstance(args[1], str) and any(c in args[1] for c in 'wax+'))
                       or (isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT)))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside fixture write')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile read')
            if path.name in ('.env', 'auth.json', 'auth.json.lock'):
                raise PermissionError('credential read')
    sys.addaudithook(audit)
    sys.path[:0] = [str(P/'tests'), str(P), str(CORE)]
    import logging
    logging.disable(logging.CRITICAL)
    # Normal CLI import avoids run_agent's standalone checkout recovery path.
    import hermes_cli.main
    from voice_checkpoint import CheckpointStore, CheckpointProvider, CheckpointError
    from voice_dispatch import CURRENT, _Run, Attempt, DispatchHooks
    from agent.memory_manager import MemoryManager
    from agent.conversation_compression import CompressionCheckpointUnavailable
    from agent.context_compressor import ContextCompressor
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    from run_agent import AIAgent
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

    events, envelopes, failures, compress_events = [], [], [], []
    notices = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            summary = len(request['messages']) == 1
            events.append('summary' if summary else 'model')
            text = ('## User Intent\nSynthetic historical fixture task.\n## Progress\nFixture history condensed.\n'
                    '## Current State\nContinue the active request, no tool execution needed.' if summary
                    else 'synthetic completed')
            payload = dict(id='fixture', object='chat.completion', created=0, model='fixture',
                           choices=[dict(index=0, message=dict(role='assistant', content=text), finish_reason='stop')],
                           usage=dict(prompt_tokens=100, completion_tokens=30, total_tokens=130))
            if request.get('stream'):
                chunk = dict(id='fixture', object='chat.completion.chunk', created=0, model='fixture',
                             choices=[dict(index=0, delta=dict(role='assistant', content=text), finish_reason=None)])
                end = dict(id='fixture', object='chat.completion.chunk', created=0, model='fixture',
                           choices=[dict(index=0, delta={}, finish_reason='stop')], usage=payload['usage'])
                body = ('data: '+json.dumps(chunk)+'\n\ndata: '+json.dumps(end)+'\n\ndata: [DONE]\n\n').encode()
                typ = 'text/event-stream'
            else:
                body = json.dumps(payload).encode(); typ = 'application/json'
            self.send_response(200); self.send_header('Content-Type', typ)
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True); server_thread.start()
    url = 'http://127.0.0.1:%d/v1' % server.server_address[1]
    config = dict(memory=dict(memory_enabled=False, user_profile_enabled=False, provider='voice-checkpoint-fixture'),
                  model=dict(context_length=65536), context=dict(engine='voice-reconcile-fixture'), fixture_case=case,
                  compression=dict(enabled=True, checkpoint_required=True, threshold_tokens=6000,
                                   protect_first_n=1, protect_last_n=2, min_tail_user_messages=1,
                                   proactive_prune_tokens=0, micro_compact=False, in_place=True,
                                   context_timeout_seconds=10, max_attempts=1),
                  auxiliary=dict(compression=dict(provider='custom', model='fixture', base_url=url,
                                                  api_key='offline-placeholder', context_length=65536)),
                  checkpoints=dict(enabled=False), sessions=dict(write_json_snapshots=False))
    import yaml
    (home/'config.yaml').write_text(yaml.safe_dump(config))
    fixture = home/'plugins/voice-checkpoint-fixture'; fixture.mkdir(parents=True)
    (fixture/'__init__.py').write_text('from voice_checkpoint import register # register_memory_provider\n')
    engine_fixture = home/'plugins/voice-reconcile-fixture'; engine_fixture.mkdir(parents=True)
    (engine_fixture/'__init__.py').write_text('from reconcile_engine_fixture import register # register_context_engine\n')
    store = CheckpointStore(home, 'fixture-profile', home/'approved.sqlite', wait_seconds=1.5)
    with store.open() as box:
        box.receive('request', 'synthetic active request', 'lane')
        owner = box.claim('lane')
    context = PluginContext(PluginManifest(name='checkpoint-offline', path=str(fixture)), get_plugin_manager())
    hooks = DispatchHooks(context)
    context.register_hook('pre_llm_call', lambda **kwargs: events.append('pre_llm'))
    run = _Run(store, hooks, Attempt(owner, 'fixture-session'))
    before = [{'role':'user' if i % 2 == 0 else 'assistant',
               'content':f'synthetic original {i} ' + 'historical fixture detail ' * 350} for i in range(20)]
    before[3]['tool_calls'] = [dict(id='call-x', type='function', function=dict(name='fixture', arguments='{}'))]
    before.insert(4, dict(role='tool', tool_call_id='call-x', content='synthetic hidden tool evidence'))
    before[-1]['tool_calls'] = [dict(id='call-tail', type='function', function=dict(name='fixture', arguments='{"n":2}'))]
    before.append(dict(role='tool', tool_call_id='call-tail', content='synthetic retained tool result'))
    before.append(dict(role='assistant',content='synthetic final historical reply'))
    original = copy.deepcopy(before)
    from reconcile_protocol_fixture import Journal, Refused, digest
    journal = Journal(home/'approved.sqlite')
    owner_dict = dict(profile_id=owner.profile_id, lane=owner.lane, request_id=owner.request_id, attempt_id=owner.attempt_id)
    journal.prepare(owner_dict, run.identity.sid, original, 'synthetic active request')
    token = CURRENT.set(run)
    try:
        agent = AIAgent(model='fixture', provider='openai', api_mode='chat_completions',
                        api_key='offline-placeholder', base_url=url, enabled_toolsets=[],
                        session_id='fixture-session', session_db=None, save_trajectories=False,
                        verbose_logging=False, quiet_mode=True, skip_memory=False,
                        skip_context_files=True, load_soul_identity=False, skip_background_review=True,
                        checkpoints_enabled=False, max_iterations=2,
                        event_callback=lambda event, data: compress_events.append((event, data)))
        from reconcile_engine_fixture import ReconcileFixture
        assert type(agent.context_compressor) is ReconcileFixture
        assert agent.context_compressor.registration_count == 1
        assert ReconcileFixture.on_turn_complete is ContextCompressor.on_turn_complete
        agent.context_compressor.update_from_response(dict(prompt_tokens=20000, completion_tokens=30, total_tokens=20030))
    finally:
        CURRENT.reset(token)
    def work():
        token = CURRENT.set(run)
        try:
            envelopes.append(agent.run_conversation('synthetic active request', conversation_history=before))
        except Exception as exc:
            failures.append(exc)
            cause = exc
            while cause is not None:
                print('CAUSE', type(cause).__name__, str(cause))
                cause = cause.__cause__
        finally:
            CURRENT.reset(token)
    thread = threading.Thread(target=work); thread.start()
    deadline = time.monotonic() + 20
    while thread.is_alive() and time.monotonic() < deadline:
        with store.open() as box:
            notices = box.notices(owner)
        if notices: break
        time.sleep(.01)
    assert len(notices) == 1, (events, failures, envelopes)
    notice = notices[0]
    assert notice['state'] == 'pending'
    assert run.started is None
    with store.open() as box:
        assert box.get('request').state == 'dispatching'
        assert box.get('request').sid is None
    assert agent.context_compressor.boundaries == []
    assert notice['notice_text']
    import hashlib
    from voice_history import _encode
    assert notice['content_digest'] == hashlib.sha256(_encode({'evidence':notice['evidence'], 'notice':notice['notice_text']}).encode()).hexdigest()
    assert not events, events
    assert before == original
    assert notice['identity']['request_id'] == owner.request_id
    assert notice['identity']['attempt_id'] == owner.attempt_id
    receipt = dict(identity=notice['identity'], event_id=notice['event_id'],
                   content_digest=notice['content_digest'], outcome='played', synthetic=True)
    if case != 'missing':
        with store.open() as box:
            accepted = box.acknowledge(receipt)
        assert accepted is True
    if case == 'cancel_candidate':
        assert agent.context_compressor.candidate_ready.wait(15), (events, failures)
        events.append('hard_cancel')
        agent.interrupt(hard_cancel=True)
        agent.context_compressor.allow_return.set()
    thread.join(25)
    assert not thread.is_alive(), 'bounded worker failed to exit'
    assert before == original
    agent.close()
    server.shutdown(); server.server_close(); server_thread.join(5)
    assert not server_thread.is_alive()
    with store.open() as box:
        row = box.get(owner.request_id)
        assert box.history(owner.lane).messages == []  # Product history remains untouched.
        binding = dict(owner=owner_dict, session_id=row.sid, turn_id=row.turn_id, state=row.state)
    if case == 'missing':
        assert len(failures) == 1 and type(failures[0]) is CompressionCheckpointUnavailable
        assert not envelopes and not agent.context_compressor.boundaries and not events
        journal.reject('ACK deadline before compression', proven_abort=True)
    elif case == 'cancel_candidate':
        assert not failures and len(envelopes) == 1
        env = envelopes[0]; saved = journal.read()
        assert env['interrupted'] and not env['completed']
        assert env['messages'][:len(saved['boundary']['before'])] == saved['boundary']['before']
        assert env['messages'][:len(saved['boundary']['candidate'])] != saved['boundary']['candidate']
        try: journal.finish(env, binding, joined=True, closed=True)
        except Refused as e: assert str(e) == 'not completed'
        else: raise AssertionError('cancel accepted')
        journal.reject('public incomplete result; original prefix restored', proven_abort=True)
    else:
        assert not failures and len(envelopes) == 1
        env = envelopes[0]
        assert events.count('summary') == 1 and events.count('model') == 1
        assert run.started == (env['session_id'], env['turn_id'])
        assert run.posted == run.started and not run.failed
        journal.finish(env, binding, joined=True, closed=True,
                       crash={'crash_before_commit':'before_commit', 'crash_after_commit':'after_commit'}.get(case))
        saved = journal.read()
        assert saved['state'] == 'plugin_committed' and saved['plugin_completed']
        assert saved['ledger'][:len(original)] == original
        assert saved['active'] == env['messages'] and saved['version'] == 1
        assert saved['boundary']['before_digest'] == digest(saved['boundary']['before'])
        assert saved['boundary']['before'][:-1] == original
    saved = journal.read()
    assert saved['original'] == original and not saved['host_commit_proven']
    assert saved['plugin_completed'] == (case == 'success')
    with store.open() as box:
        assert box.get(owner.request_id).state != 'completed'
        assert box.claim('lane') is None
    print('RESULT '+json.dumps(dict(case=case, passed=True, events=events,
        exceptions=[type(e).__name__ for e in failures], journal=saved,
        envelopes=envelopes, binding=binding, observer_required=False, real_audio=False)))


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--child': return child(sys.argv[2])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    out = args.output_dir.resolve(); out.mkdir(mode=0o700, exist_ok=False)
    from reconcile_protocol_fixture import Journal
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-reconcile-',dir=SCRATCH) as tmp:
            env = dict(HOME=tmp,HERMES_HOME=tmp,TMPDIR=tmp,PATH='/data/data/com.termux/files/usr/bin',
                       LANG='C.UTF-8',PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(CORE))
            try:
                proc = subprocess.run([sys.executable,'-B',str(Path(__file__).resolve()),'--child',case],
                    cwd=tmp,env=env,capture_output=True,text=True,timeout=120)
            except subprocess.TimeoutExpired as exc:
                (out/(case+'.timeout.log')).write_text(repr(exc)); raise
            (out/(case+'.stdout.log')).write_text(proc.stdout)
            (out/(case+'.stderr.log')).write_text(proc.stderr)
            expected = {'crash_candidate':72,'crash_before_commit':73,'crash_after_commit':74}.get(case,0)
            assert proc.returncode == expected, (case,proc.returncode,proc.stdout,proc.stderr)
            if expected:
                journal = Journal(Path(tmp)/'approved.sqlite')
                prior = journal.read(); journal.recover(); recovered = journal.read()
                expected_state = 'plugin_committed' if case == 'crash_after_commit' else 'unknown'
                assert recovered['state'] == expected_state
                assert recovered['plugin_completed'] == (case == 'crash_after_commit')
                assert recovered['ledger'][:len(prior['original'])] == prior['original']
                if expected_state == 'unknown':
                    assert recovered['active'] == prior['original'] and recovered['version'] == 0
                    assert recovered['result'] is None
                again = journal.read(); journal.recover(); assert journal.read() == again
                result = dict(case=case,passed=True,exit_code=proc.returncode,before_recovery=prior,
                              journal=recovered,engine_replayed=False,host_commit_proven=False)
            else:
                lines=[x[7:] for x in proc.stdout.splitlines() if x.startswith('RESULT ')]
                assert len(lines)==1
                result=json.loads(lines[0]); assert result['case']==case and result['passed'] is True
            with (out/(case+'.json')).open('x') as f: json.dump(result,f,ensure_ascii=False,indent=2)
            print(json.dumps(dict(case=case,passed=True,state=result['journal']['state'])))
    print(json.dumps(dict(passed=len(CASES),cases=len(CASES),paid_calls=0,real_audio=False)))

if __name__ == '__main__': main()
