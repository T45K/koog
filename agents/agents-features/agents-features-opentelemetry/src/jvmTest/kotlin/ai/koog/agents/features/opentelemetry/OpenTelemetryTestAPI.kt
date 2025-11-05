package ai.koog.agents.features.opentelemetry

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.GraphAIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.element.getNodeInfoElement
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.datetime.Clock

internal object OpenTelemetryTestAPI {

    //region Run Agents With Strategies

    internal suspend fun createAgentWithSingleLLMCallStrategy(): String {

    }

    internal suspend fun runAgentWithToolCallStrategy(): String {

    }

    internal suspend fun runAgentWithErrorStrategy(): String {

    }

    internal suspend fun runAgentWithParallelToolCallStrategy(): String {

    }

    //endregion Run Agents With Strategies

    //region Agents

    internal suspend fun createAgent(
        agentId: String = "test-agent-id",
        strategy: AIAgentGraphStrategy<String, String>,
        promptId: String? = null,
        promptExecutor: PromptExecutor? = null,
        toolRegistry: ToolRegistry? = null,
        model: LLModel? = null,
        clock: Clock = Clock.System,
        temperature: Double? = 0.0,
        maxTokens: Int? = null,
        systemPrompt: String? = null,
        userPrompt: String? = null,
        assistantPrompt: String? = null,
        installFeatures: GraphAIAgent.FeatureContext.() -> Unit = { }
    ): AIAgent<String, String> {
        val agentService = createAgentService(
            strategy,
            promptId,
            promptExecutor,
            toolRegistry,
            model,
            clock,
            temperature,
            maxTokens,
            systemPrompt,
            userPrompt,
            assistantPrompt,
            installFeatures
        )

        return agentService.createAgent(id = agentId, clock = clock)
    }

    internal fun createAgentService(
        strategy: AIAgentGraphStrategy<String, String>,
        promptId: String? = null,
        promptExecutor: PromptExecutor? = null,
        toolRegistry: ToolRegistry? = null,
        model: LLModel? = null,
        clock: Clock = Clock.System,
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
                clock = clock,
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
            promptExecutor = promptExecutor ?: getMockExecutor {},
            strategy = strategy,
            agentConfig = agentConfig,
            toolRegistry = toolRegistry ?: ToolRegistry { },
            installFeatures = installFeatures,
        )
    }

    //endregion Agents

    //region Features

    internal fun GraphAIAgent.FeatureContext.installNodeIdsCollector(): MutableMap<String, String> {
        val nodeNameToIdMap = mutableMapOf<String, String>()
        install(EventHandler.Feature) {
            onNodeExecutionStarting { eventContext ->
                getNodeInfoElement()?.id?.let { nodeId -> nodeNameToIdMap[eventContext.node.name] = nodeId }
            }
        }
        return nodeNameToIdMap
    }

    //endregion Features
}
