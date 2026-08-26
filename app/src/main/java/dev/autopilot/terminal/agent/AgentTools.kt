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
    const val WEB_SEARCH = "web_search"
    const val WEB_FETCH = "web_fetch"
    const val UNDO_EDIT = "undo_edit"
    const val MULTI_EDIT = "multi_edit"
    const val GIT_STATUS = "git_status"
    const val GIT_DIFF = "git_diff"
    const val GIT_COMMIT = "git_commit"
    const val RUN_TESTS = "run_tests"
    const val DNS_LOOKUP = "dns_lookup"
    const val PORT_CHECK = "port_check"
    const val TREE = "tree"

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
        ),
        ToolDefinition(
            function = ToolFunction(
                name = WEB_SEARCH,
                description = "Search the web for information. Returns titles, URLs, and snippets from search results. Use for finding documentation, looking up error messages, discovering APIs, or researching solutions. Claude Code does NOT have this — this is a unique capability.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("query")) }
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "Search query text")
                        })
                        put("count", buildJsonObject {
                            put("type", "integer")
                            put("description", "Number of results (1-10). Default: 5")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = WEB_FETCH,
                description = "Fetch content from a URL. Converts HTML to clean text or markdown. Use for reading documentation pages, downloading code, checking API responses. Supports content extraction and length limiting.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("url")) }
                    put("properties", buildJsonObject {
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "URL to fetch (http or https)")
                        })
                        put("max_length", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum content length in chars. Default: 5000")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = UNDO_EDIT,
                description = "Undo the last file edit or write operation. Restores the previous content. Use when you realize a change was wrong. Maintains a stack of changes per file.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("path")) }
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Path of the file to undo last change on")
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = MULTI_EDIT,
                description = "Apply multiple edits to a single file in one operation. Each edit has old_string and new_string. Edits are applied sequentially. Use when you need to make several changes to the same file — much more efficient than multiple edit_file calls.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("edits")) }
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "File path to edit")
                        })
                        put("edits", buildJsonObject {
                            put("type", "array")
                            put("description", "Array of edits to apply sequentially")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("old_string", buildJsonObject { put("type", "string") })
                                    put("new_string", buildJsonObject { put("type", "string") })
                                })
                                putJsonArray("required") { add(JsonPrimitive("old_string")); add(JsonPrimitive("new_string")) }
                            })
                        })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = GIT_STATUS,
                description = "Show git working tree status. Returns staged, unstaged, and untracked file changes. Use before committing or to understand what changed.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Git repo directory. Default: workspace root"
                        )})
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = GIT_DIFF,
                description = "Show git diff of unstaged changes. Returns line-by-line diff output. Use to review changes before committing.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "Git repo directory") })
                        put("staged", buildJsonObject { put("type", "boolean"); put("description", "Show staged changes (--cached). Default: false") })
                        put("file", buildJsonObject { put("type", "string"); put("description", "Specific file to diff") })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = GIT_COMMIT,
                description = "Stage files and commit to git. Automatically stages the specified files, creates a commit with the given message, and returns the result. Does NOT push.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("message")) }
                    put("properties", buildJsonObject {
                        put("message", buildJsonObject { put("type", "string"); put("description", "Commit message") })
                        put("files", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Files to stage. Default: all changes (git add -A)") })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = RUN_TESTS,
                description = "Detect and run the project's test suite. Automatically identifies the test framework (JUnit, pytest, jest, go test, cargo test) and runs the appropriate command. Returns test results summary.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "Project directory. Default: workspace root") })
                        put("filter", buildJsonObject { put("type", "string"); put("description", "Test name filter pattern (optional)") })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = DNS_LOOKUP,
                description = "Resolve a hostname to its IP addresses. Useful for network debugging and verifying connectivity.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("hostname")) }
                    put("properties", buildJsonObject {
                        put("hostname", buildJsonObject { put("type", "string"); put("description", "Hostname to resolve") })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = PORT_CHECK,
                description = "Check if a TCP port is open on a host. Returns open/closed status and latency. Useful for service health checks.",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("host")); add(JsonPrimitive("port")) }
                    put("properties", buildJsonObject {
                        put("host", buildJsonObject { put("type", "string"); put("description", "Hostname or IP") })
                        put("port", buildJsonObject { put("type", "integer"); put("description", "Port number") })
                        put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Connection timeout. Default: 3000") })
                    })
                }
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = TREE,
                description = "Display directory tree structure. Shows files and subdirectories in a visual tree format. Useful for understanding project layout. Excludes common ignore dirs (.git, node_modules, build).",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "Directory path. Default: workspace root") })
                        put("max_depth", buildJsonObject { put("type", "integer"); put("description", "Maximum depth. Default: 3") })
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
                    if (!isNew) {
                        undoStacks.getOrPut(file.absolutePath) { mutableListOf() }.add(oldContent)
                    }
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
                    sb.append(autoLint(file, workspaceRoot, channel))
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
                undoStacks.getOrPut(file.absolutePath) { mutableListOf() }.add(content)
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
                sb.append(autoLint(file, workspaceRoot, channel))
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

            WEB_SEARCH -> {
                val query = args["query"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'query' parameter", isError = true)
                val count = args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val searchCmd = "python3 -c \"\n" +
                    "import urllib.request, urllib.parse, json\n" +
                    "q = ${'$'}{'$'}query${'$'}\n" +
                    "url = 'https://html.duckduckgo.com/html/?q=' + urllib.parse.quote(q)\n" +
                    "req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})\n" +
                    "try:\n" +
                    "    html = urllib.request.urlopen(req, timeout=10).read().decode('utf-8', errors='ignore')\n" +
                    "    import re\n" +
                    "    results = re.findall(r'result__a\"[^>]*>(.*?)</a>.*?result__snippet.*?>(.*?)</a>', html, re.DOTALL)\n" +
                    "    for i, (t, s) in enumerate(results[:${count}]):\n" +
                    "        t = re.sub('<[^>]+>', '', t).strip()\n" +
                    "        s = re.sub('<[^>]+>', '', s).strip()\n" +
                    "        print(f'{i+1}. {t}')\n" +
                    "        print(f'   {s[:200]}')\n" +
                    "        print()\n" +
                    "except Exception as e:\n" +
                    "    print(f'Error: {e}')\n" +
                    "\" 2>&1"
                val r = channel.exec(searchCmd.replace("\${'$'}{\"\$\"}query\${'$'}", "\"$query\""), 15_000)
                if (r.output.isBlank()) ToolResult("No results found", isError = true)
                else ToolResult(r.output.take(3000))
            }

            WEB_FETCH -> {
                val url = args["url"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'url' parameter", isError = true)
                val maxLen = args["max_length"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5000
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val fetchCmd = "python3 -c \"\n" +
                    "import urllib.request, re, sys\n" +
                    "url = sys.argv[1]\n" +
                    "req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})\n" +
                    "html = urllib.request.urlopen(req, timeout=15).read().decode('utf-8', errors='ignore')\n" +
                    "html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.DOTALL)\n" +
                    "html = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL)\n" +
                    "text = re.sub(r'<[^>]+>', ' ', html)\n" +
                    "text = re.sub(r'\\s+', ' ', text).strip()\n" +
                    "print(text[:${maxLen}])\n" +
                    "\" \"$url\" 2>&1"
                val r = channel.exec(fetchCmd, 20_000)
                if (r.output.isBlank()) ToolResult("Failed to fetch $url", isError = true)
                else ToolResult(r.output.take(maxLen))
            }

            UNDO_EDIT -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'path' parameter", isError = true)
                val file = resolvePath(path, workspaceRoot)
                val stack = undoStacks.getOrPut(file.absolutePath) { mutableListOf() }
                if (stack.isEmpty()) return ToolResult("No undo history for ${file.absolutePath}", isError = true)
                val prevContent = stack.removeLast()
                if (!file.exists()) {
                    return ToolResult("File no longer exists: ${file.absolutePath}", isError = true)
                }
                val currentContent = runCatching { file.readText() }.getOrNull()
                file.writeText(prevContent)
                // Re-add current to redo stack
                if (currentContent != null) {
                    redoStacks.getOrPut(file.absolutePath) { mutableListOf() }.add(currentContent)
                }
                ToolResult("Undone: restored ${file.absolutePath} to previous state (${prevContent.length} bytes)")
            }

            MULTI_EDIT -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'path' parameter", isError = true)
                val editsArr = args["edits"]?.jsonArray
                    ?: return ToolResult("Missing 'edits' parameter", isError = true)
                val file = resolvePath(path, workspaceRoot)
                if (!file.exists()) return ToolResult("File not found: ${file.absolutePath}", isError = true)
                if (!file.canRead() || !file.canWrite()) return ToolResult("Permission denied", isError = true)

                val content = runCatching { file.readText() }.getOrElse {
                    return ToolResult("Failed to read: ${it.message}", isError = true)
                }
                // Backup for undo
                undoStacks.getOrPut(file.absolutePath) { mutableListOf() }.add(content)

                var newContent = content
                val sb = StringBuilder()
                sb.append("Multi-edit ${file.absolutePath} (${editsArr.size} edits):\n\n")
                for ((editIdx, editEl) in editsArr.withIndex()) {
                    val edit = editEl.jsonObject
                    val oldStr = edit["old_string"]?.jsonPrimitive?.content
                        ?: return ToolResult("Edit $editIdx missing old_string", isError = true)
                    val newStr = edit["new_string"]?.jsonPrimitive?.content
                        ?: return ToolResult("Edit $editIdx missing new_string", isError = true)
                    val idx = newContent.indexOf(oldStr)
                    if (idx < 0) {
                        sb.append("Edit ${editIdx+1}: old_string NOT FOUND — skipped\n")
                        continue
                    }
                    newContent = newContent.substring(0, idx) + newStr + newContent.substring(idx + oldStr.length)
                    sb.append("Edit ${editIdx+1}: replaced ${oldStr.length}→${newStr.length} chars\n")
                }
                file.writeText(newContent)
                sb.append("\nDone. File updated successfully.")
                ToolResult(sb.toString())
            }

            GIT_STATUS -> {
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val repoPath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val r = channel.exec("cd $repoPath && git status --short 2>&1", 10_000)
                val sb = StringBuilder()
                sb.append("Git status ($repoPath):\n")
                sb.append(r.output.take(2000))
                ToolResult(sb.toString(), isError = r.exitCode != 0 && r.exitCode != null)
            }

            GIT_DIFF -> {
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val repoPath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val staged = args["staged"]?.jsonPrimitive?.content == "true"
                val file = args["file"]?.jsonPrimitive?.content
                val cmd = "cd $repoPath && git diff ${if (staged) "--cached " else ""}${file ?: ""} 2>&1"
                val r = channel.exec(cmd, 15_000)
                ToolResult(r.output.take(4000), isError = r.exitCode != 0 && r.exitCode != null)
            }

            GIT_COMMIT -> {
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val msg = args["message"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'message' parameter", isError = true)
                val files = args["files"]?.jsonArray?.map { it.jsonPrimitive.content }
                val addCmd = if (files.isNullOrEmpty()) "git add -A" else files.joinToString(" ") { "git add \"$it\"" }
                val cmd = "cd ${workspaceRoot.absolutePath} && $addCmd && git commit -m \"${msg.replace("\"", "\\\"")}\" 2>&1"
                val r = channel.exec(cmd, 15_000)
                ToolResult(r.output.take(2000), isError = r.exitCode != null && r.exitCode != 0)
            }

            RUN_TESTS -> {
                if (channel == null) return ToolResult("Terminal not ready", isError = true)
                val projectPath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val filter = args["filter"]?.jsonPrimitive?.content
                val testCmd = detectTestCommand(File(projectPath), filter)
                if (testCmd == null) return ToolResult("No test framework detected in $projectPath", isError = true)
                val r = channel.exec("cd $projectPath && $testCmd 2>&1", 120_000)
                val sb = StringBuilder()
                sb.append("Test command: $testCmd\n")
                sb.append("[exit=${r.exitCode ?: "timeout"}]\n")
                sb.append(r.output.take(3000))
                ToolResult(sb.toString(), isError = r.exitCode != null && r.exitCode != 0)
            }

            DNS_LOOKUP -> {
                val hostname = args["hostname"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'hostname' parameter", isError = true)
                val addrs = runCatching {
                    java.net.InetAddress.getAllByName(hostname).map { it.hostAddress }
                }.getOrElse {
                    return ToolResult("DNS lookup failed: ${it.message}", isError = true)
                }
                ToolResult("DNS for $hostname:\n${addrs.joinToString("\n") { "  $it" }}")
            }

            PORT_CHECK -> {
                val host = args["host"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'host' parameter", isError = true)
                val port = args["port"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return ToolResult("Missing 'port' parameter", isError = true)
                val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3000
                val startMs = System.currentTimeMillis()
                val isOpen = runCatching {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    socket.isConnected
                }.getOrDefault(false)
                val latency = System.currentTimeMillis() - startMs
                ToolResult("${host}:${port} ${if (isOpen) "OPEN" else "CLOSED"} (${latency}ms)")
            }

            TREE -> {
                val basePath = args["path"]?.jsonPrimitive?.content ?: workspaceRoot.absolutePath
                val maxDepth = args["max_depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3
                val baseFile = File(basePath).let { if (it.isAbsolute) it else File(workspaceRoot, basePath) }
                if (!baseFile.exists()) return ToolResult("Directory not found: ${baseFile.absolutePath}", isError = true)
                val sb = StringBuilder()
                sb.append("${baseFile.absolutePath}\n")
                printTree(baseFile, sb, "", maxDepth, 0)
                ToolResult(sb.toString())
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

    internal val undoStacks = mutableMapOf<String, MutableList<String>>()
    internal val redoStacks = mutableMapOf<String, MutableList<String>>()

    private fun detectTestCommand(projectDir: File, filter: String?): String? {
        val f = filter?.let { " -k \"$it\"" } ?: ""
        return when {
            File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists() ->
                "./gradlew test$f 2>&1 | tail -40"
            File(projectDir, "package.json").exists() -> {
                val pkg = runCatching {
                    Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(File(projectDir, "package.json").readText())
                        .let { it as? JsonObject }
                }.getOrNull()
                val scripts = pkg?.get("scripts") as? JsonObject
                when {
                    scripts?.get("test") != null -> "npm test$f 2>&1 | tail -40"
                    else -> "npx jest$f 2>&1 | tail -40"
                }
            }
            File(projectDir, "go.mod").exists() -> "go test$f ./... 2>&1 | tail -40"
            File(projectDir, "Cargo.toml").exists() -> "cargo test$f 2>&1 | tail -40"
            File(projectDir, "pytest.ini").exists() || File(projectDir, "pyproject.toml").exists() ->
                "python3 -m pytest$f 2>&1 | tail -40"
            File(projectDir, "setup.py").exists() -> "python3 -m pytest$f 2>&1 | tail -40"
            File(projectDir, "pom.xml").exists() -> "mvn test$f 2>&1 | tail -40"
            else -> null
        }
    }

    private val treeIgnoreDirs = setOf(".git", "node_modules", "build", "__pycache__", ".gradle", "dist", "target", ".idea", ".vscode")

    private fun printTree(dir: File, sb: StringBuilder, prefix: String, maxDepth: Int, depth: Int) {
        if (depth >= maxDepth) return
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        val visible = files.filter { it.name !in treeIgnoreDirs && !it.name.startsWith(".") }
        for ((i, file) in visible.withIndex()) {
            val isLast = i == visible.size - 1
            val connector = if (isLast) "└── " else "├── "
            sb.append("$prefix$connector${file.name}")
            if (file.isDirectory) sb.append("/")
            sb.append("\n")
            if (file.isDirectory) {
                val newPrefix = prefix + if (isLast) "    " else "│   "
                printTree(file, sb, newPrefix, maxDepth, depth + 1)
            }
        }
    }

    private suspend fun autoLint(
        file: File,
        workspaceRoot: File,
        channel: CommandChannel?
    ): String {
        if (channel == null) return ""
        val ext = file.extension.lowercase()
        val projectDir = findProjectRoot(file, workspaceRoot) ?: workspaceRoot

        val lintCmd = when {
            File(projectDir, "package.json").exists() && ext in listOf("js", "ts", "jsx", "tsx", "mjs") -> {
                val pkg = runCatching {
                    Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(File(projectDir, "package.json").readText())
                        .let { it as? JsonObject }
                }.getOrNull()
                val scripts = pkg?.get("scripts") as? JsonObject
                when {
                    scripts?.get("lint") != null -> "npm run lint 2>&1 | tail -20"
                    scripts?.get("typecheck") != null -> "npm run typecheck 2>&1 | tail -20"
                    else -> null
                }
            }
            File(projectDir, "build.gradle.kts").exists() && ext == "kt" ->
                "./gradlew ktlintCheck --quiet 2>&1 | tail -20 || true"
            File(projectDir, "build.gradle").exists() && ext == "kt" ->
                "./gradlew lint --quiet 2>&1 | tail -20 || true"
            ext == "py" && File(projectDir, "pyproject.toml").exists() ->
                "cd ${projectDir.absolutePath} && ruff check ${file.absolutePath} 2>&1 | tail -15 || true"
            ext == "py" && File(projectDir, "setup.py").exists() ->
                "python3 -m py_compile ${file.absolutePath} 2>&1 | tail -15 || true"
            ext == "go" && File(projectDir, "go.mod").exists() ->
                "cd ${projectDir.absolutePath} && gofmt -l ${file.absolutePath} 2>&1 || true"
            ext == "rs" && File(projectDir, "Cargo.toml").exists() ->
                "cd ${projectDir.absolutePath} && cargo check 2>&1 | tail -15 || true"
            else -> null
        } ?: return ""

        return try {
            val result = channel.exec(lintCmd, 30_000)
            if (result.output.isBlank() || result.output.contains("no issues") || result.output.contains("All files")) {
                "\n[lint] OK"
            } else {
                "\n[lint] ${result.output.take(300)}"
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun findProjectRoot(file: File, workspaceRoot: File): File? {
        var dir = file.parentFile
        while (dir != null && dir.absolutePath != "/") {
            if (File(dir, "package.json").exists() ||
                File(dir, "build.gradle").exists() ||
                File(dir, "build.gradle.kts").exists() ||
                File(dir, "go.mod").exists() ||
                File(dir, "Cargo.toml").exists() ||
                File(dir, "pyproject.toml").exists() ||
                File(dir, "setup.py").exists()
            ) {
                return dir
            }
            if (dir.absolutePath == workspaceRoot.absolutePath) return dir
            dir = dir.parentFile
        }
        return null
    }
}
