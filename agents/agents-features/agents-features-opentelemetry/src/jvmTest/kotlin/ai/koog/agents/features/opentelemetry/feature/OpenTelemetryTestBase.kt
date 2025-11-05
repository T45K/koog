package ai.koog.agents.features.opentelemetry.feature

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

abstract class OpenTelemetryTestBase {

    protected companion object {
        val testClock: Clock = object : Clock {
            override fun now(): Instant = Instant.parse("2023-01-01T00:00:00Z")
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

    protected fun toolCallMessage(id: String, name: String, content: String) =
        Message.Tool.Call(id, name, content, ResponseMetaInfo(timestamp = testClock.now()))

    protected fun assistantMessage(content: String, finishReason: String? = null) =
        Message.Assistant(content, ResponseMetaInfo(timestamp = testClock.now()), finishReason = finishReason)
}
