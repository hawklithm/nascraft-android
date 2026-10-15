package com.hawky.nascraft

import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePasskeySupportTest {
    @Test
    fun nativePasskeyCapabilityIsBlockedBelowAndroidNine() {
        assertFalse(NativePasskeySupport.isSupportedPlatform(sdkInt = 27))
        assertTrue(NativePasskeySupport.isSupportedPlatform(sdkInt = 28))
    }

    @Test
    fun unsupportedOrUnconfiguredCredentialProvidersHaveExplicitFallbackClassification() {
        assertTrue(NativePasskeySupport.isProviderUnavailable(CreateCredentialUnsupportedException("disabled")))
        assertTrue(NativePasskeySupport.isProviderUnavailable(CreateCredentialProviderConfigurationException("missing")))
        assertTrue(NativePasskeySupport.isProviderUnavailable(GetCredentialUnsupportedException("disabled")))
        assertTrue(NativePasskeySupport.isProviderUnavailable(GetCredentialProviderConfigurationException("missing")))
        assertFalse(NativePasskeySupport.isProviderUnavailable(IllegalStateException("other failure")))
    }

    @Test
    fun creationOptionsMustUseFixedRpAndRequireUserVerification() {
        val valid = """
            {"challenge":"abc","rp":{"id":"id.home.example.com"},
             "pubKeyCredParams":[{"type":"public-key","alg":-7}],
             "authenticatorSelection":{"userVerification":"required"}}
        """.trimIndent()
        val noUv = valid.replace("required", "preferred")
        val wrongRp = valid.replace("id.home.example.com", "attacker.example.com")
        val noEs256 = valid.replace("\"alg\":-7", "\"alg\":-257")

        assertTrue(NativePasskeySupport.isValidCreationOptions(valid, "id.home.example.com"))
        assertFalse(NativePasskeySupport.isValidCreationOptions(noUv, "id.home.example.com"))
        assertFalse(NativePasskeySupport.isValidCreationOptions(wrongRp, "id.home.example.com"))
        assertFalse(NativePasskeySupport.isValidCreationOptions(noEs256, "id.home.example.com"))
    }

    @Test
    fun discoverableLoginOptionsMustKeepTheFixedRp() {
        assertTrue(
            NativePasskeySupport.isValidRequestOptions(
                "{\"challenge\":\"abc\",\"rpId\":\"id.home.example.com\",\"userVerification\":\"required\"}",
                "id.home.example.com",
            ),
        )
        assertFalse(
            NativePasskeySupport.isValidRequestOptions(
                "{\"challenge\":\"abc\",\"rpId\":\"attacker.example.com\"}",
                "id.home.example.com",
            ),
        )
        assertFalse(
            NativePasskeySupport.isValidRequestOptions(
                "{\"challenge\":\"abc\",\"rpId\":\"id.home.example.com\",\"userVerification\":\"preferred\"}",
                "id.home.example.com",
            ),
        )
    }
}
