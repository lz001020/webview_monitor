package com.lyz.webviewmonitor

data class WebMetrics(
    // ============== 1. 基础链路信息 ==============
    val traceId: Long = SnowflakeIdGenerator.nextId(),
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),   // 墙钟时间，便于多设备日志关联

    // ============== 2. Native 侧 ==============
    var nativeWebViewCreate: Long = 0,
    var nativeLoadUrl: Long = 0,
    var nativePageStart: Long = 0,
    var nativePageFinish: Long = 0,
    var nativeBlank: Long = 0,
    var userClickTime: Long = 0, // 新增：记录用户点击时刻以便计算真正的初始化阻塞

    // ============== 3. Web Vitals（null = 设备不支持或页面未触发） ==============
    var fp: Long? = null,
    var fcp: Long? = null,
    var lcp: Long? = null,
    var fid: Long? = null,
    var cls: Double? = null,
    var tti: Long? = null,
    var h5ReadyTime: Long? = null,   // 业务口径 TTI，null = H5 未调用 reportReady
    var nativeInteractive: Long = 0, // Native 代理口径 TTI = 首次 bridge 回调 - userClickTime

    // ============== 4. 网络全链路（null = 命中缓存或浏览器不暴露） ==============
    var redirect: Long? = null,
    var dns: Long? = null,
    var tcp: Long? = null,
    var ssl: Long? = null,
    var ttfb: Long? = null,
    var trans: Long? = null,
    var totalNetwork: Long? = null,

    // ============== 5. 页面加载阶段 ==============
    var domContentLoaded: Long? = null,
    var loadEventEnd: Long? = null,
    var totalLoadTime: Long = 0,        // Native 时钟：nativePageFinish - nativeLoadUrl，始终有值

    // ============== 6. 扩展 & 异常 ==============
    val resourceTimings: List<ResourceTiming> = emptyList(),
    val jsErrors: List<String> = emptyList(),
    var isErrorPage: Boolean = false
) {
    /**
     * 转为只包含有效数据的 Map，过滤掉 null（设备不支持）和无意义的 0。
     * 适合直接序列化为 JSON 上报给服务端。
     */
    fun toReportMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "traceId" to traceId,
            "url" to url,
            "timestamp" to timestamp,
            "totalLoadTime" to totalLoadTime,
            "isErrorPage" to isErrorPage,
        )
        if (nativeLoadUrl > 0) map["nativeLoadUrl"] = nativeLoadUrl
        if (nativePageStart > 0) map["nativePageStart"] = nativePageStart
        if (nativePageFinish > 0) map["nativePageFinish"] = nativePageFinish
        if (nativeBlank > 0) map["nativeBlank"] = nativeBlank

        fp?.takeIf { it > 0 }?.let { map["fp"] = it }
        fcp?.takeIf { it > 0 }?.let { map["fcp"] = it }
        lcp?.takeIf { it > 0 }?.let { map["lcp"] = it }
        fid?.takeIf { it > 0 }?.let { map["fid"] = it }
        cls?.takeIf { it > 0.0 }?.let { map["cls"] = it }
        tti?.takeIf { it > 0 }?.let { map["tti"] = it }
        h5ReadyTime?.takeIf { it > 0 }?.let { map["h5ReadyTime"] = it }
        if (nativeInteractive > 0) map["nativeInteractive"] = nativeInteractive

        redirect?.takeIf { it > 0 }?.let { map["redirect"] = it }
        dns?.takeIf { it > 0 }?.let { map["dns"] = it }
        tcp?.takeIf { it > 0 }?.let { map["tcp"] = it }
        ssl?.takeIf { it > 0 }?.let { map["ssl"] = it }
        ttfb?.takeIf { it > 0 }?.let { map["ttfb"] = it }
        trans?.takeIf { it > 0 }?.let { map["trans"] = it }
        totalNetwork?.takeIf { it > 0 }?.let { map["totalNetwork"] = it }

        domContentLoaded?.takeIf { it > 0 }?.let { map["domContentLoaded"] = it }
        loadEventEnd?.takeIf { it > 0 }?.let { map["loadEventEnd"] = it }

        if (jsErrors.isNotEmpty()) map["jsErrors"] = jsErrors
        if (resourceTimings.isNotEmpty()) map["resourceCount"] = resourceTimings.size
        return map
    }
}

data class ResourceTiming(
    val name: String,
    val duration: Long,
    val dns: Long,
    val connect: Long,
    val ttfb: Long,
    val transfer: Long
)
