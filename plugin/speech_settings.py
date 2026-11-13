"""Plugin-owned private SQLite CAS; no host config/credential reads or writes.

Only plugin writers using this store participate in CAS. No claim is made about
core config writers. Execution payload schema belongs to the caller's validator.
See evidence/speech-selection/INTERFACE.md for integration and trust boundaries.
"""
from contextlib import contextmanager
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import uuid

MAX_BYTES = 65536
MAX_REVISION = 2**63 - 1
NAMESPACES = frozenset({'speech', 'execution'})


class SettingsError(ValueError):
    """Fixed public error codes only; no paths, payloads or credential values."""


def _revision(value):
    if type(value) is not int or not 0 <= value <= MAX_REVISION:
        raise SettingsError('SETTINGS_REVISION_INVALID')


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate_key')
        result[key] = value
    return result


def _invalid_constant(_):
    raise ValueError('invalid_json_constant')


def _encode(value):
    if type(value) is not dict:
        raise SettingsError('SETTINGS_PAYLOAD_INVALID')
    try:
        data = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False)
        if len(data.encode('utf-8')) > MAX_BYTES:
            raise ValueError('oversize')
        # Reject non-JSON inputs (tuple, int keys, arbitrary mappings) instead of
        # accepting their lossy JSON conversion as a different caller payload.
        clean = json.loads(data, object_pairs_hook=_unique, parse_constant=_invalid_constant)
        if clean != value:
            raise ValueError('not_json')
        return data
    except (ValueError, TypeError, RecursionError, OverflowError):
        raise SettingsError('SETTINGS_PAYLOAD_INVALID') from None


def _validated(value, validate):
    _encode(value)
    try:
        clean = validate(deepcopy(value))
    except (ValueError, TypeError, KeyError, RecursionError):
        raise SettingsError('SETTINGS_PAYLOAD_INVALID') from None
    _encode(clean)
    return deepcopy(clean)


def _private_file(info):
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
        raise SettingsError('SETTINGS_PATH_UNSAFE')


