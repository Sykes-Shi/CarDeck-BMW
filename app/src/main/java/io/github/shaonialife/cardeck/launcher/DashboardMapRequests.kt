package io.github.shaonialife.cardeck.launcher

/** One dashboard map check per presentation. Each projection/navigation session owns its instance. */
internal class DashboardMapRequests {
    private var lastPresentation = Long.MIN_VALUE

    @Synchronized
    fun claim(presentation: Long): Boolean {
        if (presentation <= lastPresentation) return false
        lastPresentation = presentation
        return true
    }
}
