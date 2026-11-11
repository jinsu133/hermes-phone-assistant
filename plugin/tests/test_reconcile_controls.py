"""Negative controls use recorded real public output, not a simulated engine.
DB rows below are synthetic transaction-model fixtures, NOT new core runs.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from reconcile_protocol_fixture import Journal, Refused, encode


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text())
    source = evidence['journal']
    passed = []

    def exercise(name, reason=None, mutate_result=None, mutate_row=None, mutate_binding=None,
                 options=None, request_sql=None, action='finish'):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
            journal = Journal(Path(tmp)/'control.sqlite')
            initial = copy.deepcopy(source)
            initial.update(state='candidate', result=None, active=initial['original'],
                           version=0, ledger=initial['original'], plugin_completed=False)
            if mutate_row: mutate_row(initial)
            result = copy.deepcopy(evidence['envelopes'][0])
            if mutate_result: result = mutate_result(result)
            binding = copy.deepcopy(evidence['binding'])
            if mutate_binding: mutate_binding(binding)
            db = journal.db()
            db.execute('CREATE TABLE recon_fixture(id INTEGER PRIMARY KEY, payload TEXT)')
            db.execute('INSERT INTO recon_fixture VALUES(1,?)',(encode(initial),))
            db.execute('CREATE TABLE requests(request_id TEXT, lane TEXT, attempt_id TEXT, state TEXT, sid TEXT, turn_id TEXT, original_text TEXT)')
            owner=initial['owner']; b=source['boundary']['identity']
            db.execute('INSERT INTO requests VALUES(?,?,?,?,?,?,?)',(owner['request_id'],owner['lane'],owner['attempt_id'],'started',b['session_id'],b['turn_id'],initial['user']))
            if request_sql: db.execute(request_sql)
            db.close()
            kwargs=dict(joined=True,closed=True); kwargs.update(options or {})
            try:
                if action == 'enter':
                    journal.enter(b,source['boundary']['before'],source['boundary']['notice'])
                else:
                    journal.finish(result,binding,**kwargs)
            except Exception as exc:
                # An unrelated error, subclass or generic incomplete result CANNOT pass.
                assert reason is not None and type(exc) is Refused and str(exc)==reason, (name,type(exc),str(exc))
                assert journal.read() == initial, name
                if initial['state'] in ('aborted', 'unknown'):
                    # Existing terminal evidence is immutable, not reclassified.
                    journal.recover()
                    assert journal.read() == initial
                else:
                    journal.reject(name)
                after=journal.read()
                assert after['state']=='unknown' and not after['plugin_completed']
                assert after['active']==initial['active'] and after['ledger']==initial['ledger']
                journal.recover(); assert journal.read()==after
            else:
                assert reason is None, ('negative accepted',name)
                assert journal.read()['plugin_completed'] is True
                assert journal.read()['ledger'][:len(initial['original'])] == initial['original']
            passed.append(name)

    def set_result(key,value):
        def mutate(r): r[key]=value; return r
        return mutate

    exercise('real_public_output_positive')
    exercise('final_prefix_mismatch','candidate final mismatch',mutate_result=lambda r: {**r,'messages':[dict(role='user',content='foreign')]+r['messages'][1:]})
    exercise('missing_final','cancelled or missing result',mutate_result=lambda r: None)
    exercise('wrong_sid','durable binding',mutate_result=set_result('session_id','foreign'))
    exercise('wrong_turn','durable binding',mutate_result=set_result('turn_id','foreign'))
    exercise('missing_pre_binding','durable binding',mutate_binding=lambda b:b.update(state='dispatching',session_id=None,turn_id=None))
    exercise('wrong_owner','durable binding',mutate_binding=lambda b:b['owner'].update(attempt_id='foreign'))
    exercise('stale_durable_owner','request CAS',request_sql="UPDATE requests SET attempt_id='foreign'")
    exercise('interrupted','not completed',mutate_result=set_result('interrupted',True))
    exercise('failed','not completed',mutate_result=set_result('failed',True))
    exercise('incomplete','not completed',mutate_result=set_result('completed',False))
    exercise('late_cancel','cancelled or missing result',options=dict(cancelled=True))
    exercise('unjoined','lifetime',options=dict(joined=False))
    exercise('close_unconfirmed','lifetime',options=dict(closed=False))
    exercise('missing_required_capture','missing capture or closed attempt',mutate_row=lambda r:r.update(state='prepared',boundary=None))
    exercise('before_digest_corruption','capture digest',mutate_row=lambda r:r['boundary'].update(before_digest='foreign'))
    exercise('candidate_digest_corruption','capture digest',mutate_row=lambda r:r['boundary'].update(candidate_digest='foreign'))
    exercise('base_version_changed','base CAS',mutate_row=lambda r:r.update(version=2))
    exercise('wrong_user_anchor','current request anchor',mutate_result=set_result('current_turn_user_idx',0))
    exercise('unproven_extra_delta','unproven current-turn delta',mutate_result=lambda r:{**r,'messages':r['messages']+[dict(role='assistant',content='extra')]})
    exercise('duplicate_boundary','single boundary or SID',action='enter')
    exercise('terminal_attempt','missing capture or closed attempt',mutate_row=lambda r:r.update(state='unknown'))

    # The actual entrypoint must reject existing output directories before child work.
    with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as tmp:
        target=Path(tmp)/'existing';target.mkdir();(target/'sentinel').write_text('unchanged')
        script=Path(__file__).with_name('probe_voice_reconcile.py')
        result=subprocess.run([sys.executable,'-B',str(script),'--output-dir',str(target)],capture_output=True,text=True,timeout=15)
        assert result.returncode==1 and 'FileExistsError' in result.stderr
        assert [p.name for p in target.iterdir()]==['sentinel'] and (target/'sentinel').read_text()=='unchanged'
        passed.append('existing_output_refused')
        # Deliberately mismatch one entry invariant, even if this driver's HOME
        # already has a valid voice-reconcile- prefix. Keep every path private.
        unsafe_env = dict(os.environ, HERMES_HOME=str(Path(tmp)/'invalid-home'))
        result=subprocess.run([sys.executable,'-B',str(script),'--child','success'],env=unsafe_env,capture_output=True,text=True,timeout=15)
        assert result.returncode==1 and 'AssertionError' in result.stderr, (result.returncode,result.stdout,result.stderr)
        assert 'RESULT ' not in result.stdout and not (Path(tmp)/'invalid-home').exists()
        passed.append('unsafe_child_entry_refused')
    print(json.dumps(dict(passed=len(passed),cases=passed,core_runs=0)))

if __name__=='__main__': main()
