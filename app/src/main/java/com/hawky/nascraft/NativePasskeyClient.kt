package com.hawky.nascraft

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

sealed class NativePasskeyResult {
    data class Success(val credentialJson: String) : NativePasskeyResult()
    data object Cancelled : NativePasskeyResult()
    data object Unsupported : NativePasskeyResult()
    data object NoPasskey : NativePasskeyResult()
    data class Failed(val category: String) : NativePasskeyResult()
}

/** Calls Android Credential Manager; it never substitutes OTP or a local UI for a Passkey. */
class NativePasskeyClient(private val activity: Activity) {
    suspend fun create(publicKeyJson: String, fixedRpId: String): NativePasskeyResult {
        if (!NativePasskeySupport.isSupportedPlatform()) return NativePasskeyResult.Unsupported
        if (!NativePasskeySupport.isValidCreationOptions(publicKeyJson, fixedRpId)) {
            return NativePasskeyResult.Failed("The server returned incompatible Passkey options")
        }
        return try {
            val response = CredentialManager.create(activity).createCredential(
                context = activity,
                request = CreatePublicKeyCredentialRequest(requestJson = publicKeyJson),
            )
            when (response) {
                is CreatePublicKeyCredentialResponse -> NativePasskeyResult.Success(response.registrationResponseJson)
                else -> NativePasskeyResult.Failed("The credential provider returned an unsupported credential")
            }
        } catch (_: CreateCredentialCancellationException) {
            NativePasskeyResult.Cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CreateCredentialException) {
            if (NativePasskeySupport.isProviderUnavailable(failure)) NativePasskeyResult.Unsupported
            else NativePasskeyResult.Failed("Passkey creation is unavailable on this device")
        } catch (_: Exception) {
            NativePasskeyResult.Failed("Passkey creation could not be completed")
        }
    }

    suspend fun get(publicKeyJson: String, fixedRpId: String): NativePasskeyResult {
        if (!NativePasskeySupport.isSupportedPlatform()) return NativePasskeyResult.Unsupported
        if (!NativePasskeySupport.isValidRequestOptions(publicKeyJson, fixedRpId)) {
            return NativePasskeyResult.Failed("The server returned incompatible sign-in options")
        }
        return try {
            val request = GetCredentialRequest(
                listOf(GetPublicKeyCredentialOption(requestJson = publicKeyJson)),
            )
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val credential = response.credential
            if (credential is PublicKeyCredential) {
                NativePasskeyResult.Success(credential.authenticationResponseJson)
            } else {
                NativePasskeyResult.Failed("The credential provider returned an unsupported credential")
            }
        } catch (_: GetCredentialCancellationException) {
            NativePasskeyResult.Cancelled
        } catch (_: NoCredentialException) {
            NativePasskeyResult.NoPasskey
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GetCredentialException) {
            if (NativePasskeySupport.isProviderUnavailable(failure)) NativePasskeyResult.Unsupported
            else NativePasskeyResult.Failed("Passkey sign-in is unavailable on this device")
        } catch (_: Exception) {
            NativePasskeyResult.Failed("Passkey sign-in could not be completed")
        }
    }
}

object NativePasskeySupport {
    /** Credential Manager targets Android 9+ for this app's fixed native origin policy. */
    fun isSupportedPlatform(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 28

    fun isProviderUnavailable(failure: Throwable): Boolean =
        failure is CreateCredentialUnsupportedException ||
            failure is CreateCredentialProviderConfigurationException ||
            failure is GetCredentialUnsupportedException ||
            failure is GetCredentialProviderConfigurationException

    fun isValidCreationOptions(json: String, expectedRpId: String): Boolean = try {
        val root = JSONObject(json)
        val rp = root.getJSONObject("rp")
        val selection = root.getJSONObject("authenticatorSelection")
        !root.optString("challenge").isNullOrBlank() &&
            rp.getString("id") == expectedRpId &&
            selection.getString("userVerification") == "required" &&
            root.optJSONArray("pubKeyCredParams")?.let { params ->
                (0 until params.length()).any { index ->
                    params.optJSONObject(index)?.optInt("alg") == -7
                }
            } == true
    } catch (_: Exception) {
        false
    }

    fun isValidRequestOptions(json: String, expectedRpId: String): Boolean = try {
        val root = JSONObject(json)
        root.getString("rpId") == expectedRpId &&
            !root.optString("challenge").isNullOrBlank() &&
            root.optString("userVerification", "preferred") == "required"
    } catch (_: Exception) {
        false
    }
}
