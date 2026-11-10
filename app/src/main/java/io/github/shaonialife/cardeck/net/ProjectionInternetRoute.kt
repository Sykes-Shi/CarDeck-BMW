package io.github.shaonialife.cardeck.net

/** Normal selection follows the UID's VPN. Only the separately authorized lyric retry uses cellular. */
internal object ProjectionInternetRoute {
    data class Candidate<N>(
        val network: N,
        val validated: Boolean,
        val vpn: Boolean,
        val ordinaryCellular: Boolean,
    )

    fun <N> select(default: Candidate<N>?, carWifi: N?, alternatives: () -> List<Candidate<N>>): N? {
        // Android returns no default for an offline or blocked UID. Do not cross that boundary.
        default ?: return null
        if (default.vpn) return default.network.takeIf { default.validated }
        if (default.network != carWifi && default.validated) return default.network
        return alternatives().firstOrNull { it.network != carWifi && it.validated && !it.vpn && it.ordinaryCellular }?.network
    }

    fun <N> permitsLyricsRetry(failedDefault: N, current: Candidate<N>?, carWifi: N?, candidate: Candidate<N>?): Boolean =
        current?.network == failedDefault && current?.vpn == true && current.validated && carWifi != null &&
            candidate != null && candidate.network != carWifi && candidate.network != failedDefault &&
            candidate.validated && !candidate.vpn && candidate.ordinaryCellular
}
