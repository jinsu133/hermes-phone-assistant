"""Own one official API run; never substitute queue acceptance for termination.

Only opaque IDs and states are journaled. Input/output/provider credentials remain
in memory. A lost submit receipt is an unresolved external outcome, not a retry.
"""
from __future__ import annotations

from contextvars import copy_context
import fcntl
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile
import threading
import time
from .voice_run_ids import valid_session_id

TERMINAL = frozenset({'completed', 'failed', 'cancelled', 'interrupted'})
FINISHED = frozenset({'completed', 'failed', 'cancelled', 'interrupted', 'rejected', 'not_started'})
_ID = re.compile(r'[A-Za-z0-9_-]{1,128}\Z')
_RUN_ID = re.compile(r'run_[a-f0-9]{32}\Z')
_ERROR = re.compile(r'RUNS_[A-Z0-9_]{1,64}\Z')


class RunOwnerError(RuntimeError):
    pass


def _identity(value):
    if type(value) is not str or _ID.fullmatch(value) is None:
        raise RunOwnerError('RUNS_ID_INVALID')
    return value


def _private_fd(path, flags):
    fd = os.open(path, flags | os.O_NOFOLLOW, 0o600)
    try:
        s = os.fstat(fd)
        if not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid() or s.st_nlink != 1 or s.st_mode & 0o077:
            raise RunOwnerError('RUNS_STATE_PRIVATE_REQUIRED')
        return fd
    except BaseException:
        os.close(fd)
        raise


class RunJournal:
    """Single retained flock protects an atomic, bounded operation journal."""
    def __init__(self, home):
        home = Path(home)
        parent = home / 'state'
        self.root = parent / 'phone-runs'
        self.path = self.root / 'active.json'
        self.fd = None
        if parent.is_symlink() or self.root.is_symlink():
            raise RunOwnerError('RUNS_STATE_PRIVATE_REQUIRED')
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        s = self.root.stat()
        if s.st_uid != os.getuid() or s.st_mode & 0o077:
            raise RunOwnerError('RUNS_STATE_PRIVATE_REQUIRED')
        fd = _private_fd(self.root / 'owner.lock', os.O_RDWR | os.O_CREAT)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            s = os.fstat(fd)
            self._lock_identity = (s.st_dev, s.st_ino)
            self.fd = fd
            self.record = self.read()
        except BaseException:
            self.fd = None
            os.close(fd)
            raise RunOwnerError('RUNS_STATE_BUSY_OR_INVALID') from None

    def retained_lock_valid(self, expected_fd):
        """Validate/reaffirm current exclusivity under the caller's owner gate.

        Reaffirming may reacquire an unlocked retained fd; it does not prove
        uninterrupted historical ownership. Never open/create a replacement,
        unlock, or close a descriptor here, including on validation failure.
        """
        if expected_fd is None or self.fd != expected_fd:
            return False
        try:
            held = os.fstat(expected_fd)
            named = (self.root / 'owner.lock').lstat()
            for s in (held, named):
                if (not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid()
                        or s.st_nlink != 1 or s.st_mode & 0o077
                        or (s.st_dev, s.st_ino) != self._lock_identity):
                    return False
            fcntl.flock(expected_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            return False

    def read(self):
        try:
            fd = _private_fd(self.path, os.O_RDONLY)
        except FileNotFoundError:
            return None
        with os.fdopen(fd, 'rb') as stream:
            raw = stream.read(4097)
        if len(raw) > 4096:
            raise RunOwnerError('RUNS_STATE_INVALID')
        try:
            record = json.loads(raw)
            if (type(record) is not dict or set(record) != {'version', 'request_id', 'session_generation',
                    'session_id', 'run_id', 'idempotency_key', 'state'} or type(record['version']) is not int
                    or record['version'] != 1):
                raise ValueError()
            for key in ('request_id', 'session_generation', 'idempotency_key'):
                _identity(record[key])
            if not valid_session_id(record['session_id']):
                raise ValueError()
            if record['run_id'] is not None and (type(record['run_id']) is not str or not _RUN_ID.fullmatch(record['run_id'])):
                raise ValueError()
            if record['state'] not in FINISHED | {'submitting', 'running', 'stopping', 'unknown', 'delivery_unknown'}:
                raise ValueError()
            return record
        except (ValueError, TypeError, KeyError, RunOwnerError):
            raise RunOwnerError('RUNS_STATE_INVALID') from None

    def write(self, record):
        if self.fd is None:
            raise RunOwnerError('RUNS_STATE_CLOSED')
        data = json.dumps(record, separators=(',', ':'), allow_nan=False).encode()
        if len(data) > 4096:
            raise RunOwnerError('RUNS_STATE_INVALID')
        fd, name = tempfile.mkstemp(prefix='.active-', dir=self.root)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, self.path)
            directory = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
            self.record = dict(record)
        finally:
            try:
                os.unlink(name)
            except FileNotFoundError:
                pass

    def preserve_restored(self, record):
        """One private audit slot: never overwrite evidence or grow without bound.

        A different or partial prior audit refuses recovery; an operator must
        archive it explicitly before a later, unrelated recovery can proceed.
        """
        if self.fd is None:
            raise RunOwnerError('RUNS_STATE_CLOSED')
        data = json.dumps(record, separators=(',', ':'), allow_nan=False).encode()
        if len(data) > 4096:
            raise RunOwnerError('RUNS_STATE_INVALID')
        path = self.root / 'restored-interrupted.audit.json'
        try:
            fd = _private_fd(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        except FileExistsError:
            fd = _private_fd(path, os.O_RDONLY)
            with os.fdopen(fd, 'rb') as stream:
                if stream.read(4097) != data:
                    raise RunOwnerError('RUNS_RECOVERY_AUDIT_CONFLICT')
                os.fsync(stream.fileno())
        else:
            # Leave partial evidence on failure; never replace or unlink it.
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        directory = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)

    def close(self):
        if self.fd is not None:
            fd, self.fd = self.fd, None
            os.close(fd)


