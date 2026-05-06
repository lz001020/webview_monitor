package com.lyz.webviewmonitor.internal

import com.lyz.webviewmonitor.WebViewMonitorListener

internal data class MonitorState(
    val listener: WebViewMonitorListener?,
    var userClickTime: Long = 0L,
    var nativeWebViewCreate: Long = 0L,
    var nativeLoadUrl: Long = 0L,
    var nativePageStart: Long = 0L,
    var nativePageFinish: Long = 0L,
    var nativeBlank: Long = 0L,
    var jsInjected: Boolean = false,
    var currentUrl: String? = null,
    var routeChangeCount: Int = 0,
    val jsErrors: MutableList<String> = mutableListOf(),
    var isErrorPage: Boolean = false,
    var isDetached: Boolean = false,
    var h5ReadyTime: Long = 0L,
    var nativeInteractive: Long = 0L,
    var firstBridgeTime: Long = 0L,
    var spaNavStart: Long = 0L,
    var spaNavUrl: String? = null
)