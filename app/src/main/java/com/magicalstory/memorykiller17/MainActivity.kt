package com.magicalstory.memorykiller17

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import com.magicalstory.memorykiller17.data.MemoryStatsHelper
import com.magicalstory.memorykiller17.databinding.ActivityMainBinding
import com.magicalstory.memorykiller17.nativecore.NativeMemoryEater
import com.magicalstory.memorykiller17.service.MemoryCleanerUserService
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

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, MemoryCleanerUserService::class.java.name)
        )
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
                    updateShizukuStatusUi()
                    refreshMemoryLimiterStatus()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cleanerService = null
            isServiceBound = false
            runOnUiThread { updateShizukuStatusUi() }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateShizukuStatusUi()
            tryBindUserServiceIfPermitted()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        cleanerService = null
        isServiceBound = false
        runOnUiThread { updateShizukuStatusUi() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        appendLog("Shizuku 授权成功，正在绑定特权服务…")
                        tryBindUserServiceIfPermitted()
                    } else {
                        appendLog("Shizuku 权限被拒绝，请在 Shizuku 中允许本应用。")
                    }
                    updateShizukuStatusUi()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.setPadding(0, bars.top, 0, 0)
            binding.scrollContent.setPadding(
                binding.scrollContent.paddingLeft,
                binding.scrollContent.paddingTop,
                binding.scrollContent.paddingRight,
                bars.bottom + resources.getDimensionPixelSize(R.dimen.oneui_bottom_padding)
            )
            insets
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        setupClickListeners()
        refreshMemoryUi()
        updateShizukuStatusUi()
    }

    override fun onResume() {
        super.onResume()
        refreshMemoryUi()
        updateShizukuStatusUi()
        tryBindUserServiceIfPermitted()
    }

    override fun onDestroy() {
        super.onDestroy()
        eatMonitorJob?.cancel()
        NativeMemoryEater.stopAndRelease()
        runCatching { cleanerService?.restoreMemoryLimiter() }

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        if (isServiceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
            isServiceBound = false
        }
    }

    private fun setupClickListeners() {
        binding.btnRefreshIcon.setOnClickListener {
            refreshMemoryUi()
            refreshMemoryLimiterStatus()
            appendLog("已刷新内存与系统状态")
        }

        binding.tvFooterGithub.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fanyongxing/memorykiller17")))
            }.onFailure { err ->
                appendLog("无法打开浏览器：${err.message}")
            }
        }

        binding.btnRequestShizuku.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(this) }
                if (!Shizuku.pingBinder()) {
                    appendLog("未检测到运行中的 Shizuku，请先通过无线调试启动 Shizuku 后重试。")
                    return@setOnClickListener
                }
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } else {
                tryBindUserServiceIfPermitted()
            }
        }

        // 主操作胶囊按钮：一键执行最彻底的“双引擎清场”
        binding.btnQuickClean.setOnClickListener {
            launchEatEngine(useOriginalSo = true, useMmapEngine = true)
        }

        // One UI 分组列表项：三种吃内存方案 + 备用系统级清理
        binding.rowOrigEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = true, useMmapEngine = false)
        }

        binding.rowMmapEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = false, useMmapEngine = true)
        }

        binding.rowDualEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = true, useMmapEngine = true)
        }

        binding.rowSafeKillAll.setOnClickListener {
            val svc = cleanerService
            if (svc == null) {
                appendLog("请先连接 Shizuku 后再执行系统级 am kill-all。")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val before = MemoryStatsHelper.readSnapshot(this@MainActivity)
                val msg = withContext(Dispatchers.IO) {
                    runCatching { svc.runSafeClean() }.getOrElse { "执行异常：${it.message}" }
                }
                delay(500)
                val after = MemoryStatsHelper.readSnapshot(this@MainActivity)
                refreshMemoryUi()
                val diff = after.availableBytes - before.availableBytes
                val sign = if (diff >= 0) "+" else "-"
                appendLog("$msg（可用内存 $sign${MemoryStatsHelper.formatGb(kotlin.math.abs(diff))}）")
            }
        }
    }

    private fun launchEatEngine(useOriginalSo: Boolean, useMmapEngine: Boolean) {
        if (isEating) return
        val svc = cleanerService
        if (svc == null) {
            appendLog("Shizuku 尚未就绪，请先点击下方“授权 Shizuku”。")
            tryBindUserServiceIfPermitted()
            return
        }

        isEating = true
        setActionEnabled(false)
        binding.btnQuickClean.text = "正在清场…"

        eatMonitorJob?.cancel()
        eatMonitorJob = lifecycleScope.launch {
            val myUid = Process.myUid()
            val myPid = Process.myPid()

            val prepMsg = withContext(Dispatchers.IO) {
                runCatching { svc.prepareEatAndWatch(myUid, myPid, true) }
                    .getOrElse { "准备阶段异常：${it.message}" }
            }
            appendLog(prepMsg)

            if (useOriginalSo) {
                appendLog("启动原版引擎 (libhello-jni.so)…")
                runCatching { OriginalEatMem().native_eatmf() }
                    .onFailure { appendLog("原版引擎异常：${it.message}") }
            }

            if (useMmapEngine) {
                appendLog("启动 mmap 高熵引擎 (libeatmem.so, 6 线程)…")
                NativeMemoryEater.startEating(threadCount = 6, chunkMb = 8)
            }

            while (isActive) {
                refreshMemoryUi()
                delay(150)
            }
        }
    }

    private fun tryBindUserServiceIfPermitted() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        if (isServiceBound && cleanerService != null) return

        runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        }.onFailure { err ->
            appendLog("绑定 Shizuku 服务失败：${err.message}")
        }
    }

    private fun refreshMemoryUi() {
        val snap = MemoryStatsHelper.readSnapshot(this)
        val mmapMb = NativeMemoryEater.getAllocatedMb()

        binding.ringRamProgress.setProgressCompat(snap.usedPercent, true)
        binding.tvRamPercent.text = "${snap.usedPercent}%"

        val (statusLabel, colorRes) = when {
            snap.usedPercent >= 85 -> "紧张" to R.color.oneui_danger
            snap.usedPercent >= 70 -> "偏高" to R.color.oneui_warning
            else -> "良好" to R.color.oneui_blue
        }
        val tintColor = ContextCompat.getColor(this, colorRes)
        binding.ringRamProgress.setIndicatorColor(tintColor)
        binding.tvRamHealthBadge.text = statusLabel
        binding.tvRamHealthBadge.setTextColor(tintColor)

        binding.tvRamPrimary.text =
            "${MemoryStatsHelper.formatGb(snap.usedBytes)} / ${MemoryStatsHelper.formatGb(snap.totalBytes)}"
        binding.tvRamAvailable.text = "可用 ${MemoryStatsHelper.formatGb(snap.availableBytes)}"

        val swapTotalGb = snap.swapTotalKb / (1024.0 * 1024.0)
        val swapUsedGb = snap.swapUsedKb / (1024.0 * 1024.0)
        val mmapText = if (mmapMb > 0) " · mmap ${mmapMb} MB" else ""
        binding.tvSwapInfo.text = String.format(
            "RAM Plus (ZRAM)：已用 %.1f / %.1f GB%s",
            swapUsedGb, swapTotalGb, mmapText
        )
    }

    private fun refreshMemoryLimiterStatus() {
        val svc = cleanerService ?: return
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { svc.memoryLimiterStatus }.getOrElse { "读取失败" }
            }
            val friendly = if (status.contains("disabled", ignoreCase = true)) {
                "Android 17 Memory Limiter：disabled（当前固件未启用单应用限制）"
            } else {
                "Android 17 Memory Limiter：$status"
            }
            binding.tvLimiterStatus.text = friendly
        }
    }

    private fun updateShizukuStatusUi() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val ready = granted && cleanerService != null

        when {
            !running -> {
                binding.tvShizukuBadge.text = "未运行"
                binding.tvShizukuBadge.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.oneui_badge_neutral_bg))
                binding.tvShizukuBadge.setTextColor(ContextCompat.getColor(this, R.color.oneui_text_secondary))
                binding.tvShizukuDesc.text = "请先在 Shizuku 中通过无线调试启动服务"
                binding.btnRequestShizuku.visibility = View.VISIBLE
            }
            !granted -> {
                binding.tvShizukuBadge.text = "待授权"
                binding.tvShizukuBadge.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.oneui_badge_warn_bg))
                binding.tvShizukuBadge.setTextColor(ContextCompat.getColor(this, R.color.oneui_warning))
                binding.tvShizukuDesc.text = "已检测到 Shizuku，点击右侧按钮完成授权"
                binding.btnRequestShizuku.visibility = View.VISIBLE
            }
            !ready -> {
                binding.tvShizukuBadge.text = "连接中"
                binding.tvShizukuBadge.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.oneui_badge_blue_bg))
                binding.tvShizukuBadge.setTextColor(ContextCompat.getColor(this, R.color.oneui_blue))
                binding.tvShizukuDesc.text = "正在绑定 Shizuku 特权守护服务…"
                binding.btnRequestShizuku.visibility = View.VISIBLE
            }
            else -> {
                binding.tvShizukuBadge.text = "已就绪"
                binding.tvShizukuBadge.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.oneui_badge_ok_bg))
                binding.tvShizukuBadge.setTextColor(ContextCompat.getColor(this, R.color.oneui_success))
                binding.tvShizukuDesc.text =
                    "UID ${Process.myUid()} · Android ${Build.VERSION.RELEASE} · 退出自动恢复限制并回桌面"
                binding.btnRequestShizuku.visibility = View.GONE
            }
        }

        setActionEnabled(!isEating && ready)
    }

    private fun setActionEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.45f
        binding.btnQuickClean.isEnabled = enabled
        listOf(
            binding.rowOrigEngine,
            binding.rowMmapEngine,
            binding.rowDualEngine,
            binding.rowSafeKillAll
        ).forEach { row ->
            row.isEnabled = enabled
            row.alpha = alpha
        }
    }

    private fun appendLog(message: String) {
        val prev = binding.tvLog.text?.toString().orEmpty()
        binding.tvLog.text = if (prev.isBlank() || prev == "等待操作") {
            "• $message"
        } else {
            "• $message\n$prev"
        }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
