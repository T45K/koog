package ai.koog.agents.features.opentelemetry.feature

import ai.koog.agents.features.opentelemetry.OpenTelemetryTestAPI.assertMapsEqual
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

abstract class OpenTelemetryTestBase {

    protected companion object {
        private val logger = KotlinLogging.logger { }

        val testClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
        }
    }

    /**
     * Expected Span:
     *   Map<SpanName, Map<Any>>
     *       where Any = "attributes" or "events"
     *       attributes: Map<AttributeKey, AttributeValue>
     *       events: Map<EventName, Attributes>
     *           Attributes: Map<AttributeKey, AttributeValue>
     */
    @Suppress("UNCHECKED_CAST")
    protected fun assertSpans(expectedSpans: List<Map<String, Map<String, Any>>>, actualSpans: List<SpanData>) {
        // Span names
        val expectedSpanNames = expectedSpans.flatMap { it.keys }
        val actualSpanNames = actualSpans.map { it.name }

        assertSpanNames(expectedSpanNames, actualSpanNames)

        // Span attributes + events
        actualSpans.forEachIndexed { index, actualSpan ->

            val expectedSpan = expectedSpans[index]

            val expectedSpanData = expectedSpan[actualSpan.name]
            assertNotNull(expectedSpanData, "Span (name: ${actualSpan.name}) not found in expected spans")

            val spanName = actualSpan.name

            // Attributes
            val expectedAttributes = expectedSpanData["attributes"] as Map<String, Any>
            val actualAttributes = actualSpan.attributes.asMap().asSequence().associate {
                it.key.key to it.value
            }

            assertAttributes(spanName, expectedAttributes, actualAttributes)

            // Events
            val expectedEvents = expectedSpanData["events"] as Map<String, Map<String, Any>>
            val actualEvents = actualSpan.events.associate { event ->
                val actualEventAttributes = event.attributes.asMap().asSequence().associate { (key, value) ->
                    key.key to value
                }
                event.name to actualEventAttributes
            }

            assertEventsForSpan(spanName, expectedEvents, actualEvents)
        }
    }

    protected fun createCustomSdk(exporter: SpanExporter): OpenTelemetrySdk {
        val builder = OpenTelemetrySdk.builder()

        val traceProviderBuilder = SdkTracerProvider
            .builder()
            .addSpanProcessor(SimpleSpanProcessor.builder(exporter).build())

        val sdk = builder
            .setTracerProvider(traceProviderBuilder.build())
            .build()

        return sdk
    }

    protected fun assertSpanNames(expectedSpanNames: List<String>, actualSpanNames: List<String>) {
        assertEquals(
            expectedSpanNames.size,
            actualSpanNames.size,
            "Expected collection of spans should be the same size"
        )
        assertContentEquals(
            expectedSpanNames,
            actualSpanNames,
            "Expected collection of spans should be the same as actual"
        )
    }

    /**
     * Event:
     *   Map<EventName, Attributes> -> Map<EventName, Map<AttributeKey, AttributeValue>>
     */
    protected fun assertEventsForSpan(
        spanName: String,
        expectedEvents: Map<String, Map<String, Any>>,
        actualEvents: Map<String, Map<String, Any>>
    ) {
        logger.debug {
            "Asserting events for the Span (name: $spanName).\nExpected events:\n$expectedEvents\nActual events:\n$actualEvents"
        }

        assertEquals(
            expectedEvents.size,
            actualEvents.size,
            "Expected collection of events should be the same size for the span (name: $spanName)"
        )

        actualEvents.forEach { (actualEventName, actualEventAttributes) ->

            logger.debug { "Asserting event (name: $actualEventName) for the Span (name: $spanName)" }

            val expectedEventAttributes = expectedEvents[actualEventName]
            assertNotNull(
                expectedEventAttributes,
                "Event (name: $actualEventName) not found in expected events for span (name: $spanName)"
            )

            assertAttributes(spanName, expectedEventAttributes, actualEventAttributes)
        }
    }

    /**
     * Attribute:
     *   Map<AttributeKey, AttributeValue>
     */
    protected fun assertAttributes(
        spanName: String,
        expectedAttributes: Map<String, Any>,
        actualAttributes: Map<String, Any>
    ) {
        logger.debug {
            "Asserting attributes for the Span (name: $spanName).\nExpected attributes:\n$expectedAttributes\nActual attributes:\n$actualAttributes"
        }

        assertEquals(
            expectedAttributes.size,
            actualAttributes.size,
            "Expected collection of attributes should be the same size for the span (name: $spanName)\n" +
                "Expected: <${expectedAttributes.toList().joinToString(
                    prefix = "\n{\n",
                    postfix = "\n}",
                    separator = "\n"
                ) { pair ->
                    "  ${pair.first}=${pair.second}"
                }}>,\n" +
                "Actual: <${actualAttributes.toList().joinToString(
                    prefix = "\n{\n",
                    postfix = "\n}",
                    separator = "\n"
                ) { pair ->
                    "  ${pair.first}=${pair.second}"
                }}>"
        )

        actualAttributes.forEach { (actualArgName: String, actualArgValue: Any) ->

            logger.debug { "Find expected attribute (name: $actualArgName) for the Span (name: $spanName)" }
            val expectedArgValue = expectedAttributes[actualArgName]

            assertNotNull(
                expectedArgValue,
                "Attribute (name: $actualArgName) not found in expected attributes for span (name: $spanName)"
            )

            when (actualArgValue) {
                is Map<*, *> -> {
                    assertMapsEqual(expectedArgValue as Map<*, *>, actualArgValue)
                }

                is Iterable<*> -> {
                    assertContentEquals(expectedArgValue as Iterable<*>, actualArgValue.asIterable())
                }

                else -> {
                    assertEquals(
                        expectedArgValue,
                        actualArgValue,
                        "Attribute values should be the same for the span (name: $spanName)\n" +
                            "Expected: <$expectedArgValue>,\n" +
                            "Actual: <$actualArgValue>"
                    )
                }
            }
        }
    }

    protected fun toolCallMessage(id: String, name: String, content: String) =
        Message.Tool.Call(id, name, content, ResponseMetaInfo(timestamp = testClock.now()))

    protected fun assistantMessage(content: String, finishReason: String? = null) =
        Message.Assistant(content, ResponseMetaInfo(timestamp = testClock.now()), finishReason = finishReason)

}
