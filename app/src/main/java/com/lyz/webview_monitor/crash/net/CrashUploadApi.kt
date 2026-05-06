package com.lyz.webview_monitor.crash.net

import com.lyz.webview_monitor.crash.model.CrashRecord

data class UploadResult(
    val successIds: Set<String>,
    val retryIds: Set<String>
)

interface CrashUploadApi {
    fun upload(batch: List<CrashRecord>): UploadResult
}

class LogCrashUploadApi : CrashUploadApi {
    override fun upload(batch: List<CrashRecord>): UploadResult {
        val success = batch.map { it.id }.toSet()
        return UploadResult(successIds = success, retryIds = emptySet())
    }
}