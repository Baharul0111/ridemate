package app.ridemate.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * [GoogleCredentialSource] over Credential Manager: `GetGoogleIdOption` with [serverClientId] (the Firebase web
 * client id, `R.string.default_web_client_id` from google-services), all accounts on the phone
 * (`filterByAuthorizedAccounts = false`), no silent auto-select, and the hashed nonce.
 */
class CredentialManagerGoogleSource(private val serverClientId: String) : GoogleCredentialSource {

    override suspend fun requestIdToken(activity: Activity, hashedNonce: String): GoogleCredentialResult {
        if (serverClientId.isBlank()) return GoogleCredentialResult.Failed
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setNonce(hashedNonce)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleCredentialResult.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleCredentialResult.Failed
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleCredentialResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleCredentialResult.NoGoogleAccount
        } catch (e: GoogleIdTokenParsingException) {
            GoogleCredentialResult.Failed
        } catch (e: GetCredentialException) {
            GoogleCredentialResult.Failed
        }
    }
}
