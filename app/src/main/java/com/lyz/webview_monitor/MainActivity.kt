package com.lyz.webview_monitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lyz.webviewmonitor.WebMetrics
import com.lyz.webviewmonitor.WebViewMonitorListener
import com.lyz.webviewmonitor.internal.WebViewMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity(), WebViewMonitorListener {

    private val metricsFlow = MutableStateFlow<WebMetrics?>(null)
    
    // 状态管理
    private var baseMem by mutableStateOf(0)
    private var activeMem by mutableStateOf(0)
    private var urlInput by mutableStateOf("https://baidu.com")
    
    private var preWarmedWebView: WebView? = null
    private var currentWebView: WebView? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WebViewMonitor.init(application)
        baseMem = getAppMemory()

        setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    UrlInputBar(urlInput, { urlInput = it }, { urlInput = "" })

                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        currentWebView?.let { wv ->
                            key(wv) { AndroidView(factory = { wv }, modifier = Modifier.fillMaxSize()) }
                        } ?: Text("准备就绪", Modifier.align(Alignment.Center))
                    }

                    MainScreen(
                        metricsFlow = metricsFlow,
                        memStats = "基准: ${baseMem}MB | 当前: ${activeMem}MB",
                        onColdStart = { startTest(isPreWarmed = false) },
                        onPreWarm = { preWarm() },
                        onWarmStart = { startTest(isPreWarmed = true) },
                        onReset = { resetAll() }
                    )
                }
            }
        }
    }

    private fun getAppMemory(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toInt()
    }

    private fun preWarm() {
        if (preWarmedWebView == null) {
            preWarmedWebView = createWebView()
            Log.d("WebViewMonitor", "✅ 预热容器已就绪")
        }
    }

    private fun resetAll() {
        fun destroySafely(webView: WebView?) {
            webView ?: return
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
//            webView.webViewClient = null
            webView.destroy()
        }

        destroySafely(preWarmedWebView)
        preWarmedWebView = null
        destroySafely(currentWebView)
        currentWebView = null
        activeMem = 0
        System.gc()
        baseMem = getAppMemory()
    }

    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            // 1. 【关键】先设置业务 Client (拦截 Scheme)
            val businessClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (!url.startsWith("http")) {
                        handleCustomScheme(view, url)
                        return true
                    }
                    return false
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                    if (url != null && !url.startsWith("http")) {
                        handleCustomScheme(view, url)
                        return true
                    }
                    return false
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (error.errorCode == -10 || !request.url.toString().startsWith("http")) return
                    super.onReceivedError(view, request, error)
                }
            }
            webViewClient = businessClient
            
            // 2. 【关键】再进行监控注入。低版本拿不到现有 WebViewClient，需要显式传入原始 client。
            WebViewMonitor.attach(this, this@MainActivity, originalClient = businessClient)
            
            // 3. 记录创建时间
            WebViewMonitor.recordWebViewCreate(this)
        }
    }

    private fun handleCustomScheme(view: WebView, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("WebViewMonitor", "App侧无法处理: $url")
        }
    }

    private fun startTest(isPreWarmed: Boolean) {
        metricsFlow.value = null
        val wv = if (isPreWarmed && preWarmedWebView != null) {
            val instance = preWarmedWebView!!
            preWarmedWebView = null
            instance
        } else {
            createWebView()
        }
        currentWebView = wv
        WebViewMonitor.recordUserClick(wv)
        wv.post {
            WebViewMonitor.recordLoadUrl(wv)
            wv.loadUrl(if (urlInput.startsWith("http")) urlInput else "https://$urlInput")
        }
    }

    override fun onMetricsCollected(webView: WebView, metrics: WebMetrics) {
        metricsFlow.value = metrics
        activeMem = getAppMemory()
    }

    override fun onError(webView: WebView, error: String) {
        metricsFlow.value = metricsFlow.value?.let { m ->
            m.copy(jsErrors = m.jsErrors + error)
        }
    }
}

@Composable
fun UrlInputBar(url: String, onUrlChange: (String) -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入网址") },
            singleLine = true,
            trailingIcon = { if (url.isNotEmpty()) IconButton(onClick = onClear) { Text("✕") } }
        )
    }
}

@Composable
fun MainScreen(metricsFlow: StateFlow<WebMetrics?>, memStats: String, onColdStart: () -> Unit, onPreWarm: () -> Unit, onWarmStart: () -> Unit, onReset: () -> Unit) {
    val metrics by metricsFlow.collectAsState()
    Column(Modifier.fillMaxWidth().height(480.dp).padding(16.dp)) {
        Text(memStats, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onColdStart, modifier = Modifier.weight(1f)) { Text("冷启动") }
            Button(onClick = onPreWarm, colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray), modifier = Modifier.weight(1f)) { Text("预热容器") }
            Button(onClick = onWarmStart, modifier = Modifier.weight(1f)) { Text("预热启动") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item { if (metrics != null) PerformanceCard(metrics!!) }
            item { TextButton(onClick = onReset, Modifier.fillMaxWidth()) { Text("重置内存", color = Color.Red) } }
        }
    }
}

@Composable
fun PerformanceCard(metrics: WebMetrics) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("性能看板 (URL: ${metrics.url})", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            
            // 1. 启动耗时分析
            val webInit = if (metrics.nativeWebViewCreate > 0 && metrics.nativeLoadUrl > 0) 
                metrics.nativeLoadUrl - metrics.nativeWebViewCreate else 0L
            
            MetricRow("WebInit (容器准备)", "${webInit}ms", if (webInit < 50) "🚀 预热" else "❄️ 冷启")
            MetricRow("Native Blank (白屏)", "${metrics.nativeBlank}ms")
            
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // 2. Web Vitals
            Text("核心指标 (Web Vitals)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            MetricRow("FCP (首次绘制)", "${metrics.fcp ?: "-"}ms")
            MetricRow("LCP (最大绘制)", "${metrics.lcp ?: "-"}ms")
            MetricRow("TTI (可交互)", "${metrics.tti ?: "-"}ms")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // 3. 网络全链路
            Text("网络全链路 (Network)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            MetricRow("DNS 解析", "${metrics.dns ?: 0}ms")
            MetricRow("TCP/SSL 连接", "${(metrics.tcp ?: 0) + (metrics.ssl ?: 0)}ms")
            MetricRow("TTFB (首字节)", "${metrics.ttfb ?: "-"}ms")
            MetricRow("总加载时间", "${metrics.totalLoadTime}ms")
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, status: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("$status $value", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
