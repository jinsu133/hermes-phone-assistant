"""OFFLINE feasibility counterexamples, not a production extension or replacement.

Run with core/venv/bin/python -B this_file. Each case uses a fresh exec/HOME,
network/credential/write audit guards and synthetic text only. The real core
AIAgent and default ContextCompressor run; summary/model outputs, trigger
predicates, prompt construction and route feasibility are fixture seams. No
core source is changed. Private fixture knobs are NOT an integration proposal.
'played' is a synthetic protocol receipt, never real TTS/acoustic evidence.
"""
import copy
import json
import logging
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from unittest.mock import patch

P = Path(__file__).resolve().parents[1]
CORE = Path('/data/data/com.termux/files/home/.hermes/hermes-agent')
SCRATCH = Path('/data/data/com.termux/files/home/.hermes/cache/scratch')
CASES = ('played', 'same_turn', 'timeout', 'cancel', 'wrong_ack', 'memory_best_effort',
         'checkpoint_required', 'history_rewrite', 'ownership', 'codex_native', 'aux_observer')


def isolate():
    home = Path(os.environ['HOME']).resolve()
    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    def audit(event, args):
        if event in {'socket.connect', 'socket.bind', 'socket.getaddrinfo',
                     'subprocess.Popen', 'os.system'}:
            raise PermissionError('offline fixture')
        if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
            path = Path(os.fsdecode(args[0])).resolve()
            writing = ((isinstance(args[1], str) and any(c in args[1] for c in 'wax+'))
                       or (isinstance(args[2], int) and args[2] & (os.O_WRONLY | os.O_RDWR | os.O_CREAT)))
            if writing and not path.is_relative_to(home) and str(path) != '/dev/null':
                raise PermissionError('outside fixture write')
            if path.is_relative_to(CORE.parent) and not path.is_relative_to(CORE) and not path.is_relative_to(home):
                raise PermissionError('live profile read')
            if path.name in {'.env', 'auth.json', 'auth.json.lock'}:
                raise PermissionError('credential read')
    sys.addaudithook(audit)
    # No stock worker manifest is changed: this is a distinct temporary probe.
    (home/'config.yaml').write_text('memory:\n  memory_enabled: false\n  user_profile_enabled: false\ncompression:\n  enabled: true\ncheckpoints:\n  enabled: false\nsessions:\n  write_json_snapshots: false\n')
    sys.path[:0] = [str(P), str(CORE)]
    # Prevent run_agent's import-time checkout recovery from mutating the RO core.
    import types
    sys.modules['hermes_cli.main'] = types.ModuleType('hermes_cli.main')
    return home


