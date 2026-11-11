"""Attach host-owned receipts to completed reads, not dispatches or model claims.

Receipt possession proves an eligible tool returned data in this request. It does
NOT establish that a model's public summary is semantically entailed by the data.
No tool content is persisted by the receipt ledger.
"""
import json


def evidence_result(tool, args, result):
    if tool not in {'web_search', 'web_extract', 'phone_observe', 'phone_act', 'voice_draft', 'read_file'}:
        return None
    if not isinstance(args, dict):
        return None
    if tool == 'phone_act' and args.get('action') not in {'read_node', 'list_apps'}:
        return None
    if tool == 'voice_draft' and args.get('action') not in {'read', 'read_document', 'check', 'run_numeric'}:
        return None
    if isinstance(result, str):
        if len(result) > 1048576:
            return None
        try:
            value = json.loads(result)
        except ValueError:
            return None
    else:
        value = result
    if not isinstance(value, dict) or value.get('error') or value.get('ok') is False or value.get('success') is False:
        return None
    if tool in {'phone_observe', 'phone_act', 'voice_draft'}:
        if value.get('ok') is not True or not isinstance(value.get('result'), dict) or value['result'].get('error'):
            return None
    elif tool == 'read_file':
        if not isinstance(value.get('content'), str) or not value['content']:
            return None
    else:
        # Exact public retrieval shapes, never treat an error-only result as evidence.
        rows = value.get('results')
        if tool == 'web_search' and isinstance(value.get('data'), dict):
            rows = value['data'].get('web')
        if not isinstance(rows, list) or not any(isinstance(row, dict) and not row.get('error')
                and any(isinstance(row.get(k), str) and row[k] for k in ('content', 'url', 'title')) for row in rows):
            return None
    return dict(value)
