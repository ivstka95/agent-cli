package org.example.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.example.agent.LlmClient
import org.example.agent.LlmResult
import org.example.agent.Message
import org.example.agent.StructuredResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Fake whose complete() returns a name, so tests can tell which backend the switch is delegating to. */
private class NamedLlm(private val name: String) : LlmClient {
    override suspend fun complete(systemPrompt: String, messages: List<Message>) = LlmResult(name, 0, 0)
    override suspend fun completeStructured(
        systemPrompt: String, messages: List<Message>, toolName: String, toolDescription: String, inputSchema: JsonObject,
    ) = StructuredResult("{}", 0, 0)
}

class LlmProviderSwitchTest {

    private suspend fun activeName(switch: LlmProviderSwitch): String =
        switch.client.complete("s", emptyList()).replyText

    @Test
    fun `switchTo flips the active delegate`() = runBlocking {
        val switch = LlmProviderSwitch(Provider.LOCAL) { p ->
            when (p) {
                Provider.LOCAL -> NamedLlm("local")
                Provider.CLOUD -> NamedLlm("cloud")
            }
        }
        assertEquals(Provider.LOCAL, switch.current)
        assertEquals("local", activeName(switch))

        val result = switch.switchTo(Provider.CLOUD)
        assertIs<SwitchResult.Switched>(result)
        assertEquals(Provider.CLOUD, switch.current)
        assertEquals("cloud", activeName(switch))
    }

    @Test
    fun `switching to the current provider is Unchanged and does not rebuild`() = runBlocking {
        var localBuilds = 0
        val switch = LlmProviderSwitch(Provider.LOCAL) { p ->
            when (p) {
                Provider.LOCAL -> { localBuilds++; NamedLlm("local") }
                Provider.CLOUD -> NamedLlm("cloud")
            }
        }
        assertIs<SwitchResult.Unchanged>(switch.switchTo(Provider.LOCAL))
        assertEquals(1, localBuilds) // only the initial build
    }

    @Test
    fun `each provider is built lazily once and cached`() = runBlocking {
        var localBuilds = 0
        var cloudBuilds = 0
        val switch = LlmProviderSwitch(Provider.LOCAL) { p ->
            when (p) {
                Provider.LOCAL -> { localBuilds++; NamedLlm("local") }
                Provider.CLOUD -> { cloudBuilds++; NamedLlm("cloud") }
            }
        }
        assertEquals(1, localBuilds)
        assertEquals(0, cloudBuilds) // cloud not built until first selected

        switch.switchTo(Provider.CLOUD)
        switch.switchTo(Provider.LOCAL)
        switch.switchTo(Provider.CLOUD)

        assertEquals(1, localBuilds) // cached across re-selection
        assertEquals(1, cloudBuilds)
    }

    @Test
    fun `a failed build keeps the current provider and reports why`() = runBlocking {
        val switch = LlmProviderSwitch(Provider.LOCAL) { p ->
            when (p) {
                Provider.LOCAL -> NamedLlm("local")
                Provider.CLOUD -> throw IllegalStateException("Missing ANTHROPIC_API_KEY")
            }
        }
        val result = switch.switchTo(Provider.CLOUD)
        assertIs<SwitchResult.Failed>(result)
        assertTrue(result.reason.contains("Missing ANTHROPIC_API_KEY"), result.reason)
        assertEquals(Provider.LOCAL, switch.current) // stayed put
        assertEquals("local", activeName(switch)) // client still delegates to local
    }
}
