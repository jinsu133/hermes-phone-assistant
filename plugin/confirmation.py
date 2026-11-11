"""Mandatory phone-action pre-tool hook. Approval is owned by Android, never the LLM."""
from .actions import validate_action
from .backend import Denied


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
        return {'action': 'block', 'message': (
            'PHONE_CONFIRMATION_REQUIRED: 승인 여부를 도구 인자로 전달할 수 없습니다. '
            '결제는 confirmation.kind=payment와 merchant(대상), amount(최종 금액), method(결제 수단)가 필요합니다. '
            '전화는 kind=call/recipient, 메시지는 kind=message/recipient/content(전문)가 필요합니다. '
            '검증된 일상 동작은 별도 승인 없이 실행합니다. 그 외 지원되는 조작은 음성 요청이면 명시적 허용 발화를, 일반 텍스트 요청이면 기기 확인을 기다립니다. 무응답은 승인되지 않습니다.')}
    return None
