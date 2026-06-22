plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // Apply the application plugin to add support for running the MCP demo.
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Official Kotlin MCP SDK (umbrella artifact: client now, server later for Day 18).
    implementation(libs.mcp.kotlin.sdk)

    // Coroutines: connect()/listTools() are suspend functions (Main runs in runBlocking).
    implementation(libs.kotlinx.coroutines.core)

    // No-op SLF4J 2.x binding: the SDK (Ktor 3.x) logs via SLF4J 2.x; without a matching
    // provider it prints "No SLF4J providers were found" warnings. Keeps the demo output clean.
    runtimeOnly(libs.slf4j2.nop)

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
    // Define the main class for the MCP demo.
    mainClass = "org.example.mcp.MainKt"
}

tasks.named<JavaExec>("run") {
    // The demo launches a stdio subprocess (npx ...) and prints the tool list; let it
    // inherit the terminal's stdio so the server's stderr surfaces during a manual run.
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
