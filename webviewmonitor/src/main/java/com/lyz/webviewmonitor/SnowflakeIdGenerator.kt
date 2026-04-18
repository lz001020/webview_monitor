package com.lyz.webviewmonitor

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong


/**
 * Snowflake ID 生成器（64位）
 * 结构：41位时间戳 + 10位机器ID + 12位序列号
 */
object SnowflakeIdGenerator {
    private val idCounter = AtomicLong(System.currentTimeMillis())
    fun nextId(): Long = idCounter.incrementAndGet()
}