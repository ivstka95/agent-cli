package org.example.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool

/** Resolves a tool name to the name of the MCP server that owns it (for routing-visible logging). */
interface ToolRouter {
    fun serverFor(toolName: String): String?
}

/**
 * Day 20 — the multi-server orchestration layer.
 *
 * Fronts several named [McpClient]s as a single [McpClient]: it connects to each, merges their
 * advertised tools into one list for the LLM, and routes every [callTool] to the server that owns
 * that tool (an owner map built once during [listTools]). Because it IS-an [McpClient], the existing
 * single-client agentic loop drives it unchanged; it additionally implements [ToolRouter] so the
 * agent log can name the routed server per call.
 *
 * Resilience: connection and tool-listing degrade **per server** — a server that fails to connect or
 * list is logged and skipped; the rest still contribute their tools. [clients] iteration order is the
 * registration order, which also decides name-collision precedence (first-registered wins).
 */
class McpClientRegistry(
    private val clients: Map<String, McpClient>,
    private val log: (String) -> Unit = {},
) : McpClient, ToolRouter {

    /** Servers that connected successfully, in registration order. */
    private val connected = LinkedHashMap<String, McpClient>()

    /** The server that owns a tool: which client to route to, under which name (for logging). */
    private data class Owner(val serverName: String, val client: McpClient)

    /** Built during [listTools]: tool name → its owning server. */
    private val owners = mutableMapOf<String, Owner>()

    override val serverInfo: ServerInfo?
        get() = connected.values.firstOrNull()?.serverInfo

    override suspend fun connect() {
        for ((name, client) in clients) {
            try {
                client.connect()
                connected[name] = client
                log("connected to MCP server '$name'")
            } catch (e: Exception) {
                log("could not connect to MCP server '$name' (${e.message}); skipping its tools")
            }
        }
    }

    override suspend fun listTools(): List<Tool> {
        owners.clear()
        val merged = mutableListOf<Tool>()
        for ((name, client) in connected) {
            val tools = try {
                client.listTools()
            } catch (e: Exception) {
                log("could not list tools from MCP server '$name' (${e.message}); skipping")
                emptyList()
            }
            for (tool in tools) {
                val existing = owners[tool.name]
                if (existing != null) {
                    log("tool '${tool.name}' offered by both '${existing.serverName}' and '$name'; keeping '${existing.serverName}'")
                    continue
                }
                owners[tool.name] = Owner(name, client)
                merged += tool
            }
        }
        return merged
    }

    override suspend fun callTool(name: String, arguments: Map<String, Any?>): CallToolResult {
        val owner = owners[name]
            ?: throw IllegalArgumentException("No MCP server owns tool '$name'")
        return owner.client.callTool(name, arguments)
    }

    override fun serverFor(toolName: String): String? = owners[toolName]?.serverName

    override suspend fun close() {
        // Best-effort close every client (even ones that never connected); one failure can't leak others.
        for (client in clients.values) runCatching { client.close() }
    }
}
