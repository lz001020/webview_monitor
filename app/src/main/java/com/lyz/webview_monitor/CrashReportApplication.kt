package com.lyz.webview_monitor

import android.app.Application
import com.lyz.webview_monitor.crash.CrashReporter

class CrashReportApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 口径：去行号特征 + MD5 去重 + 日限流（<=10/类型）+ 异步落盘/启动上报
        CrashReporter.init(this, sampleRate = 1.0).startCapture()
    }
}

