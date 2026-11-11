"""Conversation-facing speech preference control. Never collects credentials."""
import json
from pathlib import Path
from .speech_settings import SpeechSettings, SettingsError
from .speech_catalog import discover


def settings(ctx, home):
    audience = ctx.get_config('distribution_audience', 'family')
    if audience not in ('family', 'external'):
        raise SettingsError('SPEECH_AUDIENCE_INVALID')
    return SpeechSettings(Path(home) / 'state' / 'phone-settings', audience=audience)


def perform(ctx, home, args):
    if type(args) is not dict or type(args.get('action')) is not str:
        raise SettingsError('SPEECH_UPDATE_INVALID')
    store = settings(ctx, home)
    action = args['action']
    if action in ('status','catalog'):
        if set(args) != {'action'}:
            raise SettingsError('SPEECH_UPDATE_INVALID')
        value = {'ok': True, 'selection': store.current(), 'applies_to': 'next_request',
            'credentials_collected': False}
        from .speech_runtime import SpeechRuntime
        value['runtime'] = SpeechRuntime(home, ctx, settings=store,
            audience=ctx.get_config('distribution_audience', 'family')).runtime_status()
        if action == 'catalog':
            value['catalog'] = discover()
        return value
    if action == 'select' and set(args) == {'action','expected_revision','changes'}:
        changes = args['changes']
        if type(changes) is not dict or set(changes) - {'tts','stt'} or not changes:
            raise SettingsError('SPEECH_UPDATE_INVALID')
        # Deployment audience is an operator fence, not a model-selectable privilege.
        result = store.select(args['expected_revision'], **changes)
        return {'ok': True, 'selection': result, 'applies_to': 'next_request',
            'runtime_verified': False, 'credentials_collected': False}
    raise SettingsError('SPEECH_UPDATE_INVALID')


def command(ctx, home, text):
    raw = (text or 'status').strip()
    if raw in ('status','catalog'):
        args = {'action': raw}
    else:
        try:
            args = json.loads(raw)
        except (TypeError, ValueError):
            raise SettingsError('SPEECH_UPDATE_INVALID') from None
    return json.dumps(perform(ctx, home, args), ensure_ascii=False)
