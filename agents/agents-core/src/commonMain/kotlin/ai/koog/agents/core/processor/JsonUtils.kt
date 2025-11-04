package ai.koog.agents.core.processor

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
private data class ToolCall(
    val id: String? = null,
    val tool: String,
    val args: JsonObject
)

private class ToolCallSerializer(
    private val idKeys: List<String>,
    private val toolKeys: List<String>,
    private val argsKeys: List<String>,
) : KSerializer<ToolCall> by delegate {
    override fun deserialize(decoder: Decoder): ToolCall {
        require(decoder is JsonDecoder) { "This serializer can only be used with JSON" }

        val jsonElement = decoder.decodeJsonElement()
        require(jsonElement is JsonObject) { "Expected a JSON object" }

        return deserializeFromJsonObject(jsonElement, decoder.json)
    }

    private fun deserializeFromJsonObject(jsonObject: JsonObject, json: Json): ToolCall {
        var objectToDeserialize = findNestedObject(jsonObject) ?: jsonObject

        objectToDeserialize = updateKey(objectToDeserialize, idKeys, "id")
        objectToDeserialize = updateKey(objectToDeserialize, toolKeys, "tool")
        objectToDeserialize = updateKey(objectToDeserialize, argsKeys, "args")

        return json.decodeFromJsonElement(delegate, objectToDeserialize)
    }

    private companion object {
        private val delegate = kotlinx.serialization.serializer<ToolCall>()

        private fun findNestedObject(jsonObject: JsonObject): JsonObject? =
            jsonObject.takeIf { it.size == 1 }?.entries?.first()?.value as? JsonObject

        private fun updateKey(
            jsonObject: JsonObject,
            expectedKeys: List<String>,
            updatedKey: String
        ) = buildJsonObject {
            for ((key, value) in jsonObject) {
                put(if (key in expectedKeys) updatedKey else key, value)
            }
        }
    }
}

private fun fixJsonString(jsonValue: String, allowEscapedBackslash: Boolean = false): String {
    // Remove the surrounding quotes to work with the content
    val content = if (jsonValue.startsWith('"') && jsonValue.endsWith('"')) {
        jsonValue.drop(1).dropLast(1)
    } else {
        jsonValue
    }

    val correctString = StringBuilder()
    var i = 0
    while (i < content.length) {
        if (content[i] == '\\' && i + 1 < content.length) {
            when (content[i + 1]) {
                '"' -> correctString.append('"')
                '\\' -> if (allowEscapedBackslash) '\\' else null
                '/' -> '/'
                'b' -> '\b'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> null
            }?.let {
                correctString.append(it)
                i++
            }
        } else {
            // Regular character
            correctString.append(content[i])
        }
        i++
    }

    return correctString.toString()
}

private fun getKeyPattern(keys: List<String>): String {
    return """"(${keys.joinToString("|")})""""
}

internal fun getToolName(messageContent: String, toolKeys: List<String>): String? {
    val toolKeyPattern = getKeyPattern(toolKeys)
    val toolNameRegex = """$toolKeyPattern\s*:\s*"([a-zA-Z0-9_]+)"""".toRegex()
    return toolNameRegex.find(messageContent)?.groupValues?.get(2)
}

internal fun extractToolCall(
    messageContent: String,
    metaInfo: ResponseMetaInfo,
    json: Json,
    session: AIAgentLLMWriteSession,
    idKeys: List<String>,
    toolKeys: List<String>,
    argsKeys: List<String>,
    allowEscapedBackslash: Boolean
): Message.Tool.Call? {
    runCatching {
        val decodedToolCall = json.decodeFromString(ToolCallSerializer(idKeys, toolKeys, argsKeys), messageContent)
        return Message.Tool.Call(
            decodedToolCall.id,
            decodedToolCall.tool,
            decodedToolCall.args.toString(),
            metaInfo
        )
    }

    val toolName = getToolName(messageContent, toolKeys) ?: return null

    val params = runCatching {
        session.toolRegistry.getTool(toolName).descriptor.requiredParameters
    }.getOrNull() ?: return null

    val argsKeyPattern = getKeyPattern(argsKeys)
    val argsPattern = """\{\s*${params.joinToString("\\s*,\\s*") { """"${it.name}"\s*:\s*(.+)""" }}\s*\}\s*\}"""
    val argsRegex = """$argsKeyPattern\s*:\s*$argsPattern""".toRegex()
    val argsMatch = argsRegex.find(messageContent)?.groupValues
    val args = argsMatch?.drop(2) ?: return null

    val fixedArgs = buildJsonObject {
        params.zip(args).forEach { (param, argValue) ->
            val key = param.name
            val value = when (param.type) {
                is ToolParameterType.String -> JsonPrimitive(fixJsonString(argValue, allowEscapedBackslash))
                else -> json.parseToJsonElement(argValue)
            }
            put(key, value)
        }
    }.toString()

    val idKeyPattern = getKeyPattern(idKeys)
    val idRegex = """$idKeyPattern\s*:\s*"([a-zA-Z0-9_]+)"""".toRegex()
    val id = idRegex.find(messageContent)?.groupValues?.get(3)

    return Message.Tool.Call(id, toolName, fixedArgs, metaInfo)
}

/**
 * Keys used by various models for tool ID in tool call json
 */
public val defaultIdKeys: List<String> = listOf("id", "tool_call_id")

/**
 * Keys used by various models for tool name in tool call json
 */
public val defaultToolKeys: List<String> = listOf("name", "tool", "tool_name")

/**
 * Keys used by various models for tool arguments in tool call json
 */
public val defaultArgsKeys: List<String> = listOf("arguments", "args", "parameters", "params", "tool_args")
