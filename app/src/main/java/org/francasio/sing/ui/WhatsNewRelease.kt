package org.francasio.sing.ui

/** Bump this marker only when the notes in [ReleaseHighlightsSheet] change. */
internal object WhatsNewRelease {
    const val RELEASE_MARKER = "1.1-lyrics-and-audio-update"
    const val PREFS_NAME = "whats_new"
    const val KEY_LAST_SEEN_RELEASE = "last_seen_release_marker"

    fun shouldShow(
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
}
