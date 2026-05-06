package com.lyz.webviewmonitor


data class WebViewMonitorConfig(
    val enableResourceTiming: Boolean = true,      // 是否采集资源详情
    val enablePaintTiming: Boolean = true,         // 是否开启 Paint/LCP/FID/CLS
    val enableSpaRouteMonitoring: Boolean = true,  // 是否监控 pushState / replaceState / popstate / hashchange
    val sampleRate: Float = 1.0f,                  // 采样率，生产建议 0.1~0.5
    val maxResourceCount: Int = 20,                // 资源列表上限，防内存
    val reportOnPageFinishedOnly: Boolean = false  // 是否只在 onPageFinished 上报
)
