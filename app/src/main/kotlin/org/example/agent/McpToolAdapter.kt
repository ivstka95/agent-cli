package org.example.agent

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ONE translation point between MCP and Anthropic: maps an MCP SDK [Tool] (from `listTools`)
 * to an Anthropic [ToolSpec]. Keeping it here means `:mcp` stays free of Anthropic concepts and
 * `:app` builds the provider-native `input_schema` in a single place.
 */
fun Tool.toToolSpec(): ToolSpec {
    val schema = buildJsonObject {
        put("type", "object")
        inputSchema.properties?.let { put("properties", it) }
        inputSchema.required?.let { required ->
            put("required", buildJsonArray { required.forEach { add(it) } })
        }
    }
    return ToolSpec(
        name = name,
        description = description ?: "",
        inputSchema = schema,
    )
}
