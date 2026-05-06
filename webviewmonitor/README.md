# Android WebView 全链路监控 SDK

生产级、低侵入 WebView 耗时全链路监控 SDK，支持 Web Vitals（FCP/LCP/FID/CLS）+ 网络五段 + Native 白屏期 + 启动对比。

---

## 设计原理

WebView 页面加载涉及两个独立的时钟域，需要分别采集再拼接：

```
Native 侧（SystemClock.uptimeMillis）         JS 侧（performance.now，相对 navigationStart）
─────────────────────────────────────────     ─────────────────────────────────────────────
new WebView()                                  
    │ nativeWebViewCreate                      
    ▼                                          
loadUrl()                                      
    │ nativeLoadUrl                            navigationStart ──────────┐
    ▼                                               │ redirect            │
onPageStarted()                                     │ dns                 │
    │ nativePageStart                               │ tcp / ssl           │
    ▼                                               │ ttfb                │
onPageFinished()                                    │ trans               │ domContentLoaded
    │ nativePageFinish                              ▼                    │ loadEventEnd
    │                                          first-paint (FP)          │
    │                             ─ ─ ─ ─ ─ ─ first-contentful-paint ◄──┘
    │                            │              (FCP)
    │                             ─ ─ ─ ─ ─ ─ largest-contentful-paint
    │                                          (LCP, 用户未交互前持续更新)
    ▼
totalLoadTime = nativePageFinish - nativeLoadUrl   （纯 Native 时钟，最可靠）
nativeBlank   = nativePageFinish - userClickTime   （FCP 不可用时的退化白屏期）
```

**为什么不能直接用 JS 时间戳计算 totalLoadTime？**  
JS 的 `performance.now()` 是相对 navigationStart 的毫秒偏移（如 500ms），而 Native 的 `SystemClock.uptimeMillis()` 是开机后经过的总毫秒（如 400000ms），两者直接相减无意义。因此网络/渲染指标由 JS 采集，总耗时和白屏期由 Native 时钟计算。

---

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                        调用方（App）                         │
│  WebViewMonitor.attach(webView, listener)                   │
│  WebViewMonitor.recordWebViewCreate / recordUserClick / ... │
└───────────────────────────┬─────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
MonitoredWebViewClient  MonitoredWebChromeClient  AndroidJSInterface
（生命周期拦截）         （JS 错误捕获）            （JS→Native 回调）
onPageStarted              onConsoleMessage          reportMetrics()
onPageFinished             ERROR 级别 → recordJsError reportError()
onReceivedError
onReceivedSslError
        │                                           │
        └──────────────┬────────────────────────────┘
                       ▼
              internal/WebViewMonitor
              ┌──────────────────────┐
              │  stateMap            │  WeakHashMap（同步包装），WebView → MonitorState
              │  MonitorState        │  每页面独立状态：时间戳、jsError、isErrorPage、isDetached
              │  parseMetrics()      │  合并 Native 时钟 + JS JSON → WebMetrics
              │  shouldSample()      │  采样率过滤
              └──────────────────────┘
                       │
                       ▼
              WebViewMonitorListener
              onMetricsCollected(webView, metrics)
              onError(webView, error)
```

### JS 注入时机

JS 性能脚本在 **`onPageFinished`** 时注入，而非 `onPageStarted`。原因：

- `onPageStarted` 时新页面 Frame 尚未切换，注入的脚本绑定在旧 window 上，`load` 事件永远不会触发
- `onPageFinished` 时 DOM 已稳定，`document.readyState === 'complete'`，注入后立即触发 `setTimeout(report, 50)` 兜底上报
- PerformanceObserver 使用 `buffered: true`，能回溯已经发生的 paint/lcp 事件

### SPA 路由监控

SDK 默认开启 `pushState / replaceState / popstate / hashchange` 监听：

- 首屏加载仍然依赖 `onPageFinished` 注入后上报
- SPA 软路由不会触发 `onPageFinished`，因此由注入脚本主动监听路由变化并通过 JS Bridge 回调 Native
- 软路由上报会带 `isSpaRouteChange=true`、`routeChangeCount` 和 `spaRouteDuration`
- `spaRouteDuration` 口径是“路由变化发生”到“DOM 再次稳定后触发回调”的近似时长，不复用首屏 `Navigation Timing`

### 线程模型

```
主线程：WebViewClient 回调 / evaluateJavascript / handleJsReport（经 webView.post 回到主线程）
JS线程：AndroidJSInterface.reportMetrics / reportError → webView.post → 主线程处理
同步锁：stateMap 使用 Collections.synchronizedMap，防止并发读写
```

---

## 指标说明

### Web Vitals（JS 侧，`null` = 设备不支持或未触发）

| 字段 | 含义 | 优秀阈值 |
|------|------|----------|
| `fcp` | First Contentful Paint，首次内容绘制 | < 1800ms |
| `lcp` | Largest Contentful Paint，最大内容绘制 | < 2500ms |
| `fid` | First Input Delay，首次输入延迟 | < 100ms |
| `cls` | Cumulative Layout Shift，累计布局偏移 | < 0.1 |
| `tti` | Time to Interactive（domInteractive） | < 3500ms |

> LCP 依赖 `largest-contentful-paint` PerformanceObserver，部分旧版 WebView 不支持，此时返回 `null`。  
> DNS/TCP/SSL 命中缓存时为 `null`，属正常现象。

### Native 侧（始终有值）

| 字段 | 含义 |
|------|------|
| `nativeWebViewCreate` | `new WebView()` 完成时刻（需调用 `recordWebViewCreate`）|
| `nativeLoadUrl` | `loadUrl()` 被调用时刻 |
| `nativePageStart` | `onPageStarted` 回调时刻 |
| `nativePageFinish` | `onPageFinished` 回调时刻 |
| `totalLoadTime` | `nativePageFinish - nativeLoadUrl`，最可靠的页面加载总耗时 |
| `nativeBlank` | `nativePageFinish - userClickTime`，白屏期（需调用 `recordUserClick`）|

### 启动耗时分解

```
[用户点击]
    │
    ├─ WebView 初始化 = nativeLoadUrl - nativeWebViewCreate
    │  冷启动通常 80~300ms，预热后 < 10ms
    │
    ├─ 调度 + 连接  = nativePageStart - nativeLoadUrl
    │  包含 DNS 预解析、TCP 建连
    │
    └─ 网络 + 渲染  → FCP / LCP（JS 侧）
