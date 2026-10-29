package dz.hirz.server.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VerifiedIdentity(val uid: String, val email: String?, val name: String?)

/** Turns a member's bearer token into an identity, or null when the token is not valid. */
fun interface TokenVerifier {
    suspend fun verify(idToken: String): VerifiedIdentity?
}

/** Verifies Firebase Auth ID tokens (signature, audience = project, expiry, revocation not checked). */
class FirebaseTokenVerifier(app: FirebaseApp) : TokenVerifier {
    private val auth = FirebaseAuth.getInstance(app)

    override suspend fun verify(idToken: String): VerifiedIdentity? = withContext(Dispatchers.IO) {
        runCatching { auth.verifyIdToken(idToken) }.getOrNull()
            ?.let { VerifiedIdentity(uid = it.uid, email = it.email, name = it.name) }
    }

    companion object {
        /** Uses GOOGLE_APPLICATION_CREDENTIALS (service account JSON kept outside the repository). */
        fun firebaseApp(projectId: String): FirebaseApp = FirebaseApp.initializeApp(
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .build(),
        )
    }
}
