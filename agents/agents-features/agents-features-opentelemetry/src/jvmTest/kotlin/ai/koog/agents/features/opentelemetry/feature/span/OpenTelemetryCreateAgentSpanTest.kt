package ai.koog.agents.features.opentelemetry.feature.span

import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.utils.SerializationUtils
import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI
import ai.koog.agents.features.opentelemetry.attribute.SpanAttributes
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetry
import ai.koog.agents.features.opentelemetry.feature.OpenTelemetryTestBase
import ai.koog.agents.features.opentelemetry.mock.MockSpanExporter
import ai.koog.agents.testing.tools.getMockExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.io.use
import kotlinx.coroutines.test.runTest
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenTelemetryCreateAgentSpanTest : OpenTelemetryTestBase() {

    @Test
    fun `test spans are created for agent with one llm call`() = runTest {
        MockSpanExporter().use { mockExporter ->

            val systemPrompt = "You are the application that predicts weather"
            val userPrompt = "What's the weather in Paris?"

            val agentId = "test-agent-id"
            val promptId = "test-prompt-id"
            val model = OpenAIModels.Chat.GPT4o
            val temperature = 0.4

            val strategy = strategy("test-strategy") {
                val nodeSendInput by nodeLLMRequest("test-llm-call")

                edge(nodeStart forwardTo nodeSendInput)
                edge(nodeSendInput forwardTo nodeFinish onAssistantMessage { true })
            }

            val mockResponse = "The weather in Paris is rainy and overcast, with temperatures around 57°F"

            val mockExecutor = getMockExecutor(clock = testClock) {
                mockLLMAnswer(mockResponse) onRequestEquals userPrompt
            }

            OpenTelemetryTestAPI.createAgent(
                agentId = agentId,
                strategy = strategy,
                promptId = promptId,
                systemPrompt = systemPrompt,
                promptExecutor = mockExecutor,
                model = model,
                clock = testClock,
                temperature = temperature
            ) {
                install(OpenTelemetry.Feature) {
                    addSpanExporter(mockExporter)
                    setVerbose(true)
                }
            }.use { agent ->
                agent.run(userPrompt)
            }

            val collectedSpans = mockExporter.collectedSpans
            assertTrue(collectedSpans.isNotEmpty(), "Spans should be created during agent execution")

            // Check each span

            val expectedSpans = listOf(
                mapOf(
                    "agent.$agentId" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.operation.name" to "create_agent",
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.agent.id" to agentId,
                            "gen_ai.request.model" to model.id
                        ),
                        "events" to emptyMap()
                    )
                ),

                mapOf(
                    "run.${mockExporter.lastRunId}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.operation.name" to "invoke_agent",
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.agent.id" to agentId,
                            "gen_ai.conversation.id" to mockExporter.lastRunId
                        ),
                        "events" to emptyMap()
                    )
                ),

                mapOf(
                    "node.__finish__.\"${mockResponse}\"" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "__finish__",
                            "koog.node.output" to "\"$mockResponse\"",
                            "koog.node.input" to "\"$mockResponse\"",
                        ),
                        "events" to emptyMap()
                    )
                ),

                mapOf(
                    "node.test-llm-call.\"${userPrompt}\"" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "test-llm-call",
                            "koog.node.input" to "\"$userPrompt\"",
                            "koog.node.output" to @OptIn(InternalAgentsApi::class)
                            SerializationUtils.encodeDataToStringOrDefault(
                                data = Message.Assistant(
                                    content = mockResponse,
                                    metaInfo = ResponseMetaInfo(
                                        timestamp = testClock.now()
                                    )
                                ),
                                dataType = typeOf<Message>()
                            ),
                        ),
                        "events" to emptyMap()
                    )
                ),

                mapOf(
                    "llm.${userPrompt}" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.operation.name" to "chat",
                            "gen_ai.system" to model.provider.id,
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "gen_ai.request.temperature" to temperature,
                            "gen_ai.request.model" to model.id,
                            "gen_ai.response.finish_reasons" to listOf(SpanAttributes.Response.FinishReasonType.Stop.id)
                        ),
                        "events" to mapOf(
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to userPrompt
                            )
                        ),

                        "events" to mapOf(
                            "gen_ai.system.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.System.name.lowercase(),
                                "content" to systemPrompt,
                            ),
                            "gen_ai.user.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.User.name.lowercase(),
                                "content" to userPrompt,
                            ),
                            "gen_ai.assistant.message" to mapOf(
                                "gen_ai.system" to model.provider.id,
                                "role" to Message.Role.Assistant.name.lowercase(),
                                "content" to mockResponse,
                            )
                        )
                    )
                ),

                mapOf(
                    "node.__start__.\"$userPrompt\"" to mapOf(
                        "attributes" to mapOf(
                            "gen_ai.conversation.id" to mockExporter.lastRunId,
                            "koog.node.name" to "__start__",
                            "koog.node.input" to "\"$userPrompt\"",
                            "koog.node.output" to "\"$userPrompt\"",
                        ),
                        "events" to emptyMap()
                    )
                )
            )

            assertSpans(expectedSpans, collectedSpans)
        }
    }

}
