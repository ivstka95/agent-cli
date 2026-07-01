plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)

    // kotlinx.serialization compiler plugin (for the index JSON + chunk/metadata @Serializable models).
    alias(libs.plugins.kotlin.serialization)

    // Apply the application plugin to add support for running the indexing entry point.
    application
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Ktor client (CIO engine) + JSON content negotiation — OllamaEmbedder POSTs to the local
    // Ollama HTTP API, mirroring :app's AnthropicClient Ktor setup.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // JSON serialization: the on-disk vector index + chunk/metadata models.
    implementation(libs.kotlinx.serialization.json)

    // Coroutines: Embedder.embed() is suspend; Main runs the pipeline in runBlocking.
    implementation(libs.kotlinx.coroutines.core)

    // No-op SLF4J 2.x binding: Ktor 3.x's CIO engine logs via SLF4J 2.x; without a matching
    // provider it prints "No SLF4J providers were found" warnings on startup.
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
    // Indexing entry point: loads repo docs, chunks with both strategies, embeds via Ollama,
    // writes the JSON indexes, and prints the 2-strategy comparison.
    mainClass = "org.example.rag.MainKt"
}

// [Day 22] The indexer must NOT be named `run`: a bare `./gradlew run` should launch only the :app
// interactive REPL (the sole `run` owner). Disable the application plugin's auto `run` task and expose
// the indexer under an explicit name (mirroring :app's `runDigest` / :mcp's `runClientDemo`).
tasks.named<JavaExec>("run") { enabled = false }

tasks.register<JavaExec>("runIndexer") {
    group = "application"
    description = "Build the RAG indexes: load repo docs, chunk (both strategies), embed via Ollama, write rag-index/index-*.json."
    mainClass = "org.example.rag.MainKt"
    classpath = sourceSets["main"].runtimeClasspath
    // A manually-registered JavaExec doesn't inherit the toolchain like the application plugin's `run`.
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // Run from the repo root so RAG_REPO_ROOT="." and RAG_INDEX_DIR="rag-index" resolve against the
    // project root (matching the root-anchored /rag-index/ gitignore rule). The indexer reads no stdin.
    workingDir = rootProject.projectDir
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
