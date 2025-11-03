package ai.koog.agents.core.processor

import ai.koog.agents.core.createMockAIAgentLLMWriteSession
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ResponseProcessorApi::class)
class ExtractToolCallTest {
    private companion object {
        private val testClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
        }

        private val testMetaInfo = ResponseMetaInfo.create(testClock)

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        object StringTool : Tool<StringTool.Args, StringTool.Result>() {
            @Serializable
            data class Args(
                @property:LLMDescription("First string")
                val text1: String,
                @property:LLMDescription("Second string")
                val text2: String
            )

            @Serializable
            @JvmInline
            value class Result(val result: String)

            override val name: String = "string_tool"
            override val description: String = "A tool that takes string parameters"
            override val argsSerializer = Args.serializer()
            override val resultSerializer: KSerializer<Result> = Result.serializer()

            override val descriptor = ToolDescriptor(
                name = "string_tool",
                description = "A tool that takes string parameters",
                requiredParameters = listOf(
                    ToolParameterDescriptor(
                        name = "text1",
                        description = "First string",
                        type = ToolParameterType.String
                    ),
                    ToolParameterDescriptor(
                        name = "text2",
                        description = "Second string",
                        type = ToolParameterType.String
                    )
                )
            )

            override suspend fun execute(args: Args): Result {
                return Result(args.text1 + " " + args.text2)
            }
        }

        private val toolRegistry = ToolRegistry {
            tool(StringTool)
        }

        private val mockSession = createMockAIAgentLLMWriteSession(
            toolRegistry = toolRegistry
        )

        private const val ID = "123"
        private const val TOOL = "calculator"
        private const val ARGS = """{"a":5,"b":3}"""

        private val validJson = """
            {
                "tool": "$TOOL",
                "args": $ARGS
            }
        """.trimIndent()

        private val validJsonWithId = """
            {
                "id": "$ID",
                "tool": "$TOOL",
                "args": $ARGS
            }
        """.trimIndent()

        private val validJsonWithAlternativeNames = """
            {
                "tool_call_id": "$ID",
                "tool_name": "$TOOL",
                "parameters": $ARGS
            }
        """.trimIndent()

        private val nestedJson = """
            {
                "function_call": {
                    "name": "$TOOL",
                    "arguments": $ARGS
                }
            }
        """.trimIndent()

        private val invalidJson = """
            {
                "args": $ARGS
            }
        """.trimIndent()

        private val missingToolJson = """
            {
                "args": $ARGS
            }
        """.trimIndent()

        private val taggedJson = """
            Some text before the tool call
            <tool_call>
            $validJson
            </tool_call>
            Some text after the tool call
        """.trimIndent()

        private val noTagsJson = """
            Some text without any tool call tags
            $validJson
            More text
        """.trimIndent()

        private val incompleteTagJson = """
            <tool_call>
            $validJson
            No closing tag
        """.trimIndent()

        private val multipleToolCallsJson = """
            <tool_call>
            $validJson
            </tool_call>
            Some text in between
            <tool_call>
            {
                "tool": "weather",
                "args": {
                    "location": "New York"
                }
            }
            </tool_call>
        """.trimIndent()

        private val customTaggedJson = """
            Some text before the tool call
            [TOOL]
            $validJson
            [/TOOL]
            Some text after the tool call
        """.trimIndent()

        private val extractJsonToolCall = ExtractJsonToolCall()
        private val extractTaggedJsonToolCall = ExtractTaggedJsonToolCall()

