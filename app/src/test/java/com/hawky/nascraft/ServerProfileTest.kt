package com.hawky.nascraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerProfileTest {
    @Test
    fun keepsDistinctHttpsOriginsBoundToOneServerIdentity() {
        val profile = ServerProfile.validated(
            serverId = "srv_home_12345678",
            displayName = "Home NAS",
            rpId = "ID.Home.Example.com",
            lanWebUrl = "https://Console.Home.Example.com/",
            lanApiOrigin = "https://Nas-Lan.Home.Example.com:8443/",
            remoteApiOrigin = "https://Nas-Ts.Home.Example.com",
        )

        assertEquals("id.home.example.com", profile.rpId)
        assertEquals("https://console.home.example.com", profile.lanWebUrl)
        assertEquals("https://nas-lan.home.example.com:8443", profile.apiOrigin(ApiRealm.LAN_NATIVE))
        assertEquals("https://nas-ts.home.example.com", profile.apiOrigin(ApiRealm.REMOTE_API))
        assertEquals("srv_home_12345678", profile.serverId)
    }

    @Test
    fun rejectsHttpIpAndOriginWithPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("srv_home_12345678", "NAS", "id.home.example.com", "http://console.home.example.com", "https://lan.home.example.com", "https://ts.home.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("srv_home_12345678", "NAS", "id.home.example.com", "https://console.home.example.com", "https://192.168.1.20", "https://ts.home.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("srv_home_12345678", "NAS", "id.home.example.com", "https://console.home.example.com", "https://127.1", "https://ts.home.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("srv_home_12345678", "NAS", "id.home.example.com", "https://console.home.example.com", "https://0x7f.0x0.0x0.0x1", "https://ts.home.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("srv_home_12345678", "NAS", "id.home.example.com", "https://console.home.example.com", "https://lan.home.example.com/api", "https://ts.home.example.com")
        }
    }

    @Test
    fun profileDoesNotTreatShortDiscoveryNameAsTrustedAndMayShareAnOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("", "NAS", "id.home.example.com", "https://console.home.example.com", "https://lan.home.example.com", "https://ts.home.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerProfile.validated("192.168.1.2", "NAS", "id.home.example.com", "https://console.home.example.com", "https://lan.home.example.com", "https://ts.home.example.com")
        }
        val sharedOrigin = ServerProfile.validated(
            "srv_home_12345678",
            "NAS",
            "id.home.example.com",
            "https://nas.home.example.com",
            "https://nas.home.example.com",
            "https://nas.home.example.com",
        )
        assertEquals("https://nas.home.example.com", sharedOrigin.apiOrigin(ApiRealm.LAN_NATIVE))
        assertEquals("https://nas.home.example.com", sharedOrigin.apiOrigin(ApiRealm.REMOTE_API))
    }

    @Test
    fun pairingUrlMustUseTheConfiguredWebOriginAndExactRequestLocator() {
        val profile = profile()
        val url = "https://console.home.example.com/connect?request_id=req_12345678"

        assertEquals(url, profile.validateReturnedWebUrl(url, "req_12345678"))
        assertThrows(IllegalArgumentException::class.java) {
            profile.validateReturnedWebUrl("https://attacker.example.com/connect?request_id=req_12345678", "req_12345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.validateReturnedWebUrl("https://console.home.example.com/connect?request_id=other_1234567", "req_12345678")
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.validateReturnedWebUrl("https://console.home.example.com/connect?request_id=req_12345678&next=https://attacker.example", "req_12345678")
        }
    }

    @Test
    fun qrPayloadRoundTripsEveryServerIdentityField() {
        val original = profile()
        val restored = ServerProfile.fromQrPayload(original.toQrPayload())
        assertEquals(original, restored)
        assertEquals("https://nas-lan.home.example.com", restored.lanApiOrigin)
        assertEquals("https://nas-ts.home.example.com", restored.remoteApiOrigin)
        assertEquals("https://console.home.example.com", restored.lanWebUrl)
    }

    @Test
    fun lanOnlyProfileMayOmitRemoteOriginAndBlocksRemoteTransportExplicitly() {
        val profile = ServerProfile.validated(
            serverId = "srv_home_12345678",
            displayName = "Home NAS",
            rpId = "id.home.example.com",
            lanWebUrl = "https://console.home.example.com",
            lanApiOrigin = "https://nas-lan.home.example.com",
            remoteApiOrigin = "",
        )

        assertEquals("", profile.remoteApiOrigin)
        assertEquals("https://nas-lan.home.example.com", profile.apiOrigin(ApiRealm.LAN_NATIVE))
        assertThrows(NativeApiException::class.java) { profile.apiOrigin(ApiRealm.REMOTE_API) }
        assertEquals(profile, ServerProfile.fromQrPayload(profile.toQrPayload()))
        val absentRemoteField = profile.toQrJson().replace(",\"remote_api_origin\":\"\"", "")
        assertEquals(profile, ServerProfile.fromQrJson(absentRemoteField))
    }

    private fun profile() = ServerProfile.validated(
        serverId = "srv_home_12345678",
        displayName = "Home NAS",
        rpId = "id.home.example.com",
        lanWebUrl = "https://console.home.example.com",
        lanApiOrigin = "https://nas-lan.home.example.com",
        remoteApiOrigin = "https://nas-ts.home.example.com",
    )
}
