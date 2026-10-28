plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("dz.hirz.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dhirz.version=${project.version}")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("hirz.version", project.version.toString())
    listOf("HIRZ_TEST_DB_URL", "HIRZ_TEST_DB_USER", "HIRZ_TEST_DB_PASSWORD").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
}
