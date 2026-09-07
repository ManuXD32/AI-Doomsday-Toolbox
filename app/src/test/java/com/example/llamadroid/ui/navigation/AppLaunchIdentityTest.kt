package com.example.llamadroid.ui.navigation

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLaunchIdentityTest {

    @Test
    fun `same intent and restored copy have the same identity`() {
        val original = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            data = Uri.parse("content://example.provider/image/7")
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory("com.example.CATEGORY_QA")
            putExtra(EXTRA_OPEN_ROUTE, "image_gen?startMode=1")
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.provider/image/7"))
        }

        val identity = appLaunchIdentity(original)
        val restored = Intent(original)

        assertEquals(identity, appLaunchIdentity(original))
        assertEquals(identity, appLaunchIdentity(restored))
        assertEquals(64, identity.length)
        assertTrue(identity.all { it in "0123456789abcdef" })
    }

    @Test
    fun `route presence is part of the identity even when its value is null`() {
        val absent = Intent(Intent.ACTION_VIEW)
        val presentNull = Intent(Intent.ACTION_VIEW).apply {
            putExtra(EXTRA_OPEN_ROUTE, null as String?)
        }
        val route = Intent(Intent.ACTION_VIEW).apply {
            putExtra(EXTRA_OPEN_ROUTE, "library")
        }

        assertNotEquals(appLaunchIdentity(absent), appLaunchIdentity(presentNull))
        assertNotEquals(appLaunchIdentity(presentNull), appLaunchIdentity(route))
    }

    @Test
    fun `different routes produce different identities`() {
        val first = Intent(Intent.ACTION_VIEW).putExtra(EXTRA_OPEN_ROUTE, "dashboard")
        val second = Intent(Intent.ACTION_VIEW).putExtra(EXTRA_OPEN_ROUTE, "library")

        assertNotEquals(appLaunchIdentity(first), appLaunchIdentity(second))
    }

    @Test
    fun `share mime and stream uri changes produce different identities`() {
        val image = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.provider/image/1"))
        }
        val videoMime = Intent(image).apply { type = "video/mp4" }
        val secondImage = Intent(image).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.provider/image/2"))
        }

        assertNotEquals(appLaunchIdentity(image), appLaunchIdentity(videoMime))
        assertNotEquals(appLaunchIdentity(image), appLaunchIdentity(secondImage))
    }

    @Test
    fun `stream presence is part of the identity even when its value is null`() {
        val absent = Intent(Intent.ACTION_SEND)
        val presentNull = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, null as Uri?)
        }
        val presentUri = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example.provider/image/1"))
        }

        assertNotEquals(appLaunchIdentity(absent), appLaunchIdentity(presentNull))
        assertNotEquals(appLaunchIdentity(presentNull), appLaunchIdentity(presentUri))
    }

    @Test
    fun `categories are order independent while data remains launch identity input`() {
        val first = Intent(Intent.ACTION_VIEW, Uri.parse("content://example.provider/item/1")).apply {
            addCategory("com.example.SECOND")
            addCategory("com.example.FIRST")
        }
        val sameInputsDifferentOrder = Intent(Intent.ACTION_VIEW, Uri.parse("content://example.provider/item/1")).apply {
            addCategory("com.example.FIRST")
            addCategory("com.example.SECOND")
        }
        val differentData = Intent(Intent.ACTION_VIEW, Uri.parse("content://example.provider/item/2")).apply {
            addCategory("com.example.FIRST")
            addCategory("com.example.SECOND")
        }

        assertEquals(appLaunchIdentity(first), appLaunchIdentity(sameInputsDifferentOrder))
        assertNotEquals(appLaunchIdentity(first), appLaunchIdentity(differentData))
    }

    @Test
    fun `malformed extras never make identity calculation throw or expose raw values`() {
        val malformed = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(EXTRA_OPEN_ROUTE, 42)
            putExtra(Intent.EXTRA_STREAM, 43)
        }
        val withoutExtras = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
        }
        val identity = appLaunchIdentity(malformed)

        assertEquals(64, identity.length)
        assertNotEquals(appLaunchIdentity(withoutExtras), identity)
        assertFalse(identity.contains("extra_open_route"))
        assertFalse(identity.contains("application/octet-stream"))
        assertEquals(identity, appLaunchIdentity(Intent(malformed)))
    }

    @Test
    fun `null intent is deterministic and distinct from an empty intent`() {
        val nullIdentity = appLaunchIdentity(null)

        assertEquals(nullIdentity, appLaunchIdentity(null))
        assertNotEquals(nullIdentity, appLaunchIdentity(Intent()))
    }

    private companion object {
        const val EXTRA_OPEN_ROUTE = "extra_open_route"
    }
}
