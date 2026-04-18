package com.lyz.webviewmonitor.internal

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import com.lyz.webviewmonitor.AndroidJSInterface
import com.lyz.webviewmonitor.MonitoredWebChromeClient
import com.lyz.webviewmonitor.MonitoredWebViewClient
import com.lyz.webviewmonitor.buildPerformanceMonitorJs
import com.lyz.webviewmonitor.ResourceTiming
import com.lyz.webviewmonitor.WebMetrics
import com.lyz.webviewmonitor.WebViewMonitorConfig
import com.lyz.webviewmonitor.WebViewMonitorListener
import org.json.JSONArray
import java.util.Collections
import java.util.WeakHashMap
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

object WebViewMonitor {
    private val config = AtomicReference(WebViewMonitorConfig())
    private val stateMap = Collections.synchronizedMap(WeakHashMap<WebView, MonitorState>())

    fun init(application: Application, cfg: WebViewMonitorConfig = WebViewMonitorConfig()) {
        config.set(cfg)
        // 可扩展：注册 ActivityLifecycleCallbacks 自动 detach
    }

    fun attach(webView: WebView, listener: WebViewMonitorListener? = null) {
        if (!shouldSample()) return

        val state = MonitorState(listener)
        stateMap[webView] = state

        val originalClient = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) webView.webViewClient else null
        webView.webViewClient =
            MonitoredWebViewClient(originalClient, this)

        val originalChrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) webView.webChromeClient else null
        webView.webChromeClient = MonitoredWebChromeClient(originalChrome, this, webView)

        webView.addJavascriptInterface(AndroidJSInterface(webView, this), "WebViewMonitor")
    }

    /** 在 new WebView() 之后立即调用，记录实例创建完成时刻 */
    fun recordWebViewCreate(webView: WebView) {
        stateMap[webView]?.nativeWebViewCreate = SystemClock.uptimeMillis()
    }

    /** 在用户触发导航（点击）时调用，用于计算白屏时间 */
    fun recordUserClick(webView: WebView) {
        stateMap[webView]?.userClickTime = SystemClock.uptimeMillis()
    }

    fun recordLoadUrl(webView: WebView) {
        stateMap[webView]?.nativeLoadUrl = SystemClock.uptimeMillis()
    }

    internal fun recordPageStart(webView: WebView, @Suppress("UNUSED_PARAMETER") url: String?) {
        stateMap[webView]?.let { state ->
            state.nativePageStart = SystemClock.uptimeMillis()
            if (state.nativeLoadUrl == 0L) state.nativeLoadUrl = state.nativePageStart
            state.jsInjected = false  // 每次新页面导航重置，确保重新注入
        }
    }

    internal fun recordPageFinish(webView: WebView, @Suppress("UNUSED_PARAMETER") url: String?) {
        stateMap[webView]?.let { state ->
            state.nativePageFinish = SystemClock.uptimeMillis()
            if (!state.jsInjected) {
                val cfg = config.get()
                webView.evaluateJavascript(
                    buildPerformanceMonitorJs(
                        enableResourceTiming = cfg.enableResourceTiming,
                        enablePaintTiming = cfg.enablePaintTiming,
                        maxResourceCount = cfg.maxResourceCount
                    ),
                    null
                )
                state.jsInjected = true
            }
        }
    }

    internal fun recordError(webView: WebView, error: String?) {
        stateMap[webView]?.let { state ->
            state.isErrorPage = true
            state.listener?.onError(webView, error ?: "unknown error")
        }
    }

    internal fun recordJsError(webView: WebView, error: String) {
        stateMap[webView]?.jsErrors?.add(error)
    }

    internal fun handleJsReport(webView: WebView, json: String) {
        val state = stateMap[webView] ?: return
        if (state.isDetached) return
        val metrics = parseMetrics(json, webView, state)
        state.listener?.onMetricsCollected(webView, metrics)
    }

    private fun shouldSample(): Boolean {
        val c = config.get()
        return ThreadLocalRandom.current().nextDouble() < c.sampleRate
    }

    private fun parseMetrics(json: String, webView: WebView, state: MonitorState): WebMetrics {
        val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }

        fun JSONObject.longOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) getLong(key).takeIf { it > 0 } else null

        fun JSONObject.doubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) getDouble(key).takeIf { it > 0.0 } else null

        val nativeBlank = if (state.userClickTime > 0L && state.nativePageFinish > 0L) {
            (state.nativePageFinish - state.userClickTime).coerceAtLeast(0L)
        } else {
            0L
        }
        state.nativeBlank = nativeBlank

        return WebMetrics(
            url = webView.url ?: "unknown",
            nativeWebViewCreate = state.nativeWebViewCreate,
            nativeLoadUrl = state.nativeLoadUrl,
            nativePageStart = state.nativePageStart,
            nativePageFinish = state.nativePageFinish,
            nativeBlank = nativeBlank,
            userClickTime = state.userClickTime,

            fp = obj.longOrNull("fp"),
            fcp = obj.longOrNull("fcp"),
            lcp = obj.longOrNull("lcp"),
            fid = obj.longOrNull("fid"),
            cls = obj.doubleOrNull("cls"),
            tti = obj.longOrNull("tti"),

            redirect = obj.longOrNull("redirect"),
            dns = obj.longOrNull("dns"),
            tcp = obj.longOrNull("tcp"),
            ssl = obj.longOrNull("ssl"),
            ttfb = obj.longOrNull("ttfb"),
            trans = obj.longOrNull("trans"),
            totalNetwork = obj.longOrNull("totalNetwork"),

            domContentLoaded = obj.longOrNull("domContentLoaded"),
            loadEventEnd = obj.longOrNull("loadEventEnd"),
            totalLoadTime = if (state.nativePageFinish > 0L && state.nativeLoadUrl > 0L)
                state.nativePageFinish - state.nativeLoadUrl else 0L,

            resourceTimings = parseResourceList(obj.optJSONArray("resourceTimings")),
            jsErrors = state.jsErrors.toList(),
            isErrorPage = state.isErrorPage
        )
    }

    private fun parseResourceList(array: JSONArray?): List<ResourceTiming> {
        if (array == null) return emptyList()
        val list = mutableListOf<ResourceTiming>()
        for (i in 0 until array.length().coerceAtMost(config.get().maxResourceCount)) {
            val item = array.optJSONObject(i) ?: continue
            list.add(
                ResourceTiming(
                    name = item.optString("name"),
                    duration = item.optLong("duration"),
                    dns = item.optLong("dns"),
                    connect = item.optLong("connect"),
                    ttfb = item.optLong("ttfb"),
                    transfer = item.optLong("transfer")
                )
            )
        }
        return list
    }

    fun detach(webView: WebView) {
        stateMap[webView]?.isDetached = true
        stateMap.remove(webView)
        webView.removeJavascriptInterface("WebViewMonitor")
    }
}