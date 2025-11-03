package ai.koog.integration.tests.tools

import ai.koog.agents.core.tools.SimpleTool
import kotlinx.serialization.Serializable

class FileOperationsTools {
    val fileContentsByPath = mutableMapOf<String, String>()

    val createNewFileTool = CreateNewFile(this)
    val createNewFileWithTextTool = CreateNewFileWithText(this)
    val readFileContentTool = ReadFileContent(this)
    val appendToFileTool = AppendToFile(this)
    val writeToFileTool = WriteToFile(this)

    class CreateNewFile(private val fileOperationsTools: FileOperationsTools) : SimpleTool<CreateNewFile.Args>() {
        @Serializable
        data class Args(
            val pathInProject: String
        )

        override val argsSerializer = Args.serializer()
        override val description = "Creates a new file at the specified path"

        override suspend fun doExecute(args: Args): String {
            return fileOperationsTools.createNewFileWithText(args.pathInProject, "")
        }
    }

    class CreateNewFileWithText(private val fileOperationsTools: FileOperationsTools) : SimpleTool<CreateNewFileWithText.Args>() {
        @Serializable
        data class Args(
            val pathInProject: String,
            val text: String
        )

        override val argsSerializer = Args.serializer()
        override val description = "Creates a new file at the specified path with the provided text content"

        override suspend fun doExecute(args: Args): String {
            return fileOperationsTools.createNewFileWithText(args.pathInProject, args.text)
        }
    }

    // Tool for reading file content
    class ReadFileContent(private val fileOperationsTools: FileOperationsTools) : SimpleTool<ReadFileContent.Args>() {
        @Serializable
        data class Args(
            val pathInProject: String
        )

        override val argsSerializer = Args.serializer()

        override val description = "Reads the content of a file at the specified path"

        override suspend fun doExecute(args: Args): String {
            return fileOperationsTools.readFileContent(args.pathInProject)
        }
    }

    class AppendToFile(private val fileOperationsTools: FileOperationsTools) : SimpleTool<AppendToFile.Args>() {
        @Serializable
        data class Args(
            val pathInProject: String,
            val text: String
        )

        override val argsSerializer = Args.serializer()

        override val description = "Appends the provided text to an existing file"

        override suspend fun doExecute(args: Args): String {
            return fileOperationsTools.appendToFile(args.pathInProject, args.text)
        }
    }

    class WriteToFile(private val fileOperationsTools: FileOperationsTools) : SimpleTool<WriteToFile.Args>() {
        @Serializable
        data class Args(
            val pathInProject: String,
            val text: String
        )

        override val argsSerializer = Args.serializer()

        override val description = "Writes the provided text to an existing file, replacing its current content."

        override suspend fun doExecute(args: Args): String {
            return fileOperationsTools.replaceFileContent(args.pathInProject, args.text)
        }
    }

    fun createNewFileWithText(pathInProject: String, text: String): String {
        fileContentsByPath[pathInProject] = text
        return "OK"
    }

    fun readFileContent(pathInProject: String): String {
        return fileContentsByPath[pathInProject] ?: "Error: file not found"
    }

    fun appendToFile(pathInProject: String, text: String): String {
        val existingContent = fileContentsByPath[pathInProject] ?: return "Error: file not found. If you want to create a file, use you can use a dedicated tool."
        fileContentsByPath[pathInProject] = existingContent + text
        return "OK"
    }

    fun replaceFileContent(pathInProject: String, text: String): String {
        if (!fileContentsByPath.containsKey(pathInProject)) return "Error: file not found"
        fileContentsByPath[pathInProject] = text
        return "OK"
    }

    fun asTools() = listOf(
        createNewFileTool,
        createNewFileWithTextTool,
        readFileContentTool,
        appendToFileTool,
        writeToFileTool
    )
}
