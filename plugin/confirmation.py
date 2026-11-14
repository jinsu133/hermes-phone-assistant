"""Mandatory phone-action pre-tool hook. Approval is owned by Android, never the LLM."""
from .actions import FIELDS, validate_action
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
        return {'action': 'block', 'message': _refusal(args)}
    return None


# Argument names a model may use to try to self-approve; these get the approval explanation.
_APPROVAL_KEYS = {'confirmation', 'confirm', 'confirmed', 'approve', 'approved', 'approval', 'consent'}


def _refusal(args):
    """Explain why phone_act arguments were refused.

    Only an approval-shaped argument gets PHONE_CONFIRMATION_REQUIRED. A plain argument
    mistake (for example open_app with an app name instead of a package) gets a fix-it
    hint, so the model does not treat a typo as an approval problem.
    """
    action = args.get('action') if isinstance(args, dict) else None
    if (not isinstance(args, dict) or action not in FIELDS
            or set(args) & _APPROVAL_KEYS):
        return messages.text(_CTX, 'confirmation.required')
    allowed = ', '.join(sorted(FIELDS[action][0] - {'action'})) or '-'
    return messages.text(_CTX, 'action.invalid_arguments', action=action, allowed=allowed)
