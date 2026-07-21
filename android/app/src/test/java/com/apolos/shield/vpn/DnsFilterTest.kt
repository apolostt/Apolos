package com.apolos.shield.vpn

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Unit tests for [DnsFilter]. [Context] and [AssetManager] are Android
 * framework classes, so they are mocked with Mockito rather than instantiated
 * directly; `filesDir` is backed by a real temporary directory so the
 * user-blocklist-merging behaviour can be exercised with real file I/O.
 */
class DnsFilterTest {

    /** Builds a Context whose assets throw (simulating a missing/failing asset) and whose filesDir is empty. */
    private fun contextWithNoAssetsAndFilesDir(filesDir: java.io.File): Context {
        val assets = mock(AssetManager::class.java)
        `when`(assets.open("blocklist.txt")).thenThrow(IOException("not found"))

        val context = mock(Context::class.java)
        `when`(context.assets).thenReturn(assets)
        `when`(context.filesDir).thenReturn(filesDir)
        return context
    }

    /** Builds a Context whose assets/blocklist.txt contains the given text. */
    private fun contextWithAssetBlocklist(assetText: String, filesDir: java.io.File): Context {
        val assets = mock(AssetManager::class.java)
        `when`(assets.open("blocklist.txt"))
            .thenReturn(ByteArrayInputStream(assetText.toByteArray(Charsets.UTF_8)))

        val context = mock(Context::class.java)
        `when`(context.assets).thenReturn(assets)
        `when`(context.filesDir).thenReturn(filesDir)
        return context
    }

    // ---------------------------------------------------------------
    // Seed list behaviour
    // ---------------------------------------------------------------

    @Test
    fun `isBlocked matches a seed domain exactly`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        assertTrue(filter.isBlocked("doubleclick.net"))
        assertTrue(filter.isBlocked("graph.facebook.com"))
    }

    @Test
    fun `isBlocked matches subdomains of a seed domain`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        assertTrue(filter.isBlocked("ads.doubleclick.net"))
        assertTrue(filter.isBlocked("a.b.c.doubleclick.net"))
    }

    @Test
    fun `isBlocked returns false for an unrelated domain`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        assertFalse(filter.isBlocked("example.com"))
        assertFalse(filter.isBlocked("my-bank.example.org"))
    }

    @Test
    fun `isBlocked handles null and empty hosts gracefully`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        assertFalse(filter.isBlocked(null))
        assertFalse(filter.isBlocked(""))
    }

    @Test
    fun `a failed asset load still leaves the seed list intact`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        // Seed set has 16 entries (see DnsFilter.SEED); asset + user files failed/empty.
        assertEquals(16, filter.size())
    }

    // ---------------------------------------------------------------
    // Asset blocklist merging
    // ---------------------------------------------------------------

    @Test
    fun `domains from the bundled asset blocklist are merged with the seed set`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val assetText = "tracker.example.com\n# a comment line\n\nother.example.net\n"
        val filter = DnsFilter(contextWithAssetBlocklist(assetText, emptyDir))

        assertTrue(filter.isBlocked("tracker.example.com"))
        assertTrue(filter.isBlocked("sub.tracker.example.com"))
        assertTrue(filter.isBlocked("other.example.net"))
        // Still blocks seed entries too.
        assertTrue(filter.isBlocked("doubleclick.net"))
        assertEquals(16 + 2, filter.size())
    }

    @Test
    fun `inline comments and blank lines in the asset blocklist are ignored`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val assetText = "good.example.com # tracker\n   \n#fully commented out\n"
        val filter = DnsFilter(contextWithAssetBlocklist(assetText, emptyDir))

        assertTrue(filter.isBlocked("good.example.com"))
        assertFalse(filter.isBlocked("fully"))
        assertEquals(16 + 1, filter.size())
    }

    @Test
    fun `wildcard prefixes in the asset blocklist are stripped`() {
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val assetText = "*.wild.example.com\n"
        val filter = DnsFilter(contextWithAssetBlocklist(assetText, emptyDir))

        assertTrue(filter.isBlocked("wild.example.com"))
        assertTrue(filter.isBlocked("sub.wild.example.com"))
    }

    // ---------------------------------------------------------------
    // User blocklist merging (real file on disk)
    // ---------------------------------------------------------------

    @Test
    fun `domains from files dir user_blocklist txt are merged`() {
        val dir = Files.createTempDirectory("apolos-test-user").toFile()
        java.io.File(dir, "user_blocklist.txt").writeText(
            "*.mytracker.com\nfoo.example.org # personal rule\n"
        )
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(dir))

        assertTrue(filter.isBlocked("mytracker.com"))
        assertTrue(filter.isBlocked("api.mytracker.com"))
        assertTrue(filter.isBlocked("foo.example.org"))
    }

    @Test
    fun `no user_blocklist file present does not throw and keeps seed size`() {
        val dir = Files.createTempDirectory("apolos-test-nouser").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(dir))
        assertEquals(16, filter.size())
    }

    // ---------------------------------------------------------------
    // Regression / edge case: case sensitivity of isBlocked's input
    // ---------------------------------------------------------------

    @Test
    fun `isBlocked is case-sensitive for the queried host even though stored entries are lowercased`() {
        // Blocklist entries are lowercased while loading, but isBlocked() does not
        // lowercase its `host` argument. This test documents that current, exact
        // behaviour so a future change in either direction is caught by a test.
        val emptyDir = Files.createTempDirectory("apolos-test").toFile()
        val filter = DnsFilter(contextWithNoAssetsAndFilesDir(emptyDir))

        assertTrue(filter.isBlocked("doubleclick.net"))
        assertFalse(filter.isBlocked("DoubleClick.net"))
    }
}