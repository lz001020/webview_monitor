package com.lyz.webviewmonitor

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import com.lyz.webviewmonitor.internal.WebViewMonitor

private const val TAG = "WebViewMonitor"

class MonitoredWebViewClient(
    private val originalClient: WebViewClient? = null,
    private val monitor: WebViewMonitor
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        monitor.recordPageStart(view, url)
        originalClient?.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        monitor.recordPageFinish(view, url)
        originalClient?.onPageFinished(view, url)
    }

    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        monitor.recordError(view, description)
        originalClient?.onReceivedError(view, errorCode, description, failingUrl)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            monitor.recordError(view, error.description.toString())
        }
        originalClient?.onReceivedError(view, request, error)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        monitor.recordError(view, "RenderProcessGone")
        return originalClient?.onRenderProcessGone(view, detail) ?: super.onRenderProcessGone(view, detail)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        return originalClient?.shouldOverrideUrlLoading(view, url) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return originalClient?.shouldOverrideUrlLoading(view, request) ?: false
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) {
            monitor.recordError(view, "HTTP ${errorResponse.statusCode}")
        }
        originalClient?.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val detail = "SSL Error ${error.primaryError} on ${error.url}"
        monitor.recordError(view, detail)
        if (originalClient != null) {
            originalClient.onReceivedSslError(view, handler, error)
        } else {
            handler.cancel()
        }
    }
}
