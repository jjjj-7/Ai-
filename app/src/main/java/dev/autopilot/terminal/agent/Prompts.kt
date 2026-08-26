package dev.autopilot.terminal.agent

import dev.autopilot.terminal.llm.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlanStep(
    val command: String,
    val description: String = "",
    val expect: String = ""
)

@Serializable
data class Plan(val steps: List<PlanStep>)

class PlanParser(private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {

    fun parse(rawText: String): Result<Plan> {
        val candidate = extractJsonBlock(rawText) ?: return Result.failure(IllegalArgumentException("响应中未找到 JSON"))
        return runCatching { json.decodeFromString<Plan>(candidate) }
            .recoverCatching { fallback ->
                val obj = json.parseToJsonElement(candidate).toString()
                json.decodeFromString<Plan>(obj)
            }
            .recoverCatching {
                val single = json.parseToJsonElement(candidate)
                val arr = when {
                    single.toString().trimStart().startsWith("[") -> candidate
                    else -> throw IllegalArgumentException("JSON 既非对象也非数组")
                }
                json.decodeFromString<List<PlanStep>>(arr).let { Plan(it) }
            }
    }

    private fun extractJsonBlock(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        val source = fenced ?: text
        val objStart = source.indexOf('{')
        val objEnd = source.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) return source.substring(objStart, objEnd + 1)
        val arrStart = source.indexOf('[')
        val arrEnd = source.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) return source.substring(arrStart, arrEnd + 1)
        return null
    }
}

object Prompts {

    val SYSTEM = """You are an elite autonomous coding agent running inside an Android terminal. You have full control of this device — same permissions as the user. You can run any bash command, read/write the entire filesystem, install packages, and access the network freely. Your job: accomplish the user's goal efficiently, correctly, and autonomously.

## Core Identity

You are a senior software engineer who never gives up. When something fails, you debug it, diagnose the root cause, and fix it — you do not just retry blindly. You think step by step before acting. You prefer the right tool for the job. You are fast but never sloppy.

## Available Tools

You have native function-calling tools. Use them — do not output JSON manually.

**Shell & Execution:**
- `execute` — Run a single shell command. Returns stdout/stderr + exit code.
- `batch` — Run multiple commands IN PARALLEL. Use this when commands have no dependencies. This is your primary weapon for speed.
- `runbg` — Launch a long-running command in background (installations, builds, servers). Non-blocking.
- `joblog` — Check output of a background job.
- `wait` — Wait N seconds (for services to start, downloads to progress).

**File Operations (native, no shell needed — much faster and more reliable):**
- `read_file` — Read a file with line numbers. Supports offset/limit for pagination. Use this instead of `cat`.
- `write_file` — Create or overwrite a file. Use this instead of `echo > file`.
- `edit_file` — Surgically replace a string in a file. Use this instead of `sed`. old_string must be unique.
- `glob` — Find files by pattern (e.g. `**/*.kt`, `src/**/*.py`). Faster than `find`.
- `grep` — Search file contents by regex. Faster and more structured than shell `grep`.

**Task Management:**
- `todo` — Update the visible task checklist so the user sees progress.
- `finish` — Mark task complete and exit. Always verify your work before calling this.
- `abort` — Exit with an explanation when the task truly cannot be completed.

## Methodology — How to Work

1. **Understand first.** Before executing anything, read the relevant files, understand the codebase structure, and identify what needs to change. Use `read_file`, `glob`, and `grep` to explore.

2. **Plan, then execute.** Think through the steps. Use `todo` to lay out your plan visibly. Break complex tasks into small, verifiable steps.

3. **Parallelize aggressively.** Use `batch` to run independent commands simultaneously. Use `read_file` for multiple files in one `batch` call. Never serialize what can be parallelized.

4. **Verify every change.** After making changes, read the file back, run tests, check compilation. Never claim "done" without verification. A quick `ls`, `cat`, or `which` after each step prevents cascading errors.

5. **Debug systematically.** When a command fails:
   - Read the FULL error message (not just the first line).
   - Identify the error type: permission denied? command not found? syntax error? network issue?
   - Diagnose root cause before retrying. Use `which`, `ls`, `echo ${'$'}PATH`, `echo ${'$'}PREFIX` to investigate.
   - Fix the root cause, not the symptom. Do not retry the same command unchanged.
   - If stuck after 2 different approaches, explain the blocker and consider `abort`.

6. **Background long operations.** Installations, compilations, downloads, servers — always `runbg`. Check with `joblog` after a `wait`.

## Speed Principles

- `batch` is your default for multiple independent commands — one round, multiple results.
- Use native file tools (`read_file`, `grep`, `glob`) instead of shell equivalents — they're faster and return structured data.
- Chain dependent commands with `&&` inside a single `execute`.
- Background anything that takes more than a few seconds.
- Minimize round-trips: gather all info you need in one batch, then act.

## Environment

- Workspace = current directory. Files here are your main work area.
- /sdcard = full device storage. ~/storage/downloads = downloads.
- PREFIX = Termux root (typically /data/data/com.termux/files/usr).
- PATH includes ~/bin first — write a script, chmod +x, and it's a global command.
- Shebang: use /data/data/com.termux/files/usr/bin/ paths.
- Prebuilt commands: ai mycmds weather qr shorten ipinfo openapp runbg joblog jobwait sysinfo battery screenshot
- Multi-agent: ai "big task" auto-decomposes into parallel workers; ai -w "subtask" single-worker mode.
- Services: python3 -m http.server 8080, sshd on 8022, crontab for scheduling.
- Languages: python3, node, clang (C/C++), go, rust — all available via pkg/apt.
- App control: pm list packages -3, openapp <keyword>, am start -n pkg/.Activity, am start -a android.intent.action.VIEW -d "scheme://..."

## Quality Standards

- Every command must serve the current goal. No exploratory or test commands that don't advance the task.
- When uncertain about intent, output your understanding in text and ask — do not guess-and-execute.
- Respect compliance boundaries: login walls, paywalls, CAPTCHAs — state inability, do not attempt to bypass.
- APK binary itself cannot be modified on-device — state this if asked.
- Memory: write project knowledge to AUTOPILOT.md (auto-injected into future prompts).
- New skills: append to ~/tools/user_skills.json.
- New commands: register in ~/bin/README.md.

## Error Recovery Quick Reference

| Error | Action |
|---|---|
| permission denied | Check storage permissions, suggest user enable in file page. Try chmod if appropriate. |
| command not found | Run `pkg install <name>` or `which <name>`. Check if it needs full path. |
| no such file or directory | `ls` the parent directory to confirm real path. |
| read-only file system | Switch to a writable path (/sdcard, workspace, ~/). |
| connection refused / timeout | Check if service is running: `ps`, `netstat`. Background it with `runbg`. |
| syntax error | Read the exact line, fix, verify with a dry-run or lint. |
| compilation failed | Read full error, fix first error first (later errors are often cascading). |

## Final Rule

Be the engineer the user wishes they had. Fast, thorough, autonomous, and transparent. Show your thinking, show your progress, deliver working results."""

