package ai.koog.agents.features.opentelemetry

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.element.getNodeInfoElement
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.mock.MockSpanExporter
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.utils.io.use
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.io.use

internal object OpenTelemetryTestAPI {

    internal val testClock: Clock = object : Clock {
        override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
    }

    //region Run Agents With Strategies

    internal suspend fun runAgentWithSingleLLMCallStrategy(
        filter: (SpanData) -> Boolean = { true },
    ): OpenTelemetryTestData {

        val strategy = strategy("test-single-llm-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
        }

        val userPrompt = "What's the weather in Paris?"
        val llmResult = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

        val executor = getMockExecutor(clock = testClock) {
            mockLLMAnswer(llmResult) onRequestEquals userPrompt
        }

        val collectedTestData = OpenTelemetryTestData(userPrompt = userPrompt, result = llmResult)

        return runAgentWithStrategy(
            strategy = strategy,
            userPrompt = userPrompt,
            executor = executor,
            filter = filter,
            collectedTestData = collectedTestData
        )
    }

    internal suspend fun runAgentWithSingleToolCallStrategy(
        filter: (SpanData) -> Boolean = { true },
    ): OpenTelemetryTestData {

        val strategy = strategy("test-tool-calls-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")
            val nodeExecuteTool by nodeExecuteTool("test-tool-call")
            val nodeSendToolResult by nodeLLMSendToolResult("test-node-llm-send-tool-result")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
        }

        val userPrompt = "What's the weather in Paris?"
        val llmResult = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

        val toolRegistry = ToolRegistry.Companion {
            tool(TestGetWeatherTool)
        }

        val toolCallId = "tool-call-id"
        val toolCallArg = "Paris"

        val executor = getMockExecutor(clock = testClock) {
            mockLLMToolCall(
                tool = TestGetWeatherTool,
                args = TestGetWeatherTool.Args(toolCallArg),
                toolCallId = toolCallId
            ) onRequestEquals userPrompt
            mockLLMAnswer(llmResult) onRequestContains TestGetWeatherTool.DEFAULT_PARIS_RESULT
        }

        val collectedTestData = OpenTelemetryTestData(
            userPrompt = userPrompt,
            result = llmResult,
            toolCallId = toolCallId,
            toolCallArg = toolCallArg,
        )

