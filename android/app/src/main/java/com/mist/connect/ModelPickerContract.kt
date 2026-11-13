package com.mist.connect

/** Framework-free exact picker contract. No configuration/network authority. */
data class ModelPickerSelection(val provider: String?, val model: String?, val effort: String?)
data class ModelPickerModel(val id: String, val reasoning: Boolean?, val canDisable: Boolean?,
                            val unavailable: Boolean? = null)
data class ModelPickerProvider(val id: String, val aliases: List<String>, val auth: String,
    val models: List<ModelPickerModel>, val nonselectableAliases: List<String> = emptyList(),
    val unavailableModels: List<String>? = null, val freeTierPending: Boolean? = null)
data class ModelPickerSnapshot(val revision: Long, val liveRevision: Long?, val liveMatches: Boolean,
    val operatorWriteReady: Boolean, val hostProvider: String, val hostModel: String,
    val providers: List<ModelPickerProvider>, val efforts: List<String>, val selection: ModelPickerSelection)

object ModelPickerContract {
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:/@+~\\-]{0,511}")
    private val metadataAlias = Regex("[A-Za-z0-9][A-Za-z0-9 ._:/+~\\-]{0,511}")
    private val credentialHint = Regex("(?:^|[^A-Za-z0-9])(?:sk-|AIza|Bearer|ghp_|github_pat_)", RegexOption.IGNORE_CASE)
    private val effortValues = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    const val MAX_REVISION = Long.MAX_VALUE

    fun validIdentifier(value: String): Boolean = identifier.matches(value) &&
        listOf("sk-", "AIza", "Bearer", "ghp_", "github_pat_").none { value.startsWith(it) }

    // Display-only stock aliases may contain spaces. Never trim or make them writable.
    fun validMetadataAlias(value: String): Boolean = metadataAlias.matches(value) &&
        !value.contains("://") && !credentialHint.containsMatchIn(value)

    fun availabilityLabel(row: ModelPickerProvider?, model: ModelPickerModel?): String = when {
        row?.freeTierPending == true -> AppText.get(R.string.rt_avail_pending, "이용 권한 확인 대기 · 조회 시점 미확인 (선택 불가)")
        model?.unavailable == true -> AppText.get(R.string.rt_avail_unavailable, "카탈로그에서 사용 불가 (선택 불가)")
        model?.unavailable == false -> AppText.get(R.string.rt_avail_not_listed, "사용 불가 목록에 없음 (실제 이용 가능 여부는 미확인)")
        else -> AppText.get(R.string.rt_avail_unknown, "이용 가능 여부 미확인")
    }

    fun provider(snapshot: ModelPickerSnapshot, value: ModelPickerSelection): ModelPickerProvider? {
        val id = value.provider ?: snapshot.hostProvider
        return snapshot.providers.filter { it.id == id || id in it.aliases }.singleOrNull()
    }

    fun validationError(snapshot: ModelPickerSnapshot, value: ModelPickerSelection): String? {
        if (snapshot.revision !in 0..MAX_REVISION) return AppText.get(R.string.rt_pick_revision, "설정 revision이 잘못되었습니다.")
        if (listOf(value.provider, value.model).any { it != null && !validIdentifier(it) })
            return AppText.get(R.string.rt_pick_identifier, "식별자를 변경하거나 공백을 보정할 수 없습니다. 목록에서 정확히 선택하세요.")
        if (value.effort != null && (value.effort !in snapshot.efforts || value.effort !in effortValues))
            return AppText.get(R.string.rt_pick_effort, "API가 받는 추론 강도가 아닙니다.")
        if (value.provider == null && value.model == null) return null
        if (value.provider == null || value.model == null)
            return AppText.get(R.string.rt_pick_partial, "제공자와 모델을 모두 지정하거나 둘 다 상속하세요. 일부만 상속한 실행 경로는 확인할 수 없습니다.")
        val row = provider(snapshot, value) ?: return AppText.get(R.string.rt_pick_no_provider, "선택한 제공자가 현재 카탈로그에 없습니다.")
        if (row.auth != "ready") return AppText.get(R.string.rt_pick_auth, "선택한 제공자의 인증 준비를 확인하지 못했습니다.")
        val model = row.models.singleOrNull { it.id == (value.model ?: snapshot.hostModel) }
            ?: return AppText.get(R.string.rt_pick_no_model, "선택한 모델이 해당 제공자 목록에 없습니다. 모델을 직접 다시 선택하세요.")
        if (model.unavailable == true || value.model in (row.unavailableModels ?: emptyList()))
            return AppText.get(R.string.rt_pick_model_unavailable, "카탈로그가 이 모델을 사용 불가로 표시합니다. 전달 명령을 만들 수 없습니다.")
        if (row.freeTierPending == true)
            return AppText.get(R.string.rt_pick_pending, "이용 권한이 확인 대기 상태입니다. 카탈로그를 다시 조회한 뒤 선택하세요.")
        if (value.effort != null && (model.reasoning == false ||
                (value.effort == "none" && model.canDisable == false)))
            return AppText.get(R.string.rt_pick_reasoning, "카탈로그가 이 추론 요청을 지원하지 않는다고 표시합니다.")
        return null
    }

    /** Canonical closed JSON; identifier validation excludes quotes, shell delimiters and whitespace. */
    fun operatorCommand(snapshot: ModelPickerSnapshot, value: ModelPickerSelection): String {
        require(validationError(snapshot, value) == null) { "MODEL_SELECTION_INVALID" }
        fun field(v: String?): String = if (v == null) "null" else "\"$v\""
        return "/phone-execution select ${snapshot.revision} '{\"provider\":${field(value.provider)}," +
            "\"model\":${field(value.model)},\"reasoning_effort\":${field(value.effort)}}'"
    }

    fun savedMatches(snapshot: ModelPickerSnapshot, draft: ModelPickerSelection): Boolean =
        snapshot.selection == draft
}
