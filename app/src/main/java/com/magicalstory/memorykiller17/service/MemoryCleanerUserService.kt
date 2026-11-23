package com.magicalstory.memorykiller17.service

import android.content.Context
import androidx.annotation.Keep
import com.magicalstory.memorykiller17.IMemoryCleanerService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
class MemoryCleanerUserService : IMemoryCleanerService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private val watchdogActive = AtomicBoolean(false)

    override fun destroy() {
        watchdogActive.set(false)
        execCommand(arrayOf("am", "memory-limiter", "ignore", "none"))
        exitProcess(0)
    }

    override fun prepareEatAndWatch(uid: Int, hostPid: Int, returnHomeOnExit: Boolean): String {
        val res = execCommand(arrayOf("am", "memory-limiter", "ignore", uid.toString()))
        startHostWatchdog(hostPid, returnHomeOnExit)
        return if (res.exitCode == 0) {
            "已对本应用 UID $uid 临时解除 Memory Limiter，并启动退出自动恢复与回桌面守护。"
        } else {
            "Memory Limiter 指令返回 (code=${res.exitCode}): ${res.output.trim()}（已启动退出回桌面守护）。"
        }
    }

    override fun restoreMemoryLimiter(): String {
        watchdogActive.set(false)
        val res = execCommand(arrayOf("am", "memory-limiter", "ignore", "none"))
        return if (res.exitCode == 0) {
            "已恢复 Android 17 Memory Limiter 默认限制 (ignore none)。"
        } else {
            "恢复限制返回 (code=${res.exitCode}): ${res.output.trim()}"
        }
    }

    override fun getMemoryLimiterStatus(): String {
        val res = execCommand(arrayOf("am", "memory-limiter", "status"))
        return if (res.exitCode == 0 && res.output.isNotBlank()) {
            res.output.trim()
        } else {
            "未获取到 Memory Limiter 状态：${res.output.trim()}"
        }
    }

    override fun runSafeClean(): String {
        val res = execCommand(arrayOf("am", "kill-all"))
        return if (res.exitCode == 0) {
            "已执行系统级 am kill-all。"
        } else {
            "am kill-all 失败 (code=${res.exitCode}): ${res.output.trim()}"
        }
    }

    private fun startHostWatchdog(hostPid: Int, returnHomeOnExit: Boolean) {
        if (hostPid <= 0) return
        watchdogActive.set(false)
        Thread.sleep(40)
        watchdogActive.set(true)
        thread(isDaemon = true, name = "limiter-watchdog") {
            val procDir = File("/proc/$hostPid")
            while (watchdogActive.get()) {
                if (!procDir.exists()) {
                    execCommand(arrayOf("am", "memory-limiter", "ignore", "none"))
                    if (returnHomeOnExit) {
                        execCommand(
                            arrayOf(
                                "am", "start",
                                "-a", "android.intent.action.MAIN",
                                "-c", "android.intent.category.HOME",
                                "--activity-reset-task-if-needed"
                            )
                        )
                    }
                    watchdogActive.set(false)
                    break
                }
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun execCommand(cmd: Array<String>): CommandResult {
        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
            val code = process.waitFor()
            CommandResult(code, output)
        } catch (e: Exception) {
            CommandResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }
}
