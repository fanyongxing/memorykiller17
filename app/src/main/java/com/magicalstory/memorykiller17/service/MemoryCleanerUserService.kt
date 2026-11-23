package com.magicalstory.memorykiller17.service

import android.content.Context
import androidx.annotation.Keep
import com.magicalstory.memorykiller17.IMemoryCleanerService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
class MemoryCleanerUserService : IMemoryCleanerService.Stub {
    @Keep constructor() : super()
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private val watchdogActive = AtomicBoolean(false)

    override fun destroy() {
        watchdogActive.set(false)
        exec("am", "memory-limiter", "ignore", "none")
        exitProcess(0)
    }

    override fun prepareEatAndWatch(uid: Int, hostPid: Int, returnHomeOnExit: Boolean): String {
        val (code, _) = exec("am", "memory-limiter", "ignore", uid.toString())
        startWatchdog(hostPid, returnHomeOnExit)
        return if (code == 0) "已解除 UID $uid 限制，已开启自动恢复与回桌面守护"
        else "当前固件未开启 Memory Limiter，已开启退出回桌面守护"
    }

    override fun restoreMemoryLimiter(): String {
        watchdogActive.set(false)
        val (code, out) = exec("am", "memory-limiter", "ignore", "none")
        return if (code == 0) "已恢复 Memory Limiter 默认限制" else "恢复返回($code): $out"
    }

    override fun getMemoryLimiterStatus(): String {
        val (code, out) = exec("am", "memory-limiter", "status")
        return if (code == 0 && out.isNotBlank()) out else "disabled"
    }

    override fun runSafeClean(): String {
        val (code, out) = exec("am", "kill-all")
        return if (code == 0) "已执行系统级 am kill-all" else "执行失败($code): $out"
    }

    private fun startWatchdog(hostPid: Int, returnHomeOnExit: Boolean) {
        if (hostPid <= 0) return
        watchdogActive.set(false)
        Thread.sleep(40)
        watchdogActive.set(true)
        thread(isDaemon = true, name = "limiter-watchdog") {
            val proc = File("/proc/$hostPid")
            while (watchdogActive.get()) {
                if (!proc.exists()) {
                    exec("am", "memory-limiter", "ignore", "none")
                    if (returnHomeOnExit) {
                        exec(
                            "am", "start",
                            "-a", "android.intent.action.MAIN",
                            "-c", "android.intent.category.HOME",
                            "--activity-reset-task-if-needed"
                        )
                    }
                    watchdogActive.set(false)
                    break
                }
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun exec(vararg cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText().trim() }
        p.waitFor() to out
    } catch (e: Exception) {
        -1 to (e.message ?: "error")
    }
}
