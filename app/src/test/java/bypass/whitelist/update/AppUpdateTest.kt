package bypass.whitelist.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two decisions that stand between a published release and the
 * user: whether it is newer than what is running, and which of its files is
 * the one to hand over.
 *
 * Both fail quietly when they are wrong. A string comparison hides 1.3.10
 * behind 1.3.7 and the update is simply never offered; the wrong asset hands
 * someone a checksum file or a debug build that Android then refuses to
 * install. Neither shows up as a crash, so it has to show up here.
 */
class AppUpdateTest {

    @Test
    fun `a later version is newer, in numbers not letters`() {
        // The whole reason the comparison is not a string one: "1.3.10" sorts
        // before "1.3.7" as text, which would hide the release it names.
        assertTrue(AppUpdate.isNewer("1.3.10", "1.3.7"))
        assertFalse(AppUpdate.isNewer("1.3.7", "1.3.10"))
        assertTrue(AppUpdate.isNewer("1.4.0", "1.3.9"))
        assertTrue(AppUpdate.isNewer("2.0.0", "1.99.99"))
        assertFalse(AppUpdate.isNewer("1.3.6", "1.3.7"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(AppUpdate.isNewer("1.3.7", "1.3.7"))
        // Trailing zeros are the same release written longer, not a later one.
        assertFalse(AppUpdate.isNewer("1.3.7.0", "1.3.7"))
        assertFalse(AppUpdate.isNewer("1.3", "1.3.0"))
        assertTrue(AppUpdate.isNewer("1.3.7.1", "1.3.7"))
    }

    @Test
    fun `the tag's leading v is not part of the version`() {
        assertTrue(AppUpdate.isNewer("v1.4.0", "1.3.7"))
        assertFalse(AppUpdate.isNewer("v1.3.7", "1.3.7"))
        assertEquals("1.4.0", AppUpdate.versionOf("v1.4.0"))
        assertEquals("1.4.0", AppUpdate.versionOf(" 1.4.0 "))
    }

    @Test
    fun `a tag that cannot be read is not an update`() {
        // Reported as "nothing new" rather than guessed at: guessing either
        // nags every user forever or buries a real release.
        assertFalse(AppUpdate.isNewer("nightly", "1.3.7"))
        assertFalse(AppUpdate.isNewer("v1.4.0-rc1", "1.3.7"))
        assertFalse(AppUpdate.isNewer("", "1.3.7"))
        assertFalse(AppUpdate.isNewer("v", "1.3.7"))
        assertFalse(AppUpdate.isNewer("1..4", "1.3.7"))
        assertFalse(AppUpdate.isNewer("1.4.0", "snapshot"))
        // "+4" parses as a number to Kotlin and must not parse as one here.
        assertFalse(AppUpdate.isNewer("1.+4.0", "1.3.7"))
    }

    @Test
    fun `the apk is picked out of everything else the release carries`() {
        val assets = listOf(
            AppUpdate.Asset("mapping.txt", "https://example.invalid/mapping.txt", 900_000L),
            AppUpdate.Asset("prometheus-connect-1.4.0.apk.sha256", "https://example.invalid/sum", 64L),
            AppUpdate.Asset("prometheus-connect-1.4.0-debug.apk", "https://example.invalid/debug", 40_000_000L),
            AppUpdate.Asset("prometheus-connect-1.4.0.apk", "https://example.invalid/apk", 34_000_000L),
        )
        val picked = AppUpdate.pickApk(assets, "1.4.0")
        assertEquals("prometheus-connect-1.4.0.apk", picked?.name)
        assertEquals(34_000_000L, picked?.sizeBytes)
    }

    @Test
    fun `an apk left over from an earlier build loses to the one named after this release`() {
        val assets = listOf(
            AppUpdate.Asset("prometheus-connect-1.3.9.apk", "https://example.invalid/old", 33_000_000L),
            AppUpdate.Asset("prometheus-connect-1.4.0.apk", "https://example.invalid/new", 34_000_000L),
        )
        assertEquals("prometheus-connect-1.4.0.apk", AppUpdate.pickApk(assets, "1.4.0")?.name)
    }

    @Test
    fun `a release with no installable file offers nothing`() {
        val assets = listOf(
            AppUpdate.Asset("prometheus-connect-1.4.0.apk.sha256", "https://example.invalid/sum", 64L),
            AppUpdate.Asset("prometheus-connect-1.4.0-unsigned.apk", "https://example.invalid/u", 34_000_000L),
        )
        assertNull(AppUpdate.pickApk(assets, "1.4.0"))
    }

    @Test
    fun `a newer release parses into what the screen shows`() {
        val update = AppUpdate.parse(releaseJson(tag = "v1.4.0"), currentVersion = "1.3.7")
        assertNotNull(update)
        assertEquals("1.4.0", update?.version)
        assertEquals("prometheus-connect-1.4.0.apk", update?.assetName)
        assertEquals(
            "https://github.com/prometheus-connect/prometheus-connect-app/releases/download/v1.4.0/prometheus-connect-1.4.0.apk",
            update?.downloadUrl,
        )
        assertEquals(34_012_345L, update?.sizeBytes)
        assertEquals(
            "https://github.com/prometheus-connect/prometheus-connect-app/releases/tag/v1.4.0",
            update?.pageUrl,
        )
        assertEquals("Split routing is on by default.\nFaster reconnects.", update?.notes)
    }

    @Test
    fun `the release we are already running is not offered`() {
        assertNull(AppUpdate.parse(releaseJson(tag = "v1.3.7"), currentVersion = "1.3.7"))
        assertNull(AppUpdate.parse(releaseJson(tag = "v1.3.6"), currentVersion = "1.3.7"))
        assertNull(AppUpdate.parse(releaseJson(tag = "nightly"), currentVersion = "1.3.7"))
    }

    @Test
    fun `an answer that is not a release is not an update`() {
        // What a rate-limited or blocked api.github.com actually returns.
        assertNull(AppUpdate.parse("""{"message":"API rate limit exceeded"}""", "1.3.7"))
        assertNull(AppUpdate.parse("<html>403</html>", "1.3.7"))
        assertNull(AppUpdate.parse("", "1.3.7"))
    }

    @Test
    fun `long notes are cut on a line boundary`() {
        val body = (1..80).joinToString("\n") { "line $it padded out a little" }
        val trimmed = AppUpdate.trimNotes(body, limit = 100)
        assertTrue(trimmed.endsWith("\n…"))
        assertFalse(trimmed.removeSuffix("\n…").endsWith("padded out a l"))
        assertTrue(trimmed.startsWith("line 1 padded out a little"))
    }

    @Test
    fun `notes that fit are left alone`() {
        assertEquals("Two lines.\nThat is all.", AppUpdate.trimNotes("  Two lines.\r\nThat is all.\n\n"))
    }

    private fun releaseJson(tag: String): String = """
        {
          "tag_name": "$tag",
          "name": "Prometheus Connect $tag",
          "body": "Split routing is on by default.\r\nFaster reconnects.",
          "html_url": "https://github.com/prometheus-connect/prometheus-connect-app/releases/tag/$tag",
          "assets": [
            {
              "name": "prometheus-connect-1.4.0.apk.sha256",
              "browser_download_url": "https://github.com/prometheus-connect/prometheus-connect-app/releases/download/$tag/prometheus-connect-1.4.0.apk.sha256",
              "size": 64
            },
            {
              "name": "prometheus-connect-1.4.0.apk",
              "browser_download_url": "https://github.com/prometheus-connect/prometheus-connect-app/releases/download/$tag/prometheus-connect-1.4.0.apk",
              "size": 34012345
            }
          ]
        }
    """.trimIndent()
}
