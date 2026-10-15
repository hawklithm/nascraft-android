package com.hawky.nascraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class Base64CodecTest {
    @Test
    fun matchesPaddedStructuredFieldAndUnpaddedUrlAlphabet() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x01)
        assertEquals(Base64.getEncoder().encodeToString(bytes), Base64Codec.encodeStandard(bytes))
        assertEquals("-_8B", Base64Codec.encodeUrlNoPadding(bytes))
        assertArrayEquals(bytes, Base64Codec.decodeUrlNoPadding("-_8B"))
    }

    @Test
    fun rejectsNonCanonicalUrlValues() {
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decodeUrlNoPadding("Z") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decodeUrlNoPadding("Zg=") }
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decodeUrlNoPadding("Zh") }
    }
}
