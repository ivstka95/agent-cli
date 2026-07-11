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

    // Day 22: the agent uses the RAG retriever (embed + vector search). One-directional
    // (:app -> :rag); :rag stays retrieval-only and must NOT depend on :app.
    implementation(project(":rag"))

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

// [Day 18] Background digest run mode, kept separate from the interactive REPL (the default `run`).
// Mirrors :mcp's `runClientDemo`: a second entry point under the same module/classpath.
tasks.register<JavaExec>("runDigest") {
    group = "application"
    description = "Run the Day-18 background commit-digest daemon (periodically calls get_recent_commits)."
    mainClass = "org.example.digest.DigestMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // A manually-registered JavaExec does not inherit the toolchain the way the application
    // plugin's `run` does — pin it to the project's Java 21 launcher so it matches the bytecode.
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so DigestStore's File("digest/...") resolves to the project-root
    // digest/ dir (matching the root-anchored /digest/ gitignore rule), like the REPL's memory/.
    workingDir = rootProject.projectDir
}

// [Day 22] RAG evaluation run mode, kept separate from the interactive REPL (the default `run`),
// mirroring `runDigest`: runs the 10 control questions through both modes (with/without RAG).
tasks.register<JavaExec>("runRagEval") {
    group = "application"
    description = "Run the Day-22 RAG evaluation: 10 control questions through both modes side by side."
    mainClass = "org.example.ragmode.CompareMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // Pin to the project's Java 21 launcher (a manual JavaExec doesn't inherit the toolchain).
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so RagConfig's default indexDir "rag-index" resolves at the project root.
    workingDir = rootProject.projectDir
}

// [Day 25] Verification run mode, kept separate from the interactive REPL (the default `run`),
// mirroring `runRagEval`: drives the agent (task memory + RAG + history) through the two long
// scenarios over this codebase, checking the goal is retained and every answer stays sourced.
tasks.register<JavaExec>("runDay25Eval") {
    group = "application"
    description = "Run the Day-25 verification: two 10–15-message scenarios through the grounded agent."
    mainClass = "org.example.day25.Day25EvalMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // Pin to the project's Java 21 launcher (a manual JavaExec doesn't inherit the toolchain).
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so RagConfig's default indexDir "rag-index" resolves at the project root.
    workingDir = rootProject.projectDir
}

// [Day 28] Local-vs-cloud RAG comparison, kept separate from the interactive REPL (the default `run`),
// mirroring `runRagEval`: runs the same 3 questions through the RAG path on local Ollama vs cloud
// Anthropic, side by side with metrics. Cloud is optional — no ANTHROPIC_API_KEY runs fully local.
tasks.register<JavaExec>("runLocalVsCloud") {
    group = "application"
    description = "Day 28: run 3 questions through the RAG path on local Ollama vs cloud Anthropic, side by side with metrics."
    mainClass = "org.example.compare.LocalVsCloudMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // Pin to the project's Java 21 launcher (a manual JavaExec doesn't inherit the toolchain).
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so RagConfig's default indexDir "rag-index" resolves at the project root.
    workingDir = rootProject.projectDir
}

// [Day 29] Local-LLM optimization comparison, mirroring `runLocalVsCloud`: runs the same 3 questions
// through the RAG path on the local model with DEFAULT vs OPTIMIZED generation params + prompt, side by
// side with metrics (elapsed, tokens) — the before/after of tuning in one run. Fully offline.
tasks.register<JavaExec>("runOptimizationCompare") {
    group = "application"
    description = "Day 29: run 3 questions through the local model with default vs optimized params + prompt, side by side with metrics."
    mainClass = "org.example.compare.OptimizationCompareMainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // Pin to the project's Java 21 launcher (a manual JavaExec doesn't inherit the toolchain).
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so RagConfig's default indexDir "rag-index" resolves at the project root.
    workingDir = rootProject.projectDir
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
