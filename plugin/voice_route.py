"""Release voice admission. No guessed host API, credentials, network or config writes.

The inspected public plugin API cannot bind a route before context preparation or
prevent fallback at every physical send. Therefore strict voice is unavailable in
this version, including inherit mode. Do not turn this into a user-set capability flag.
"""
from copy import deepcopy

DEFAULT_ROUTE = dict(version=1, mode='inherit', provider_id=None, endpoint_binding=None,
                     model_id=None, effort='auto', revision=0)
_FIELDS = frozenset(DEFAULT_ROUTE)
_EFFORTS = frozenset({'auto','none','minimal','low','medium','high','xhigh'})
_MISSING = object()


def validate_route(value):
    if type(value) is not dict or set(value) != _FIELDS:
        raise ValueError('VOICE_ROUTE_INVALID')
    if type(value['version']) is not int or value['version'] != 1:
        raise ValueError('VOICE_ROUTE_INVALID')
    if type(value['revision']) is not int or not 0 <= value['revision'] < 2**63:
        raise ValueError('VOICE_ROUTE_INVALID')
    if type(value['mode']) is not str or value['mode'] not in ('inherit','override'):
        raise ValueError('VOICE_ROUTE_INVALID')
    if type(value['effort']) is not str or value['effort'] not in _EFFORTS:
        raise ValueError('VOICE_ROUTE_INVALID')
    bindings = [value[k] for k in ('provider_id','endpoint_binding','model_id')]
    if value['mode'] == 'inherit':
        if any(v is not None for v in bindings) or value['effort'] != 'auto':
            raise ValueError('VOICE_ROUTE_INVALID')
    elif any(type(v) is not str or not 1 <= len(v) <= 256
             or any(ord(c)<32 or ord(c)==127 for c in v) for v in bindings):
        raise ValueError('VOICE_ROUTE_INVALID')
    return deepcopy(value)


def snapshot(ctx):
    try:
        value=ctx.get_config('voice_route',_MISSING)
        route=validate_route(DEFAULT_ROUTE if value is _MISSING else value)
    except (TypeError, ValueError):
        return {'schema_version':1,'ok':False,'error':'VOICE_ROUTE_INVALID','route':None,'editable':False}
    except Exception:
        return {'schema_version':1,'ok':False,'error':'VOICE_ROUTE_UNREADABLE','route':None,'editable':False}
    return {'schema_version':1,'ok':False,'error':'HOST_ROUTE_CONTRACT_UNAVAILABLE',
            'route':route,'editable':False}


def capabilities():
    return dict(schema_version=1,ok=True,plugin_version='0.18.0-runs-preview.1',
                strict_voice=False,model_catalog=False,voice_route_write=False,
                scoped_cancel=False,integrated_mcp=True,procedural_route_learning=True,
                blocked_reason='HOST_ROUTE_CONTRACT_UNAVAILABLE')
