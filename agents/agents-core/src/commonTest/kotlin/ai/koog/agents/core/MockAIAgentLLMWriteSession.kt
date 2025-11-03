package ai.koog.agents.core

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.environment.AIAgentEnvironment
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.OllamaModels
import ai.koog.prompt.message.Message
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

private object MockClock : Clock {
    override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
}

/**
 * A factory function to create a mock AIAgentLLMWriteSession for testing purposes.
 */
fun createMockAIAgentLLMWriteSession(
    executor: PromptExecutor = getMockExecutor(clock = MockClock) {},
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
): AIAgentLLMWriteSession {
    val mockClock = object : Clock {
        override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
    }

    val mockEnvironment = object : AIAgentEnvironment {
        override suspend fun executeTools(toolCalls: List<Message.Tool.Call>): List<ReceivedToolResult> {
            return emptyList()
        }

        override suspend fun reportProblem(exception: Throwable) {
            throw exception
        }
    }

    return AIAgentLLMWriteSession(
        environment = mockEnvironment,
        executor = executor,
        tools = toolRegistry.tools.map { it.descriptor },
        toolRegistry = toolRegistry,
        prompt = Prompt.Empty,
        model = OllamaModels.Meta.LLAMA_3_2,
        config = AIAgentConfig(
            prompt = Prompt.Empty,
            model = OllamaModels.Meta.LLAMA_3_2,
            maxAgentIterations = 1
        ),
        clock = mockClock
    )
}
