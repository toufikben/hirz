plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("dz.hirz.sim.MainKt") }

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit5)
}

tasks.test { useJUnitPlatform() }

val integrationTest by tasks.registering(Test::class) {
    description = "Round-trips simulated fixes through a real Traccar (needs TRACCAR_URL)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    val url = System.getenv("TRACCAR_URL")
    onlyIf("TRACCAR_URL is set") {
        if (url.isNullOrBlank()) println("integrationTest skipped: TRACCAR_URL is not set")
        !url.isNullOrBlank()
    }
}
