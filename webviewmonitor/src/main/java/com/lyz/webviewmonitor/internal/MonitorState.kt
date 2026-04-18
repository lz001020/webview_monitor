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
    val jsErrors: MutableList<String> = mutableListOf(),
    var isErrorPage: Boolean = false,
    var isDetached: Boolean = false
)