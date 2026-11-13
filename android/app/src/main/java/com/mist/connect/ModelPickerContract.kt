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
        // The working model always follows the Hermes default profile.
        return AppText.get(R.string.rt_pick_follows_default, "모델은 Hermes 기본 프로필을 따르며 여기서 바꿀 수 없습니다.")
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
