package com.hawky.nascraft

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeBusinessTargetTest {
    @Test
    fun allowsCommonBusinessPathsOnTheSelectedFixedOrigin() {
        NativeBusinessTarget.validate("GET", "/api/uploaded_files?page=2&page_size=20")
        NativeBusinessTarget.validate("POST", "/api/upload")
        NativeBusinessTarget.validate("GET", "/api/download/file_12345678")
    }

    @Test
    fun rejectsOriginOverridesAuthRealmAliasesAndAmbiguousTargets() {
        listOf(
            "https://evil.example/api/uploaded_files",
            "//evil.example/api/uploaded_files",
            "/api/remote/v1/auth/logout",
            "/api/lan/native/v1/auth/logout",
            "/api/download/../secret",
            "/api/download/%2f%2fevil",
            "/api/download/file#fragment",
            "/api/download/file\\other",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { NativeBusinessTarget.validate("GET", path) }
        }
        assertThrows(IllegalArgumentException::class.java) { NativeBusinessTarget.validate("TRACE", "/api/uploaded_files") }
    }

    @Test
    fun permitsUploadMetadataHeadersButBlocksTransportAndSignatureOverrides() {
        NativeBusinessTarget.validateAdditionalHeaders(
            mapOf("X-File-ID" to "file_12345678", "X-Start-Offset" to "0", "Content-Range" to "bytes 0-9/10"),
        )
        listOf("Host", "Authorization", "Content-Digest", "Nascraft-Nonce").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeBusinessTarget.validateAdditionalHeaders(mapOf(name to "attacker-value"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeBusinessTarget.validateAdditionalHeaders(mapOf("X-File-ID" to "file\r\ninjected: yes"))
        }
    }
}
