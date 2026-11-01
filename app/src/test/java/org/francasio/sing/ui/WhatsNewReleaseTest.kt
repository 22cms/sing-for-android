package org.francasio.sing.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewReleaseTest {
    @Test fun freshInstallDoesNotShowReleaseNotes() {
        assertFalse(WhatsNewRelease.shouldShow(null, 100, 100))
    }

    @Test fun existingInstallSeesNotesAfterUpgradeWithoutOldPreference() {
        assertTrue(WhatsNewRelease.shouldShow(null, 100, 200))
    }

    @Test fun differentReleaseMarkerTriggersOnlyOnce() {
        assertTrue(WhatsNewRelease.shouldShow("previous-release", 100, 200))
        assertFalse(WhatsNewRelease.shouldShow(WhatsNewRelease.RELEASE_MARKER, 100, 200))
    }

    @Test fun minorUpdateSuppressesPopupWhileDetectingUpdate() {
        assertTrue(WhatsNewRelease.isAppUpdated("previous-release", 100, 200))
        assertFalse(WhatsNewRelease.shouldShow("previous-release", 100, 200, isMinorUpdate = true))
    }

    @Test fun majorUpdateShowsPopupAndDetectsUpdate() {
        assertTrue(WhatsNewRelease.isAppUpdated("previous-release", 100, 200))
        assertTrue(WhatsNewRelease.shouldShow("previous-release", 100, 200, isMinorUpdate = false))
    }
}