class SettingsStore:
    """Reusable CAS for speech/execution; validator returns a JSON dict.

    directory must be absolute, contain no '.'/'..' or symlink components, and
    be a dedicated owner-only 0700 directory (created if absent). Every DB,
    journal/WAL/SHM or lock-shaped file must be owner-only regular, not hardlinked.
    SQLite BEGIN IMMEDIATE + FULL synchronous commit supplies atomic revision CAS.
    The process UID is trusted: same-UID hostile processes are outside this fence.
    """
    def __init__(self, directory):
        raw = os.fspath(directory)
        if (not isinstance(raw, str) or not raw.startswith('/') or '\x00' in raw
                or any(part in ('.', '..') for part in raw.split('/'))):
            raise SettingsError('SETTINGS_PATH_UNSAFE')
        self.directory = Path(raw)
        if self.directory == Path('/'):
            raise SettingsError('SETTINGS_PATH_UNSAFE')

    @contextmanager
    def _directory(self):
        # Android permits traversing / and /data but denies directory reads.
        # O_PATH pins ancestors without requiring their read/list permission.
        fd = os.open('/', os.O_PATH | os.O_DIRECTORY)
        try:
            parts = self.directory.parts[1:]
            for index, part in enumerate(parts):
                access = os.O_RDONLY if index == len(parts) - 1 else os.O_PATH
                flags = access | os.O_DIRECTORY | os.O_NOFOLLOW
                try:
                    child = os.open(part, flags, dir_fd=fd)
                except FileNotFoundError:
                    try: os.mkdir(part, mode=0o700, dir_fd=fd)
                    except FileExistsError: pass
                    child = os.open(part, flags, dir_fd=fd)
                os.close(fd); fd = child
            info = os.fstat(fd)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise SettingsError('SETTINGS_PATH_UNSAFE')
            yield fd
        finally:
            os.close(fd)

    @contextmanager
    def _connection(self):
        try:
            with self._directory() as directory_fd:
                flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
                try:
                    db_fd = os.open('settings.sqlite3', flags | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=directory_fd)
                    os.fsync(directory_fd)
                except FileExistsError:
                    db_fd = os.open('settings.sqlite3', flags, dir_fd=directory_fd)
                try:
                    identity = os.fstat(db_fd); _private_file(identity)
                    for name in ('settings.sqlite3-journal', 'settings.sqlite3-wal', 'settings.sqlite3-shm'):
                        try: info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                        except FileNotFoundError: continue
                        _private_file(info)
                    # Pinned descriptor prevents an ancestor rename from redirecting
                    # SQLite's path. Linux/Android /proc/self/fd is required.
                    connection = sqlite3.connect('/proc/self/fd/%d/settings.sqlite3' % directory_fd,
                                                 timeout=15, isolation_level=None)
                    try:
                        connection.execute('PRAGMA trusted_schema=OFF')
                        connection.execute('PRAGMA synchronous=FULL')
                        connection.execute('BEGIN IMMEDIATE')
                        version = connection.execute('PRAGMA user_version').fetchone()[0]
                        if version == 0:
                            existing = connection.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
                            if existing: raise SettingsError('SETTINGS_STORE_INVALID')
                            connection.execute('CREATE TABLE settings (namespace TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision > 0), payload TEXT NOT NULL)')
                            connection.execute('PRAGMA user_version=1')
                        elif version != 1:
                            raise SettingsError('SETTINGS_STORE_VERSION_UNSUPPORTED')
                        yield connection
                        after = os.stat('settings.sqlite3', dir_fd=directory_fd, follow_symlinks=False)
                        _private_file(after)
                        if (identity.st_ino, identity.st_dev) != (after.st_ino, after.st_dev):
                            raise SettingsError('SETTINGS_PATH_CHANGED')
                        connection.execute('COMMIT')
                        os.fsync(directory_fd)
                    finally:
                        if connection.in_transaction: connection.execute('ROLLBACK')
                        connection.close()
                finally:
                    os.close(db_fd)
        except SettingsError:
            raise
        except sqlite3.OperationalError:
            raise SettingsError('SETTINGS_STORE_UNAVAILABLE') from None
        except (OSError, sqlite3.DatabaseError, ValueError, TypeError):
            raise SettingsError('SETTINGS_STORE_INVALID') from None

    @staticmethod
    def _namespace(namespace):
        if type(namespace) is not str or namespace not in NAMESPACES:
            raise SettingsError('SETTINGS_NAMESPACE_INVALID')

    @staticmethod
    def _read(connection, namespace, default, validate):
        row = connection.execute('SELECT revision,payload FROM settings WHERE namespace=?', (namespace,)).fetchone()
        if row is None:
            return dict(revision=0, payload=_validated(default, validate))
        revision, raw = row
        _revision(revision)
        if revision == 0 or type(raw) is not str or len(raw.encode('utf-8')) > MAX_BYTES:
            raise SettingsError('SETTINGS_STORE_INVALID')
        try:
            payload = json.loads(raw, object_pairs_hook=_unique, parse_constant=_invalid_constant)
        except (ValueError, TypeError, RecursionError):
            raise SettingsError('SETTINGS_STORE_INVALID') from None
        return dict(revision=revision, payload=_validated(payload, validate))

    def current(self, namespace, *, default, validate):
        self._namespace(namespace)
        _validated(default, validate)
        with self._connection() as connection:
            result = self._read(connection, namespace, default, validate)
        return result

    def replace(self, namespace, expected_revision, payload, *, default, validate):
        self._namespace(namespace); _revision(expected_revision)
        clean = _validated(payload, validate)
        _validated(default, validate)
        with self._connection() as connection:
            current = self._read(connection, namespace, default, validate)
            if current['revision'] != expected_revision:
                raise SettingsError('SETTINGS_CONFLICT')
            if expected_revision == MAX_REVISION:
                raise SettingsError('SETTINGS_REVISION_EXHAUSTED')
            revision = expected_revision + 1
            connection.execute('INSERT INTO settings(namespace,revision,payload) VALUES(?,?,?) '
                               'ON CONFLICT(namespace) DO UPDATE SET revision=excluded.revision,payload=excluded.payload',
                               (namespace, revision, _encode(clean)))
        return dict(revision=revision, payload=deepcopy(clean))


_TOKEN = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}\Z')
_PROVIDER = re.compile(r'[a-z][a-z0-9_-]{0,63}\Z')
_CHANNEL_FIELDS = {'mode', 'provider', 'credential_ref', 'model', 'voice', 'language'}


