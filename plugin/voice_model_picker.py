"""Read-only phone picker and exact operator handoff. No settings write authority.

The only apply path is registered /phone-execution select -> its existing OFF /
settled gate -> handle_execution_command(configure). HTTP never dispatches a
command, stops listening, applies settings, or treats a screen gesture as trusted
operator identity. No private core imports or new provider probes are used.

Stock GET /api/model/options supplies authenticated, slug, models, aliases and
capabilities[model].reasoning/can_disable_reasoning. It deliberately omits per-
model supported_efforts. API-accepted request values MUST NOT be called model-
supported values; downstream ignore/clamp/fallback cannot be ruled out here.
"""
from copy import deepcopy
import json
import re

from .speech_settings import MAX_BYTES, MAX_REVISION, SettingsError
from .voice_execution_settings import (
    _EFFORTS, _SELECTION, _identifier, _unique, _reject_constant, validate_execution,
)

REQUEST_EFFORTS = [x for x in ('none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra')
                  if x in _EFFORTS]
SELECTION_KEYS = {'provider', 'model', 'reasoning_effort'}
_METADATA_ALIAS = re.compile(r'[A-Za-z0-9][A-Za-z0-9 ._:/+~\-]{0,511}\Z', re.ASCII)
_CREDENTIAL_HINT = re.compile(r'(?:^|[^A-Za-z0-9])(?:sk-|AIza|Bearer|ghp_|github_pat_)', re.I | re.ASCII)


def _fail(code='EXECUTION_CATALOG_INVALID'):
    raise SettingsError(code)


def _id(value, empty=False):
    return (empty and value == '') or _identifier(value, _SELECTION)


def _metadata_alias(value):
    """Bounded display text, NOT a writable identifier; never trim or rewrite.

    ASCII spaces are stock legacy aliases. Controls, userinfo, credential-like
    tokens and URL text are not public metadata. No secret-bearing fields copied.
    """
    return (type(value) is str and _METADATA_ALIAS.fullmatch(value) is not None
            and '://' not in value and _CREDENTIAL_HINT.search(value) is None)


def normalize_catalog(catalog):
    """Allowlisted projection of the REAL public catalog; never a synthetic inventory."""
    if (type(catalog) is not dict or type(catalog.get('providers')) is not list
            or len(catalog['providers']) > 256
            or not _id(catalog.get('provider'), empty=True)
            or not _id(catalog.get('model'), empty=True)):
        _fail()
    providers, seen = [], set()
    total = 0
    for row in catalog['providers']:
        if type(row) is not dict or not _id(row.get('slug')) or row['slug'] in seen:
            _fail()
        seen.add(row['slug'])
        models = row.get('models')
        if type(models) is not list or any(not _id(m) for m in models):
            _fail()
        if len(set(models)) != len(models):
            _fail()
        total += len(models)
        if total > 20000:
            _fail()
        caps = row.get('capabilities', {})
        aliases = row.get('aliases', [])
        if (type(caps) is not dict or type(aliases) is not list
                or len(aliases) > 64 or any(not _metadata_alias(a) for a in aliases)):
            _fail()
        # Absence remains unknown, not an invented availability guarantee.
        unavailable = row.get('unavailable_models')
        pending = row.get('free_tier_pending')
        if (unavailable is not None and (type(unavailable) is not list
                or len(unavailable) > 20000 or any(not _id(m) for m in unavailable))):
            _fail()
        if pending is not None and type(pending) is not bool:
            _fail()
        unavailable_set = set(unavailable) if unavailable is not None else None
        auth = row.get('authenticated')
        if auth is not None and type(auth) is not bool:
            _fail()
        entries = []
        for model in models:
            c = caps.get(model, {})
            if type(c) is not dict:
                _fail()
            for key in ('reasoning', 'can_disable_reasoning'):
                if key in c and type(c[key]) is not bool:
                    _fail()
            entries.append({'id': model, 'reasoning_hint': c.get('reasoning'),
                'can_disable_reasoning': c.get('can_disable_reasoning'),
                'unavailable': model in unavailable_set if unavailable_set is not None else None,
                'supported_efforts': None})  # Explicit UNKNOWN, never guessed from model name.
        providers.append({'id': row['slug'], 'aliases': list(aliases),
            'nonselectable_aliases': [a for a in aliases if not _id(a)],
            'unavailable_models': list(unavailable) if unavailable is not None else None,
            'free_tier_pending': pending,
            'auth_readiness': 'ready' if auth is True else 'not_ready' if auth is False else 'unknown',
            'models': entries})
    return {'providers': providers, 'host_provider': catalog['provider'], 'host_model': catalog['model'],
            'request_efforts': list(REQUEST_EFFORTS), 'model_source': 'hermes_default_profile', 'model_effort_support_known': False,
            'strict_physical_route': False, 'selection_semantics': 'request_preferences'}