class RunsOwner:
    """One joined worker per phone request. Network calls never hold the owner lock.

    Callbacks are plugin-owned adapters, not model output or authority. The terminal
    callback must independently check current request/turn/device identity.
    """
    def __init__(self, home, client, *, on_terminal, on_failure, on_accepted=None,
                 poll_seconds=.3, deadline_seconds=600, on_settled=None):
        for value in (poll_seconds, deadline_seconds):
            if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
                raise RunOwnerError('RUNS_BOUNDS_INVALID')
        self.client = client
        self.on_terminal, self.on_failure, self.on_accepted = on_terminal, on_failure, on_accepted
        self.on_settled = on_settled
        self.poll_seconds, self.deadline_seconds = poll_seconds, deadline_seconds
        self._lock = threading.RLock()
        self.journal = RunJournal(home)
        self._record = self.journal.record
        self._thread = None
        self._cancel = threading.Event()
        self._closed = False
        self._blocked = bool(self._record and self._record['state'] not in FINISHED)
        # Provenance is constructor-local, never inferred from a later live state.
        self._restored_record = (self._record if self._record and
                                 self._record['state'] in {'running', 'stopping'} else None)
        self._restored_snapshot = dict(self._restored_record) if self._restored_record else None
        self._reconciling = False

    def reconcile_restored_interrupted(self):
        """Settle only the original disk-restored exact run, without delivery.

        One read-only status request runs outside the owner gate. Rejections do
        not alter the record; audit/persistence errors propagate with admission
        still blocked and the owner/flock retained by the caller.
        """
        with self._lock:
            original = self._restored_record
            snapshot = self._restored_snapshot
            journal, client = self.journal, self.client
            fd = journal.fd
            if (self._closed or self._reconciling or self._thread is not None
                    or not self._blocked or original is None or self._record is not original
                    or original != snapshot or fd is None
                    or type(original['run_id']) is not str
                    or _RUN_ID.fullmatch(original['run_id']) is None
                    or not valid_session_id(original['session_id'])
                    or not journal.retained_lock_valid(fd)):
                return False
            self._reconciling = True
        try:
            try:
                result = client.status(snapshot['run_id'])
            except Exception:
                # No raw remote error, response, or answer is persisted/delivered.
                return False
            if (type(result) is not dict or type(result.get('run_id')) is not str
                    or type(result.get('session_id')) is not str
                    or type(result.get('status')) is not str
                    or result['run_id'] != snapshot['run_id']
                    or result['session_id'] != snapshot['session_id']
                    or result['status'] != 'interrupted'):
                return False
            with self._lock:
                if (self._closed or self._thread is not None or not self._blocked
                        or self._record is not original or self._restored_record is not original
                        or self._record != snapshot or self.journal is not journal
                        or self.client is not client or journal.fd != fd
                        or journal.read() != snapshot
                        or not journal.retained_lock_valid(fd)):
                    return False
                journal.preserve_restored(snapshot)
                corrected = dict(snapshot, state='interrupted')
                # Do not use _commit: admission must stay blocked until write succeeds.
                journal.write(corrected)
                self._record = corrected
                self._restored_record = None
                self._blocked = False
                return True
        finally:
            with self._lock:
                self._reconciling = False

    def scope(self):
        with self._lock:
            return dict(self._record) if self._record else None

    def terminal_snapshot(self, request_id, session_generation):
        """Read-only exact-run evidence, never infer terminal from a stop ACK or timeout."""
        with self._lock:
            record = self._record
            if (self._closed or not record or record['request_id'] != request_id
                    or record['session_generation'] != session_generation):
                return {"run_id": None, "terminal": "UNKNOWN"}
            state = record['state']
            terminal = 'PENDING'
            if self._blocked and state not in {'submitting', 'running', 'stopping'}:
                terminal = 'UNKNOWN'
            elif state in TERMINAL and record['run_id'] is not None:
                terminal = 'CLEAN' if not self.busy() and not self._blocked else 'PENDING'
            elif state not in {'submitting', 'running', 'stopping'}:
                terminal = 'UNKNOWN'
            return {"run_id": record['run_id'], "terminal": terminal}

    def owns(self, request_id):
        with self._lock:
            return bool(self._record and self._record['request_id'] == request_id)

    def busy(self):
        with self._lock:
            return self._thread is not None and self._thread.is_alive()

    def blocked(self):
        with self._lock:
            return self._blocked

    def begin(self, *, request_id, session_generation, session_id, input_text,
              model=None, provider=None, model_options=None):
        for value in (request_id, session_generation):
            _identity(value)
        if not valid_session_id(session_id):
            raise RunOwnerError('RUNS_ID_INVALID')
        if type(input_text) is not str or not input_text.strip() or len(input_text) > 8000:
            raise RunOwnerError('RUNS_INPUT_INVALID')
        with self._lock:
            if self._closed:
                raise RunOwnerError('RUNS_STATE_CLOSED')
            if self._blocked or self._reconciling or self.busy():
                raise RunOwnerError('RUNS_PREVIOUS_UNRESOLVED')
            self._restored_record = None
            record = dict(version=1, request_id=request_id, session_generation=session_generation,
                session_id=session_id, run_id=None, idempotency_key='phone_' + secrets.token_hex(24), state='submitting')
            # Commit before dispatch. A crash cannot silently turn an uncertain call into a fresh one.
            self.journal.write(record)
            self._record = record
            self._cancel = threading.Event()
            context = copy_context()
            worker = threading.Thread(target=lambda: context.run(self._work, record, input_text,
                model, provider, model_options), name='phone-host-run', daemon=True)
            self._thread = worker
            try:
                worker.start()
            except BaseException:
                self._thread = None
                self._commit('not_started')
                raise
        return dict(record)

    def cancel(self, request_id):
        with self._lock:
            if not self.owns(request_id) or self._record['state'] in FINISHED:
                return False
            self._cancel.set()  # Signal only; stop HTTP belongs to the worker outside this lock.
            return True

    def join(self, timeout=3):
        with self._lock:
            worker = self._thread
        if worker is None:
            return True
        if worker is threading.current_thread():
            return False
        worker.join(timeout)
        return not worker.is_alive()

    def _commit(self, state, run_id=None):
        with self._lock:
            record = dict(self._record)
            record['state'] = state
            if run_id is not None:
                record['run_id'] = run_id
            # In-memory uncertainty is sticky even if the durable write itself fails.
            self._blocked = state not in FINISHED
            self.journal.write(record)
            self._record = record

    def _failure(self, code):
        if type(code) is not str or _ERROR.fullmatch(code) is None:
            code = 'RUNS_FAILED'
        try:
            self.on_failure(self.scope(), code)
        except Exception:
            self._commit('delivery_unknown')

    def _work(self, scope, input_text, model, provider, model_options):
        known_run = None
        try:
            if self._cancel.is_set():
                self._commit('not_started')
                return
            try:
                receipt = self.client.submit(input_text, session_id=scope['session_id'],
                    idempotency_key=scope['idempotency_key'], model=model,
                    provider=provider, model_options=model_options)
            except Exception as error:
                unknown = getattr(error, 'submission_unknown', True) is not False
                self._commit('unknown' if unknown else 'rejected')
                code = getattr(error, 'code', 'RUNS_SUBMISSION_UNKNOWN')
                if code in {'invalid_configuration', 'invalid_argument', 'request_too_large',
                            'http_rejected', 'transport_unavailable', 'redirect_blocked', 'submission_unknown'}:
                    code = 'RUNS_' + code.upper()
                self._failure(code)
                return
            run_id = receipt.get('run_id') if type(receipt) is dict else None
            if type(run_id) is not str or _RUN_ID.fullmatch(run_id) is None:
                self._commit('unknown')
                self._failure('RUNS_RECEIPT_INVALID')
                return
            known_run = run_id
            self._commit('running', run_id)
            if self.on_accepted is not None:
                self.on_accepted(self.scope(), dict(receipt))
            deadline = time.monotonic() + self.deadline_seconds
            stop_sent = False
            while time.monotonic() < deadline:
                if self._cancel.is_set() and not stop_sent:
                    stop_sent = True
                    self._commit('stopping')
                    try:
                        self.client.stop(run_id)
                    except Exception:
                        # A failed stop cannot certify cancellation; read-only reconciliation continues.
                        pass
                try:
                    result = self.client.status(run_id)
                except Exception:
                    time.sleep(self.poll_seconds)
                    continue
                if (type(result) is not dict or result.get('run_id') != run_id
                        or result.get('session_id') != scope['session_id']):
                    self._cancel.set()
                    if not stop_sent:
                        stop_sent = True
                        try:
                            self.client.stop(run_id)
                        except Exception:
                            pass
                    self._commit('unknown')
                    self._failure('RUNS_SESSION_MISMATCH')
                    return
                status = result.get('status')
                if status in TERMINAL:
                    if status == 'completed' and (result.get('completed') is not True
                            or result.get('partial') is True or result.get('interrupted') is True):
                        self._commit('unknown')
                        self._failure('RUNS_TERMINAL_INVALID')
                        return
                    if self._cancel.is_set():
                        # Even a raced completed result cannot speak after STOP.
                        self._commit(status)
                        return
                    if status == 'completed':
                        try:
                            self.on_terminal(self.scope(), dict(result))
                        except Exception:
                            self._commit('delivery_unknown')
                            self._failure('RUNS_DELIVERY_UNCONFIRMED')
                            return
                    else:
                        self._failure('RUNS_' + status.upper())
                    self._commit(status)
                    return
                time.sleep(self.poll_seconds)
            self._cancel.set()
            if not stop_sent:
                try:
                    self.client.stop(run_id)
                except Exception:
                    pass
            self._commit('unknown')
            self._failure('RUNS_TERMINAL_UNKNOWN')
        except Exception:
            # Programmer/persistence/callback failure does not turn a dispatched effect into a clean idle state.
            with self._lock:
                self._blocked = True
            self._cancel.set()
            if known_run:
                try:
                    self.client.stop(known_run)
                except Exception:
                    pass
            self._failure('RUNS_OWNER_FAILED')
        finally:
            input_text = None
            if self.on_settled is not None:
                try:
                    self.on_settled(self.scope())
                except Exception:
                    with self._lock:
                        self._blocked = True

    def close(self):
        with self._lock:
            if self._closed:
                return
            if self.busy():
                raise RunOwnerError('RUNS_WORKER_NOT_JOINED')
            self.journal.close()
            self._closed = True
