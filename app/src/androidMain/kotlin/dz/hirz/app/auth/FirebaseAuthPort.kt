package dz.hirz.app.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dz.hirz.app.AuthPort
import dz.hirz.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in through Credential Manager, then Firebase Auth. The server only ever sees Firebase ID tokens;
 * no password exists anywhere in Hirz (design §6.5).
 */
class FirebaseAuthPort(private val activityProvider: () -> Activity) : AuthPort {
    private val auth = FirebaseAuth.getInstance()
    private val state = MutableStateFlow(auth.currentUser != null)
    override val signedIn: StateFlow<Boolean> = state

    init {
        auth.addAuthStateListener { state.value = it.currentUser != null }
    }

    override suspend fun signIn(): Result<Unit> = runCatching {
        val activity = activityProvider()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .build()
        val response = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(option)))
        val google = GoogleIdTokenCredential.createFrom(response.credential.data)
        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        Unit
    }

    override suspend fun signOut() = auth.signOut()

    override suspend fun idToken(forceRefresh: Boolean): String? =
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token
}
