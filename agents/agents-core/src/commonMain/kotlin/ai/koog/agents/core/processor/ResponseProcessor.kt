package ai.koog.agents.core.processor

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.prompt.message.Message
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.RequiresOptIn
import kotlin.jvm.JvmStatic

/**
 * Opt-in annotation for ResponseProcessor API.
 */
@RequiresOptIn(
    message = "ResponseProcessor API can update LLM responses and make additional calls to LLM. Please opt-in only when you reviewed the api and understand the risks."
)
public annotation class ResponseProcessorApi

/**
 * A processor for handling and potentially modifying LLM responses.
 */
public abstract class ResponseProcessor {

    protected companion object {
        @JvmStatic
        protected val logger: KLogger = KotlinLogging.logger {}
    }

    internal suspend fun process(session: AIAgentLLMWriteSession, messages: List<Message.Response>): List<Message.Response> {
        return with(session.copy()) { updateMessages(session, messages) }
    }

    internal suspend fun process(session: AIAgentLLMWriteSession, message: Message.Response): Message.Response =
        process(session, listOf(message)).first()

    /**
     * Updates a list of messages.
     *
     * @param session The session to use for updating.
     * @param messages The messages to update.
     * @return The updated messages.
     */
    protected abstract suspend fun updateMessages(
        session: AIAgentLLMWriteSession,
        messages: List<Message.Response>
    ): List<Message.Response>

    /**
     * Chains multiple response processors together.
     */
    public class Chain(vararg processors: ResponseProcessor) : ResponseProcessor() {
        private val processors = processors.toList()

        override suspend fun updateMessages(
            session: AIAgentLLMWriteSession,
            messages: List<Message.Response>
        ): List<Message.Response> {
            var result = messages
            for (processor in processors) {
                result = processor.process(session, result)
            }
            return result
        }
    }

    /**
     * A ResponseProcessor that does not modify messages.
     * This implementation is exempt from the opt-in requirement.
     */
    public object None : ResponseProcessor() {
        override suspend fun updateMessages(
            session: AIAgentLLMWriteSession,
            messages: List<Message.Response>
        ): List<Message.Response> = messages
    }
}
