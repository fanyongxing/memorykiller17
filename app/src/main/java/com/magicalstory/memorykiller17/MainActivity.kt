package com.magicalstory.memorykiller17

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import `as`.mke.eatmem.MainActivity as OriginalEatMem
import com.magicalstory.memorykiller17.databinding.ActivityMainBinding
import com.magicalstory.memorykiller17.nativecore.NativeMemoryEater
import com.magicalstory.memorykiller17.service.MemoryCleanerUserService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cleanerService: IMemoryCleanerService? = null
    private var isServiceBound = false
    private var eatMonitorJob: Job? = null
    private var isEating = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, MemoryCleanerUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("limiter_service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                cleanerService = IMemoryCleanerService.Stub.asInterface(service)
                isServiceBound = true
                runOnUiThread {
                    updateShizukuUi()
                    refreshLimiterStatus()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            cleanerService = null
            isServiceBound = false
            runOnUiThread { updateShizukuUi() }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuUi(); tryBindService() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        cleanerService = null; isServiceBound = false; runOnUiThread { updateShizukuUi() }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, res ->
        if (code == 1001) runOnUiThread {
            if (res == PackageManager.PERMISSION_GRANTED) {
                log("Shizuku 授权成功，正在绑定服务…")
                tryBindService()
            } else log("Shizuku 权限被拒绝")
            updateShizukuUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        setupClicks()
        refreshMemUi()
        updateShizukuUi()
    }

    override fun onResume() {
        super.onResume()
        refreshMemUi()
        updateShizukuUi()
        tryBindService()
    }

    override fun onDestroy() {
        super.onDestroy()
        eatMonitorJob?.cancel()
        NativeMemoryEater.stopAndRelease()
        runCatching { cleanerService?.restoreMemoryLimiter() }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (isServiceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
            isServiceBound = false
        }
    }

    private fun setupClicks() {
        binding.btnRefreshIcon.setOnClickListener {
            refreshMemUi()
            refreshLimiterStatus()
            log("已刷新内存与系统状态")
        }
        binding.tvFooterGithub.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fanyongxing/memorykiller17")))
            }.onFailure { log("无法打开浏览器：${it.message}") }
        }
        binding.btnRequestShizuku.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(this) }
                if (!Shizuku.pingBinder()) {
                    log("未检测到 Shizuku，请先通过无线调试启动")
                    return@setOnClickListener
                }
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            } else tryBindService()
        }

        binding.rowOrigEngine.setOnClickListener { launchEat(orig = true, mmap = false) }
        binding.rowMmapEngine.setOnClickListener { launchEat(orig = false, mmap = true) }
        binding.rowDualEngine.setOnClickListener { launchEat(orig = true, mmap = true) }
        binding.rowSafeKillAll.setOnClickListener {
            val svc = cleanerService ?: return@setOnClickListener log("请先连接 Shizuku")
            lifecycleScope.launch {
                val before = readMem().avail
                val msg = withContext(Dispatchers.IO) { runCatching { svc.runSafeClean() }.getOrElse { "异常：${it.message}" } }
                delay(500)
                val diff = readMem().avail - before
                refreshMemUi()
                log("$msg（可用 ${if (diff >= 0) "+" else "-"}${fmtGb(kotlin.math.abs(diff))}）")
            }
        }
    }

    private fun launchEat(orig: Boolean, mmap: Boolean) {
        if (isEating) return
        val svc = cleanerService ?: run {
            log("Shizuku 未就绪，请先授权")
            tryBindService()
            return
        }
        isEating = true
        setRowsEnabled(false)

        eatMonitorJob?.cancel()
        eatMonitorJob = lifecycleScope.launch {
            val prep = withContext(Dispatchers.IO) {
                runCatching { svc.prepareEatAndWatch(Process.myUid(), Process.myPid(), true) }
                    .getOrElse { "准备异常：${it.message}" }
            }
            log(prep)
            if (orig) {
                log("启动原版引擎 (libhello-jni.so)…")
                runCatching { OriginalEatMem().native_eatmf() }.onFailure { log("原版引擎异常：${it.message}") }
            }
            if (mmap) {
                log("启动 mmap 高熵引擎 (6 线程)…")
                NativeMemoryEater.startEating(6, 8)
            }
            while (isActive) {
                refreshMemUi()
                delay(150)
            }
        }
    }

    private fun tryBindService() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        if (isServiceBound && cleanerService != null) return
        runCatching { Shizuku.bindUserService(userServiceArgs, userServiceConnection) }
            .onFailure { log("绑定服务失败：${it.message}") }
    }

    private data class MemInfo(val total: Long, val avail: Long, val used: Long, val pct: Int, val swapTotalKb: Long, val swapUsedKb: Long)

    private fun readMem(): MemInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val total = mi.totalMem.coerceAtLeast(1L)
        val avail = mi.availMem.coerceIn(0L, total)
        val used = (total - avail).coerceAtLeast(0L)
        var sTot = 0L; var sFree = 0L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("SwapTotal:")) sTot = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                else if (line.startsWith("SwapFree:")) sFree = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
            }
        }
        return MemInfo(total, avail, used, ((used * 100L) / total).toInt().coerceIn(0, 100), sTot, (sTot - sFree).coerceAtLeast(0L))
    }

    private fun fmtGb(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    private fun refreshMemUi() {
        val m = readMem()
        val mmapMb = NativeMemoryEater.getAllocatedMb()
        binding.ringRamProgress.setProgressCompat(m.pct, true)
        binding.tvRamPercent.text = "${m.pct}%"
        val (label, cRes) = when {
            m.pct >= 85 -> "紧张" to R.color.oneui_danger
            m.pct >= 70 -> "偏高" to R.color.oneui_warning
            else -> "良好" to R.color.oneui_blue
        }
        val color = ContextCompat.getColor(this, cRes)
        binding.ringRamProgress.setIndicatorColor(color)
        binding.tvRamHealthBadge.text = label
        binding.tvRamHealthBadge.setTextColor(color)
        binding.tvRamPrimary.text = "${fmtGb(m.used)} / ${fmtGb(m.total)}"
        binding.tvRamAvailable.text = "可用 ${fmtGb(m.avail)}"
        val sTotGb = m.swapTotalKb / 1048576.0
        val sUsedGb = m.swapUsedKb / 1048576.0
        val extra = if (mmapMb > 0) " · mmap ${mmapMb}MB" else ""
        binding.tvSwapInfo.text = String.format("RAM Plus：%.1f / %.1f GB%s", sUsedGb, sTotGb, extra)
    }

    private fun refreshLimiterStatus() {
        val svc = cleanerService ?: return
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { svc.memoryLimiterStatus }.getOrElse { "读取失败" } }
            binding.tvLimiterStatus.text = if (st.contains("disabled", true))
                "Memory Limiter：disabled（当前固件未启用）" else "Memory Limiter：$st"
        }
    }

    private fun updateShizukuUi() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val ready = granted && cleanerService != null

        val (badge, bgRes, fgRes, desc) = when {
            !running -> Quad("未运行", R.color.oneui_badge_neutral_bg, R.color.oneui_text_secondary, "请先通过无线调试启动 Shizuku")
            !granted -> Quad("待授权", R.color.oneui_badge_warn_bg, R.color.oneui_warning, "点击右侧按钮完成 Shizuku 授权")
            !ready -> Quad("连接中", R.color.oneui_badge_blue_bg, R.color.oneui_blue, "正在绑定 Shizuku 守护服务…")
            else -> Quad("已就绪", R.color.oneui_badge_ok_bg, R.color.oneui_success, "UID ${Process.myUid()} · Android ${Build.VERSION.RELEASE} · 退出自动回桌面")
        }
        binding.tvShizukuBadge.text = badge
        binding.tvShizukuBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgRes))
        binding.tvShizukuBadge.setTextColor(ContextCompat.getColor(this, fgRes))
        binding.tvShizukuDesc.text = desc
        binding.btnRequestShizuku.visibility = if (ready) View.GONE else View.VISIBLE
        setRowsEnabled(!isEating && ready)
    }

    private data class Quad(val a: String, val b: Int, val c: Int, val d: String)

    private fun setRowsEnabled(enabled: Boolean) {
        val a = if (enabled) 1f else 0.45f
        listOf(binding.rowOrigEngine, binding.rowMmapEngine, binding.rowDualEngine, binding.rowSafeKillAll).forEach {
            it.isEnabled = enabled; it.alpha = a
        }
    }

    private fun log(msg: String) {
        val prev = binding.tvLog.text?.toString().orEmpty()
        binding.tvLog.text = if (prev.isBlank() || prev == "等待操作") "• $msg" else "• $msg\n$prev"
    }
}
