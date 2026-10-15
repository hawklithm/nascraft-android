package com.hawky.nascraft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NativeDownloadStreamTest {
    @Test
    fun parsesOnlyConsistentSingleContentRangeValues() {
        assertEquals(ParsedContentRange(1024, 2047, 4096), NativeDownloadStream.parseContentRange("bytes 1024-2047/4096"))
        assertNull(NativeDownloadStream.parseContentRange("bytes 2047-1024/4096"))
        assertNull(NativeDownloadStream.parseContentRange("bytes 0-4096/4096"))
        assertNull(NativeDownloadStream.parseContentRange("bytes 0-1/*"))
        assertNull(NativeDownloadStream.parseContentRange("bytes=0-1"))
    }

    @Test
    fun copiesToOutputWithExactLengthAndRejectsShortOrOversizedResponses() {
        val bytes = ByteArray(96 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val written = NativeDownloadStream.copyExactly(ByteArrayInputStream(bytes), output, bytes.size.toLong())

        assertEquals(bytes.size.toLong(), written)
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(IllegalStateException::class.java) {
            NativeDownloadStream.copyExactly(ByteArrayInputStream(bytes), ByteArrayOutputStream(), (bytes.size - 1).toLong())
        }
        assertThrows(IllegalStateException::class.java) {
            NativeDownloadStream.copyExactly(ByteArrayInputStream(bytes, 0, bytes.size - 1), ByteArrayOutputStream(), bytes.size.toLong())
        }
    }
}
