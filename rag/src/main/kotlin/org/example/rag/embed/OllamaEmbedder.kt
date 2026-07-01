package org.example.rag.embed

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.rag.config.RagConfig

/**
 * [Embedder] backed by a local Ollama server, mirroring :app's `AnthropicClient` Ktor setup.
 *
 * Verified against the live API (2026-07-01): `POST {host}/api/embeddings` with body
 * `{"model": …, "prompt": …}` returns `{"embedding": [<dim> floats]}`. Warns once (stderr) if the
 * returned dimension differs from [RagConfig.embedDim].
 *
 * The [engine] is injectable for tests; production uses CIO. Call [close] when done.
 */
class OllamaEmbedder(
    private val config: RagConfig,
    engine: HttpClientEngine? = null,
) : Embedder {

    private val http = if (engine != null) {
        HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    } else {
        HttpClient(CIO) {
            engine { requestTimeout = 120_000 }
            install(ContentNegotiation) { json(json) }
        }
    }

    @Volatile
    private var dimWarned = false

    override suspend fun embed(text: String): FloatArray {
        val response: HttpResponse = http.post("${config.ollamaHost}/api/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(EmbedRequest(model = config.ollamaModel, prompt = text))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            throw RuntimeException("Ollama embeddings error ${response.status.value}: $body")
        }
        val embedding = response.body<EmbedResponse>().embedding
        if (embedding.size != config.embedDim && !dimWarned) {
            dimWarned = true
            System.err.println(
                "[RAG] warning: Ollama returned ${embedding.size}-dim vectors, expected ${config.embedDim} " +
                    "(model=${config.ollamaModel}). Indexing continues with the returned dimension.",
            )
        }
        return embedding.toFloatArray()
    }

    fun close() = http.close()

    // ── Ollama /api/embeddings DTOs ──────────────────────────────────────────────
    @Serializable
    private data class EmbedRequest(val model: String, val prompt: String)

    @Serializable
    private data class EmbedResponse(val embedding: List<Float> = emptyList())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
