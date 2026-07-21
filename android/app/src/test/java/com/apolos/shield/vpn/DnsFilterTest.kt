package com.apolos.shield.vpn

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Unit tests for [DnsFilter]'s blocklist loading and suffix-matching logic.
 * [Context] is mocked so the test never touches real Android assets/files.
 */
class DnsFilterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Builds a mocked [Context] whose assets/filesDir behave as configured. */
    private fun mockContext(
        assetBytes: ByteArray? = null,
        userBlocklist: String? = null,
    ): Context {
        val context = mock(Context::class.java)
        val assetManager = mock(AssetManager::class.java)
        `when`(context.assets).thenReturn(assetManager)

        if (assetBytes != null) {
            `when`(assetManager.open("blocklist.txt")).thenReturn(ByteArrayInputStream(assetBytes))
        } else {
            `when`(assetManager.open("blocklist.txt")).thenThrow(IOException("no asset"))
        }

        val filesDir = tempFolder.newFolder()
        `when`(context.filesDir).thenReturn(filesDir)
        if (userBlocklist != null) {
            filesDir.resolve("user_blocklist.txt").writeText(userBlocklist)
        }
        return context
    }

    // ---------------------------------------------------------------- seed list

    @Test
    fun `seed domains are blocked even without assets or a user blocklist`() {
        val filter = DnsFilter(mockContext())
        assertTrue(filter.isBlocked("doubleclick.net"))
        assertTrue(filter.isBlocked("graph.facebook.com"))
    }

    @Test
    fun `unrelated domains are not blocked`() {
        val filter = DnsFilter(mockContext())
        assertFalse(filter.isBlocked("example.com"))
        assertFalse(filter.isBlocked("anthropic.com"))
    }

    @Test
    fun `an asset read failure is tolerated and the seed list still applies`() {
        // mockContext() with assetBytes = null makes assetManager.open throw,
        // which the constructor must swallow rather than propagate.
        val filter = DnsFilter(mockContext())
        assertTrue(filter.isBlocked("doubleclick.net"))
    }

    // ---------------------------------------------------------------- suffix matching

    @Test
    fun `subdomains of a blocked domain are also blocked`() {
        val filter = DnsFilter(mockContext())
        assertTrue(filter.isBlocked("sub.doubleclick.net"))
        assertTrue(filter.isBlocked("a.b.c.doubleclick.net"))
    }

    @Test
    fun `a domain that merely contains a blocked substring is not blocked`() {
        val filter = DnsFilter(mockContext())
        // "notdoubleclick.net" is not a subdomain of "doubleclick.net".
        assertFalse(filter.isBlocked("notdoubleclick.net"))
    }

    @Test
    fun `null and empty host are never blocked`() {
        val filter = DnsFilter(mockContext())
        assertFalse(filter.isBlocked(null))
        assertFalse(filter.isBlocked(""))
    }

    @Test
    fun `lookup is case-sensitive against the stored (lowercased) entries`() {
        val filter = DnsFilter(mockContext())
        // Seed entries are already lowercase; isBlocked does not itself lowercase input.
        assertFalse(filter.isBlocked("DoubleClick.net"))
        assertTrue(filter.isBlocked("doubleclick.net"))
    }

    // ---------------------------------------------------------------- assets loading

    @Test
    fun `domains from the bundled assets blocklist are honoured`() {
        val assetContent = "trackerone.example\nadnetwork.example\n"
        val filter = DnsFilter(mockContext(assetBytes = assetContent.toByteArray()))

        assertTrue(filter.isBlocked("trackerone.example"))
        assertTrue(filter.isBlocked("sub.adnetwork.example"))
    }

    @Test
    fun `asset blocklist comments and blank lines are ignored`() {
        val assetContent = """
            # this is a comment

            good.example # trailing comment
        """.trimIndent()
        val filter = DnsFilter(mockContext(assetBytes = assetContent.toByteArray()))

        assertTrue(filter.isBlocked("good.example"))
        assertFalse(filter.isBlocked("comment"))
    }

    @Test
    fun `asset blocklist entries are lowercased and wildcard prefixes are stripped`() {
        val assetContent = "*.WILDCARD.EXAMPLE\n"
        val filter = DnsFilter(mockContext(assetBytes = assetContent.toByteArray()))

        assertTrue(filter.isBlocked("wildcard.example"))
        assertTrue(filter.isBlocked("foo.wildcard.example"))
    }

    // ---------------------------------------------------------------- user blocklist

    @Test
    fun `domains from the user blocklist file are honoured`() {
        val filter = DnsFilter(mockContext(userBlocklist = "userblocked.example\n"))
        assertTrue(filter.isBlocked("userblocked.example"))
    }

    @Test
    fun `missing user blocklist file does not break construction`() {
        val filter = DnsFilter(mockContext())
        assertTrue(filter.isBlocked("doubleclick.net"))
    }

    @Test
    fun `asset and user blocklists are merged together with the seed list`() {
        val filter = DnsFilter(
            mockContext(
                assetBytes = "asset.example\n".toByteArray(),
                userBlocklist = "user.example\n",
            ),
        )

        assertTrue(filter.isBlocked("doubleclick.net")) // seed
        assertTrue(filter.isBlocked("asset.example"))    // assets
        assertTrue(filter.isBlocked("user.example"))     // user file
    }

    // ---------------------------------------------------------------- size()

    @Test
    fun `size reflects the seed list plus any merged entries`() {
        val baseline = DnsFilter(mockContext()).size()
        val withExtra = DnsFilter(
            mockContext(userBlocklist = "extra-one.example\nextra-two.example\n"),
        ).size()

        assertEquals(baseline + 2, withExtra)
    }

    @Test
    fun `size does not double count duplicate entries`() {
        val withDuplicate = DnsFilter(mockContext(userBlocklist = "doubleclick.net\n")).size()
        val baseline = DnsFilter(mockContext()).size()

        assertEquals(baseline, withDuplicate)
    }
}