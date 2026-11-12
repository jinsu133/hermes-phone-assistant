"""Mandatory phone-action pre-tool hook. Approval is owned by Android, never the LLM."""
from .actions import validate_action
from .backend import Denied
from . import messages

# Set by register(); the hook itself receives no plugin context.
_CTX = None


def guard_phone_action(**kwargs):
    name = kwargs.get('tool_name')
    args = kwargs.get('args')
    if name == 'tool_call':
        if not isinstance(args, dict):
            return None
        for call in args.get('calls', []):
            if isinstance(call, dict):
                result = guard_phone_action(tool_name=call.get('name'), args=call.get('arguments'))
                if result: return result
        return None
    if name not in {'phone_act', 'phone_run_plan'}:
        return None
    if name == 'phone_run_plan':
        # Plan arguments are validated by actions.query_plan before any device call.
        return None
    try:
        validate_action(args)
    except Denied:
        return {'action': 'block', 'message': messages.text(_CTX, 'confirmation.required')}
    return None
