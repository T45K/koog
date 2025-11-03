package ai.koog.agents.core.processor

import ai.koog.agents.core.CalculatorTools
import ai.koog.agents.core.createMockAIAgentLLMWriteSession
import ai.koog.agents.core.prompt.Prompts.assessToolCallIntent
import ai.koog.agents.core.prompt.Prompts.fixToolCall
import ai.koog.agents.core.prompt.Prompts.fixToolCallFormat
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ResponseProcessorApi::class)
class ToolCallFixLLMAsAJudgeTest {
    private companion object {
        private val testClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
        }

        private val testMetaInfo = ResponseMetaInfo.create(testClock)

        private val notToolCallMessage = Message.Assistant("NO", metaInfo = testMetaInfo)
        private val intendedToolCallMessage = Message.Assistant("YES", metaInfo = testMetaInfo)
        private val toolCallMessage = Message.Tool.Call(
            id = null,
            tool = "plus",
            content = """{"a":5,"b":3}""",
            metaInfo = testMetaInfo
        )

        private val toolRegistry = ToolRegistry {
            tool(CalculatorTools.PlusTool)
        }

        private val processor = ToolCallFixLLMAsAJudge()

        private val message = Message.Assistant("I want to use the calculator tool", metaInfo = testMetaInfo)
    }

    private class MockExecutor(
        private val responses: List<Message.Response>,
    ) : PromptExecutor {
        private var index = 0
        val prompts = mutableListOf<Prompt>()

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): List<Message.Response> = listOf(responses[index++]).also { prompts.add(prompt) }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> = error("Not supported")

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel
        ): ModerationResult = error("Not supported")

        override fun close() {}
    }

    @Test
    fun testNotToolCall_whenToolCallIsIntended() = runTest {
        val executor = MockExecutor(listOf(notToolCallMessage))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val result = processor.process(mockSession, message)
        assertEquals(message, result)
        assertEquals(
            executor.prompts.last().messages.dropLast(1).last().content,
            markdown { assessToolCallIntent() }
        )
    }

    @Test
    fun testToolCallIntended_assistant() = runTest {
        val executor = MockExecutor(listOf(intendedToolCallMessage, toolCallMessage))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val message = Message.Assistant("assistant", metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)
        assertEquals(toolCallMessage, result)
        assertEquals(
            executor.prompts.last().messages.dropLast(2).last().content,
            markdown { fixToolCall() }
        )
        assertEquals(
            executor.prompts.last().messages.last().content,
            markdown { fixToolCallFormat(toolRegistry.tools.map { it.descriptor }) }
        )
    }

    @Test
    fun testToolCallIntended_incorrectName() = runTest {
        val executor = MockExecutor(listOf(intendedToolCallMessage, toolCallMessage))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val message = toolCallMessage.copy(tool = "minus")
        val result = processor.process(mockSession, message)
        assertEquals(toolCallMessage, result)
        assertEquals(
            executor.prompts.last().messages.last().content,
            markdown { fixToolCallFormat(toolRegistry.tools.map { it.descriptor }) }
        )
    }

    @Test
    fun testToolCallIntended_incorrectArguments() = runTest {
        val executor = MockExecutor(listOf(intendedToolCallMessage, toolCallMessage))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val message = Message.Tool.Call(
            id = null,
            tool = "plus",
            content = """{"x":5,"y":3}""",
            metaInfo = testMetaInfo
        )
        val result = processor.process(mockSession, message)
        assertEquals(toolCallMessage, result)
        assertContains(
            executor.prompts.last().messages.dropLast(2).last().content,
            markdown { "Failed to parse tool arguments with error" }
        )
    }

    @Test
    fun testFixToolCall_retryMechanism() = runTest {
        val executor = MockExecutor(
            listOf(intendedToolCallMessage, toolCallMessage.copy(tool = "minus"), toolCallMessage)
        )
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val message = Message.Assistant("assistant", metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)
        assertEquals(toolCallMessage, result)
    }

    @Test
    fun testFixToolCall_maxRetriesReached() = runTest {
        val executor = MockExecutor(listOf(intendedToolCallMessage, toolCallMessage.copy(tool = "minus")))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val processor = ToolCallFixLLMAsAJudge(
            maxRetries = 1,
        )

        val message = Message.Assistant("assistant", metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)
        assertEquals(message, result)
    }

    @Test
    fun testFixToolCall_fallbackMechanism() = runTest {
        val executor = MockExecutor(listOf(intendedToolCallMessage, toolCallMessage))
        val mockSession = createMockAIAgentLLMWriteSession(
            executor = executor,
            toolRegistry = toolRegistry
        )

        val fallbackExecutor = MockExecutor(listOf(toolCallMessage))
        val processor = ToolCallFixLLMAsAJudge(
            fallback = { fallbackExecutor.execute(prompt, model, tools).first() },
            maxRetries = 1,
        )

        val message = Message.Assistant("assistant", metaInfo = testMetaInfo)
        val result = processor.process(mockSession, message)
        assertEquals(toolCallMessage, result)
    }
}
