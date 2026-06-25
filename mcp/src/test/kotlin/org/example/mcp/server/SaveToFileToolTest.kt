package org.example.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.mcp.server.tools.SaveToFileTool
import org.example.mcp.textOrError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit-tests the Day-19 `save_to_file` handler against a temp base dir. Verifies the happy path, the
 * filename sanitization, and — the security-critical part — that path-traversal inputs NEVER write a
 * file outside the protected base directory.
 */
class SaveToFileToolTest {

    private val base: Path = Files.createTempDirectory("mcp-out-test").toAbsolutePath().normalize()
    private val tool = SaveToFileTool(base)

    @Test
    fun `writes content and returns the saved path`() {
        val content = "Commit Report\n=============\nTotal commits: 3\n"
        val result = tool.handle(request("kotlin-report.md", content))

        assertEquals(false, result.isError, result.textOrError())
        val saved = base.resolve("kotlin-report.md")
        assertTrue(saved.exists(), "file should exist under the base dir")
        assertEquals(content, saved.readText(), "file content must match the input verbatim")
        assertTrue(result.textOrError().contains(saved.toString()), result.textOrError())
    }

    @Test
    fun `sanitizes a messy filename to a safe name`() {
        val result = tool.handle(request("my report!.md", "x"))

        assertEquals(false, result.isError, result.textOrError())
        // Spaces and '!' → '_'; the safe charset is [A-Za-z0-9._-].
        assertTrue(base.resolve("my_report_.md").exists(), base.listDirectoryEntries().toString())
    }

    @Test
    fun `rejects path traversal — nothing is written outside the base dir`() {
        val parent = base.parent

        // Each traversal attempt collapses to a flat, safe name INSIDE the base dir.
        tool.handle(request("../escape.md", "nope"))
        tool.handle(request("/etc/passwd", "nope"))
        tool.handle(request("a/b/c.md", "nope"))

        // The would-be escaped targets do not exist next to / above the base dir.
        assertFalse(parent.resolve("escape.md").exists(), "must not write into the parent dir")
        assertFalse(base.resolve("a").exists(), "must not create nested dirs from a/b/c.md")

        // Every entry actually written lives directly inside the base dir.
        base.listDirectoryEntries().forEach { entry ->
            assertTrue(entry.toAbsolutePath().normalize().startsWith(base), "escaped: $entry")
            assertTrue(Files.isRegularFile(entry), "should be a flat file, not a dir: $entry")
        }
        // The sanitized flat names are present.
        assertTrue(base.resolve(".._escape.md").exists(), base.listDirectoryEntries().toString())
        assertTrue(base.resolve("_etc_passwd").exists(), base.listDirectoryEntries().toString())
        assertTrue(base.resolve("a_b_c.md").exists(), base.listDirectoryEntries().toString())
    }

    @Test
    fun `returns an error result when filename is blank`() {
        val result = tool.handle(request("   ", "content"))
        assertEquals(true, result.isError)
    }

    private fun request(filename: String, content: String): CallToolRequest =
        CallToolRequest(
            CallToolRequestParams(
                name = SaveToFileTool.NAME,
                arguments = buildJsonObject {
                    put("filename", filename)
                    put("content", content)
                },
            ),
        )
}
