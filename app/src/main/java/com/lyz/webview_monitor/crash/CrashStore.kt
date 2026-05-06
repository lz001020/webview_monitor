package com.lyz.webview_monitor.crash

import android.content.Context
import com.lyz.webview_monitor.crash.model.CrashRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CrashStore(context: Context) {
    private val dir = File(context.filesDir, "crash").apply { mkdirs() }

    fun persist(record: CrashRecord): Boolean {
        return runCatching {
            // 1. 磁盘配额管理：防止无限占用存储
            val files = dir.listFiles { f -> f.extension == "json" }
            if (files != null && files.size >= 20) {
                files.sortedBy { it.lastModified() }.firstOrNull()?.delete()
            }

            val dst = File(dir, "${record.id}.json")
            // 2. 考虑多进程安全：使用文件锁或临时文件重命名
            val tmp = File(dir, "${record.id}.tmp")
            
            // 3. 生产环境建议使用流式写入 (JsonWriter) 减少内存占用，这里先做健壮性保护
            tmp.outputStream().use { fos ->
                fos.write(toJson(record).toString().toByteArray(Charsets.UTF_8))
            }
            
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse { 
            // 极端情况下（如磁盘满）不应抛出异常干扰主逻辑
            false 
        }
    }

    fun loadPending(limit: Int = 100): List<CrashRecord> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file -> runCatching { fromJson(JSONObject(file.readText())) }.getOrNull() }
            .orEmpty()
    }

    fun delete(ids: List<String>) {
        ids.forEach { id -> File(dir, "$id.json").delete() }
    }

    private fun toJson(record: CrashRecord): JSONObject {
        return JSONObject().apply {
            put("id", record.id)
            put("ts", record.ts)
            put("day", record.day)
            put("processName", record.processName)
            put("threadName", record.threadName)
            put("exceptionType", record.exceptionType)
            put("messageNorm", record.messageNorm)
            put("stackTopNorm", JSONArray(record.stackTopNorm))
            put("rawStack", record.rawStack)
            put("dedupeKey", record.dedupeKey)
            put("appVersion", record.appVersion)
            put("osVersion", record.osVersion)
            put("deviceModel", record.deviceModel)
        }
    }

    private fun fromJson(obj: JSONObject): CrashRecord {
        val stackArr = obj.optJSONArray("stackTopNorm") ?: JSONArray()
        val stack = buildList {
            for (i in 0 until stackArr.length()) add(stackArr.optString(i))
        }
        return CrashRecord(
            id = obj.optString("id"),
            ts = obj.optLong("ts"),
            day = obj.optString("day"),
            processName = obj.optString("processName"),
            threadName = obj.optString("threadName"),
            exceptionType = obj.optString("exceptionType"),
            messageNorm = obj.optString("messageNorm"),
            stackTopNorm = stack,
            rawStack = obj.optString("rawStack"),
            dedupeKey = obj.optString("dedupeKey"),
            appVersion = obj.optString("appVersion"),
            osVersion = obj.optString("osVersion"),
            deviceModel = obj.optString("deviceModel")
        )
    }
}