def _selection(value, audience, kind):
    if type(value) is not dict or type(value.get('mode')) is not str:
        raise ValueError('selection_invalid')
    mode = value['mode']
    if mode in ('inherit', 'disabled'):
        if set(value) != {'mode'} or (mode == 'inherit' and audience != 'family'):
            raise ValueError('selection_invalid')
        return deepcopy(value)
    if mode != 'explicit' or set(value) != _CHANNEL_FIELDS:
        raise ValueError('selection_invalid')
    provider = value['provider']
    if type(provider) is not str or not _PROVIDER.fullmatch(provider):
        raise ValueError('provider_invalid')
    for key in ('model', 'voice', 'language'):
        token = value[key]
        if token is not None and (type(token) is not str or not _TOKEN.fullmatch(token)
                                  or '..' in token or token.startswith(('sk-', 'AIza', 'Bearer'))):
            raise ValueError('option_invalid')
    if kind == 'stt' and value['voice'] is not None:
        raise ValueError('stt_voice_invalid')
    reference = value['credential_ref']
    # Hermes-routed adapters use Hermes' own provider configuration; the plugin holds no reference.
    if provider in ('android-native', 'local', 'hermes-stt', 'hermes-tts'):
        if reference is not None: raise ValueError('reference_unexpected')
    else:
        if (type(reference) is not dict or set(reference) != {'scope','id'}
                or type(reference['scope']) is not str or reference['scope'] not in ('family','user')
                or type(reference['id']) is not str):
            raise ValueError('reference_invalid')
        try: canonical = str(uuid.UUID(reference['id']))
        except (ValueError, AttributeError): raise ValueError('reference_invalid') from None
        if canonical != reference['id']: raise ValueError('reference_invalid')
        if audience == 'external' and reference['scope'] != 'user': raise ValueError('external_reference_required')
    if provider == 'android-native' and (kind != 'stt' or value['model'] not in (None,'on-device-ko')
                                       or not (value['language'] is None or (type(value['language']) is str and len(value['language']) <= 35))):
        raise ValueError('android_option_unsupported')
    return deepcopy(value)


def validate_speech(value):
    """Strict persistent schema validation only; no readiness/auth/source probes."""
    if type(value) is not dict or set(value) != {'schema_version','audience','tts','stt'}:
        raise ValueError('speech_invalid')
    if type(value['schema_version']) is not int or value['schema_version'] != 1:
        raise ValueError('speech_version_invalid')
    audience = value['audience']
    if type(audience) is not str or audience not in ('family','external'):
        raise ValueError('audience_invalid')
    for kind in ('tts','stt'):
        _selection(value[kind], audience, kind)
    return deepcopy(value)


class SpeechSettings:
    """Independent selection preferences, not a credential or live runtime store.

    `audience='external'` is a deployment fence: a family row is refused, never
    inherited/reset. A family process may deliberately transition to external by
    replacing both channels. Binding a setting into a live turn is parent's job.
    """
    def __init__(self, directory, *, audience='family', profile=None, core_root=None):
        if type(audience) is not str or audience not in ('family','external'):
            raise SettingsError('SPEECH_AUDIENCE_INVALID')
        self.store = SettingsStore(directory)
        self.audience = audience
        self.profile = deepcopy(profile)
        self.core_root = core_root
        mode = 'inherit' if audience == 'family' else 'disabled'
        self.default = dict(schema_version=1,audience=audience,tts=dict(mode=mode),stt=dict(mode=mode))

    def _validate(self, payload):
        clean = validate_speech(payload)
        if self.audience == 'external' and clean['audience'] != 'external':
            raise ValueError('external_family_state_forbidden')
        return clean

    def current(self):
        return self.store.current('speech',default=self.default,validate=self._validate)

    def select(self, expected_revision, **changes):
        _revision(expected_revision)
        if not changes or set(changes) - {'audience','tts','stt'}:
            raise SettingsError('SPEECH_UPDATE_INVALID')
        current = self.current()
        if current['revision'] != expected_revision:
            raise SettingsError('SETTINGS_CONFLICT')
        candidate = deepcopy(current['payload'])
        if changes.get('audience',candidate['audience']) != candidate['audience'] and not {'tts','stt'} <= set(changes):
            raise SettingsError('SPEECH_AUDIENCE_REQUIRES_SELECTIONS')
        candidate.update(deepcopy(changes))
        clean = _validated(candidate,self._validate)
        from .speech_catalog import discover
        catalog = discover(profile=self.profile,core_root=self.core_root)
        rows = {(r['kind'],r['provider']):r for r in catalog['providers']}
        for kind in ('tts','stt'):
            selected = clean[kind]
            if selected['mode'] != 'explicit': continue
            row = rows.get((kind,selected['provider']))
            if row is None or row['selectable'] is not True:
                raise SettingsError('SPEECH_ADAPTER_UNSUPPORTED')
            if clean['audience'] not in row.get('audiences',['family','external']):
                raise SettingsError('SPEECH_ADAPTER_AUDIENCE_FORBIDDEN')
        return self.store.replace('speech',expected_revision,clean,default=self.default,validate=self._validate)
