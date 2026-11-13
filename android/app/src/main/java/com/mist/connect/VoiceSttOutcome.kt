package com.mist.connect

import org.json.JSONObject

/** Display-only fixed vocabulary. Never speak/retry, infer silence, or reflect wire detail. */
internal object VoiceSttOutcome {
    private val reasons = setOf("recognized", "no_match", "speech_timeout", "empty_final",
        "incomplete", "cancelled", "cleanup_unconfirmed", "failed")
    data class Facts(val reason: String, val cleanupConfirmed: Boolean)

    fun facts(value: JSONObject): Facts? {
        if (value.opt("stt_contract") !is Int || value.opt("stt_contract") != 2) return null
        val reason = value.opt("stt_reason") as? String ?: return null
        val cleanup = value.opt("stt_cleanup_confirmed") as? Boolean ?: return null
        if (reason !in reasons || (reason == "recognized" && !cleanup) ||
            (reason == "cleanup_unconfirmed" && cleanup)) return null
        return Facts(reason, cleanup)
    }

    fun failure(value: JSONObject): Facts? {
        if (value.opt("ok") != false || value.opt("error") != "transcription_failed" ||
            value.has("detail") || value.has("transcript") || value.has("command_reason") || value.has("accepted")) return null
        return facts(value)?.takeIf { it.reason != "recognized" }
    }

    fun accepted(value: JSONObject): String? {
        val modern = listOf("stt_contract", "stt_reason", "stt_cleanup_confirmed").any { value.has(it) }
        if (modern) {
            val facts = facts(value)
            if (facts == null || facts.reason != "recognized" || value.opt("ok") != true ||
                value.opt("accepted") !is Boolean || value.has("error") || value.has("no_speech") || value.has("stopped"))
                throw VoiceBridgeClient.BridgeException("bridge_response_invalid")
        }
        val command = value.opt("command_reason")
        if (command != null && command != JSONObject.NULL) {
            if (value.opt("accepted") != false || command !in setOf("stop_only", "wake_only"))
                throw VoiceBridgeClient.BridgeException("bridge_response_invalid")
            return if (command == "stop_only") AppText.get(R.string.rt_stt_stop_only, "중지 발화로 실행 없이 종료했습니다") else AppText.get(R.string.rt_stt_wake_only, "호출어만 인식하여 실행하지 않았습니다")
        }
        if (!modern && (value.opt("no_speech") == true || value.opt("stopped") == true))
            return AppText.get(R.string.rt_stt_legacy, "이전 버전에서 실행 없이 종료했습니다")
        return null
    }

    fun message(reason: String?, cleanup: Boolean? = null): String? {
        val text = when (reason) {
            "no_match" -> AppText.get(R.string.rt_stt_no_match, "음성을 인식하지 못했습니다")
            "speech_timeout" -> AppText.get(R.string.rt_stt_timeout, "발화를 감지하지 못했습니다")
            "empty_final" -> AppText.get(R.string.rt_stt_empty, "인식 결과가 비어 있어 실행하지 않았습니다")
            "incomplete" -> AppText.get(R.string.rt_stt_incomplete, "끝까지 처리하지 못해 실행하지 않았습니다")
            "cancelled" -> AppText.get(R.string.rt_stt_cancelled, "요청이 취소되었습니다")
            "cleanup_unconfirmed" -> AppText.get(R.string.rt_stt_cleanup, "음성 처리 정리를 확인하지 못했습니다")
            "failed" -> AppText.get(R.string.rt_stt_failed, "음성을 처리하지 못해 실행하지 않았습니다")
            else -> return null
        }
        return text + if (cleanup == false && reason != "cleanup_unconfirmed") (" · " + AppText.get(R.string.rt_stt_cleanup_also, "처리 정리도 확인하지 못했습니다")) else ""
    }
}
