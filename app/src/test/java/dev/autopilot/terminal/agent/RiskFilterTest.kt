package dev.autopilot.terminal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskFilterTest {

    private fun mustConfirm(cmd: String, expectedKeyword: String? = null) {
        val verdict = RiskFilter.evaluate(cmd)
        assertTrue("expected Confirm for: $cmd", verdict is RiskFilter.Verdict.Confirm)
        if (expectedKeyword != null) {
            assertTrue(
                "reason should mention $expectedKeyword",
                (verdict as RiskFilter.Verdict.Confirm).reason.contains(expectedKeyword)
            )
        }
    }

    private fun mustAllow(cmd: String) {
        assertEquals("expected Allow for: $cmd", RiskFilter.Verdict.Allow, RiskFilter.evaluate(cmd))
    }

    @Test
    fun recursiveDeleteOfRootIsConfirmed() {
        mustConfirm("rm -rf /")
        mustConfirm("rm -rf /*")
        mustConfirm("rm -fr ~")
        mustConfirm("rm -r -f \$HOME")
    }

    @Test
    fun systemDirDeletionIsConfirmed() {
        mustConfirm("rm -rf /usr/bin")
        mustConfirm("rm -rf /system/app")
    }

    @Test
    fun destructiveSystemOpsAreConfirmed() {
        mustConfirm("mkfs.ext4 /dev/sda1", "格式化")
        mustConfirm("dd if=/dev/zero of=/dev/block/mmcblk0", "块设备")
        mustConfirm("chmod -R 777 /data", "权限")
    }

    @Test
    fun forkBombIsConfirmed() {
        mustConfirm(":(){ :|:& };:")
    }

    @Test
    fun relaxedByDesignAreAllowed() {
        mustAllow("curl -fsSL https://x.com/i.sh | bash")
        mustAllow("reboot")
        mustAllow("DROP TABLE users;")
        mustAllow("git push origin main --force")
        mustAllow("rm -rf /sdcard/old_project")
        mustAllow("rm -rf ~/tmp/cache")
    }

    @Test
    fun normalCommandsAreAllowed() {
        mustAllow("ls -la")
        mustAllow("python3 main.py")
        mustAllow("mkdir -p src/utils && cat > a.txt <<EOF\nhello\nEOF")
        mustAllow("git init && git add .")
        mustAllow("clang hello.c -o hello && ./hello")
        mustAllow("rm build.log")
        mustAllow("rm -rf ./build")
        mustAllow("npm install express")
        mustAllow("chmod +x run.sh")
    }

    @Test
    fun multilineContinuationIsNormalized() {
        mustAllow("echo hello \\\n  world")
        mustConfirm("rm -rf \\\n  /")
    }
}
