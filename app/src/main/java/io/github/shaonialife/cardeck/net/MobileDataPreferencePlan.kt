package io.github.shaonialife.cardeck.net

/** Android 16 ConnectivitySettingsManager uses semicolons in the Secure setting.
 * ConnectivityService dumps the corresponding in-memory ArraySet on a separate line.
 */
internal object MobileDataPreferencePlan {
    private val packageRow = Regex("""package:([A-Za-z0-9_.]+)\s+uid:(\d+)""")
    private val uidToken = Regex("""[0-9]+""")
    const val userRange = 100_000

    fun parseSetting(raw: String): Set<Int>? {
        if (raw == "null" || raw.isEmpty()) return emptySet()
        if (raw.any { it !in '0'..'9' && it != ';' }) return null
        return numbers(raw, ';')
    }

    private fun numbers(raw: String, separator: Char): Set<Int>? {
        val result = mutableSetOf<Int>()
        raw.split(separator).forEach {
            val token = it.trim()
            if (!uidToken.matches(token)) return null
            result.add(token.toIntOrNull() ?: return null)
        }
        return result
    }

    fun observedUids(output: String): Set<Int>? {
        val lines = output.lineSequence().map { it.trim() }.toList()
        if ("Default requests:" !in lines) return null
        val i = lines.indexOf("Mobile data preferred UIDs:")
        if (i < 0) return emptySet()
        val line = lines.getOrNull(i + 1) ?: return null
        if (!(line.startsWith('{') && line.endsWith('}') || line.startsWith('[') && line.endsWith(']'))) return null
        val contents = line.substring(1, line.lastIndex).trim()
        return if (contents.isEmpty()) emptySet() else numbers(contents, ',')
    }

    fun installed(output: String, user: Int): Map<Int, Set<String>>? {
        val groups = mutableMapOf<Int, MutableSet<String>>()
        for (line in output.lineSequence().filter { it.isNotBlank() }) {
            val m = packageRow.matchEntire(line.trim()) ?: return null
            val uid = m.groupValues[2].toIntOrNull() ?: return null
            if (uid / userRange == user) groups.getOrPut(uid) { mutableSetOf() }.add(m.groupValues[1])
        }
        return groups
    }

    data class Change(
        val after: Set<Int>,
        val owners: Map<Int, Set<String>>,
        val target: Map<Int, Set<String>>,
        val skipped: Set<String>,
        val reassigned: Set<Int>,
    )

    fun plan(
        before: Set<Int>,
        owned: Map<Int, Set<String>>,
        installed: Map<Int, Set<String>>,
        eligible: Set<String>,
        selected: Set<String>,
        selfUid: Int,
    ): Change {
        val target = installed.filter { (uid, names) ->
            uid != selfUid && uid / userRange == selfUid / userRange && uid % userRange in 10_000..19_999 &&
                names.isNotEmpty() && names.all { it in selected && it in eligible }
        }
        // A changed identity may represent a reused UID. Relinquish ownership without removing its preference.
        val reassigned = owned.keys.filterTo(mutableSetOf()) { it in before && installed[it] != owned[it] && installed[it] != null }
        val removable = owned.keys.filter { uid ->
            uid !in target && uid / userRange == selfUid / userRange &&
                (installed[uid] == null || installed[uid] == owned[uid])
        }
        val after = (before - removable) + target.keys
        val owners = target.filter { (uid, names) -> uid !in before || owned[uid] == names }
        return Change(after, owners, target, selected - target.values.flatten().toSet(), reassigned)
    }
}
