plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // kotlinx.serialization compiler plugin (for the GitHub response @Serializable DTOs).
    alias(libs.plugins.kotlin.serialization)

    // java-library: provides the `api` configuration so :mcp can expose the MCP SDK types
    // (returned by McpClient) to :app. The application plugin alone only offers `implementation`.
    `java-library`

    // Apply the application plugin to add support for running the MCP server.
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Official Kotlin MCP SDK (umbrella artifact: client + server).
    // `api` (not `implementation`): McpClient's public methods return SDK types (Tool,
    // CallToolResult), so :app must see them to compile against :mcp (the first coupling).
    api(libs.mcp.kotlin.sdk)

    // Ktor server (CIO engine + SSE) — hosts our GitHub MCP server over HTTP (Application.mcp).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.sse)

    // Ktor client (CIO + JSON) — the GitHub HTTP client, and the SSE client transport.
    // The client SSE plugin (install(SSE), sseSession) ships in ktor-client-core in Ktor 3.x.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines: connect()/listTools()/callTool() are suspend functions (Main runs in runBlocking).
    implementation(libs.kotlinx.coroutines.core)

    // No-op SLF4J 2.x binding: the SDK (Ktor 3.x) logs via SLF4J 2.x; without a matching
    // provider it prints "No SLF4J providers were found" warnings. Keeps the demo output clean.
    runtimeOnly(libs.slf4j2.nop)

    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Use the JUnit 5 integration.
    testImplementation(libs.junit.jupiter.engine)

    // Ktor MockEngine — stub the GitHub API in the tool handler test.
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
    // Primary run target (Day 17): our GitHub MCP server over HTTP.
    // The Day-16 client demo (org.example.mcp.MainKt) stays runnable via the `runClientDemo` task.
    mainClass = "org.example.mcp.server.ServerMainKt"
}

// [Day 22] The server must NOT be named `run`: a bare `./gradlew run` should launch only the :app
// interactive REPL (the sole `run` owner). Disable the application plugin's auto `run` task and expose
// the server under an explicit name (mirroring the `runClientDemo` task below).
tasks.named<JavaExec>("run") { enabled = false }

// Primary run target (Day 17): our GitHub MCP server over HTTP. `./gradlew :mcp:runServer`.
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run our GitHub MCP server over HTTP (Day 17)."
    mainClass = "org.example.mcp.server.ServerMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // A manually-registered JavaExec doesn't inherit the toolchain like the application plugin's `run`.
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // The HTTP server logs its bind URL and blocks; inherit stdio so logs surface.
    standardInput = System.`in`
}

// Day-16 stdio client demo, kept runnable: `./gradlew :mcp:runClientDemo`.
tasks.register<JavaExec>("runClientDemo") {
    group = "application"
    description = "Run the Day-16 stdio MCP client demo (connects to server-everything, lists tools)."
    mainClass = "org.example.mcp.MainKt"
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
