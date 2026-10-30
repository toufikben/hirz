package dz.hirz.server.auth

/** End-to-end CI only (HIRZ_ENV=ci): accepts `test.<uid>` like the unit tests. main() refuses it anywhere else. */
object CiTokenVerifier : TokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity? =
        idToken.removePrefix("test.").takeIf { idToken.startsWith("test.") && it.isNotBlank() }
            ?.let { VerifiedIdentity(uid = it, email = "$it@hirz.test", name = it) }
}
