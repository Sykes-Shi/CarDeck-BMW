package io.github.shaonialife.cardeck.net

/** Only the phone assistant explicitly requested in this projection may receive this exception. */
internal object BixbyNetworkPolicy {
    const val PACKAGE = "com.samsung.android.bixby.agent"

    fun needsPreference(route: ProjectedUidRoute.Kind, owned: Boolean, direct: () -> Boolean?): Boolean {
        val carOrExistingLease = route == ProjectedUidRoute.Kind.CAR_WIFI ||
            (owned && route == ProjectedUidRoute.Kind.CELLULAR)
        return carOrExistingLease && direct() == true
    }
}
