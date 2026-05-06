package com.lyz.webview_monitor.crash

import java.security.MessageDigest

object StacktraceNormalizer {
    fun normalizeTopFrames(throwable: Throwable, limit: Int = 15): List<String> {
        return throwable.stackTrace
            .asSequence()
            .filter { it.className.isNotBlank() && it.methodName.isNotBlank() }
            .take(limit)
            .map { normalizeFrame(it) }
            .toList()
    }

    fun normalizeMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("0x[0-9a-fA-F]+"), "0x*")
            .replace(Regex("\\b\\d{4,}\\b"), "*")
            .replace(Regex("([?&][^=]+=)[^&\\s]+"), "$1*")
            .take(200)
    }

    fun normalizeFrame(frame: StackTraceElement): String {
        val cls = frame.className.replace(Regex("\\$\\d+"), "\\$*")
        return "$cls#${frame.methodName}"
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}