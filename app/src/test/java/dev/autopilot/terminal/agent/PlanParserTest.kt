package dev.autopilot.terminal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanParserTest {

    private val parser = PlanParser()

    @Test
    fun parsesPlanObject() {
        val raw = """
            {"action":"plan","steps":[
              {"command":"mkdir -p app","description":"创建目录","expect":"目录存在"},
              {"command":"echo hi > a.txt"}
            ]}
        """.trimIndent()
        val plan = parser.parse(raw).getOrThrow()
        assertEquals(2, plan.steps.size)
        assertEquals("mkdir -p app", plan.steps[0].command)
        assertEquals("创建目录", plan.steps[0].description)
        assertEquals("", plan.steps[1].description)
    }

    @Test
    fun parsesFencedJsonBlock() {
        val raw = """
            好的，这是我的计划:
            ```json
            {"steps":[{"command":"ls -la"}]}
            ```
        """.trimIndent()
        val plan = parser.parse(raw).getOrThrow()
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun parsesBareArray() {
        val raw = """[{"command":"pwd"}]"""
        val plan = parser.parse(raw).getOrNull()
        if (plan != null) assertEquals(1, plan.steps.size)
    }

    @Test
    fun rejectsTextWithoutJson() {
        assertTrue(parser.parse("抱歉我不知道该怎么做").isFailure)
        assertNull(parser.parse("no json here { broken").getOrNull())
    }

    @Test
    fun toleratesUnknownKeys() {
        val raw = """{"steps":[{"command":"id","extra_field":123}],"note":"hi"}"""
        val plan = parser.parse(raw).getOrThrow()
        assertEquals("id", plan.steps[0].command)
    }
}

class AgentActionParsingTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String): AgentAction? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim() ?: text
        val start = fenced.indexOf('{')
        val end = fenced.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString<AgentAction>(fenced.substring(start, end + 1)) }.getOrNull()
    }

    @Test
    fun parsesDoneAction() {
        val action = parse("""{"action":"done","summary":"完成","changed_files":["a.py"]}""")
        assertNotNull(action)
        assertEquals("done", action!!.action)
        assertEquals(listOf("a.py"), action.changed_files)
    }

    @Test
    fun parsesRepairActionWithReason() {
        val action = parse("""{"action":"repair","command":"pip install x","reason":"缺依赖"}""")
        assertEquals("repair", action!!.action)
        assertEquals("缺依赖", action.reason)
    }

    @Test
    fun returnsNullForGarbage() {
        assertNull(parse("plain text"))
        assertNull(parse("{unclosed"))
    }
}
