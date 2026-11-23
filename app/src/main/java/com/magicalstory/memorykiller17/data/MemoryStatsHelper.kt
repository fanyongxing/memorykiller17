package com.magicalstory.memorykiller17.data

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class MemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usedPercent: Int,
    val lowMemory: Boolean,
    val swapTotalKb: Long,
    val swapFreeKb: Long
) {
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)
}

object MemoryStatsHelper {

    fun readSnapshot(context: Context): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val total = memInfo.totalMem.coerceAtLeast(1L)
        val avail = memInfo.availMem.coerceIn(0L, total)
        val used = (total - avail).coerceAtLeast(0L)
        val percent = ((used * 100L) / total).toInt().coerceIn(0, 100)

        var swapTotalKb = 0L
        var swapFreeKb = 0L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("SwapTotal:") -> swapTotalKb = parseKb(line)
                    line.startsWith("SwapFree:") -> swapFreeKb = parseKb(line)
                }
            }
        }

        return MemorySnapshot(
            totalBytes = total,
            availableBytes = avail,
            usedBytes = used,
            usedPercent = percent,
            lowMemory = memInfo.lowMemory,
            swapTotalKb = swapTotalKb,
            swapFreeKb = swapFreeKb
        )
    }

    private fun parseKb(line: String): Long {
        val parts = line.trim().split(Regex("\\s+"))
        return if (parts.size >= 2) parts[1].toLongOrNull() ?: 0L else 0L
    }

    fun formatGb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }
}
