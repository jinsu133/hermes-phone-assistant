"""Opt-in plugin-owned execution configuration, not host runtime configuration.

Construction/import is inert. Only current/configure/create_client initialize the
private CAS store. No legacy voice_route migration or implicit session binding exists.
Model/provider are exact request preferences, not per-send route immutability;
None means host selection. The caller owns the run lifecycle and must bind a
single settings snapshot to a request. This module never submits a run.

Operator entry point: handle_execution_command(settings, shlex_args) -> dict.
Accepts either a command string or already shlex-split list/tuple of strings.
Only explicit catalog performs network I/O (GET /api/model/options); status and
configure never probe provider readiness. The parent registers/authenticates the
operator command, and must not expose configure to untrusted phone input.
"""
from copy import deepcopy
import json
import os
import re
import shlex

from .speech_settings import MAX_BYTES, MAX_REVISION, SettingsError, SettingsStore
from .voice_runs_transport import RunsClient, RunsTransportError
from .voice_run_ids import valid_session_id

DEFAULT_EXECUTION = {
    'mode': 'strict',
    'base_url': 'http://127.0.0.1:8642',
    'token_env': 'API_SERVER_KEY',
    'session_id': None,
    'model': None,
    'provider': None,
    'model_options': {},
}
_URL = re.compile(r'http://127\.0\.0\.1:([1-9][0-9]{0,4})\Z', re.ASCII)
_ENV = re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,127}\Z', re.ASCII)
_SELECTION = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/@+~\-]{0,511}\Z', re.ASCII)
# Installed api_server.py _REASONING_EFFORTS and _request_reasoning_config;
# official docs/user-guide/features/api-server documents reasoning_effort.
_EFFORTS = frozenset({'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'})


def _identifier(value, pattern):
    return (type(value) is str and pattern.fullmatch(value) is not None
            and not value.startswith(('sk-', 'AIza', 'Bearer', 'ghp_', 'github_pat_')))


def validate_execution(value):
    """Closed schema; no normalization, secrets, arbitrary options, or discovery."""
    if type(value) is not dict or set(value) != set(DEFAULT_EXECUTION):
        raise SettingsError('EXECUTION_PAYLOAD_INVALID')
    if type(value['mode']) is not str or value['mode'] not in ('strict', 'runs'):
        raise SettingsError('EXECUTION_MODE_INVALID')
    url = value['base_url']
    match = _URL.fullmatch(url) if type(url) is str else None
    if not match or not 1 <= int(match[1]) <= 65535:
        raise SettingsError('EXECUTION_URL_INVALID')
    if not _identifier(value['token_env'], _ENV):
        raise SettingsError('EXECUTION_TOKEN_ENV_INVALID')
    session = value['session_id']
    if (session is None and value['mode'] == 'runs') or (session is not None and not valid_session_id(session)):
        raise SettingsError('EXECUTION_SESSION_REQUIRED')
    for key in ('model', 'provider'):
        if value[key] is not None and not _identifier(value[key], _SELECTION):
            raise SettingsError('EXECUTION_SELECTION_INVALID')
    options = value['model_options']
    if type(options) is not dict or set(options) - {'reasoning_effort'}:
        raise SettingsError('EXECUTION_OPTIONS_INVALID')
    if 'reasoning_effort' in options:
        effort = options['reasoning_effort']
        if type(effort) is not str or effort not in _EFFORTS:
            raise SettingsError('EXECUTION_OPTIONS_INVALID')
    return deepcopy(value)