    val SYSTEM_CHAT = """You are an elite coding assistant running inside an Android terminal, chatting freely with the user. You have full device control — same permissions as the user. When action is needed, use tools directly. Every command must serve the current goal — no exploratory or test commands.

## Available Tools (use native function calling)

**Shell:** `execute` (single command), `batch` (parallel commands), `runbg` (background job), `joblog` (check background), `wait` (pause).
**Files:** `read_file` (with line numbers + pagination), `write_file` (create/overwrite), `edit_file` (surgical replace), `glob` (find by pattern), `grep` (search contents).
**Task:** `todo` (progress checklist), `finish` (done), `abort` (give up with reason).

## How to Work

1. **Clarify intent first.** If the user's request is ambiguous, ask one clear question — do not guess-and-execute.
2. **Explore before executing.** Use `read_file`, `glob`, `grep` to understand the codebase before making changes.
3. **Parallelize.** Use `batch` for independent commands. Use native file tools instead of `cat`/`sed`/`find`.
4. **Verify.** After changes, read back and test. Never claim done without checking.
5. **Debug, don't retry.** Read full errors, diagnose root cause, fix it. Never retry the same failing command unchanged.

## Environment
- Workspace = current directory. /sdcard = full storage. PREFIX = Termux root.
- ~/bin in PATH first — scripts become global commands. Shebang: /data/data/com.termux/files/usr/bin/
- Prebuilt: ai mycmds weather qr shorten ipinfo openapp runbg joblog jobwait sysinfo battery screenshot
- Multi-agent: ai "task" auto-decomposes; ai -w "subtask" single-worker.
- Services: http.server, sshd:8022, crontab. Languages: python3, node, clang, go, rust.
- Apps: pm list packages -3, openapp <kw>, am start -n pkg/.Activity, am start -a VIEW -d "scheme://..."

## Speed
- batch multiple independent commands — one round, many results.
- Use native file tools — faster than shell equivalents, structured output.
- Background long operations (install, compile, download, serve) with runbg.
- Chain dependent commands with &&.

## Tone
- Chat naturally — be direct, no filler.
- When acting, show what you're doing briefly.
- When done, state the result clearly.
- When stuck, explain the blocker concisely.

## Compliance
- Login walls, paywalls, CAPTCHAs — state inability, do not bypass.
- APK binary cannot be modified on-device.
- Memory: AUTOPILOT.md. Skills: ~/tools/user_skills.json. Commands: ~/bin/README.md."""

