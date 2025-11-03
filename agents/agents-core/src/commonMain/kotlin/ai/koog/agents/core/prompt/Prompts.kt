package ai.koog.agents.core.prompt

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.markdown.markdown
import ai.koog.prompt.text.TextContentBuilderBase

internal object Prompts {
    fun TextContentBuilderBase<*>.selectRelevantTools(tools: List<ToolDescriptor>, subtaskDescription: String) =
        markdown {
            +"You will be now concentrating on solving the following task:"
            br()

            h2("TASK DESCRIPTION")
            br()
            +subtaskDescription
            br()

            h2("AVAILABLE TOOLS")
            br()
            +"You have the following tools available:"
            br()
            bulleted {
                tools.forEach {
                    item("Name: ${it.name}\nDescription: ${it.description}")
                }
            }
            br()
            br()

            +"Please, provide a list of the tools ONLY RELEVANT FOR THE GIVEN TASK, separated by commas."
            +"Think carefully about the tools you select, and make sure they are relevant to the task."
        }

    fun TextContentBuilderBase<*>.summarizeInTLDR() =
        markdown {
            +"Create a comprehensive summary of this conversation."
            br()
            +"Include the following in your summary:"
            numbered {
                item("Key objectives and problems being addressed")
                item("All tools used along with their purpose and outcomes")
                item("Critical information discovered or generated")
                item("Current progress status and conclusions reached")
                item("Any pending questions or unresolved issues")
            }
            br()
            +"FORMAT YOUR SUMMARY WITH CLEAR SECTIONS for easy reference, including:"
            bulleted {
                item("Key Objectives")
                item("Tools Used & Results")
                item("Key Findings")
                item("Current Status")
                item("Next Steps")
            }
            br()
            +"This summary will be the ONLY context available for continuing this conversation, along with the system message."
            +"Ensure it contains ALL essential information needed to proceed effectively."
        }

    fun TextContentBuilderBase<*>.assessToolCallIntent(): Unit =
        markdown {
            +"You are a helpful assistant specialized in determining if a message is intended to call a tool."
            br()

            h2("TASK")
            +"The user will provide you with a message."
            +"Your task is to determine if this message is intended to call a tool or if it's just a regular assistant message."
            br()

            h2("INSTRUCTIONS")
            +"Determine if the message is meant to call a tool or perform a specific action that would require a tool call."
            +"Important: Distinguish between reports about actions and actual intent to perform actions:"
            bulleted {
                item("If the message only reports or describes what was done or what happened, it is NOT a tool call")
                item("If the message expresses an intent to perform an action or request an action to be performed, it IS a tool call")
                item("If the message contains both, it IS a tool call")
            }

            h3("EXAMPLES OF INTENT PHRASES")
            +"These phrases indicate an intent to perform an action (IS a tool call):"
            bulleted {
                item("Now I will do that")
                item("Now I have to do that")
                item("Let me search for that")
                item("When a message contains a json-like structure designed to call a tool: {name: <tool_name>, args: <tool_args>}")
            }

            h2("RESPONSE FORMAT")
            +"Respond with ONLY ONE of these exact options:"

            bulleted {
                item("YES - if a tool call was intended")
                item("NO - if no tool call was intended")
            }
            br()
        }

    fun TextContentBuilderBase<*>.fixToolCall(): Unit =
        markdown {
            +"You are a helpful assistant specialized in fixing tool call formats."
            br()

            h2("TASK")
            +"You will see a tool call message with an incorrect format: invalid JSON or use incorrect tool names."
            +"Your task is to convert the message to the proper format."
            br()

            h2("COMMON ISSUES TO FIX")
            bulleted {
                item("Intent message instead of direct tool call")
                item("Invalid JSON syntax")
                item("Missing required parameters for the tool")
                item("Incorrect tool names (misspelled or non-existent)")
            }
            br()

            +"Your goal is to fix the format while preserving the original intention of the message."
            +"YOUR RESPONSE MUST BE A TOOL CALL MESSAGE IN THE CORRECT FORMAT!"
            br()
        }

    fun TextContentBuilderBase<*>.fixToolCallFormat(tools: List<ToolDescriptor>) =
        markdown {
            +"The message appears to be intending to call a tool, but it's not in the proper tool call format."
            br()

            +"Please generate a proper tool call message based on the provided message."
            br()

            h2("IMPORTANT INSTRUCTIONS")
            bulleted {
                item("DO NOT explain what you're going to do - just call the tool directly")
                item("DO NOT respond with text descriptions - use the JSON format")
            }
            br()

            h2("POSSIBLE ISSUES")
            bulleted {
                item("The message shows an intention to call a tool but does not produce a tool call")
                item("Incorrect json formatting in tool call json: unescaped characters, missing quotes, etc.")
            }

            h2("Available tools")
            showTools(tools)
        }

    fun TextContentBuilderBase<*>.fixToolName(toolName: String, tools: List<ToolDescriptor>) =
        markdown {
            +"Tool name \"$toolName\" is not recognized."
            br()

            +"Available tools:"
            showTools(tools)
        }

    fun TextContentBuilderBase<*>.fixToolArguments(errorMessage: String, tool: ToolDescriptor) =
        markdown {
            +"Failed to parse tool arguments with error: $errorMessage"
            br()

            +"$tool"
            br()

            +"Please rewrite the tool call using proper JSON format."
        }

    fun TextContentBuilderBase<*>.showTools(tools: List<ToolDescriptor>) =
        markdown {
            bulleted {
                tools.forEach { tool ->
                    item(tool.name)
                }
            }
        }
}
