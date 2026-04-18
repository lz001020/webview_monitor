package com.lyz.webviewmonitor

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.lyz.webviewmonitor.internal.WebViewMonitor
import java.lang.ref.WeakReference

private const val TAG = "WebViewMonitor"

class AndroidJSInterface(
    webView: WebView,
    private val monitor: WebViewMonitor
) {
    private val webViewRef = WeakReference(webView)

    @JavascriptInterface
    fun reportMetrics(json: String) {
        Log.d(TAG, "reportMetrics called, json length=${json.length}")
        val webView = webViewRef.get() ?: return
        webView.post {
            monitor.handleJsReport(webView, json)
        }
    }

    @JavascriptInterface
    fun reportError(error: String) {
        Log.w(TAG, "reportError: $error")
        val webView = webViewRef.get() ?: return
        monitor.recordJsError(webView, error)
    }

    @JavascriptInterface
    fun reportReady() {
        val webView = webViewRef.get() ?: return
        webView.post { monitor.recordH5Ready(webView) }
    }
}