def history_case(case, home):
    from voice_history import HistoryStore, HistoryError
    from voice_dispatch import DispatchHooks, Attempt, _Run, CURRENT
    from dataclasses import replace
    class Registry:
        def register_hook(self, *args): pass
    store = HistoryStore(home, 'fixture', home/'approved.sqlite')
    with store.open() as box:
        box.receive('r1', 'synthetic original one', 'lane')
        first = box.claim('lane'); h1 = box.prepare_history(first)
        assert box.bind_start(first, 's1', 't1')
        originals = [{'role':'user', 'content':'synthetic original one'},
                     {'role':'assistant', 'content':'synthetic reply one'}]
        assert box.complete_history(first, 's1', 't1', h1, originals)
        box.receive('r2', 'synthetic original two', 'lane')
        owner = box.claim('lane'); handoff = box.prepare_history(owner)
    hooks = DispatchHooks(Registry())
    run = _Run(store, hooks, Attempt(owner, 's2'))
    run.handoff = handoff
    token = CURRENT.set(run)
    try:
        hooks.pre_llm_call(session_id='s2', turn_id='t2')
        if case == 'ownership':
            assert hooks.pre_tool_call(session_id='s2', turn_id='t2') is None
            assert hooks.pre_tool_call(session_id='rotated', turn_id='t2')['action'] == 'block'
            assert hooks.pre_tool_call(session_id='s2', turn_id='t2')['action'] == 'block'
            run.cancelled.set()
        else:
            compacted = [{'role':'user','content':'synthetic compacted predecessor'},
                         {'role':'assistant','content':'synthetic compacted answer'},
                         {'role':'user','content':'synthetic original two'},
                         {'role':'assistant','content':'synthetic reply two'}]
            with store.open() as box:
                try: box.complete_history(owner, 's2', 't2', handoff, compacted)
                except HistoryError as exc: assert str(exc) == 'history rewrite refused'
                else: raise AssertionError('compacted prefix accepted')
                assert box.get('r2').state == 'started'
                assert box.history('lane').messages == originals
            hooks.post_llm_call(session_id='s2', turn_id='t2')
            outcome = run.finish(dict(session_id='s2', turn_id='t2', completed=True,
                                      turn_exit_reason='text_response(stop)', messages=compacted))
            assert not outcome.engine_turn_verified and outcome.state == 'unknown_outcome'
        with store.open() as box:
            assert box.history('lane').messages == originals
            assert box.get('r1').original_text == 'synthetic original one'
            assert box.get('r2').original_text == 'synthetic original two'
            # No duplicate admission/replay after an unresolved attempt.
            box.receive('r2', 'synthetic original two', 'lane')
            assert box.claim('lane') is None
            if case == 'ownership':
                for wrong in (replace(owner, attempt_id='wrong'), replace(owner, lane='wrong')):
                    try: box.complete_history(wrong, 's2', 't2', handoff, originals)
                    except HistoryError: pass
                    else: raise AssertionError('foreign owner accepted')
        return {'case':case, 'originals_preserved':True, 'replay':False,
                'transformed_prefix_accepted':False if case == 'history_rewrite' else None}
    finally:
        CURRENT.reset(token)