        private fun validateToolCallResult(result: Message.Response, checkId: Boolean = false) {
            assertIs<Message.Tool.Call>(result)
            if (checkId) assertEquals(ID, result.id)
            assertEquals(TOOL, result.tool)
            assertEquals(ARGS, result.content)
        }
    }

    @Test
    fun testExtractJsonToolCall_validJson() = runTest {
        val message = Message.Assistant(validJson, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractJsonToolCall_validJsonWithId() = runTest {
        val message = Message.Assistant(validJsonWithId, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        validateToolCallResult(result, checkId = true)
    }

    @Test
    fun testExtractJsonToolCall_validJsonWithAlternativeNames() = runTest {
        val message = Message.Assistant(validJsonWithAlternativeNames, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractJsonToolCall_nestedJson() = runTest {
        val message = Message.Assistant(nestedJson, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractJsonToolCall_invalidJson() = runTest {
        val message = Message.Assistant(invalidJson, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        assertEquals(message, result)
    }

    @Test
    fun testExtractJsonToolCall_missingRequiredFields() = runTest {
        val message = Message.Assistant(missingToolJson, metaInfo = testMetaInfo)
        val result = extractJsonToolCall.process(mockSession, message)

        assertEquals(message, result)
    }

    @Test
    fun testExtractTaggedJsonToolCall_validTaggedJson() = runTest {
        val message = Message.Assistant(taggedJson, metaInfo = testMetaInfo)
        val result = extractTaggedJsonToolCall.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractTaggedJsonToolCall_missingTags() = runTest {
        val message = Message.Assistant(noTagsJson, metaInfo = testMetaInfo)
        val result = extractTaggedJsonToolCall.process(mockSession, message)

        assertEquals(message, result)
    }

    @Test
    fun testExtractTaggedJsonToolCall_incompleteTag() = runTest {
        val message = Message.Assistant(incompleteTagJson, metaInfo = testMetaInfo)
        val result = extractTaggedJsonToolCall.process(mockSession, message)

        assertEquals(message, result)
    }

    @Test
    fun testExtractTaggedJsonToolCall_multipleToolCalls() = runTest {
        val message = Message.Assistant(multipleToolCallsJson, metaInfo = testMetaInfo)
        val result = extractTaggedJsonToolCall.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractTaggedJsonToolCall_customTags() = runTest {
        val processor = ExtractTaggedJsonToolCall(
            startTag = "[TOOL]",
            endTag = "[/TOOL]",
            json = json
        )
        val message = Message.Assistant(customTaggedJson, metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)

        validateToolCallResult(result)
    }

    @Test
    fun testExtractJsonToolCall_withCorrectJson() = runTest {
        val text1 = "Test \"quoted\" string"
        val text2 = "Test a string with\na new line"

        val validJson = """
            {
                "tool": "string_tool",
                "args": {
                    "text1": "Test \"quoted\" string",
                    "text2": "Test a string with\na new line"
                }
            }
        """.trimIndent()

        val processor = ExtractJsonToolCall(json = json)
        val message = Message.Assistant(validJson, metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)

        assertNotNull(result)
        assertIs<Message.Tool.Call>(result)
        assertEquals("string_tool", result.tool)

        val expectedContentJson = buildJsonObject {
            put("text1", JsonPrimitive(text1))
            put("text2", JsonPrimitive(text2))
        }
        assertEquals(expectedContentJson.toString(), result.content)
    }

    @Test
    fun testExtractJsonToolCall_withMalformedStringParameters() = runTest {
        val text1 = "Test \"quoted\" string"
        val text2 = "Test a string with\na new line"

        val malformedJson = """
            {
                "tool": "string_tool",
                "args": {
                    "text1": "$text1",
                    "text2": "Test a string with\na new line"
                }
            }
        """.trimIndent()

        val processor = ExtractJsonToolCall(json = json)
        val message = Message.Assistant(malformedJson, metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)

        assertNotNull(result)
        assertIs<Message.Tool.Call>(result)
        assertEquals("string_tool", result.tool)

        val expectedContentJson = buildJsonObject {
            put("text1", JsonPrimitive(text1))
            put("text2", JsonPrimitive(text2))
        }
        assertEquals(expectedContentJson.toString(), result.content)
    }

    @Test
    fun testExtractJsonToolCall_withMissingTool() = runTest {
        val malformedJson = """
            {
                "args": {
                    "text1": "Hello",
                    "text2": "World"
                }
            }
        """.trimIndent()

        val processor = ExtractJsonToolCall(json = json)
        val message = Message.Assistant(malformedJson, metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)

        assertEquals(message, result)
    }

    @Test
    fun testExtractJsonToolCall_withNonexistentTool() = runTest {
        val malformedJson = """
            {
                "tool": "nonexistent_tool",
                "args": {
                    "text1": "\'incorrect\'",
                    "text2": "\'json\'"
                }
            }
        """.trimIndent()

        val processor = ExtractJsonToolCall(json = json)
        val message = Message.Assistant(malformedJson, metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)

        assertEquals(message, result)
    }
}