def validate_selection(value, catalog):
    """Validate the request preference. The model is never chosen here.

    Provider and model must both be None: voice runs always use the Hermes default
    profile's model. Effort independently inherits with None.
    """
    if type(value) is not dict or set(value) != SELECTION_KEYS:
        _fail('EXECUTION_SELECTION_INVALID')
    for key in ('provider', 'model'):
        if value[key] is not None and not _id(value[key]):
            _fail('EXECUTION_SELECTION_INVALID')
    effort = value['reasoning_effort']
    if effort is not None and (type(effort) is not str or effort not in REQUEST_EFFORTS):
        _fail('EXECUTION_EFFORT_INVALID')
    normalize_catalog(catalog)
    # A run's inherited route can come from its SESSION, not the catalog's global
    # default. Never treat that display default as proof of the effective route.
    if value['provider'] is None and value['model'] is None:
        return deepcopy(value)
    # The working model follows the Hermes default profile; only reasoning effort is selectable.
    _fail('EXECUTION_MODEL_FOLLOWS_DEFAULT_PROFILE')


def prepare_selection(settings, parts):
    """Network/read-only phase. Caller MUST reacquire its lifecycle gate to apply."""
    if (len(parts) != 3 or parts[0] != 'select'
            or not re.fullmatch(r'0|[1-9][0-9]{0,18}', parts[1])
            or int(parts[1]) > MAX_REVISION or len(parts[2]) > MAX_BYTES):
        _fail('EXECUTION_COMMAND_INVALID')
    value = json.loads(parts[2], object_pairs_hook=_unique, parse_constant=_reject_constant)
    snapshot = settings.current()
    if snapshot['revision'] != int(parts[1]):
        _fail('SETTINGS_CONFLICT')
    catalog = settings.create_client(snapshot).model_options()
    clean = validate_selection(value, catalog)
    if settings.current() != snapshot:
        _fail('SETTINGS_CONFLICT')
    payload = deepcopy(snapshot['payload'])
    # Also clears any override saved by an older version.
    payload['model'] = None
    payload['provider'] = None
    payload['model_options'] = ({} if clean['reasoning_effort'] is None
                                else {'reasoning_effort': clean['reasoning_effort']})
    return {'revision': snapshot['revision'], 'payload': validate_execution(payload)}


def binding_readback(snapshot, link):
    """Caller holds both link locks. Saved revision is NOT proof of live binding."""
    bound = deepcopy(link._runs.snapshot) if link._runs is not None else None
    return {'live_revision': bound['revision'] if bound is not None else None,
            'live_binding_matches': bool(link.listening and bound == snapshot),
            'operator_write_ready': not (link.listening or link._cancel_cleanup_blocked
                or link._ingress_active or link._worker_cleanup_pending()),
            'app_write_supported': False, 'strict_physical_route': False,
            'selection_semantics': 'request_preferences', 'model_effort_support_known': False}


def picker_snapshot(settings, link):
    """Authenticated GET handler helper; readback is coherent or explicitly fails."""
    with link._lifecycle_lock, link._lock:
        snapshot = settings.current()
    catalog = settings.create_client(snapshot).model_options()  # NO owner locks during HTTP.
    normalized = normalize_catalog(catalog)
    with link._lifecycle_lock, link._lock:
        if settings.current() != snapshot:
            _fail('SETTINGS_CONFLICT')
        binding = binding_readback(snapshot, link)
    payload = snapshot['payload']
    return {'ok': True, 'schema_version': 2, **normalized, **binding,
            'revision': snapshot['revision'], 'mode': payload['mode'],
            'model_source': 'hermes_default_profile',
            'selection': {'provider': None, 'model': None,
                          'reasoning_effort': payload['model_options'].get('reasoning_effort')},
            'operator_command': 'phone-execution', 'operator_action': 'select'}
