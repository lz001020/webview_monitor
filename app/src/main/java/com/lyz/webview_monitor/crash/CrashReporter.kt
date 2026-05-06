package com.lyz.webview_monitor.crash

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.lyz.webview_monitor.crash.model.CrashRecord
import com.lyz.webview_monitor.crash.net.CrashUploadApi
import com.lyz.webview_monitor.crash.net.LogCrashUploadApi
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

class CrashReporter private constructor(
    private val app: Application,
    private val store: CrashStore,
    private val limiter: CrashLimiter,
    private val uploadApi: CrashUploadApi,
    val sampleRate: Double = 1.0
) {
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "crash-io") }

    // 启动时预取，crash 现场不做慢操作
    private val cachedAppVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private val cachedProcessName: String = runCatching {
        val pid = Process.myPid()
        val am = app.getSystemService(Application.ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: app.packageName
    }.getOrDefault(app.packageName)

    // OOM 兜底：进程启动时预分配，crash 时直接复用，不依赖堆
    private val emergencyBuffer = ByteArray(8 * 1024)
    private val emergencyFile = File(app.filesDir, "crash/emergency.txt")

    fun startCapture() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrashInternal(thread.name, throwable)
            } catch (oom: OutOfMemoryError) {
                writeEmergencyRecord(thread.name, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture crash", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        reportPendingAsync()
    }

    private fun handleCrashInternal(threadName: String, throwable: Throwable) {
        val stackTopNorm = StacktraceNormalizer.normalizeTopFrames(throwable, 15)
        val rawStack = Log.getStackTraceString(throwable)
        val messageNorm = StacktraceNormalizer.normalizeMessage(throwable.message)
        val exceptionType = throwable.javaClass.name
        val dedupeKey = StacktraceNormalizer.md5("$exceptionType|$messageNorm|${stackTopNorm.joinToString(";")}")

        if (!limiter.shouldAccept(dedupeKey)) return
        limiter.onAccepted(dedupeKey)

        store.persist(
            CrashRecord(
                id = UUID.randomUUID().toString(),
                ts = System.currentTimeMillis(),
                day = limiter.currentDay(),
                processName = cachedProcessName,
                threadName = threadName,
                exceptionType = exceptionType,
                messageNorm = messageNorm,
                stackTopNorm = stackTopNorm,
                rawStack = rawStack,
                dedupeKey = dedupeKey,
                appVersion = cachedAppVersion,
                osVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL
            )
        )
    }

    // OOM 时只用预分配的 emergencyBuffer，写入最小信息
    private fun writeEmergencyRecord(threadName: String, throwable: Throwable) {
        runCatching {
            // throwable.javaClass.name 是 interned String，不触发新分配
            val type = throwable.javaClass.name
            val info = "$type thread=$threadName ts=${System.currentTimeMillis()}"
            val bytes = info.toByteArray(Charsets.UTF_8)
            val len = minOf(bytes.size, emergencyBuffer.size)
            System.arraycopy(bytes, 0, emergencyBuffer, 0, len)
            emergencyFile.parentFile?.mkdirs()
            emergencyFile.writeBytes(emergencyBuffer.copyOf(len))
        }
    }

    fun reportPendingAsync() {
        ioExecutor.execute {
            runCatching {
                val pending = store.loadPending(limit = 10)
                if (pending.isEmpty()) return@runCatching

                // 采样在上报层：全量落盘，按比例上报
                val sampled = if (sampleRate >= 1.0) pending
                    else pending.filter { ThreadLocalRandom.current().nextDouble() < sampleRate }
                if (sampled.isEmpty()) return@runCatching

                val result = uploadApi.upload(sampled)
                // 未采样的也删除，避免无限堆积
                val toDelete = (result.successIds + pending.map { it.id }.toSet() - result.retryIds).toList()
                if (toDelete.isNotEmpty()) store.delete(toDelete)
            }.onFailure {
                Log.e(TAG, "Async report failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "CrashReporter"

        @Volatile private var instance: CrashReporter? = null

        fun init(
            app: Application,
            uploadApi: CrashUploadApi = LogCrashUploadApi(),
            sampleRate: Double = 1.0
        ): CrashReporter {
            return instance ?: synchronized(this) {
                instance ?: CrashReporter(
                    app = app,
                    store = CrashStore(app),
                    limiter = CrashLimiter(app),
                    uploadApi = uploadApi,
                    sampleRate = sampleRate
                ).also { instance = it }
            }
        }

        fun get(): CrashReporter = checkNotNull(instance) { "CrashReporter not initialized" }
    }
}

