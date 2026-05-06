package com.lyz.webviewmonitor

import android.webkit.WebView

interface WebViewMonitorListener {
    fun onMetricsCollected(webView: WebView, metrics: WebMetrics)
    fun onError(webView: WebView, error: String)
    /** SPA 路由变化（pushState/replaceState/popstate），url 为新路由地址 */
    fun onSpaNavigate(webView: WebView, url: String) {}
    /** SPA 新路由就绪，durationMs = reportReady() 调用时刻 - pushState 时刻 */
    fun onSpaReady(webView: WebView, url: String, durationMs: Long) {}
}