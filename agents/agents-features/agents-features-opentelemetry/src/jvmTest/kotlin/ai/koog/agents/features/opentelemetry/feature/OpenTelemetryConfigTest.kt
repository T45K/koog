package ai.koog.agents.features.opentelemetry.feature

import ai.koog.agents.core.agent.context.element.getNodeInfoElement
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.features.opentelemetry.OpenTelemetrySpanAsserts.assertSpans
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.createAgent
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.testClock
import ai.koog.agents.features.opentelemetry.attribute.CustomAttribute
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes.Response.FinishReasonType
import ai.koog.agents.features.opentelemetry.integration.SpanAdapter
import ai.koog.agents.features.opentelemetry.mock.MockSpanExporter
import ai.koog.agents.features.opentelemetry.mock.TestGetWeatherTool
import ai.koog.agents.features.opentelemetry.span.GenAIAgentSpan
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.agents.utils.HiddenString
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.message.Message
import ai.koog.utils.io.use
import io.opentelemetry.sdk.OpenTelemetrySdk
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the OpenTelemetry feature.
 *
 * These tests verify that spans are created correctly during agent execution
 * and that the structure of spans matches the expected hierarchy.
 */
class OpenTelemetryConfigTest : OpenTelemetryTestBase() {

    @Test
    fun `test Open Telemetry feature default configuration`() = runTest {
        val strategy = strategy("test-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
        }

        var actualServiceName: String? = null
        var actualServiceVersion: String? = null
        var actualIsVerbose: Boolean? = null

        createAgent(
            strategy = strategy,
            clock = testClock,
        ) {
            install(OpenTelemetry) {
                actualServiceName = serviceName
                actualServiceVersion = serviceVersion
                actualIsVerbose = isVerbose
            }
        }

        val props = Properties()
        this::class.java.classLoader.getResourceAsStream("product.properties")?.use { stream -> props.load(stream) }

        assertEquals(props["name"], actualServiceName)
        assertEquals(props["version"], actualServiceVersion)
        assertEquals(false, actualIsVerbose)
    }

    @Test
    fun `test Open Telemetry feature custom configuration`() = runTest {
        val strategy = strategy("test-strategy") {
            val nodeSendInput by nodeLLMRequest("test-llm-call")

            edge(nodeStart forwardTo nodeSendInput)
            edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
        }

        val expectedServiceName = "test-service-name"
        val expectedServiceVersion = "test-service-version"
        val expectedIsVerbose = true

        var actualServiceName: String? = null
        var actualServiceVersion: String? = null
        var actualIsVerbose: Boolean? = null

        createAgent(
            strategy = strategy,
            clock = testClock,
        ) {
            install(OpenTelemetry) {
                setServiceInfo(expectedServiceName, expectedServiceVersion)
                setVerbose(expectedIsVerbose)

                actualServiceName = serviceName
                actualServiceVersion = serviceVersion
                actualIsVerbose = isVerbose
            }
        }

        assertEquals(expectedServiceName, actualServiceName)
        assertEquals(expectedServiceVersion, actualServiceVersion)
        assertEquals(expectedIsVerbose, actualIsVerbose)
    }

    @Test
    fun `test filter is not allowed for open telemetry feature`() = runTest {
        // TODO: SD -- add test to verify that events are not filtered
    }

    @Test
    fun `test install Open Telemetry feature with custom sdk, should use provided sdk`() = runTest {
        val strategy = strategy<String, String>("test-strategy") {
            edge(nodeStart forwardTo nodeFinish transformed { "Done" })
        }

        val expectedSdk = OpenTelemetrySdk.builder().build()
        var actualSdk: OpenTelemetrySdk? = null

        createAgent(
            strategy = strategy,
        ) {
            install(OpenTelemetry) {
                setSdk(expectedSdk)
                actualSdk = sdk
            }
        }

        assertEquals(expectedSdk, actualSdk)
    }

