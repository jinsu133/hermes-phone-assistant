"""Sequential clean-HOME integration tests; no core monkeypatch/private writes.
Only a loopback HTTP fixture serves fixed summary/model JSON (no real provider).
Run: core/venv/bin/python -B tests/test_voice_checkpoint.py
"""
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
SCRATCH = CORE.parent / 'cache/scratch'
CASES = ('valid', 'missing', 'failed', 'wrong_owner', 'wrong_event', 'wrong_content',
         'wrong_profile', 'wrong_lane', 'wrong_request', 'wrong_session', 'wrong_turn', 'not_synthetic',
         'cancel', 'restart', 'storage_failure', 'duplicate', 'no_context', 'mapping')


def child(case):
    home = Path(os.environ['HOME'])
    os.umask(0o077)
    # Permit only the local fixed-response endpoint, never remote DNS/connections.
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
    sys.path[:0] = [str(P), str(CORE)]
    import logging
    logging.disable(logging.CRITICAL)
    # Normal CLI import avoids run_agent's standalone checkout recovery path.
    import hermes_cli.main
    from voice_checkpoint import CheckpointStore, CheckpointProvider, CheckpointError
    from voice_dispatch import CURRENT, _Run, Attempt, DispatchHooks
    from agent.memory_manager import MemoryManager
    from agent.context_compressor import ContextCompressor
    from agent.relay_runtime import current_turn
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    from run_agent import AIAgent
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

    from voice_inbox import Ownership
    if case == 'restart_seed':
        store = CheckpointStore(home, 'fixture-profile', home/'approved.sqlite', wait_seconds=1.5)
        with store.open() as box:
            box.receive('request', 'synthetic active request', 'lane')
            owner = box.claim('lane')
            identity = dict(profile_id=owner.profile_id,lane=owner.lane,request_id=owner.request_id,
                            attempt_id=owner.attempt_id,session_id='fixture-session',turn_id='previous-turn')
            box.issue(identity,[dict(role='user',content='synthetic previous process')])
        print('RESULT '+json.dumps(dict(case=case,passed=True)))
        return
    events, envelopes, failures, compress_events = [], [], [], []
    issued = threading.Event()
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
                  model=dict(context_length=65536), context=dict(engine='compressor'),
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
    store = CheckpointStore(home, 'fixture-profile', home/'approved.sqlite', wait_seconds=1.5)
    with store.open() as box:
        box.receive('request', 'synthetic active request', 'lane')
        owner = box.claim('lane')
        if case == 'restart':
            row = box.get('request')
            owner = Ownership('fixture-profile',row.lane,row.request_id,row.attempt_id)
    context = PluginContext(PluginManifest(name='checkpoint-offline', path=str(fixture)), get_plugin_manager())
    hooks = DispatchHooks(context)
    run = _Run(store, hooks, Attempt(owner, 'fixture-session'))
    before = [{'role':'user' if i % 2 == 0 else 'assistant',
               'content':f'synthetic original {i} ' + 'historical fixture detail ' * 350} for i in range(20)]
    if case == 'mapping':
        before[3]['tool_calls'] = [dict(id='call-x', type='function', function=dict(name='fixture', arguments='{}'))]
        before.insert(4, dict(role='tool', tool_call_id='call-x', content='synthetic hidden tool evidence'))
    original = copy.deepcopy(before)
    token = CURRENT.set(run)
    try:
        agent = AIAgent(model='fixture', provider='openai', api_mode='chat_completions',
                        api_key='offline-placeholder', base_url=url, enabled_toolsets=[],
                        session_id='fixture-session', session_db=None, save_trajectories=False,
                        verbose_logging=False, quiet_mode=True, skip_memory=False,
                        skip_context_files=True, load_soul_identity=False, skip_background_review=True,
                        checkpoints_enabled=False, max_iterations=2,
                        event_callback=lambda event, data: compress_events.append((event, data)))
        # Read-only inspection, never replace manager/compressor or alter private fields.
        assert isinstance(agent._memory_manager, MemoryManager)
        assert type(agent.context_compressor) is ContextCompressor
        assert agent.compression_checkpoint_required and agent.compression_enabled
        assert agent.context_compressor.proactive_prune_tokens == 0
        # Public usage update supplies fixed previous-provider usage; no private
        # threshold/predicate override and no claim of real token measurement.
        agent.context_compressor.update_from_response(dict(prompt_tokens=20000, completion_tokens=30, total_tokens=20030))
        providers = agent._memory_manager.providers
        assert len(providers) == 1 and isinstance(providers[0], CheckpointProvider)
        provider = providers[0]
        assert provider.initialized and provider.registration_count == 1
    finally:
        CURRENT.reset(token)

    # Observational profiler does not replace code, arguments, returns or attributes.
    def trace(frame, event, arg):
        if event == 'call' and frame.f_code.co_filename == str(CORE/'agent/context_compressor.py'):
            if frame.f_code.co_name in ('compress', '_prune_old_tool_results'):
                events.append(frame.f_code.co_name)
        return trace
    threading.setprofile(trace)
    def work():
        token = CURRENT.set(None if case == 'no_context' else run)
        try:
            envelopes.append(agent.run_conversation('synthetic active request', conversation_history=before))
        except Exception as exc:
            failures.append(type(exc).__name__)
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
    if case != 'no_context':
        assert len(notices) == 1, (events, failures, envelopes)
        notice = notices[0]
        assert notice['state'] == 'pending'
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
        if case == 'wrong_owner': receipt['identity'] = {**receipt['identity'], 'attempt_id':'foreign'}
        for scenario, key in [('wrong_profile','profile_id'),('wrong_lane','lane'),('wrong_request','request_id'),
                              ('wrong_session','session_id'),('wrong_turn','turn_id')]:
            if case == scenario: receipt['identity'] = {**receipt['identity'], key:'foreign'}
        if case == 'not_synthetic': receipt['synthetic'] = False
        if case == 'wrong_event': receipt['event_id'] = 'foreign-event'
        if case == 'wrong_content': receipt['content_digest'] = '0'*64
        if case == 'failed': receipt['outcome'] = 'failed'
        if case == 'cancel': run.cancelled.set()
        elif case == 'restart':
            with store.open() as box: assert not box.acknowledge(receipt)
        elif case == 'storage_failure':
            # Refuse reads/writes through the vetted path validator, no mock.
            (home/'approved.sqlite').rename(home/'unavailable.sqlite')
        elif case != 'missing':
            with store.open() as box:
                accepted = box.acknowledge(receipt)
            assert accepted == (case in ('valid', 'duplicate', 'mapping', 'failed'))
            if case == 'duplicate':
                with store.open() as box: assert box.acknowledge(receipt)
    thread.join(25)
    assert not thread.is_alive(), 'bounded worker failed to exit'
    threading.setprofile(None)
    good = case in ('valid', 'duplicate', 'mapping')
    assert before == original
    if good:
        assert not failures and envelopes[0]['completed'], (failures, envelopes)
        result = envelopes[0]
        assert result['session_id'] == notice['identity']['session_id']
        assert result['turn_id'] == notice['identity']['turn_id']
        assert run.started == (result['session_id'], result['turn_id'])
        assert events.count('model') == 1 and events.count('summary') == 1, events
        assert events.index('compress') < events.index('_prune_old_tool_results') < events.index('summary') < events.index('model')
        assert len(result['messages']) < len(before)
        with store.open() as box:
            saved = box.notices(owner)
            assert len(saved) == 1 and saved[0]['state'] == 'permitted'
            assert saved[0]['receipt'] == receipt
            assert box.issue(saved[0]['identity'],saved[0]['evidence']) == (saved[0]['event_id'],saved[0]['content_digest'])
            try:
                box.issue(saved[0]['identity'],saved[0]['evidence']+[dict(role='user',content='changed evidence')])
            except CheckpointError: pass
            else: raise AssertionError('changed boundary reused permission')
            try:
                box.issue(saved[0]['identity'],[dict(role='user',content='x'*store.max_checkpoint_bytes)])
            except CheckpointError: pass
            else: raise AssertionError('checkpoint byte limit ignored')
            assert not box.consume(saved[0]['event_id'], saved[0]['identity'], saved[0]['content_digest'])
            if case == 'mapping':
                assert all('tool_calls' not in m and m['role'] in ('user','assistant') for m in saved[0]['evidence'])
                assert 'synthetic hidden tool evidence' not in json.dumps(saved[0]['evidence'])
        payloads = [data for event, data in compress_events if event == 'session:compress']
        assert len(payloads) == 1
        assert not any(key in payloads[0] for key in ('before','after','messages','turn_id','request_id'))
    else:
        assert events == [], (events, failures, envelopes)
        assert failures or (envelopes and not envelopes[0].get('completed')), envelopes
        assert not any(event == 'session:compress' for event, data in compress_events)
    agent.close(); server.shutdown(); server.server_close()
    if case != 'storage_failure':
        with store.open() as box:
            assert box.claim('lane') is None
            assert box.get('request').original_text == 'synthetic active request'
            assert box.history('lane').messages == []  # no history v2 commit claimed
    for path in home.rglob('*'):
        if path.is_file() and b'synthetic' in path.read_bytes():
            assert path.name in ('approved.sqlite','approved.sqlite-journal','unavailable.sqlite'), path

    print('RESULT '+json.dumps(dict(case=case, passed=True, events=events, exceptions=failures,
                                    same_request=good, full_mapping=False, real_audio=False)))


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--child': return child(sys.argv[2])
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-checkpoint-', dir=SCRATCH) as tmp:
            env = dict(HOME=tmp, HERMES_HOME=tmp, TMPDIR=tmp,
                       PATH='/data/data/com.termux/files/usr/bin', LANG='C.UTF-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONPATH=str(CORE))
            if case == 'restart':
                seed = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--child', 'restart_seed'],
                                      cwd=tmp, env=env, capture_output=True, text=True, timeout=30)
                assert seed.returncode == 0, seed.stderr
            proc = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--child', case],
                                  cwd=tmp, env=env, capture_output=True, text=True, timeout=65)
            if proc.returncode:
                print('FAIL',case,proc.stdout,proc.stderr); raise SystemExit(1)
            results = [x for x in proc.stdout.splitlines() if x.startswith('RESULT ')]
            assert len(results) == 1, proc.stdout
            print(results[0])
    print(json.dumps(dict(passed=len(CASES), cases=len(CASES), paid_calls=0, real_audio=False)))


if __name__ == '__main__': main()
