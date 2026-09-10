package dev.experimental.edgetts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderPolicyTest {

    @Test
    fun defaultUserAgentAndOriginAreValid() {
        assertTrue(HeaderPolicy.isUserAgent(EdgeProtocolConstants.DEFAULT_USER_AGENT))
        assertTrue(HeaderPolicy.isOrigin(EdgeProtocolConstants.DEFAULT_ORIGIN))
    }

    @Test
    fun rejectsControlCharactersAndEmoji() {
        assertFalse(HeaderPolicy.isUserAgent("Mozilla\nBad"))
        assertFalse(HeaderPolicy.isUserAgent("Mozilla\u0000"))
        assertFalse(HeaderPolicy.isUserAgent("Mozilla 😀"))
        assertFalse(HeaderPolicy.isOrigin("https://www.bing.com\n"))
    }

    @Test
    fun originRequiresScheme() {
        assertFalse(HeaderPolicy.isOrigin("www.bing.com"))
        assertTrue(HeaderPolicy.isOrigin("https://www.bing.com"))
        assertTrue(HeaderPolicy.isOrigin("http://localhost"))
        assertEquals(
            EdgeProtocolConstants.DEFAULT_ORIGIN,
            HeaderPolicy.orDefaultOrigin("not-a-url")
        )
    }
}
