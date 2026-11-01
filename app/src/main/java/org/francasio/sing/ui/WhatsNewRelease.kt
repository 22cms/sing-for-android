package org.francasio.sing.ui

import org.francasio.sing.provider.ENGINE_VERSION
import org.francasio.sing.provider.IS_MINOR_UPDATE

internal object WhatsNewRelease {
    val RELEASE_MARKER: String = ENGINE_VERSION
    const val PREFS_NAME = "whats_new"
    const val KEY_LAST_SEEN_RELEASE = "last_seen_release_marker"

    fun isAppUpdated(
        lastSeenRelease: String?,
        firstInstallTime: Long,
        lastUpdateTime: Long
    ): Boolean = lastSeenRelease != RELEASE_MARKER &&
        if (lastSeenRelease != null) {
            true
        } else {
            // Older app versions did not store this preference. Android keeps
            // firstInstallTime across upgrades, unlike on a fresh installation.
            lastUpdateTime > firstInstallTime
        }

    fun shouldShow(
        lastSeenRelease: String?,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        isMinorUpdate: Boolean = false
    ): Boolean = !isMinorUpdate && isAppUpdated(lastSeenRelease, firstInstallTime, lastUpdateTime)
}