    @Test
    fun `test custom sdk configuration emits correct spans`() = runTest {
        MockSpanExporter().use { mockExporter ->
            val userPrompt = "What's the weather in Paris?"

            val strategy = strategy("test-strategy") {
                val nodeSendInput by nodeLLMRequest("test-llm-call")
                edge(nodeStart forwardTo nodeSendInput)
                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
            }

            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

            val mockExecutor = getMockExecutor {
                mockLLMAnswer(mockResponse) onRequestEquals userPrompt
            }

            val expectedSdk = createCustomSdk(mockExporter)

            val agent = createAgent(
                promptExecutor = mockExecutor,
                strategy = strategy,
            ) {
                install(OpenTelemetry) {
                    setSdk(expectedSdk)
                }
            }

            agent.run(userPrompt)
            val collectedSpans = mockExporter.collectedSpans
            agent.close()

            assertEquals(6, collectedSpans.size)
        }
    }

    @Test
    fun `test span adapter applies custom attribute to invoke agent span`() = runTest {
        MockSpanExporter().use { mockExporter ->

            val userPrompt = "What's the weather in Paris?"
            val agentId = "test-agent-id"
            val promptId = "test-prompt-id"
            val model = OpenAIModels.Chat.GPT4o

            val strategyName = "test-strategy"

            val strategy = strategy(strategyName) {
                val nodeSendInput by nodeLLMRequest("test-llm-call")

                edge(nodeStart forwardTo nodeSendInput)
                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
            }

            val mockResponse = "Sunny"
            val mockExecutor = getMockExecutor(clock = testClock) {
                mockLLMAnswer(mockResponse) onRequestEquals userPrompt
            }

            // Custom SpanAdapter that adds a test attribute to each processed span
            val customBeforeStartAttribute = CustomAttribute(key = "test.adapter.before.start.key", value = "test-value-before-start")
            val customBeforeFinishAttribute = CustomAttribute(key = "test.adapter.before.finish.key", value = "test-value-before-finish")
            val adapter = object : SpanAdapter() {
                override fun onBeforeSpanStarted(span: GenAIAgentSpan) {
                    span.addAttribute(customBeforeStartAttribute)
                }

                override fun onBeforeSpanFinished(span: GenAIAgentSpan) {
                    span.addAttribute(customBeforeFinishAttribute)
                }
            }

            createAgent(
                agentId = agentId,
                strategy = strategy,
                promptId = promptId,
                promptExecutor = mockExecutor,
                model = model,
                clock = testClock,
            ) {
                install(OpenTelemetry) {
                    addSpanExporter(mockExporter)

                    // Add custom span adapter
                    addSpanAdapter(adapter)
                }
            }.use { agent ->
                agent.run(userPrompt)
            }

            val collectedSpans = mockExporter.collectedSpans
            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

            val actualInvokeAgentSpans = collectedSpans.filter { span -> span.name.startsWith("run.") }
            assertEquals(1, actualInvokeAgentSpans.size, "Invoke agent span should be present")

            val expectedInvokeAgentSpans = listOf(
                mapOf(

                    "run.${mockExporter.lastRunId}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            customBeforeStartAttribute.key to customBeforeStartAttribute.value,
                            customBeforeFinishAttribute.key to customBeforeFinishAttribute.value,
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.agent.id" to agentId,
                            "gen_ai.operation.name" to SpanAttributes.Operation.OperationNameType.INVOKE_AGENT.id,
                        ),
                        "events" to emptyMap()
                    )
                )
            )

