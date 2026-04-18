package com.lyz.webviewmonitor

import android.webkit.WebView

interface WebViewMonitorListener {
    fun onMetricsCollected(webView: WebView, metrics: WebMetrics)
    fun onError(webView: WebView, error: String)
}