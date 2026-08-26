package dev.autopilot.terminal.agent

import dev.autopilot.terminal.llm.ToolDefinition
import dev.autopilot.terminal.llm.ToolFunction
import dev.autopilot.terminal.perms.CommandChannel
import dev.autopilot.terminal.perms.ExecResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

object AgentTools {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val EXECUTE = "execute"
    const val BATCH = "batch"
    const val READ_FILE = "read_file"
    const val WRITE_FILE = "write_file"
    const val EDIT_FILE = "edit_file"
    const val GLOB = "glob"
    const val GREP = "grep"
    const val TODO = "todo"
    const val WAIT = "wait"
    const val FINISH = "finish"
    const val ABORT = "abort"
    const val RUNBG = "runbg"
    const val JOBLOG = "joblog"
    const val SUBAGENT = "dispatch_subagent"
    const val LISTEN = "listen"

    fun schemas(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = EXECUTE,
                description = "Execute a single shell command in the terminal. Returns stdout+stderr and exit code. Use this for running scripts, installing packages, system operations, etc. Prefer batch when running multiple independent commands.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("command")) }
                    put("properties", buildJsonObject {
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "The exact shell command to execute")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Brief one-line description of what this command does and why")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = BATCH,
                description = "Execute multiple shell commands in parallel. Commands run concurrently and results are returned in order. Use for independent operations like reading multiple files, checking multiple services, or parallel data collection.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("commands")) }
                    put("properties", buildJsonObject {
                        put("commands", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "Array of shell commands to execute in parallel")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "Brief description of what this batch accomplishes")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = READ_FILE,
                description = "Read a file's content natively (no shell needed). Supports line-based pagination via offset and limit. Returns content with line numbers. Much faster and more reliable than 'cat' for reading files. Automatically handles large files by paginating.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Absolute or workspace-relative file path")
                        })
                        put("offset", buildJsonObject {
                            put("type", "integer")
                            put("description", "Starting line number (1-indexed). Default: 1")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of lines to read. Default: 2000")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = WRITE_FILE,
                description = "Create or overwrite a file with the given content. Creates parent directories if needed. Much more reliable than echo/redirection for writing code files.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("content")) }
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Absolute or workspace-relative file path")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The complete content to write to the file")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = EDIT_FILE,
                description = "Surgically edit a file by replacing an exact string with a new string. The old_string must match exactly (including whitespace) and must be unique in the file. If not unique, provide more surrounding context to make it unique. Much safer than sed/awk for targeted edits.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("old_string")); add(JsonPrimitive("new_string")) }
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Absolute or workspace-relative file path")
                        })
                        put("old_string", buildJsonObject {
                            put("type", "string")
                            put("description", "The exact string to find in the file (must match exactly including whitespace)")
                        })
                        put("new_string", buildJsonObject {
                            put("type", "string")
                            put("description", "The replacement string")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = GLOB,
                description = "Find files matching a glob pattern. Patterns: ** for recursive, * for single level, {ext1,ext2} for alternatives. Examples: **/*.kt finds all Kotlin files recursively, src/*.ts finds TypeScript in src/ only. Much faster than 'find' command.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Glob pattern, e.g. **/*.kt, src/**/*.py, *.{js,ts}")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Base directory for the search. Default: workspace root")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = GREP,
                description = "Search file contents using regex. Returns matching lines with file paths and line numbers. Supports include patterns to filter file types. Much faster and more structured than shell grep.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("pattern")) }
                    put("properties", buildJsonObject {
                        put("pattern", buildJsonObject {
                            put("type", "string")
                            put("description", "Regex pattern to search for in file contents")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Directory to search in. Default: workspace root")
                        })
                        put("include", buildJsonObject {
                            put("type", "string")
                            put("description", "File pattern to include, e.g. *.kt, *.py. Default: all files")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = TODO,
                description = "Update the visible task checklist. Use this to show progress to the user. Each item has text and done status.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("items")) }
                    put("properties", buildJsonObject {
                        put("items", buildJsonObject {
                            put("type", "array")
                            put("description", "Task list items")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("text", buildJsonObject { put("type", "string") })
                                    put("done", buildJsonObject { put("type", "boolean") })
                                })
                            })
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = RUNBG,
                description = "Run a long-running command in the background (non-blocking). Returns immediately with a job name. Use joblog to check progress later. Perfect for installations, compilations, downloads, servers.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("command")) }
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Job identifier name for tracking")
                        })
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "The command to run in background")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = JOBLOG,
                description = "Check the output log of a background job. Returns recent output lines.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("name")) }
                    put("properties", buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Job name to check")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = WAIT,
                description = "Wait for a specified number of seconds before continuing. Use when waiting for background jobs or services to start.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("seconds", buildJsonObject {
                            put("type", "integer")
                            put("description", "Seconds to wait (1-120)")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = FINISH,
                description = "Mark the task as complete and exit the agent loop. Provide a summary of what was accomplished and list any files that were changed.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("summary", buildJsonObject {
                            put("type", "string")
                            put("description", "Summary of what was accomplished")
                        })
                        put("changed_files", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "List of files that were created or modified")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = ABORT,
                description = "Abort the current task with an explanation. Use when the task cannot be completed or a dead end is reached.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("reason", buildJsonObject {
                            put("type", "string")
                            put("description", "Why the task is being aborted")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = SUBAGENT,
                description = "Dispatch a sub-agent to handle a complex subtask independently. The sub-agent gets its own context window, tool access, and execution loop. It returns a summary of what it accomplished. Use this for: researching a large codebase, implementing a self-contained feature, running a test suite with analysis, or any task that would pollute the main context with excessive tool output. Multiple sub-agents can be dispatched in parallel.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("goal")) }
                    put("properties", buildJsonObject {
                        put("goal", buildJsonObject {
                            put("type", "string")
                            put("description", "Clear, self-contained goal for the sub-agent. Include all context needed — the sub-agent starts fresh with no knowledge of the main conversation.")
                        })
                        put("max_iterations", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum iterations for the sub-agent. Default: 15")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = LISTEN,
                description = "Send a message directly to the user and wait for their response. Use when you need user input, confirmation, or clarification mid-task.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("message")) }
                    put("properties", buildJsonObject {
                        put("message", buildJsonObject {
                            put("type", "string")
                            put("description", "Message or question to present to the user")
                        })
                    })
                }
            )
        )
    )

    data class ToolResult(
        val output: String,
        val isError: Boolean = false,
        val exitCode: Int? = null
    )

    suspend fun execute(
        toolName: String,
        arguments: String,
        channel: CommandChannel?,
        workspaceRoot: File,
        timeoutMs: Long
    ): ToolResult {
        val args = try {
            json.parseToJsonElement(arguments).jsonObject
        } catch (e: Exception) {
            return ToolResult("Failed to parse arguments: ${e.message}", isError = true)
        }

        return when (toolName) {
            EXECUTE -> {
                val cmd = args["command"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'command' parameter", isError = true)
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val result = channel.exec(cmd, timeoutMs)
                val sb = StringBuilder()
                sb.append("[exit=${result.exitCode ?: "timeout"}]\n")
                sb.append(result.output)
                ToolResult(sb.toString(), isError = result.exitCode != null && result.exitCode != 0, exitCode = result.exitCode)
            }

            BATCH -> {
                val cmds = args["commands"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: return ToolResult("Missing 'commands' parameter", isError = true)
                if (cmds.isEmpty()) return ToolResult("Empty command list", isError = true)
                if (channel == null) return ToolResult("Terminal not ready", isError = true)

                val results: List<Pair<String, ExecResult>> = coroutineScope {
                    cmds.map { cmd ->
                        async { Pair(cmd, channel.exec(cmd, timeoutMs)) }
                    }.awaitAll()
                }

                val sb = StringBuilder()
                var anyError = false
                results.forEachIndexed { i, (cmd, r) ->
                    if (r.exitCode != null && r.exitCode != 0) anyError = true
                    sb.append("[${i+1}/${results.size} exit=${r.exitCode ?: "timeout"}] \$ ${cmd.take(120)}\n")
                    sb.append(r.output.take(800))
                    sb.append("\n\n")
                }
                ToolResult(sb.toString(), isError = anyError)
            }

            READ_FILE -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'path' parameter", isError = true)
                val offset = args["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2000
                val file = resolvePath(path, workspaceRoot)
                if (!file.exists()) return ToolResult("File not found: ${file.absolutePath}", isError = true)
                if (!file.isFile) return ToolResult("Not a file: ${file.absolutePath}", isError = true)
                if (!file.canRead()) return ToolResult("Permission denied: ${file.absolutePath}", isError = true)

                val lines = runCatching { file.readLines() }.getOrElse {
                    return ToolResult("Failed to read file: ${it.message}", isError = true)
                }
                val totalLines = lines.size
                val startIdx = (offset - 1).coerceAtLeast(0).coerceAtMost(totalLines)
                val endIdx = (startIdx + limit).coerceAtMost(totalLines)
                val sb = StringBuilder()
                sb.append("(file: ${file.absolutePath}, total: $totalLines lines, showing: $startIdx+1-$endIdx)\n")
                for (i in startIdx until endIdx) {
                    sb.append("${i + 1}: ${lines[i]}\n")
                }
                if (endIdx < totalLines) {
                    sb.append("\n(${-1 + endIdx - startIdx + 1} of $totalLines lines shown. Use offset=${endIdx + 1} to read next page.)")
                }
                ToolResult(sb.toString())
            }

            WRITE_FILE -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'path' parameter", isError = true)
                val content = args["content"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'content' parameter", isError = true)
                val file = resolvePath(path, workspaceRoot)
                try {
                    val isNew = !file.exists()
                    val oldContent = if (file.exists()) file.readText() else ""
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    val sb = StringBuilder()
                    if (isNew) {
                        sb.append("Created ${file.absolutePath} (${content.length} bytes)\n\n")
                        val lines = content.lines()
                        sb.append("--- new file (${lines.size} lines) ---\n")
                        lines.take(30).forEach { sb.append("+ $it\n") }
                        if (lines.size > 30) sb.append("+ ... (${lines.size - 30} more lines)\n")
                    } else {
                        sb.append("Overwritten ${file.absolutePath} (${content.length} bytes, was ${oldContent.length} bytes)\n")
                    }
                    ToolResult(sb.toString())
                } catch (e: Exception) {
                    ToolResult("Failed to write: ${e.message}", isError = true)
                }
            }

            EDIT_FILE -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'path' parameter", isError = true)
                val oldStr = args["old_string"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'old_string' parameter", isError = true)
                val newStr = args["new_string"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'new_string' parameter", isError = true)
                val file = resolvePath(path, workspaceRoot)
                if (!file.exists()) return ToolResult("File not found: ${file.absolutePath}", isError = true)
                if (!file.canRead() || !file.canWrite()) return ToolResult("Permission denied: ${file.absolutePath}", isError = true)

                val content = runCatching { file.readText() }.getOrElse {
                    return ToolResult("Failed to read file: ${it.message}", isError = true)
                }
                val idx = content.indexOf(oldStr)
                if (idx < 0) return ToolResult("old_string not found in file", isError = true)
                val secondIdx = content.indexOf(oldStr, idx + 1)
                if (secondIdx >= 0) return ToolResult("old_string is not unique (found at positions $idx and $secondIdx). Provide more surrounding context.", isError = true)

                val newContent = content.substring(0, idx) + newStr + content.substring(idx + oldStr.length)
                file.writeText(newContent)

                val sb = StringBuilder()
                sb.append("Edited ${file.absolutePath}: replaced ${oldStr.length} chars with ${newStr.length} chars\n\n")
                val lineNumBefore = content.take(idx).count { it == '\n' } + 1
                sb.append("--- diff (around line $lineNumBefore) ---\n")
                val oldLines = oldStr.lines()
                val newLines = newStr.lines()
                val maxLines = maxOf(oldLines.size, newLines.size)
                for (i in 0 until maxLines) {
                    val old = oldLines.getOrNull(i)
                    val newL = newLines.getOrNull(i)
                    if (old != null) sb.append("- $old\n")
                    if (newL != null) sb.append("+ $newL\n")
                }
                ToolResult(sb.toString())
            }

            GLOB -> {
                val pattern = args["pattern"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'pattern' parameter", isError = true)
                val basePath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val baseFile = File(basePath).let { if (it.isAbsolute) it else File(workspaceRoot, basePath) }
                if (!baseFile.exists()) return ToolResult("Directory not found: ${baseFile.absolutePath}", isError = true)

                val results = mutableListOf<String>()
                val maxResults = 200
                globSearch(baseFile, pattern, results, maxResults)
                val sb = StringBuilder()
                sb.append("(found ${results.size} files${
                    if (results.size >= maxResults) " (capped at $maxResults)" else ""
                })\n")
                results.forEach { sb.append("$it\n") }
                ToolResult(sb.toString())
            }

            GREP -> {
                val pattern = args["pattern"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'pattern' parameter", isError = true)
                val basePath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val include = args["include"]?.jsonPrimitive?.content
                val baseFile = File(basePath).let { if (it.isAbsolute) it else File(workspaceRoot, basePath) }
                if (!baseFile.exists()) return ToolResult("Directory not found: ${baseFile.absolutePath}", isError = true)

                val regex = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    return ToolResult("Invalid regex: ${e.message}", isError = true)
                }
                val includeRegex = include?.let {
                    val p = if (it.contains("*")) it.replace(".", "\\.").replace("*", ".*") else ".*$it"
                    try { Regex(p) } catch (e: Exception) { null }
                }

                val results = mutableListOf<String>()
                val maxResults = 100
                grepSearch(baseFile, regex, includeRegex, results, maxResults, baseFile.absolutePath)
                val sb = StringBuilder()
                sb.append("(found ${results.size} matches${
                    if (results.size >= maxResults) " (capped at $maxResults)" else ""
                })\n")
                results.forEach { sb.append("$it\n") }
                ToolResult(sb.toString())
            }

            TODO -> {
                ToolResult("Todo updated (handled by engine)")
            }

            WAIT -> {
                val sec = (args["seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5).coerceIn(1, 120)
                kotlinx.coroutines.delay(sec * 1000L)
                ToolResult("Waited $sec seconds")
            }

            FINISH -> {
                ToolResult("__FINISH__")
            }

            ABORT -> {
                ToolResult("__ABORT__")
            }

            RUNBG -> {
                val name = args["name"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'name' parameter", isError = true)
                val cmd = args["command"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'command' parameter", isError = true)
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val fullCmd = "runbg $name $cmd"
                val r = channel.exec(fullCmd, 5000)
                ToolResult("[bg] $name started\n${r.output.take(200)}")
            }

            JOBLOG -> {
                val name = args["name"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'name' parameter", isError = true)
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val r = channel.exec("joblog $name", 5000)
                ToolResult(r.output.take(2000))
            }

            SUBAGENT, LISTEN -> {
                ToolResult("__DELEGATED__")
            }

            else -> ToolResult("Unknown tool: $toolName", isError = true)
        }
    }

    private fun resolvePath(path: String, workspaceRoot: File): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(workspaceRoot, path)
    }

    private fun globSearch(dir: File, pattern: String, results: MutableList<String>, max: Int, depth: Int = 0) {
        if (results.size >= max || depth > 15) return
        val files = dir.listFiles() ?: return
        val parts = pattern.split("/", limit = 2)
        val currentPart = parts[0]
        val rest = if (parts.size > 1) parts[1] else ""

        for (file in files.sortedBy { it.name }) {
            if (results.size >= max) return
            val matches = matchGlobSegment(currentPart, file.name)
            if (matches) {
                if (rest.isEmpty() && file.isFile) {
                    results.add(file.absolutePath)
                } else if (rest.isNotEmpty() && file.isDirectory) {
                    globSearch(file, rest, results, max, depth + 1)
                }
            }
            if (currentPart == "**" && file.isDirectory) {
                globSearch(file, pattern, results, max, depth + 1)
            }
        }
    }

    private fun matchGlobSegment(segment: String, name: String): Boolean {
        if (segment == "*" || segment == "**") return true
        if (segment.startsWith("{") && segment.endsWith("}")) {
            val inner = segment.substring(1, segment.length - 1)
            return inner.split(",").any { matchGlobSegment(it.trim(), name) }
        }
        return matchSimpleGlob(segment, name)
    }

    private fun matchSimpleGlob(pattern: String, name: String): Boolean {
        val regexStr = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
        return Regex("^$regexStr$").matches(name)
    }

    private fun grepSearch(
        dir: File,
        regex: Regex,
        includeRegex: Regex?,
        results: MutableList<String>,
        max: Int,
        basePath: String
    ) {
        if (results.size >= max) return
        val files = dir.listFiles() ?: return
        for (file in files.sortedBy { it.name }) {
            if (results.size >= max) return
            if (file.isDirectory) {
                if (file.name.startsWith(".") && file.name != "." && file.name != "..") continue
                if (file.name == "node_modules" || file.name == ".git" || file.name == "build" || file.name == "__pycache__") continue
                grepSearch(file, regex, includeRegex, results, max, basePath)
            } else if (file.isFile) {
                if (includeRegex != null && !includeRegex.matches(file.name)) continue
                if (file.length() > 512 * 1024) continue
                val content = runCatching { file.readText() }.getOrNull() ?: continue
                content.lines().forEachIndexed { lineNum, line ->
                    if (results.size >= max) return@forEachIndexed
                    if (regex.containsMatchIn(line)) {
                        val relPath = file.absolutePath.removePrefix(basePath).removePrefix("/")
                        results.add("$relPath:${lineNum + 1}: ${line.take(200)}")
                    }
                }
            }
        }
    }
}
