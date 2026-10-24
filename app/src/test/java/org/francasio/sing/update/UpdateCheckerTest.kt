package org.francasio.sing.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun normalizesGithubTags() {
        assertEquals("1.2.0", UpdateChecker.normalizeVersion("v1.2.0"))
        assertEquals("2.0", UpdateChecker.normalizeVersion("2.0"))
        assertNull(UpdateChecker.normalizeVersion("latest"))
    }

    @Test fun comparesNumericVersions() {
        assertTrue(UpdateChecker.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"))
        assertTrue(UpdateChecker.compareVersions("2.0", "1.99") > 0)
    }

    @Test fun parsesNewStableRelease() {
        val result = UpdateChecker.parseRelease(
            """{"tag_name":"v1.2","html_url":"https://example.com/release","body":"Changes","draft":false,"prerelease":false}""",
            "1.1"
        )
        assertEquals("1.2", result?.availableVersion)
        assertEquals("Changes", result?.releaseNotes)
    }

    @Test fun ignoresCurrentOlderAndPrereleaseVersions() {
        assertNull(UpdateChecker.parseRelease(
            """{"tag_name":"v1.0","html_url":"https://example.com","draft":false,"prerelease":false}""",
            "1.1"
        ))
        assertNull(UpdateChecker.parseRelease(
            """{"tag_name":"v1.2-beta","html_url":"https://example.com","draft":false,"prerelease":true}""",
            "1.1"
        ))
    }
}