```

---

## 快速开始

```kotlin
// Application.onCreate
WebViewMonitor.init(this)

// 创建 WebView 后立即打点
val webView = WebView(this)
WebViewMonitor.attach(webView, listener)
WebViewMonitor.recordWebViewCreate(webView)   // 记录实例创建时刻

// 用户触发导航时打点
WebViewMonitor.recordUserClick(webView)       // 记录白屏起点
WebViewMonitor.recordLoadUrl(webView)         // 记录 loadUrl 时刻
webView.loadUrl("https://your-page.com")

// 接收回调
object : WebViewMonitorListener {
    override fun onMetricsCollected(webView: WebView, metrics: WebMetrics) {
        val report = metrics.toReportMap()    // 过滤 null 和无效 0，直接上报
        yourReporter.send(report)
    }
    override fun onError(webView: WebView, error: String) { }
}
```

### 预热模式（降低冷启动耗时）

```kotlin
// App 启动后提前初始化，分摊 WebView 初始化成本
WebViewMonitor.attach(preWarmedWebView, listener)
WebViewMonitor.recordWebViewCreate(preWarmedWebView)

// 用户真正打开页面时
WebViewMonitor.recordUserClick(preWarmedWebView)
WebViewMonitor.recordLoadUrl(preWarmedWebView)
preWarmedWebView.loadUrl(url)
```

---

## 配置项

```kotlin
WebViewMonitor.init(
    application = this,
    cfg = WebViewMonitorConfig(
        enableResourceTiming = true,   // 采集资源详情列表
        enablePaintTiming = true,      // 采集 Paint/LCP/FID/CLS
        enableSpaRouteMonitoring = true, // 监控 SPA 软路由
        sampleRate = 0.3f,             // 采样率，生产建议 0.1~0.5
        maxResourceCount = 20,         // 资源列表上限，防内存膨胀
    )
)
```

## 低版本 WebViewClient 接入

`API 26` 以下无法从 `WebView` 实例反向取回已设置的 `webViewClient/webChromeClient`，所以如果业务已经先设置了自定义 client，需要显式传给 SDK：

```kotlin
val businessClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return false
    }
}

webView.webViewClient = businessClient
WebViewMonitor.attach(
    webView = webView,
    listener = listener,
    originalClient = businessClient
)
```

否则在 Android 8.0 以下，SDK 包装后业务 `shouldOverrideUrlLoading/onReceivedError` 等回调会丢失。

---

## 上报数据格式

调用 `metrics.toReportMap()` 获取过滤后的 Map，只包含有效字段：

```json
{
  "traceId": 1745123456789,
  "url": "https://example.com/",
  "timestamp": 1745123456000,
  "totalLoadTime": 1240,
  "nativePageStart": 403644800,
  "nativePageFinish": 403646040,
  "fcp": 532,
  "ttfb": 180,
  "trans": 320,
  "domContentLoaded": 480,
  "resourceCount": 12
}
```

`null` 字段（设备不支持）和 `0` 值（缓存命中/未触发）均不出现在上报数据中，服务端收到的每个字段都有意义。

---

## 注意事项

- **`evaluateJavascript` 要求 WebView 挂入视图树**，否则在部分厂商 ROM 上静默失败。确保 WebView 有实际显示尺寸。
- **`detach` 应在页面销毁时调用**，避免 listener 在 WebView 销毁后仍被回调。
- **采样率**：生产环境建议设置 `sampleRate = 0.1~0.3`，避免全量采集影响性能。
- **LCP 兼容性**：`largest-contentful-paint` observer 在 Android WebView < 77 不支持，`lcp` 字段将为 `null`。
