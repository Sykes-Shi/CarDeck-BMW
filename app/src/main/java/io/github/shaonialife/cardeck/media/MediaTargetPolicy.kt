package io.github.shaonialife.cardeck.media

/** 车载播放页锁定应用；目标会话消失时保持空态，不能转控另一个正在发声的应用。 */
internal object MediaTargetPolicy {
    data class Candidate(
        val packageName: String,
        val playing: Boolean,
        val hasMetadata: Boolean,
    )

    fun pickIndex(candidates: List<Candidate>, targetPackage: String? = null): Int? {
        val matches = candidates.indices.filter { targetPackage == null || candidates[it].packageName == targetPackage }
        return matches.firstOrNull { candidates[it].playing }
            ?: matches.firstOrNull { candidates[it].hasMetadata }
            ?: matches.firstOrNull()
    }
}
