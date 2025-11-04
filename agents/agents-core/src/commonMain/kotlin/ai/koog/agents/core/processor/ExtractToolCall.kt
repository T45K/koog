package ai.koog.agents.core.processor

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.Json

/**
 * A response processor that extracts tool calls from JSON responses.
 * Fixes incorrectly formatted json, e.g.
 *   - incorrect tool id / name / arguments keys
 *   - missing escapes in strings
 */
@ResponseProcessorApi
public class ExtractJsonToolCall(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    private val idKeys: List<String> = defaultIdKeys,
    private val toolKeys: List<String> = defaultToolKeys,
    private val argsKeys: List<String> = defaultArgsKeys,
    private val allowEscapedBackslash: Boolean = false
) : ResponseProcessor() {
    override suspend fun updateMessages(
        session: AIAgentLLMWriteSession,
        messages: List<Message.Response>
    ): List<Message.Response> = messages.map { message ->
        message
            as? Message.Tool.Call
            ?: extractToolCall(
                message.content,
                message.metaInfo,
                json,
                session,
                idKeys,
                toolKeys,
                argsKeys,
                allowEscapedBackslash
            )
            ?: message
    }
}

/**
 * A response processor that extracts tagged tool calls from llm responses.
 */
@ResponseProcessorApi
public class ExtractTaggedJsonToolCall(
    private val startTag: String = "<tool_call>",
    private val endTag: String = "</tool_call>",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
    private val idKeys: List<String> = defaultIdKeys,
    private val toolKeys: List<String> = defaultToolKeys,
    private val argsKeys: List<String> = defaultArgsKeys,
    private val allowEscapedBackslash: Boolean = false,
) : ResponseProcessor() {
    override suspend fun updateMessages(
        session: AIAgentLLMWriteSession,
        messages: List<Message.Response>
    ): List<Message.Response> = messages.map { message ->
        message as? Message.Tool.Call ?: searchToolCall(message, session) ?: message
    }

    private fun searchToolCall(message: Message.Response, session: AIAgentLLMWriteSession): Message.Tool.Call? {
        val content = message.content
        var startIndex = content.indexOf(startTag)

        while (startIndex != -1) {
            startIndex += startTag.length

            val endIndex = content.indexOf(endTag, startIndex)

            if (endIndex == -1) break

            val toolCall = content.substring(startIndex, endIndex)
            val result = extractToolCall(toolCall, message.metaInfo, json, session, idKeys, toolKeys, argsKeys, allowEscapedBackslash)
            result?.let { return it }

            startIndex += startTag.length
        }

        return null
    }
}