def core_case(case, home):
    from run_agent import AIAgent
    from agent.context_compressor import ContextCompressor
    from agent.conversation_compression import compress_context, COMPACTION_STATUS
    from hermes_cli.plugins import PluginContext, PluginManifest, get_plugin_manager
    from openai.types.chat import ChatCompletion
    from types import SimpleNamespace
    events = []
    identity = ('fixture-profile', 'lane', 'request', 'attempt', 'fixture-session', 'turn')
    issued, release = threading.Event(), threading.Event()
    expected_ack = (*identity, 'notice-1', 'played')
    receipts = []
    a = None
    def status(kind, text):
        if text != COMPACTION_STATUS: return
        if 'notice-issued' in events: return  # fixture per-attempt dedupe only
        events.append('notice-issued'); issued.set()
        if case in ('played', 'timeout', 'cancel', 'wrong_ack'):
            if not release.wait(3): raise AssertionError('fixture release missing')
            if case == 'cancel':
                assert a.interrupt(hard_cancel=True)
                events.append('cancel-requested')
                return
            if receipts != [expected_ack]:
                events.append('ack-rejected' if receipts else 'ack-timeout')
                raise TimeoutError('synthetic missing or mismatched played ACK')
            events.append('played-ACK')
    a = AIAgent(model='fixture', provider='openai', api_mode='chat_completions',
                api_key='offline-placeholder', base_url='https://offline.invalid/v1',
                enabled_toolsets=[], session_id=identity[4], session_db=None,
                save_trajectories=False, verbose_logging=False, quiet_mode=True,
                skip_memory=True, skip_context_files=True, load_soul_identity=False,
                skip_background_review=True, checkpoints_enabled=False,
                status_callback=status, event_callback=lambda event, data: events.append(event),
                max_iterations=2)
    assert isinstance(a.context_compressor, ContextCompressor)
    # Bypass ONLY lazy route discovery; no provider/auth/catalog access permitted.
    a._compression_feasibility_checked = True
    a._invalidate_system_prompt = lambda: None
    a._build_system_prompt = lambda *args: 'synthetic system'
    a._cached_system_prompt = 'synthetic system'
    a.suppress_status_output = True
    compressor = a.context_compressor
    compressor.protect_first_n = 1
    compressor.protect_last_n = 3
    compressor.tail_token_budget = 64
    before = [{'role':'user' if i % 2 == 0 else 'assistant',
               'content':f'synthetic message {i} ' + ('synthetic filler ' * 120)} for i in range(20)]
    before += [{'role':'user','content':'synthetic active request'}]
    original = copy.deepcopy(before)
    def summary(*args, **kwargs):
        # Preserve real cancellation semantics at the replaced summary seam.
        if compressor._compression_cancelled():
            from agent.auxiliary_client import AuxiliaryExplicitCancellation
            raise AuxiliaryExplicitCancellation()
        events.append('summary-fixture')
        return 'Synthetic summary, not model output.'
    def phase1(*args, **kwargs):
        events.append('context-reduction-entered')
        return original_prune(*args, **kwargs)
    original_prune = compressor._prune_old_tool_results
    if case == 'same_turn':
        # One real public run_conversation, including its preflight and finalizer.
        # Force only the fixture threshold; retain the built-in compressor.
        from agent.relay_runtime import current_turn
        ids = []
        def turn_status(kind, text):
            if text != COMPACTION_STATUS: return
            if 'notice-issued' in events: return
            turn = current_turn()
            # This read is diagnostic, not a supported replacement for request
            # ownership. An absent relay turn must be reported, not guessed.
            ids.append((turn.lease.session_id, turn.turn_id) if turn is not None else (a.session_id, None))
            events.append('notice-issued'); issued.set()
            assert release.wait(3)
            assert receipts == [(*ids[0], 'notice-1', 'played')]
            events.append('played-ACK')
        a.status_callback = turn_status
        compressor.threshold_tokens = 5000
        a.max_compression_attempts = 1
        manager = get_plugin_manager()
        ctx = PluginContext(PluginManifest(name='same-turn-offline', path=str(P)), manager)
        hooks = []
        def pre(**kw):
            events.append('pre_llm_call'); ids.append((kw['session_id'], kw['turn_id']))
        def post(**kw):
            events.append('post_llm_call'); ids.append((kw['session_id'], kw['turn_id']))
        def model(**kw):
            events.append('model-fixture')
            assert any('synthetic active request' in str(m.get('content')) for m in kw['request']['messages'])
            return ChatCompletion.model_validate(dict(id='fixture', object='chat.completion', created=0,
                model='fixture', choices=[dict(index=0,message=dict(role='assistant',content='synthetic completed'),finish_reason='stop')],
                usage=dict(prompt_tokens=1,completion_tokens=1,total_tokens=2)))
        hooks.append(ctx.register_hook('pre_llm_call', pre))
        hooks.append(ctx.register_hook('post_llm_call', post))
        hooks.append(ctx.register_middleware('llm_execution', model))
        result, failure = [], []
        def run_turn():
            try: result.append(a.run_conversation('synthetic active request', conversation_history=before[:-1]))
            except Exception as exc: failure.append(type(exc).__name__)
        try:
            with patch.object(compressor, '_generate_summary', side_effect=summary), \
                 patch.object(compressor, '_prune_old_tool_results', side_effect=phase1), \
                 patch('agent.turn_context._should_run_preflight_estimate', return_value=True), \
                 patch.object(compressor, 'should_compress', side_effect=lambda *args: 'summary-fixture' not in events), \
                 patch.object(compressor, 'should_defer_preflight_to_real_usage', return_value=False), \
                 patch.object(compressor, '_feasibility_skip', return_value=False):
                thread = threading.Thread(target=run_turn); thread.start()
                assert issued.wait(8), (events, failure, a.compression_enabled, compressor.threshold_tokens,
                                        compressor.compression_count, compressor._last_compress_aborted)
                assert events == ['notice-issued']
                receipts.append((*ids[0], 'notice-1', 'played')); release.set()
                thread.join(15)
                assert not thread.is_alive()
            assert not failure and result, failure
            envelope = result[0]
            assert len(ids) == 3 and ids[0] == ids[1] == ids[2], ids
            assert (envelope['session_id'], envelope['turn_id']) == ids[0]
            assert envelope['completed'] and not envelope.get('failed')
            assert len(envelope['messages']) < len(original)
            assert events == ['notice-issued','played-ACK','context-reduction-entered','summary-fixture',
                              'session:compress','pre_llm_call','model-fixture','post_llm_call'], events
            assert before == original
            return {'case':case,'events':events,'same_original_turn_proved':True,
                    'same_session':True,'model_calls':1,'history_commit_supported':False}
        finally:
            release.set()
            for handle in reversed(hooks): handle.dispose()
            a.close()
    if case in ('memory_best_effort', 'checkpoint_required'):
        def memory(*args, **kwargs):
            events.append('memory-notice-timeout')
            raise TimeoutError('synthetic missing played ACK')
        # Contract stub, not a registered/replaced memory provider.
        a._memory_manager = SimpleNamespace(on_pre_compress=memory,
            supports_pre_compress_checkpoint=lambda version: True,
            on_session_switch=lambda *args, **kwargs: None)
        a.compression_checkpoint_required = case == 'checkpoint_required'
    if case == 'aux_observer':
        from agent.auxiliary_hooks import run_with_aux_hooks
        manager = get_plugin_manager()
        ctx = PluginContext(PluginManifest(name='aux-offline', path=str(P)), manager)
        def observer(**kw):
            events.append('aux-notice-timeout')
            raise TimeoutError('synthetic played ACK timeout')
        handle = ctx.register_hook('pre_auxiliary_call', observer)
        try:
            result = run_with_aux_hooks(lambda: events.append('aux-model-fixture') or None,
                aux_task='compression', metadata={'api_request_id':'fixture'},
                client=SimpleNamespace(base_url='https://offline.invalid/v1'),
                kwargs={'messages':[{'role':'user','content':'synthetic'}]},
                provider='openai', model='fixture', api_mode='chat_completions')
            assert result is None and events == ['aux-notice-timeout','aux-model-fixture'], events
            return {'case':case, 'events':events, 'fail_closed':False}
        finally:
            handle.dispose(); a.close()
    if case == 'codex_native':
        a.api_mode = 'codex_app_server'
        a.codex_app_server_auto_compaction = 'native'
        a._codex_session = SimpleNamespace(compact_thread=lambda: events.append('native-compact'))
        result, _ = compress_context(a, before, 'synthetic system', force=False)
        assert result == original and events == []
        a._codex_session = None
        a.close()
        return {'case':case,'hermes_notice':False,'hermes_compaction':False,
                'native_provider_behavior':'not exercised'}
    results, errors = [], []
    def work():
        try: results.append(compress_context(a, before, 'synthetic system', force=True, approx_tokens=20000))
        except Exception as exc: errors.append(type(exc).__name__)
    with patch.object(compressor, '_generate_summary', side_effect=summary), \
         patch.object(compressor, '_prune_old_tool_results', side_effect=phase1):
        thread = threading.Thread(target=work)
        thread.start()
        assert issued.wait(8), 'no start status'
        if case in ('played', 'timeout', 'cancel', 'wrong_ack'):
            # Deterministic proof: while ACK is pending no prune/summary has run.
            assert events == ['notice-issued'] and before == original
            if case == 'played': receipts.append(expected_ack)
            if case == 'wrong_ack': receipts.append((*identity[:-1], 'foreign-turn', 'notice-1', 'played'))
            release.set()
        thread.join(12)
        assert not thread.is_alive(), 'bounded core fixture did not join'
    if case == 'checkpoint_required':
        assert errors == ['CompressionCheckpointUnavailable'], errors
        assert events == ['notice-issued', 'memory-notice-timeout'], events
        assert before == original
    elif case == 'cancel':
        assert not errors, errors
        assert 'session:compress' not in events, events
        assert results[0][0] == original
        # The mocked summary bypasses provider interrupt checks. Its entry is
        # not proof of an actual post-cancel model call; the real core rolls back.
        # A hard stop can enter the compressor/prune phase before cancellation
        # reaches its summary choke point. Do not mislabel this a pre-reduction gate.
    else:
        assert not errors, errors
        after = results[0][0]
        assert len(after) < len(original), (len(after), len(original), events)
        assert events.index('context-reduction-entered') < events.index('summary-fixture') < events.index('session:compress')
        assert a.session_id == identity[4]
        if case == 'played':
            assert events[:3] == ['notice-issued','played-ACK','context-reduction-entered'], events
        else:
            assert 'played-ACK' not in events, events
    # A separate public turn proves the reduced history is consumable, NOT that
    # the same original engine turn resumed. Same-turn acceptance remains NO-GO.
    continuation = False
    if case == 'played':
        manager = get_plugin_manager()
        ctx = PluginContext(PluginManifest(name='compaction-offline', path=str(P)), manager)
        def model(**kwargs):
            events.append('model-fixture')
            return ChatCompletion.model_validate(dict(id='fixture', object='chat.completion', created=0,
                model='fixture', choices=[dict(index=0, message=dict(role='assistant',content='synthetic continuation'), finish_reason='stop')],
                usage=dict(prompt_tokens=1,completion_tokens=1,total_tokens=2)))
        handle = ctx.register_middleware('llm_execution', model)
        try:
            envelope = a.run_conversation('synthetic follow-up', conversation_history=results[0][0][:-1])
            assert envelope['session_id'] == identity[4]
            assert envelope.get('turn_id')
            assert events.count('model-fixture') == 1
            continuation = True
        finally: handle.dispose()
    a._memory_manager = None  # fixture stub has no persistence lifecycle
    a.close()
    return {'case':case,'events':events,'errors':errors,'same_session':a.session_id == identity[4],
            'separate_turn_consumes_compacted_history':continuation,
            'same_original_turn_proved':False}


