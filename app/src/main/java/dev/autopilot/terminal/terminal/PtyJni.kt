package dev.autopilot.terminal.terminal

object PtyJni {
    val available: Boolean = runCatching {
        System.loadLibrary("autopilot_pty")
        true
    }.getOrDefault(false)

    external fun forkPty(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        pidOut: IntArray
    ): Int

    external fun readFd(fd: Int, buf: ByteArray): Int

    external fun writeFd(fd: Int, data: ByteArray, len: Int): Int

    external fun resize(fd: Int, cols: Int, rows: Int)

    external fun waitFor(pid: Int, timeoutMs: Int): Int

    external fun closeFd(fd: Int)

    external fun killProcess(pid: Int)
}
