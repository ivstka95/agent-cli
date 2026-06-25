package org.example.mcp.server.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.error
import io.modelcontextprotocol.kotlin.sdk.types.success
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.mcp.server.McpServerLog
import org.example.mcp.textOrError
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Day-19 pipeline stage 3 (the "save" tool): `save_to_file(filename, content)` writes `content` to a
 * file inside a fixed, protected [baseDir] on the SERVER side and returns the saved path. **NO LLM** —
 * pure file IO.
 *
 * Security: the filename is sanitized to a flat name (path separators and unsafe chars are replaced),
 * so traversal inputs like `../escape.md` or `/etc/passwd` collapse to a single file INSIDE [baseDir].
 * A canonical "resolved path must stay under base" check is the defense-in-depth backstop. The tool
 * NEVER writes outside [baseDir].
 */
class SaveToFileTool(private val baseDir: Path) {

    fun definition(): McpToolDefinition = McpToolDefinition(
        name = NAME,
        description =
            "Save text content to a file on the server. Call this as the LAST step to persist a " +
                "report or result. Pass the EXACT, FULL content you want written (do not summarize " +
                "or shorten it) as 'content', and a simple 'filename'. Returns the saved path.",
        inputSchema = SCHEMA,
        handler = { request -> handle(request) },
    )

    /** Pure handler, exposed for unit testing without a running server. */
    fun handle(request: CallToolRequest): CallToolResult {
        val args = request.arguments
        val filename = (args?.get("filename") as? JsonPrimitive)?.contentOrNull
        val content = (args?.get("content") as? JsonPrimitive)?.contentOrNull

        log("tool call: $NAME(filename=$filename, content=${content?.length ?: 0} chars)")

        if (filename.isNullOrBlank()) {
            return CallToolResult.error("$NAME requires a non-empty 'filename' argument.").also(::logResult)
        }
        if (content == null) {
            return CallToolResult.error("$NAME requires a 'content' argument.").also(::logResult)
        }

        val safeName = sanitize(filename)
        val base = baseDir.toAbsolutePath().normalize()
        val target = base.resolve(safeName).normalize()

        // Defense-in-depth: after sanitizing there are no separators, but verify the target never
        // escapes the base directory before touching the filesystem.
        if (!target.startsWith(base)) {
            return CallToolResult.error("$NAME refused to write outside the output directory.").also(::logResult)
        }

        return try {
            Files.createDirectories(base)
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            Files.write(target, bytes)
            CallToolResult.success("Saved ${bytes.size} bytes to $target")
        } catch (e: Exception) {
            CallToolResult.error("$NAME failed to write '$safeName': ${e.message}")
        }.also(::logResult)
    }

    private fun logResult(result: CallToolResult) = log("result:\n${result.textOrError()}")

    private fun log(body: String) = McpServerLog.line(body)

    /**
     * Reduce an arbitrary filename to a single safe path segment: every char outside
     * `[A-Za-z0-9._-]` becomes `_` (so `/`, `\`, spaces, etc. can't form a directory or traverse).
     * An empty or all-dots result (`.`, `..`) falls back to [DEFAULT_NAME]; the length is capped.
     */
    internal fun sanitize(filename: String): String {
        val replaced = buildString {
            filename.trim().forEach { ch -> append(if (ch in SAFE_CHARS) ch else '_') }
        }.take(MAX_NAME_LEN)
        return if (replaced.isEmpty() || replaced.all { it == '.' }) DEFAULT_NAME else replaced
    }

    companion object {
        const val NAME = "save_to_file"
        const val DEFAULT_NAME = "report.txt"
        private const val MAX_NAME_LEN = 200
        private val SAFE_CHARS: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('.', '_', '-')).toSet()

        private val SCHEMA: ToolSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("filename") {
                    put("type", "string")
                    put("description", "Target file name, e.g. \"kotlin-report.md\". A simple name, no path.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "The exact, full text to write to the file (not summarized).")
                }
            },
            required = listOf("filename", "content"),
        )
    }
}
