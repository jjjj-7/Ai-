package dev.autopilot.terminal.bootstrap

import java.io.File

interface BootstrapSource {
    suspend fun fetch(pkg: String, dest: File): Result<File>
}

data class TermuxRepoSource(
    val mirrorBase: String = "https://packages.termux.dev/apt/termux-main",
    val distribution: String = "stable"
) : BootstrapSource {

    override suspend fun fetch(pkg: String, dest: File): Result<File> = runCatching {
        val archDir = when (Abi.current()) {
            Abi.ARM64 -> "aarch64"
            Abi.X86_64 -> "x86_64"
        }
        val url = "$mirrorBase/dists/$distribution/main/binary-$archDir/Packages"
        val index = Http.get(url)
        val block = parsePackagesIndex(index).firstOrNull { it["Package"] == pkg }
            ?: error("package not found in repo index: $pkg")
        val relPath = block["Filename"] ?: error("missing Filename for $pkg")
        dest.parentFile?.mkdirs()
        Http.download("$mirrorBase/$relPath", dest)
        dest
    }

    companion object {
        fun parsePackagesIndex(text: String): List<Map<String, String>> {
            val blocks = mutableListOf<Map<String, String>>()
            val current = mutableMapOf<String, String>()
            text.lineSequence().forEach { line ->
                if (line.isBlank()) {
                    if (current.isNotEmpty()) blocks += current.toMap()
                    current.clear()
                } else {
                    val idx = line.indexOf(':')
                    if (idx > 0) current[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
            if (current.isNotEmpty()) blocks += current.toMap()
            return blocks
        }
    }
}

enum class Abi {
    ARM64, X86_64;

    companion object {
        fun current(): Abi {
            val supported = android.os.Build.SUPPORTED_ABIS
            return when {
                supported.any { it.startsWith("arm64") } -> ARM64
                else -> X86_64
            }
        }
    }
}

internal object Http {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .readTimeout(java.time.Duration.ofMinutes(10))
        .build()

    fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            return resp.body?.string() ?: error("empty body for $url")
        }
    }

    fun download(url: String, dest: File) {
        val req = okhttp3.Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            val body = resp.body ?: error("empty body for $url")
            val tmp = File(dest.absolutePath + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(dest)) error("rename failed for ${dest.name}")
        }
    }
}