def child(case):
    home = isolate()
    result = history_case(case, home) if case in ('history_rewrite','ownership') else core_case(case, home)
    for path in home.rglob('*'):
        if path.is_file() and b'synthetic' in path.read_bytes():
            assert path.name in {'approved.sqlite', 'approved.sqlite-wal'}, path.name
    result['synthetic_marker_outside_approved_store'] = False
    print('RESULT ' + json.dumps(result, sort_keys=True))


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--child':
        child(sys.argv[2]); return
    passed = 0
    for case in CASES:
        with tempfile.TemporaryDirectory(prefix='voice-compaction-', dir=SCRATCH) as tmp:
            env = {'HOME':tmp,'HERMES_HOME':tmp,'TMPDIR':tmp,
                   'PATH':'/data/data/com.termux/files/usr/bin','LANG':'C.UTF-8',
                   'PYTHONDONTWRITEBYTECODE':'1','PYTHONPATH':str(CORE)}
            proc = subprocess.run([sys.executable,'-B',str(Path(__file__).resolve()),'--child',case],
                                  cwd=tmp,env=env,text=True,capture_output=True,timeout=50)
            if proc.returncode:
                print('FAIL',case,proc.stdout,proc.stderr); raise SystemExit(1)
            lines = [line for line in proc.stdout.splitlines() if line.startswith('RESULT ')]
            assert len(lines) == 1, proc.stdout
            print(lines[0]); passed += 1
    print(json.dumps({'passed':passed,'cases':len(CASES),'paid_calls':0,'real_audio':False}))


if __name__ == '__main__': main()
