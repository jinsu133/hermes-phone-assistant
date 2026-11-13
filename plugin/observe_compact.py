"""Smaller phone_observe results for the model.

A screen snapshot expires in 15 seconds but stays in the conversation, so its size is
paid again on every later turn. Node fields at their default value are left out, and
the default values are stated once per result so nothing becomes ambiguous.
"""
NODE_DEFAULTS = {
    'text': '', 'label': '', 'resource_id': '', 'class': '', 'clickable_parent': '',
    'role': '', 'target_url': '', 'editable': False, 'supports_search_submit': False,
    'text_truncated': False, 'label_truncated': False, 'clickable': False,
    'scrollable': False, 'enabled': True, 'focused': False,
}


def _is_default(key, value):
    default = NODE_DEFAULTS.get(key, ...)
    return default is not ... and type(value) is type(default) and value == default


def compact_observation(value):
    if type(value) is not dict or type(value.get('nodes')) is not list:
        return value
    out = dict(value)
    out['nodes'] = [{k: v for k, v in node.items() if not _is_default(k, v)} if type(node) is dict else node
                    for node in value['nodes']]
    diagnosis = out.get('detail_diagnosis')
    if type(diagnosis) is dict and not diagnosis.get('detail_reason'):
        del out['detail_diagnosis']
    out['node_defaults'] = dict(NODE_DEFAULTS)
    return out
