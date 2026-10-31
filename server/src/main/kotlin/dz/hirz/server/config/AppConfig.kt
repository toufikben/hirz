package dz.hirz.server.config

/** Runtime configuration from the environment; secrets never live in the repository. */
data class AppConfig(
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val firebaseProjectId: String?,
    /** 32 random bytes, base64; encrypts the family code word. Generate once and keep it with the other secrets. */
    val dataKey: ByteArray,
    val publicBaseUrl: String = "http://localhost:8080",
    val traccarSecret: String? = null,
    /** Traccar admin API (all three or none). */
    val traccarUrl: String? = null,
    val traccarUser: String? = null,
    val traccarPassword: String? = null,
    val deviceHost: String = "localhost",
    /** Firebase uids of the field-trial operators (HIRZ_OPERATORS, comma-separated); empty means none. */
    val operators: Set<String> = emptySet(),
    val claimProofS: IntRange = 45..95,
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
            publicBaseUrl = env["HIRZ_PUBLIC_BASE_URL"] ?: "http://localhost:${env["PORT"] ?: "8080"}",
            deviceHost = env["HIRZ_DEVICE_HOST"] ?: "localhost",
            operators = env["HIRZ_OPERATORS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            // A blank value (an unfilled .env line) counts as unset (review I2).
            traccarUrl = env["HIRZ_TRACCAR_URL"]?.takeIf { it.isNotBlank() },
            traccarUser = env["HIRZ_TRACCAR_USER"]?.takeIf { it.isNotBlank() },
            traccarPassword = env["HIRZ_TRACCAR_PASSWORD"]?.takeIf { it.isNotBlank() },
            claimProofS = env["HIRZ_CLAIM_PROOF_S"]?.takeIf { it.isNotBlank() }?.split('-')?.let { (a, b) -> a.trim().toInt()..b.trim().toInt() }
                ?.also { require(it.first >= 5 && it.last >= it.first) { "HIRZ_CLAIM_PROOF_S must be like 45-95" } } ?: (45..95),
            traccarSecret = env["HIRZ_TRACCAR_SECRET"]?.also { require(it.length >= 32) { "HIRZ_TRACCAR_SECRET must be at least 32 characters" } },
        )
    }
}
