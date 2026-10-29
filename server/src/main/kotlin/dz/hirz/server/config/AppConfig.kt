package dz.hirz.server.config

/** Runtime configuration from the environment; secrets never live in the repository. */
data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val firebaseProjectId: String?,
    /** 32 random bytes, base64; encrypts the family code word. Generate once and keep it with the other secrets. */
    val dataKey: ByteArray,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig = AppConfig(
            dbUrl = env["HIRZ_DATABASE_URL"] ?: error("HIRZ_DATABASE_URL is required"),
            dbUser = env["HIRZ_DATABASE_USER"] ?: error("HIRZ_DATABASE_USER is required"),
            dbPassword = env["HIRZ_DATABASE_PASSWORD"] ?: error("HIRZ_DATABASE_PASSWORD is required"),
            firebaseProjectId = env["HIRZ_FIREBASE_PROJECT_ID"],
            dataKey = (env["HIRZ_DATA_KEY"] ?: error("HIRZ_DATA_KEY is required (32 random bytes, base64)"))
                .let { java.util.Base64.getDecoder().decode(it.trim()) }
                .also { require(it.size == 32) { "HIRZ_DATA_KEY must decode to 32 bytes" } },
        )
    }
}
