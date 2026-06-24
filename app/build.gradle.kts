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
    // Day 17: the agent uses the MCP client to call tools. First coupling — one-directional
    // (:app -> :mcp). :mcp must NOT depend on :app.
    implementation(project(":mcp"))

    // Ktor client (CIO engine) + JSON content negotiation.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // JSON serialization.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (REPL runs in runBlocking).
    implementation(libs.kotlinx.coroutines.core)

    // No-op SLF4J 2.x binding: Ktor 3.x's CIO engine logs via SLF4J 2.x; without a matching
    // provider it prints "No SLF4J providers were found" warnings on startup.
    runtimeOnly(libs.slf4j2.nop)

    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Use the JUnit 5 integration.
    testImplementation(libs.junit.jupiter.engine)

    // Ktor MockEngine — stub the Anthropic API in the tool-use mapping test.
    testImplementation(libs.ktor.client.mock)

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
    // Run from the repo root so MemoryStore's File("memory") resolves to the
    // project-root memory/ (matching the root-anchored /memory/ gitignore rule),
    // not app/memory/ (the module dir, the application plugin's default).
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
