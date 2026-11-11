"""SOURCE-ONLY ContextEngine feasibility, adapted from test_voice_checkpoint.
Official loader + public run_conversation; no core monkeypatch/private writes.
Only loopback fixed summary/model JSON; all raw artifact text is synthetic.
Run: core/venv/bin/python -B tests/probe_voice_context_engine.py --output-dir NEW_DIR
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
SCRATCH = CORE.parent / 'cache/scratch'
CASES = ('mapping', 'missing', 'wrong_owner', 'cancel', 'cancel_candidate', 'summary_failure', 'repeat', 'rollover', 'rotation')


def assert_checkpoint_rejection(failures, envelopes, expected_type):
    assert len(failures) == 1 and type(failures[0]) is expected_type, [type(e).__name__ for e in failures]
    assert not envelopes, 'rejection unexpectedly returned an envelope'


def assert_candidate_prefix(boundary, envelope):
    matches = envelope['messages'][:len(boundary['candidate'])] == boundary['candidate']
    assert matches, 'successful fixture candidate differs from final prefix'
    boundary['candidate_matches_final_prefix'] = True
    # Fixture equality only: this is not a durable host-commit receipt.


def child(case):
    assert case in CASES, case
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
            if summary and case == 'summary_failure':
                self.send_response(400); self.send_header('Content-Length','0'); self.end_headers(); return
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
                  model=dict(context_length=65536), context=dict(engine='voice-boundary-fixture'), fixture_case=case,
                  compression=dict(enabled=True, checkpoint_required=True, threshold_tokens=6000,
                                   protect_first_n=1, protect_last_n=2, min_tail_user_messages=1,
                                   proactive_prune_tokens=0, micro_compact=False, in_place=(case != 'rotation'),
                                   context_timeout_seconds=10, max_attempts=1),
                  auxiliary=dict(compression=dict(provider='custom', model='fixture', base_url=url,
                                                  api_key='offline-placeholder', context_length=65536)),
                  checkpoints=dict(enabled=False), sessions=dict(write_json_snapshots=False))
    import yaml
    (home/'config.yaml').write_text(yaml.safe_dump(config))
    fixture = home/'plugins/voice-checkpoint-fixture'; fixture.mkdir(parents=True)
    (fixture/'__init__.py').write_text('from voice_checkpoint import register # register_memory_provider\n')
    engine_fixture = home/'plugins/voice-boundary-fixture'; engine_fixture.mkdir(parents=True)
    (engine_fixture/'__init__.py').write_text('from context_engine_fixture import register # register_context_engine\n')
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
        from context_engine_fixture import BoundaryFixture
        assert type(agent.context_compressor) is BoundaryFixture
        assert agent.context_compressor.registration_count == 1
        assert isinstance(agent.context_compressor, ContextCompressor)
        clone = agent.context_compressor.clone_for_agent()
        assert clone is not agent.context_compressor and clone.boundaries == []
        clone.update_from_response(dict(prompt_tokens=999,completion_tokens=1,total_tokens=1000))
        assert agent.context_compressor.last_prompt_tokens != 999
        assert type(agent.context_compressor).update_from_response is ContextCompressor.update_from_response
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
                if frame.f_code.co_name == 'compress':
                    observation = agent.context_compressor.boundaries[-1]['durable_at_entry']
                    assert observation['state'] == 'permitted'
                    events.append('durable_permitted_observed')
                events.append(frame.f_code.co_name)
        return trace
    threading.setprofile(trace)
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
    if case == 'wrong_owner': receipt['identity'] = {**receipt['identity'], 'attempt_id':'foreign'}
    if case == 'cancel': run.cancelled.set()
    elif case != 'missing':
        with store.open() as box:
            accepted = box.acknowledge(receipt)
        assert accepted == (case not in ('wrong_owner',))
    if case == 'cancel_candidate':
        assert agent.context_compressor.candidate_ready.wait(15), (events, failures)
        events.append('hard_cancel')
        agent.interrupt(hard_cancel=True)
        agent.context_compressor.allow_return.set()
    thread.join(25)
    assert not thread.is_alive(), 'bounded worker failed to exit'
    threading.setprofile(None)
    good = case in ('mapping','repeat','rollover','rotation')
    assert before == original
    if good:
        assert not failures and len(envelopes) == 1 and envelopes[0]['completed'], (failures, envelopes)
        result = envelopes[0]
        assert result['session_id'] == notice['identity']['session_id']
        assert result['turn_id'] == notice['identity']['turn_id']
        assert run.started == (result['session_id'], result['turn_id'])
        assert events.count('model') == 1 and events.count('summary') == 1, events
        assert events.index('durable_permitted_observed') < events.index('compress') < events.index('pre_llm')
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
        if case in ('missing','wrong_owner','cancel'):
            assert events == [], (events, failures, envelopes)
            assert_checkpoint_rejection(failures, envelopes, CompressionCheckpointUnavailable)
            assert agent.context_compressor.boundaries == []
        else:
            assert not failures and len(envelopes) == 1, failures
            assert events.count('compress') == 1
            assert 'summary' in events
            assert all(not b['committed'] for b in agent.context_compressor.boundaries)
        assert not any(event == 'session:compress' for event, data in compress_events)
    boundaries = agent.context_compressor.boundaries
    if boundaries:
        boundary = boundaries[0]
        assert boundary['pre_llm_started'] is None
        assert boundary['identity'] == notice['identity']
        if case in ('cancel_candidate','summary_failure'):
            assert envelopes[0]['messages'][:len(boundary['before'])] == boundary['before']
        if case == 'cancel_candidate':
            assert boundary['candidate'] != boundary['before']
            assert envelopes[0]['messages'][:len(boundary['candidate'])] != boundary['candidate']
            assert envelopes[0]['interrupted'] and not envelopes[0]['completed']
        if case == 'summary_failure':
            assert boundary['candidate'] == boundary['before']
            assert events.count('model') == 1
        calls = [m for m in boundary['before'] if m.get('tool_calls')]
        tools = [m for m in boundary['before'] if m.get('role') == 'tool']
        assert calls[0]['tool_calls'] == original[3]['tool_calls']
        assert tools[0] == original[4]
        assert boundary['before'].index(calls[0]) < boundary['before'].index(tools[0])
        if good:
            assert boundary['before'] != boundary['candidate']
            candidate = boundary['candidate']
            kept_calls = [m for m in candidate if m.get('tool_calls')]
            kept_tools = [m for m in candidate if m.get('role') == 'tool']
            assert kept_calls and kept_tools, 'fixture must exercise preserved after tools'
            assert kept_calls[-1]['tool_calls'] == original[-3]['tool_calls']
            assert all(kept_tools[-1][k] == v for k,v in original[-2].items())
            assert kept_tools[-1].get('_compaction_tail') is True  # host-authored metadata retained
            assert candidate.index(kept_calls[-1]) < candidate.index(kept_tools[-1])
            assert_candidate_prefix(boundary, envelopes[0])
            # This is evidence about the fixture, NOT a post-commit mapping contract.
        if case == 'rotation':
            payloads = [data for event,data in compress_events if event == 'session:compress']
            assert len(payloads) == 1 and payloads[0]['in_place'] is False
            assert payloads[0]['old_session_id'] == ''
            assert envelopes[0]['session_id'] == notice['identity']['session_id']
            assert not any(x['kwargs'].get('boundary_reason') == 'compression' for x in agent.context_compressor.lifecycle)
        if case == 'repeat':
            # Second real public run, same owner: current increment must refuse
            # additional boundary, not reuse the consumed permission.
            prior_events = len(compress_events)
            prior_calls = list(events)
            agent.context_compressor.update_from_response(dict(prompt_tokens=20000,completion_tokens=30,total_tokens=20030))
            token = CURRENT.set(run)
            repeat_failures, repeat_envelopes = [], []
            try:
                try:
                    repeat_envelopes.append(agent.run_conversation('synthetic repeated request', conversation_history=original))
                except Exception as exc:
                    repeat_failures.append(exc)
            finally:
                CURRENT.reset(token)
            assert_checkpoint_rejection(repeat_failures, repeat_envelopes, CompressionCheckpointUnavailable)
            failures.extend(repeat_failures)
            assert len(boundaries) == 1
            assert len(compress_events) == prior_events
            assert events == prior_calls, 'rejected repeat invoked compression/model path'
        if case == 'rollover':
            foreign = _Run(store, hooks, Attempt(owner, 'rotated-session'))
            token = CURRENT.set(foreign)
            try:
                try: provider.on_pre_compress(notice['evidence'], require_checkpoint=True)
                except CheckpointError: pass
                else: raise AssertionError('foreign SID admitted')
            finally: CURRENT.reset(token)
    assert all(not b['committed'] for b in boundaries)
    agent.close(); server.shutdown(); server.server_close()
    server_thread.join(5)
    assert not server_thread.is_alive(), 'owned loopback server did not exit'
    with store.open() as box:
        assert box.claim('lane') is None
        assert box.get('request').original_text == 'synthetic active request'
        assert box.history('lane').messages == []  # no history v2 commit claimed
    for path in home.rglob('*'):
        if path.is_file() and b'synthetic' in path.read_bytes():
            assert path.name in ('approved.sqlite','approved.sqlite-journal'), path

    print('RESULT '+json.dumps(dict(case=case, passed=True, events=events, exceptions=[type(exc).__name__ for exc in failures],
                                    same_request=bool(envelopes and notices and envelopes[0].get('completed') and
                                        envelopes[0].get('session_id') == notices[0]['identity']['session_id'] and
                                        envelopes[0].get('turn_id') == notices[0]['identity']['turn_id']),
                                    full_mapping=False, real_audio=False, boundaries=boundaries,
                                    lifecycle=agent.context_compressor.lifecycle, host_events=compress_events, envelopes=envelopes)))


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--child': return child(sys.argv[2])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True,
                        help='New per-run evidence directory; existing paths are refused')
    args = parser.parse_args()
    artifacts = args.output_dir.resolve()
    artifacts.mkdir(mode=0o700, exist_ok=False)  # Never overwrite prior evidence.
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-context-engine-', dir=SCRATCH) as tmp:
            env = dict(HOME=tmp, HERMES_HOME=tmp, TMPDIR=tmp,
                       PATH='/data/data/com.termux/files/usr/bin', LANG='C.UTF-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONPATH=str(CORE))
            try:
                proc = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--child', case],
                                      cwd=tmp, env=env, capture_output=True, text=True, timeout=90)
            except subprocess.TimeoutExpired as exc:
                # subprocess.run kills and waits for its owned child on timeout.
                with (artifacts/(case+'.timeout.log')).open('x') as log:
                    log.write(repr(exc))
                raise
            with (artifacts/(case+'.stdout.log')).open('x') as log:
                log.write(proc.stdout)
            with (artifacts/(case+'.stderr.log')).open('x') as log:
                log.write(proc.stderr)
            if proc.returncode:
                print('FAIL',case,proc.stdout,proc.stderr); raise SystemExit(1)
            results = [x for x in proc.stdout.splitlines() if x.startswith('RESULT ')]
            assert len(results) == 1, proc.stdout
            evidence = json.loads(results[0][7:])
            assert evidence['case'] == case and evidence['passed'] is True
            with (artifacts/(case+'.json')).open('x') as output:
                json.dump(evidence, output, ensure_ascii=False, indent=2)
            print(json.dumps({k:v for k,v in evidence.items() if k not in ('boundaries','lifecycle','host_events','envelopes')}))
    print(json.dumps(dict(passed=len(CASES), cases=len(CASES), paid_calls=0, real_audio=False)))


if __name__ == '__main__': main()
