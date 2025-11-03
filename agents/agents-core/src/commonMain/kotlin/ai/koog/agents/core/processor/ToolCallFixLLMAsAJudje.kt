package ai.koog.agents.core.processor

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.prompt.Prompts.assessToolCallIntent
import ai.koog.agents.core.prompt.Prompts.fixToolArguments
import ai.koog.agents.core.prompt.Prompts.fixToolCall
import ai.koog.agents.core.prompt.Prompts.fixToolCallFormat
import ai.koog.agents.core.prompt.Prompts.fixToolName
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.text.text

/**
 * A response processor that fixes incorrectly communicated tool calls.
 *
 * Applies LLM-As-A-Judge approach to assess if a tool call was intended.
 * If a tool call was intended, LLM is asked to do a proper llm call.
 */
@ResponseProcessorApi
public class ToolCallFixLLMAsAJudge(
    private val intentSystemMessage: String = text { assessToolCallIntent() },
    private val fixSystemMessage: String = text { fixToolCall() },
    private val getFeedback: AIAgentLLMWriteSession.(Message.Response, List<String>) -> String? =
        { message, toolKeys -> defaultGetFeedback(message, toolKeys) },
    idKeys: List<String> = defaultIdKeys,
    private val toolKeys: List<String> = defaultToolKeys,
    argsKeys: List<String> = defaultArgsKeys,
    allowEscapedBackslash: Boolean = false,
    private val fallback: suspend AIAgentLLMWriteSession.(Message.Response) -> Message.Response = { it },
    private val messagePreprocessing: ResponseProcessor = Chain(
        ExtractJsonToolCall(
            idKeys = idKeys,
            toolKeys = toolKeys,
            argsKeys = argsKeys,
            allowEscapedBackslash = allowEscapedBackslash
        ),
        ExtractTaggedJsonToolCall(
            idKeys = idKeys,
            toolKeys = toolKeys,
            argsKeys = argsKeys,
            allowEscapedBackslash = allowEscapedBackslash
        )
    ),
    private val maxRetries: Int = 3,
    private val showHistory: Boolean = false,
) : ResponseProcessor() {
    init {
        require(maxRetries > 0) { "numRetries must be greater than 0" }
    }

    override suspend fun updateMessages(
        session: AIAgentLLMWriteSession,
        messages: List<Message.Response>
    ): List<Message.Response> = messages.map { session.updateMessage(it) }

    private suspend fun AIAgentLLMWriteSession.updateMessage(message: Message.Response): Message.Response {
        logger.info { "Updating message: $message" }

        val message = messagePreprocessing.process(this, message)
        if (!isToolCallIntended(message)) return message

        val fixedMessage = with(copy()) {
            if (!showHistory) {
                prompt = prompt("fix-tool-call") {}
            }

            appendPrompt {
                system(fixSystemMessage)
                message(message)
            }

            var result = message
            var i = 0

            while (i++ < maxRetries) {
                val feedback = getFeedback(result, toolKeys) ?: break
                appendPrompt { user(feedback) }
                result = requestLLMProcessed()
            }

            if (i > maxRetries && getFeedback(result, toolKeys) != null) null else result
        }

        return (fixedMessage ?: fallback(message)).also { logger.info { "Updated message: $it" } }
    }

    private suspend fun AIAgentLLMWriteSession.isToolCallIntended(message: Message.Response) =
        message is Message.Tool.Call ||
            with(
                copy(
                    prompt = prompt("check-tool-call-intended") {
                        system(intentSystemMessage)
                        user(message.content)
                    }
                )
            ) {
                val response = requestLLMWithoutTools()

                response is Message.Tool.Call || response.content.contains("YES", ignoreCase = true)
            }

    private suspend fun AIAgentLLMWriteSession.requestLLMProcessed() =
        requestLLM(responseProcessor = messagePreprocessing)
}

/**
 * Default implementation for getting feedback on a message.
 *
 * @param message The message to get feedback for.
 * @return The feedback message, or null if no feedback is needed.
 */
private fun AIAgentLLMWriteSession.defaultGetFeedback(message: Message.Response, toolKeys: List<String>): String? {
    val toolName = if (message is Message.Tool.Call) {
        message.tool
    } else {
        getToolName(message.content, toolKeys)
    } ?: return text { fixToolCallFormat(tools) }

    if (!tools.any { it.name == toolName }) {
        return text { fixToolName(toolName, tools) }
    }

    val tool = toolRegistry.getTool(toolName)

    try {
        tool.decodeArgs((message as Message.Tool.Call).contentJson)
    } catch (e: Exception) {
        val errorMessage = e.message ?: "Unknown error"
        return text { fixToolArguments(errorMessage, tool.descriptor) }
    }

    return null
}
