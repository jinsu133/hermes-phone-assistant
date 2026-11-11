"""Isolated single-boundary transaction model. NOT a production history API.

Owns only fixture recon_* tables. Does not complete voice requests, relax v1,
write core state or infer durable host commit. All payloads must be synthetic.
"""
import copy
import hashlib
import json
import sqlite3


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(encode(value).encode()).hexdigest()


class Refused(RuntimeError):
    pass


class Journal:
    def __init__(self, path):
        self.path = path

    def db(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.execute('PRAGMA synchronous=FULL')
        return db

    @staticmethod
    def load(db):
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='recon_fixture'").fetchone():
            return None
        row = db.execute('SELECT payload FROM recon_fixture WHERE id=1').fetchone()
        return json.loads(row[0]) if row else None

    def read(self):
        db = self.db()
        try:
            return self.load(db)
        finally:
            db.close()

    def change(self, update, crash=None):
        db = self.db()
        try:
            db.execute('BEGIN IMMEDIATE')
            row = self.load(db)
            if row is None:
                raise Refused('incomplete fixture initialization')
            if update(row, db) is False:
                db.execute('ROLLBACK')
                return
            db.execute('UPDATE recon_fixture SET payload=? WHERE id=1', (encode(row),))
            if crash == 'before_commit':
                import os
                os._exit(73)
            db.execute('COMMIT')
            if crash == 'after_commit':
                import os
                os._exit(74)
        finally:
            db.close()

    def prepare(self, owner, sid, history, user, *, crash=None):
        db = self.db()
        try:
            db.execute('BEGIN IMMEDIATE')
            db.execute('CREATE TABLE recon_fixture(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)')
            if crash == 'after_schema':
                import os
                os._exit(75)
            row = dict(state='prepared', owner=owner, sid=sid, original=copy.deepcopy(history),
                       user=user, base_digest=digest(history), active=copy.deepcopy(history),
                       version=0, boundary=None, result=None, ledger=copy.deepcopy(history),
                       plugin_completed=False, host_commit_proven=False)
            db.execute('INSERT INTO recon_fixture VALUES(1,?)', (encode(row),))
            db.execute('COMMIT')
        finally:
            db.close()

    def enter(self, identity, before, notice):
        def update(r, db):
            if r['state'] != 'prepared' or identity['session_id'] != r['sid']:
                raise Refused('single boundary or SID')
            if any(identity[k] != v for k, v in r['owner'].items()):
                raise Refused('owner')
            receipt = notice['receipt']
            if (notice['state'] != 'permitted' or notice['identity'] != identity or
                receipt['identity'] != identity or receipt['event_id'] != notice['event_id'] or
                receipt['content_digest'] != notice['content_digest'] or
                receipt['outcome'] != 'played' or receipt['synthetic'] is not True):
                raise Refused('permission')
            if (len(before) != len(r['original'])+1 or before[:-1] != r['original'] or
                before[-1].get('role') != 'user' or before[-1].get('content') != r['user'] or
                set(before[-1]) - {'role', 'content', 'timestamp'}):
                raise Refused('not a pre-first-model boundary')
            r['boundary'] = dict(identity=identity, before=copy.deepcopy(before),
                                 before_digest=digest(before), notice=copy.deepcopy(notice), candidate=None)
            # This plugin receipt LINKS the full transcript to an earlier normalized
            # ACK. It does NOT claim that the ACK signed this full transcript digest.
            r['boundary']['link_digest'] = digest(dict(identity=identity,
                before_digest=digest(before), event_id=notice['event_id'],
                ack_digest=notice['content_digest']))
            r['state'] = 'entered'
        self.change(update)

    def candidate(self, messages):
        def update(r, db):
            if r['state'] != 'entered': raise Refused('candidate order')
            r['boundary']['candidate'] = copy.deepcopy(messages)
            r['boundary']['candidate_digest'] = digest(messages)
            r['state'] = 'candidate'
        self.change(update)

    def finish(self, result, binding, *, joined, closed, cancelled=False, crash=None):
        def update(r, db):
            if r['state'] != 'candidate': raise Refused('missing capture or closed attempt')
            b = r['boundary']
            if not joined or not closed: raise Refused('lifetime')
            if digest(b['before']) != b['before_digest'] or digest(b['candidate']) != b['candidate_digest']:
                raise Refused('capture digest')
            if digest(r['original']) != r['base_digest'] or r['active'] != r['original'] or r['version'] != 0:
                raise Refused('base CAS')
            if cancelled or not isinstance(result, dict): raise Refused('cancelled or missing result')
            if result.get('completed') is not True or any(result.get(k) for k in
                    ('failed', 'partial', 'interrupted', 'error', 'compression_deferred', 'compression_exhausted')):
                raise Refused('not completed')
            sid, turn = b['identity']['session_id'], b['identity']['turn_id']
            expected_binding = dict(owner=r['owner'], session_id=sid, turn_id=turn, state='started')
            if binding != expected_binding or (result.get('session_id'), result.get('turn_id')) != (sid, turn):
                raise Refused('durable binding')
            stored = db.execute('SELECT lane,attempt_id,state,sid,turn_id,original_text FROM requests WHERE request_id=?',
                                (r['owner']['request_id'],)).fetchone()
            if stored != (r['owner']['lane'],r['owner']['attempt_id'],'started',sid,turn,r['user']):
                raise Refused('request CAS')
            if not str(result.get('turn_exit_reason', '')).startswith('text_response('):
                raise Refused('exit reason')
            messages, candidate = result.get('messages'), b['candidate']
            if not isinstance(messages, list) or not candidate or messages[:len(candidate)] != candidate:
                raise Refused('candidate final mismatch')
            if candidate == b['before']: raise Refused('no reduction')
            idx = result.get('current_turn_user_idx')
            if (type(idx) is not int or idx != len(candidate)-1 or
                candidate[idx] != {**b['before'][-1], '_compaction_tail': True}):
                raise Refused('current request anchor')
            suffix = messages[len(candidate):]
            # Deliberately narrower than general tool turns: one final text only.
            if (len(suffix) != 1 or not isinstance(suffix[0], dict) or
                suffix[0].get('role') != 'assistant' or
                suffix[0].get('tool_calls') or not isinstance(suffix[0].get('content'), str) or
                suffix[0]['content'] != result.get('final_response')):
                raise Refused('unproven current-turn delta')
            r.update(result=copy.deepcopy(result), active=copy.deepcopy(messages), version=1,
                     ledger=copy.deepcopy(b['before']+suffix), state='plugin_committed', plugin_completed=True)
        self.change(update, crash=crash)

    def reject(self, reason, *, proven_abort=False):
        """Terminal state AND reason are immutable; exact repeats are no-ops."""
        def update(r, db):
            if r['state'] == 'plugin_committed': raise Refused('terminal commit')
            target = 'aborted' if proven_abort else 'unknown'
            if r['state'] in ('aborted', 'unknown'):
                if r['state'] == target and r.get('reason') == reason:
                    return False
                raise Refused('terminal conflict')
            r['state'] = target
            r['reason'] = reason
        self.change(update)

    def recover(self):
        def update(r, db):
            if r['state'] in ('plugin_committed', 'aborted', 'unknown'):
                return False
            r.update(state='unknown', reason='exclusive restart; no automatic replay')
        self.change(update)
