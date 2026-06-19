plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // kotlinx.serialization compiler plugin (for @Serializable DTOs).
    alias(libs.plugins.kotlin.serialization)

    // Apply the application plugin to add support for building a CLI application.
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Ktor client (CIO engine) + JSON content negotiation.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // JSON serialization.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (REPL runs in runBlocking).
    implementation(libs.kotlinx.coroutines.core)

    // No-op SLF4J binding: Ktor's CIO engine logs via SLF4J; without a binding it
    // prints "Failed to load class StaticLoggerBinder" warnings on startup.
    runtimeOnly(libs.slf4j.nop)

    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Use the JUnit 5 integration.
    testImplementation(libs.junit.jupiter.engine)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Define the main class for the application.
    mainClass = "org.example.MainKt"
}

// Forward the terminal's stdin to the app so the interactive REPL can read input.
// Without this, `gradle run` gives the app an empty stdin and the REPL exits at once.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