            assertSpans(expectedInvokeAgentSpans, actualInvokeAgentSpans)
        }
    }

    @Test
    fun `test spans for agent with tool call and verbose level set to false`() = runTest {
        MockSpanExporter().use { mockExporter ->

            val systemPrompt = "You are the application that predicts weather"
            val userPrompt = "What's the weather in Paris?"
            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

            val agentId = "test-agent-id"
            val promptId = "test-prompt-id"
            val model = OpenAIModels.Chat.GPT4o
            val temperature = 0.4

            val strategy = strategy("test-strategy") {
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

            val toolRegistry = ToolRegistry {
                tool(TestGetWeatherTool)
            }

            val toolCallId = "tool-call-id"

            val mockExecutor = getMockExecutor(clock = testClock) {
                mockLLMToolCall(tool = TestGetWeatherTool, args = TestGetWeatherTool.Args("Paris"), toolCallId = toolCallId) onRequestEquals userPrompt
                mockLLMAnswer(mockResponse) onRequestContains "57°F"
            }

            val nodeNameToIdMap = mutableMapOf<String, String>()

            createAgent(
                agentId = agentId,
                strategy = strategy,
                systemPrompt = systemPrompt,
                promptId = promptId,
                toolRegistry = toolRegistry,
                promptExecutor = mockExecutor,
                model = model,
                clock = testClock,
                temperature = temperature
            ) {
                install(OpenTelemetry) {
                    addSpanExporter(mockExporter)
                    setVerbose(false)
                }

                install(EventHandler) {
                    onNodeExecutionStarting { eventContext ->
                        getNodeInfoElement()?.id?.let { nodeId -> nodeNameToIdMap[eventContext.node.name] = nodeId }
                    }
                }
            }.use { agent ->
                agent.run(userPrompt)
            }

            val collectedSpans = mockExporter.collectedSpans
            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

            // Check Spans

            val expectedSpans = listOf(
                mapOf(
                    "agent.$agentId" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.agent.id" to agentId,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.operation.name" to "create_agent",
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "run.${mockExporter.lastRunId}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.agent.id" to agentId,
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.operation.name" to "invoke_agent",
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "node.__finish__.${nodeNameToIdMap["__finish__"]}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "__finish__",
                            "koog.node.input" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "koog.node.output" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "node.test-node-llm-send-tool-result.${nodeNameToIdMap["test-node-llm-send-tool-result"]}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "test-node-llm-send-tool-result",
                            "koog.node.input" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "koog.node.output" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "llm.${TestGetWeatherTool.DEFAULT_PARIS_RESULT}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.operation.name" to "chat",
                            "gen_ai.request.temperature" to temperature,
                            "gen_ai.response.finish_reasons" to listOf(FinishReasonType.Stop.id),
                        ),
                        "events" to mapOf(
                            "gen_ai.system.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.System.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            ),
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            ),
                            "gen_ai.choice" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "tool_calls" to "[{\"function\":{\"name\":\"${HiddenString.HIDDEN_STRING_PLACEHOLDER}\",\"arguments\":\"${HiddenString.HIDDEN_STRING_PLACEHOLDER}\"},\"id\":\"$toolCallId\",\"type\":\"function\"}]",
                                "finish_reason" to FinishReasonType.ToolCalls.id,
                            ),
                            "gen_ai.tool.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                                "id" to toolCallId,
                            ),
                            "gen_ai.assistant.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Assistant.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            ),
                        )
                    )
                ),
                mapOf(
                    "node.test-tool-call.${nodeNameToIdMap["test-tool-call"]}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "test-tool-call",
                            "koog.node.input" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "koog.node.output" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "tool.Get whether" to mapOf(
                        "attributes" to mapOf(
                            "output.value" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "input.value" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "gen_ai.tool.description" to "The test tool to get a whether based on provided location.",
                            "gen_ai.tool.name" to "Get whether",
                            "gen_ai.tool.call.id" to toolCallId,
                        ),
                        "events" to mapOf()
                    )
                ),
                mapOf(
                    "node.test-llm-call.${nodeNameToIdMap["test-llm-call"]}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "test-llm-call",
                            "koog.node.input" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "koog.node.output" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                        ),
                        "events" to emptyMap()
                    )
                ),
                mapOf(
                    "llm.${userPrompt}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.operation.name" to "chat",
                            "gen_ai.request.temperature" to temperature,
                            "gen_ai.response.finish_reasons" to listOf(FinishReasonType.ToolCalls.id),
                        ),
                        "events" to mapOf(
                            "gen_ai.system.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.System.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            ),
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            ),
                            "gen_ai.choice" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "index" to 0L,
                                "role" to Message.Role.Tool.name.lowercase(),
                                "tool_calls" to "[{\"function\":{\"name\":\"${HiddenString.HIDDEN_STRING_PLACEHOLDER}\",\"arguments\":\"${HiddenString.HIDDEN_STRING_PLACEHOLDER}\"},\"id\":\"$toolCallId\",\"type\":\"function\"}]",
                                "finish_reason" to FinishReasonType.ToolCalls.id,
                            ),
                        )
                    )
                ),
                mapOf(
                    "node.__start__.${nodeNameToIdMap["__start__"]}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "__start__",
                            "koog.node.input" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                            "koog.node.output" to HiddenString.HIDDEN_STRING_PLACEHOLDER,
                        ),
                        "events" to emptyMap()
                    )
                ),
            )

            assertSpans(expectedSpans, collectedSpans)
        }
    }

}
