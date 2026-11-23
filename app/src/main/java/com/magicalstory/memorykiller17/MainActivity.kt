package com.magicalstory.memorykiller17

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
                        appendLog("Shizuku 权限被拒绝，请在 Shizuku 应用中允许本应用。")
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        binding.btnRequestShizuku.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(this) }
                if (!Shizuku.pingBinder()) {
                    appendLog("未检测到运行中的 Shizuku 服务。请先在 Shizuku 中通过“无线调试”启动服务，再点击此按钮重试。")
                    return@setOnClickListener
                }
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } else {
                tryBindUserServiceIfPermitted()
            }
        }

        binding.btnRefreshMem.setOnClickListener {
            refreshMemoryUi()
            refreshMemoryLimiterStatus()
        }

        // 方案 1：100% 原版 libhello-jni.so 引擎
        binding.btnOrigEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = true, useMmapEngine = false)
        }

        // 方案 2：独立 mmap 高熵防 ZRAM 压缩引擎 (libeatmem.so)
        binding.btnMmapEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = false, useMmapEngine = true)
        }

        // 方案 3：原版 + mmap 双引擎极限清场
        binding.btnDualEngine.setOnClickListener {
            launchEatEngine(useOriginalSo = true, useMmapEngine = true)
        }

        // 备用：系统级 am kill-all
        binding.btnSafeKillAll.setOnClickListener {
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
                appendLog("$msg\n可用内存变化：${if (diff >= 0) "+" else "-"}${MemoryStatsHelper.formatMb(kotlin.math.abs(diff))}")
            }
        }
    }

    private fun launchEatEngine(useOriginalSo: Boolean, useMmapEngine: Boolean) {
        if (isEating) return
        val svc = cleanerService
        if (svc == null) {
            appendLog("Shizuku 尚未就绪！请先连接 Shizuku。")
            tryBindUserServiceIfPermitted()
            return
        }

        isEating = true
        setActionButtonsEnabled(false)

        eatMonitorJob?.cancel()
        eatMonitorJob = lifecycleScope.launch {
            val myUid = Process.myUid()
            val myPid = Process.myPid()

            val prepMsg = withContext(Dispatchers.IO) {
                runCatching { svc.prepareEatAndWatch(myUid, myPid, true) }
                    .getOrElse { "解除限制异常：${it.message}" }
            }
            appendLog(prepMsg)
            refreshMemoryLimiterStatus()

            if (useOriginalSo) {
                appendLog("已启动【原版引擎】：libhello-jni.so -> native_eatmf()")
                runCatching {
                    OriginalEatMem().native_eatmf()
                }.onFailure { err ->
                    appendLog("调用原版 libhello-jni.so 失败：${err.message}")
                }
            }

            if (useMmapEngine) {
                appendLog("已启动【mmap 高熵防 ZRAM 引擎】：libeatmem.so (6 线程 x 8MB 不可压缩随机填充)")
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
        binding.progressRam.setProgressCompat(snap.usedPercent, true)
        binding.tvRamSummary.text =
            "已用 ${MemoryStatsHelper.formatMb(snap.usedBytes)} / 总计 ${MemoryStatsHelper.formatMb(snap.totalBytes)} (${snap.usedPercent}%)"
        val swapTotalMb = snap.swapTotalKb / 1024
        val swapUsedMb = snap.swapUsedKb / 1024
        binding.tvRamDetail.text =
            "可用 RAM：${MemoryStatsHelper.formatMb(snap.availableBytes)}  |  mmap 已占：${mmapMb} MB  |  ZRAM：${swapUsedMb}/${swapTotalMb} MB  |  Android ${Build.VERSION.RELEASE}"
    }

    private fun refreshMemoryLimiterStatus() {
        val svc = cleanerService ?: return
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { svc.memoryLimiterStatus }.getOrElse { "读取失败：${it.message}" }
            }
            binding.tvLimiterStatus.text = "Android 17 Memory Limiter 实时状态：\n$status"
        }
    }

    private fun updateShizukuStatusUi() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val statusText = when {
            !running -> "Shizuku 状态：未运行（请先通过无线调试启动 Shizuku）"
            !granted -> "Shizuku 状态：已运行，待授权"
            cleanerService == null -> "Shizuku 状态：已授权，正在绑定特权服务…"
            else -> "Shizuku 状态：已就绪（本应用 UID=${Process.myUid()}，清理结束后自动恢复限制并回桌面）"
        }
        binding.tvShizukuStatus.text = statusText
        binding.btnRequestShizuku.isEnabled = !granted || cleanerService == null
        setActionButtonsEnabled(!isEating && cleanerService != null)
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.btnOrigEngine.isEnabled = enabled
        binding.btnMmapEngine.isEnabled = enabled
        binding.btnDualEngine.isEnabled = enabled
        binding.btnSafeKillAll.isEnabled = enabled
    }

    private fun appendLog(message: String) {
        val prev = binding.tvLog.text?.toString().orEmpty()
        binding.tvLog.text = if (prev.isBlank()) message else "$message\n\n$prev"
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
