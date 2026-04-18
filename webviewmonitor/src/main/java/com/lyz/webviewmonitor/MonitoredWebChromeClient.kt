package com.lyz.webviewmonitor

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.lyz.webviewmonitor.internal.WebViewMonitor
import java.lang.ref.WeakReference

class MonitoredWebChromeClient(
    private val originalChrome: WebChromeClient? = null,
    private val monitor: WebViewMonitor,
    webView: WebView
) : WebChromeClient() {
    private val webViewRef = WeakReference(webView)

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            webViewRef.get()?.let { monitor.recordJsError(it, consoleMessage.message()) }
        }
        return originalChrome?.onConsoleMessage(consoleMessage) ?: super.onConsoleMessage(consoleMessage)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        originalChrome?.onProgressChanged(view, newProgress) ?: super.onProgressChanged(view, newProgress)
    }
}