class ExecutionSettings:
    """Private HOME/state/phone-settings CAS, namespace execution only.

    HOME is explicitly supplied by the parent (active Hermes profile home).
    token_env stores an environment variable NAME, never its secret value.
    """
    def __init__(self, home):
        try:
            raw = os.fspath(home)
            if not isinstance(raw, str) or not raw.startswith('/') or not raw.strip('/'):
                raise ValueError
            # Pass the raw path through: do not normalize away forbidden '..'.
            self.store = SettingsStore(raw.rstrip('/') + '/state/phone-settings')
        except (TypeError, ValueError):
            raise SettingsError('SETTINGS_PATH_UNSAFE') from None

    def current(self):
        return self.store.current('execution', default=DEFAULT_EXECUTION, validate=validate_execution)

    def configure(self, expected_revision, payload):
        # Validate before touching disk, including malformed revision inputs.
        if type(expected_revision) is not int or not 0 <= expected_revision <= MAX_REVISION:
            raise SettingsError('SETTINGS_REVISION_INVALID')
        clean = validate_execution(payload)
        return self.store.replace('execution', expected_revision, clean,
                                  default=DEFAULT_EXECUTION, validate=validate_execution)

    def create_client(self, snapshot=None):
        """Bind a client to one exact current() snapshot, without network I/O.

        Run callers MUST pass the same snapshot used for their request payload.
        Missing snapshot is for explicit catalog use only. A stale revision OR
        altered payload raises SETTINGS_CONFLICT; no silent refresh occurs.
        Construction uses only the copied snapshot, with a second freshness
        check after secret resolution/construction. The final read is the
        handoff's linearization point, not a lease over a later submit/run.
        """
        if snapshot is None:
            snapshot = self.current()
        try:
            if (type(snapshot) is not dict or set(snapshot) != {'revision', 'payload'}
                    or type(snapshot['revision']) is not int
                    or not 0 <= snapshot['revision'] <= MAX_REVISION):
                raise ValueError
            bound = {'revision': snapshot['revision'],
                     'payload': validate_execution(snapshot['payload'])}
        except (ValueError, TypeError, RecursionError):
            raise SettingsError('EXECUTION_SNAPSHOT_INVALID') from None
        if self.current() != bound:
            raise SettingsError('SETTINGS_CONFLICT')
        selected = bound['payload']
        if selected['mode'] != 'runs':
            raise SettingsError('EXECUTION_STRICT')

        # Public core contract: context-local profile secrets, never direct env
        # access or .env reads. Lazy import keeps status/configure credential-free.
        from agent.secret_scope import (
            UnscopedSecretError, current_secret_scope, current_secret_scope_home,
            get_secret, serves_routed_profile,
        )
        from pathlib import Path
        scope_home = current_secret_scope_home()
        if scope_home is not None and Path(scope_home) != self.store.directory.parent.parent:
            raise SettingsError('EXECUTION_TOKEN_UNAVAILABLE')
        scope = current_secret_scope()
        routed = serves_routed_profile()
        if routed and (scope is None or not scope.get(selected['token_env'])):
            raise SettingsError('EXECUTION_TOKEN_UNAVAILABLE')
        try:
            token = get_secret(selected['token_env'])
        except UnscopedSecretError:
            raise SettingsError('EXECUTION_TOKEN_UNAVAILABLE') from None
        # get_secret deliberately resolves deployment/global names from env;
        # a custom token_env must not use that escape hatch across profiles.
        if not token or (routed and token != scope.get(selected['token_env'])):
            raise SettingsError('EXECUTION_TOKEN_UNAVAILABLE')
        try:
            client = RunsClient(selected['base_url'], token)
        except RunsTransportError:
            raise SettingsError('EXECUTION_CLIENT_INVALID') from None
        if self.current() != bound:
            raise SettingsError('SETTINGS_CONFLICT')
        return client


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result


def _reject_constant(_):
    raise ValueError


def parse_execution_args(args):
    if type(args) is str:
        if len(args) > MAX_BYTES * 2:
            raise ValueError
        return tuple(shlex.split(args))
    if (type(args) in (list, tuple) and all(type(part) is str for part in args)
            and sum(map(len, args)) <= MAX_BYTES * 2):
        return tuple(args)
    raise ValueError


def handle_execution_command(settings, args):
    """Framework-free operator command dispatcher; fixed public error codes.

    configure is FULL replacement: configure <revision> '<JSON object>'.
    No model/provider calls or network occur except the explicit catalog GET.
    """
    try:
        parts = parse_execution_args(args)
        if parts == ('status',):
            return {'ok': True, 'settings': settings.current()}
        if parts == ('catalog',):
            return {'ok': True, 'catalog': settings.create_client().model_options()}
        if len(parts) != 3 or parts[0] != 'configure':
            raise ValueError
        if not re.fullmatch(r'0|[1-9][0-9]{0,18}', parts[1]) or len(parts[2]) > MAX_BYTES:
            raise ValueError
        candidate = json.loads(parts[2], object_pairs_hook=_unique, parse_constant=_reject_constant)
        return {'ok': True, 'settings': settings.configure(int(parts[1]), candidate)}
    except SettingsError as exc:
        return {'ok': False, 'error': str(exc)}
    except RunsTransportError as exc:
        return {'ok': False, 'error': 'EXECUTION_CATALOG_FAILED', 'transport_code': exc.code}
    except (ValueError, TypeError, RecursionError, OverflowError):
        return {'ok': False, 'error': 'EXECUTION_COMMAND_INVALID'}