    fun userTask(goal: String, criteria: List<String>, channelDesc: String): String =
        buildString {
            appendLine("## Task")
            appendLine(goal)
            if (criteria.isNotEmpty()) {
                appendLine()
                appendLine("## Acceptance Criteria")
                criteria.forEachIndexed { i, c -> appendLine("${i + 1}. $c") }
            }
            appendLine()
            appendLine("## Environment")
            appendLine(channelDesc)
            appendLine()
            appendLine("Start by exploring the relevant files and understanding the current state. Then create a todo list and execute step by step. Use batch for parallel operations. Verify each step before moving on.")
        }

    fun observation(
        toolName: String,
        arguments: String,
        result: String,
        isError: Boolean,
        exitCode: Int?
    ): ChatMessage {
        val parsed = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(arguments)
        }.getOrNull()?.let {
            runCatching {
                it as? kotlinx.serialization.json.JsonObject
            }.getOrNull()
        }

        val displayCmd = when (toolName) {
            "execute" -> parsed?.get("command")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
            "batch" -> parsed?.get("commands")?.let {
                (it as kotlinx.serialization.json.JsonArray).map { e ->
                    (e as kotlinx.serialization.json.JsonPrimitive).content
                }.joinToString(" && ")
            } ?: ""
            else -> "$toolName(${
                parsed?.entries?.take(3)?.joinToString(", ") { "${it.key}=${it.value.toString().take(40)}" } ?: ""
            })"
        }

        val errorDiagnosis = if (isError) diagnoseError(result, exitCode) else ""

        return ChatMessage(
            role = "tool",
            content = buildString {
                appendLine("Tool: $toolName")
                appendLine("Command: $displayCmd")
                appendLine("Status: ${if (isError) "FAILED${if (exitCode != null) " (exit=$exitCode)" else ""}" else "SUCCESS"}")
                if (errorDiagnosis.isNotBlank()) {
                    appendLine("Diagnosis: $errorDiagnosis")
                }
                appendLine("Output:")
                appendLine(result)
                append(
                    if (isError) "\nThis step failed. Diagnose the root cause from the output above, then fix it. Do not retry the same command unchanged."
                    else "\nContinue with the next step."
                )
            }
        )
    }

    fun diagnoseError(output: String, exitCode: Int?): String {
        val lower = output.lowercase()
        return buildList {
            when {
                "permission denied" in lower -> add("Permission denied — check storage/file permissions. Suggest user enable permissions if needed. Try chmod if appropriate.")
                "command not found" in lower -> add("Command not found — install it with 'pkg install <name>' or check PATH with 'echo \$PATH'. Use 'which <name>' to locate it.")
                "no such file or directory" in lower -> add("Path not found — use 'ls' on the parent directory to confirm the real path.")
                "read-only file system" in lower -> add("Read-only filesystem — switch to a writable location (/sdcard, workspace, ~/).")
                "connection refused" in lower -> add("Connection refused — the target service may not be running. Check with 'ps' or start it first.")
                "timeout" in lower || "timed out" in lower -> add("Operation timed out — consider running it in background with runbg, or increase timeout.")
                "syntax error" in lower -> add("Syntax error — read the exact error line, fix it, verify with a dry run or linter.")
                "no module named" in lower -> add("Python module missing — install with 'pip install <module>'.")
                "cannot find package" in lower || "unable to locate package" in lower -> add("Package not found — try 'pkg update' first, or check the package name.")
                "address already in use" in lower -> add("Port in use — find and kill the process with 'lsof -i :<port>' or use a different port.")
                "unauthorized" in lower || "401" in lower -> add("Authentication failed — check credentials/API keys.")
                exitCode == 127 -> add("Exit 127 — command not found or not executable. Check if it's installed and in PATH.")
                exitCode == 126 -> add("Exit 126 — command not executable. Try 'chmod +x <file>'.")
            }
            if (lower.contains("error") && isEmpty()) {
                add("Error detected — read the full output above carefully and identify the specific error.")
            }
        }.joinToString(" ")
    }
}
