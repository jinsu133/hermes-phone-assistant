"""Fixed Android STT wire vocabulary; no arbitrary response data is reflected."""
FIELDS = ('stt_contract', 'stt_reason', 'stt_cleanup_confirmed')
ERROR_REASONS = {
    'stt_no_match': 'no_match', 'stt_speech_timeout': 'speech_timeout',
    'stt_empty_final': 'empty_final', 'stt_input_incomplete': 'incomplete',
    'stt_cancelled': 'cancelled', 'stt_cleanup_unconfirmed': 'cleanup_unconfirmed',
}
ERRORS = frozenset(ERROR_REASONS) | {
    'stt_wrong_thread', 'stt_pcm_invalid', 'stt_busy', 'stt_request_invalid',
    'stt_pipe_unavailable', 'stt_on_device_unavailable', 'stt_text_too_large',
    'stt_korean_model_missing', 'stt_language_model_missing', 'stt_source_io', 'stt_start_failed',
    'stt_setup_failed', 'stt_timeout',
} | {'stt_android_error_' + str(i) for i in range(1, 16)} | {
    'stt_support_error_' + str(i) for i in range(1, 16)}


def failure(code):
    return dict(success=False, transcript='', provider='android-native', error=code)


def validated(value):
    """Validate a v2 companion/provider envelope, never coerce types.

    Identity is independently checked by the HTTP adapter before calling this.
    Legacy APKs are intentionally NOT eligible for execution, even with text.
    """
    bad = failure('android_stt_response_invalid')
    if (type(value) is not dict or type(value.get('success')) is not bool
            or value.get('provider') != 'android-native' or value.get('on_device') is not True):
        return bad
    text = value.get('transcript')
    if type(text) is not str or len(text) > 6000:
        return bad
    if not any(k in value for k in FIELDS):
        return failure('android_stt_legacy_empty' if value['success'] and not text.strip()
                       else 'android_stt_contract_unsupported')
    if (type(value.get('stt_contract')) is not int or value['stt_contract'] != 2
            or type(value.get('stt_reason')) is not str
            or type(value.get('stt_cleanup_confirmed')) is not bool
            or ('no_speech' in value and value['no_speech'] is not False)):
        return bad
    reason, cleanup = value['stt_reason'], value['stt_cleanup_confirmed']
    if value['success']:
        if not text.strip() or reason != 'recognized' or not cleanup or 'error' in value:
            return bad
        result = dict(success=True, transcript=text, provider='android-native', no_speech=False)
    else:
        error = value.get('error')
        if (text != '' or type(error) is not str or error not in ERRORS
                or reason != ERROR_REASONS.get(error, 'failed')
                or (reason == 'cleanup_unconfirmed' and cleanup)):
            return bad
        result = failure(error)
    result.update(on_device=True, **{k: value[k] for k in FIELDS})
    return result


def bridge_fields(result):
    """Only validated v2 facts cross /audio; local adapter failures have no facts."""
    checked = validated(result)
    return {k: checked[k] for k in FIELDS if k in checked}
