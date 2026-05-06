package com.lyz.webview_monitor.crash.model

data class CrashRecord(
    val id: String,
    val ts: Long,
    val day: String,
    val processName: String,
    val threadName: String,
    val exceptionType: String,
    val messageNorm: String,
    val stackTopNorm: List<String>,
    val rawStack: String,
    val dedupeKey: String,
    val appVersion: String,
    val osVersion: String,
    val deviceModel: String
)