        return runAgentWithStrategy(
            strategy = strategy,
            userPrompt = userPrompt,
            executor = executor,
            toolRegistry = toolRegistry,
            filter = filter,
            collectedTestData = collectedTestData
        )
    }

    internal suspend fun runAgentWithToolCallStrategy(
        userPrompt: String,
        toolArgsLocation: String,
        llmResponse: String,
        executor: PromptExecutor? = null,
        filter: (SpanData) -> Boolean = { true },
    ): OpenTelemetryTestData {

        val strategy = strategy("test-tool-calls-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")
            val nodeExecuteTool by nodeExecuteTool("test-tool-call")
            val nodeSendToolResult by nodeLLMSendToolResult("test-node-llm-send-tool-result")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
        }

        val toolRegistry = ToolRegistry.Companion {
            tool(TestGetWeatherTool)
        }

        val toolCallId = "tool-call-id"

        val executor = executor ?: getMockExecutor(clock = testClock) {
            mockLLMToolCall(
                tool = TestGetWeatherTool,
                args = TestGetWeatherTool.Args(toolArgsLocation),
                toolCallId = toolCallId
            ) onRequestEquals userPrompt
            mockLLMAnswer(llmResponse) onRequestContains toolArgsLocation
        }

        return runAgentWithStrategy(
            strategy = strategy,
            userPrompt = userPrompt,
            executor = executor,
            toolRegistry = toolRegistry,
            filter = filter,
        )
    }

    internal suspend fun runAgentWithErrorStrategy(
        userPrompt: String? = null,
        executor: PromptExecutor? = null,
        filter: (SpanData) -> Boolean = { true },
    ): OpenTelemetryTestData {
        val strategy = strategy("test-error-strategy") {
            val nodeWithError by node<String, String>("node-with-error") {
                throw IllegalStateException("Test error")
            }

            edge(nodeStart forwardTo nodeWithError)
            edge(nodeWithError forwardTo nodeFinish)
        }

        return runAgentWithStrategy(
            strategy = strategy,
            userPrompt = userPrompt,
            executor = executor,
            filter = filter,
        )
    }

    internal suspend fun runAgentWithParallelToolCallStrategy(
        userPrompt: String? = null,
        executor: PromptExecutor? = null,
        filter: (SpanData) -> Boolean = { true },
    ): OpenTelemetryTestData {
        val strategy = strategy("test-parallel-strategy") {
            val nodeFirstJoke by node<String, String> { topic ->
                "First joke about $topic: Why do programmers prefer dark mode? Because light attracts bugs!"
            }

            val nodeSecondJoke by node<String, String> { topic ->
                "Second joke about $topic: Why do Java developers wear glasses? Because they don't C#!"
            }

            val nodeThirdJoke by node<String, String> { topic ->
                "Third joke about $topic: A SQL query walks into a bar, walks up to two tables and asks, 'Can I join you?'"
            }

            // Define a node to run joke generation in parallel
            val nodeGenerateJokes by parallel(
                nodeFirstJoke,
                nodeSecondJoke,
                nodeThirdJoke
            ) {
                selectByIndex {
                    // Always select the first joke for testing purposes
                    0
                }
            }

            edge(nodeStart forwardTo nodeGenerateJokes)
            edge(nodeGenerateJokes forwardTo nodeFinish)
        }

        return runAgentWithStrategy(
            strategy = strategy,
            userPrompt = userPrompt,
            executor = executor,
            filter = filter,
        )
    }

    internal suspend fun runAgentWithStrategy(
        strategy: AIAgentGraphStrategy<String, String>,
        userPrompt: String? = null,
        executor: PromptExecutor? = null,
        toolRegistry: ToolRegistry? = null,
        filter: (SpanData) -> Boolean = { true },
        collectedTestData: OpenTelemetryTestData? = null
    ): OpenTelemetryTestData {

        val systemPrompt = "You are the application that predicts weather"
        val userPrompt = userPrompt ?: "What's the weather in Paris?"

        val agentId = "test-agent-id"
        val promptId = "test-prompt-id"
        val model = OpenAIModels.Chat.GPT4o
        val temperature = 0.4

        var nodesInfo: List<NodeInfo> = emptyList()

        return MockSpanExporter(filter).use { mockExporter ->
            createAgent(
                agentId = agentId,
                strategy = strategy,
                executor = executor,
                promptId = promptId,
                toolRegistry = toolRegistry,
                systemPrompt = systemPrompt,
                model = model,
                temperature = temperature
            ) {
                install(OpenTelemetry) {
                    addSpanExporter(mockExporter)
                    setVerbose(true)
                }

                installNodeIdsCollector().also { nodesInfo = it }
            }.use { agent ->
                agent.run(userPrompt)
            }

            (collectedTestData ?: OpenTelemetryTestData()).merge(
                OpenTelemetryTestData(
                    agentId = agentId,
                    runId = mockExporter.lastRunId,
                    model = model,
                    temperature = temperature,
                    userPrompt = userPrompt,
                    systemPrompt = systemPrompt,
                    collectedSpans = mockExporter.collectedSpans,
                    collectedNodeIds = nodesInfo
                )
            )
        }
    }

    //endregion Run Agents With Strategies

    //region Agents

    internal suspend fun createAgent(
        agentId: String = "test-agent-id",
        strategy: AIAgentGraphStrategy<String, String>,
        executor: PromptExecutor? = null,
        promptId: String? = null,
        toolRegistry: ToolRegistry? = null,
        model: LLModel? = null,
        temperature: Double? = 0.0,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
        userPrompt: String? = null,
        assistantPrompt: String? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = { }
    ): AIAgent<String, String> {
        val agentService = createAgentService(
            strategy,
            executor,
            promptId,
            toolRegistry,
            model,
            temperature,
            maxTokens,
            systemPrompt,
            userPrompt,
            assistantPrompt,
            installFeatures
        )

        return agentService.createAgent(id = agentId)
    }

    internal fun createAgentService(
        strategy: AIAgentGraphStrategy<String, String>,
        executor: PromptExecutor? = null,
        promptId: String? = null,
        toolRegistry: ToolRegistry? = null,
        model: LLModel? = null,
        temperature: Double? = 0.0,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
        userPrompt: String? = null,
        assistantPrompt: String? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = { }
    ): GraphAIAgentService<String, String> {
        val agentConfig = AIAgentConfig(
            prompt = prompt(
                id = promptId ?: "Test prompt",
                clock = testClock,
                params = LLMParams(
                    temperature = temperature,
                    maxTokens = maxTokens
                )
            ) {
                systemPrompt?.let { system(systemPrompt) }
                userPrompt?.let { user(userPrompt) }
                assistantPrompt?.let { assistant(assistantPrompt) }
            },
            model = model ?: OpenAIModels.Chat.GPT4o,
            maxAgentIterations = 10,
        )

        return AIAgentService(
            promptExecutor = executor ?: getMockExecutor(clock = testClock) { },
            strategy = strategy,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry ?: ToolRegistry { },
            installFeatures = installFeatures,
        )
    }

    //endregion Agents

    //region Features

    internal fun GraphAIAgent.FeatureContext.installNodeIdsCollector(): List<NodeInfo> {
        val nodesInfo = mutableListOf<NodeInfo>()
        install(EventHandler.Feature) {
            onNodeExecutionStarting { eventContext ->
                getNodeInfoElement()?.id?.let { nodeId ->
                    nodesInfo.add(NodeInfo(nodeName = eventContext.node.name, nodeId = nodeId))
                }
            }
        }
        return nodesInfo
    }

    //endregion Features
}
