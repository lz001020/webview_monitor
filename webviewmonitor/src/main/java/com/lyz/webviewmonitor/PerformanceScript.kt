package com.lyz.webviewmonitor

fun buildPerformanceMonitorJs(enableResourceTiming: Boolean, enablePaintTiming: Boolean, maxResourceCount: Int): String {
    return """
(function() {
    'use strict';

    if (window.__WebViewMonitorInjected) return;
    window.__WebViewMonitorInjected = true;

    const ENABLE_RESOURCE_TIMING = ${enableResourceTiming};
    const ENABLE_PAINT_TIMING = ${enablePaintTiming};
    const MAX_RESOURCE_COUNT = ${maxResourceCount};

    const metrics = {
        fp: 0, fcp: 0, lcp: 0, fid: 0, cls: 0.0, tti: 0,
        redirect: 0, dns: 0, tcp: 0, ssl: 0, ttfb: 0, trans: 0, totalNetwork: 0,
        domContentLoaded: 0, loadEventEnd: 0,
        resourceTimings: []
    };

    let hasReported = false;
    let intervalId = null;

    function collectNavigation() {
        const nav = (performance.getEntriesByType && performance.getEntriesByType('navigation')[0]) || performance.timing;
        if (!nav) return;

        const start = nav.navigationStart || (nav.startTime || 0);
        metrics.redirect = Math.round((nav.redirectEnd || 0) - (nav.redirectStart || 0));
        metrics.dns = Math.round((nav.domainLookupEnd || 0) - (nav.domainLookupStart || 0));
        metrics.tcp = Math.round((nav.connectEnd || 0) - (nav.connectStart || 0));
        metrics.ssl = nav.secureConnectionStart ? Math.round((nav.connectEnd || 0) - nav.secureConnectionStart) : 0;
        metrics.ttfb = Math.round((nav.responseStart || 0) - (nav.requestStart || 0));
        metrics.trans = Math.round((nav.responseEnd || 0) - (nav.responseStart || 0));
        metrics.totalNetwork = Math.round(nav.duration || (performance.now() - (nav.startTime || 0)));
        metrics.domContentLoaded = Math.round((nav.domContentLoadedEventEnd || 0) - start);
        metrics.loadEventEnd = Math.round(performance.now());
        metrics.tti = Math.round((nav.domInteractive || 0) - start);

        Object.keys(metrics).forEach(key => {
            if (typeof metrics[key] === 'number' && metrics[key] < 0) metrics[key] = 0;
        });
    }

    function collectResources() {
        if (!ENABLE_RESOURCE_TIMING || !performance.getEntriesByType) return;

        const res = performance.getEntriesByType('resource').slice(0, MAX_RESOURCE_COUNT);
        metrics.resourceTimings = res.map(r => ({
            name: r.name.split('?')[0],
            duration: Math.round(r.duration),
            dns: Math.round((r.domainLookupEnd || 0) - (r.domainLookupStart || 0)),
            connect: Math.round((r.connectEnd || 0) - (r.connectStart || 0)),
            ttfb: Math.round((r.responseStart || 0) - (r.requestStart || 0)),
            transfer: Math.round((r.responseEnd || 0) - (r.responseStart || 0))
        }));
    }

    function stopTimers() {
        if (intervalId !== null) {
            clearInterval(intervalId);
            intervalId = null;
        }
    }

    function reportOnce() {
        if (hasReported) return;

        collectNavigation();
        collectResources();

        if (window.WebViewMonitor && window.WebViewMonitor.reportMetrics) {
            window.WebViewMonitor.reportMetrics(JSON.stringify(metrics));
            hasReported = true;
            stopTimers();
        }
    }

    if (ENABLE_PAINT_TIMING && 'PerformanceObserver' in window) {
        try {
            new PerformanceObserver(l => {
                l.getEntries().forEach(e => {
                    if (e.name === 'first-paint') metrics.fp = Math.round(e.startTime);
                    if (e.name === 'first-contentful-paint') metrics.fcp = Math.round(e.startTime);
                });
            }).observe({type: 'paint', buffered: true});
        } catch(e) {}

        try {
            new PerformanceObserver(l => {
                const entries = l.getEntries();
                if (entries.length) {
                    metrics.lcp = Math.round(entries[entries.length - 1].startTime);
                }
            }).observe({type: 'largest-contentful-paint', buffered: true});
        } catch(e) {}

        try {
            new PerformanceObserver(l => {
                const first = l.getEntries()[0];
                if (first) metrics.fid = Math.round(first.processingStart - first.startTime);
            }).observe({type: 'first-input', buffered: true});
        } catch(e) {}

        try {
            let clsValue = 0;
            new PerformanceObserver(l => {
                l.getEntries().forEach(e => {
                    if (!e.hadRecentInput) clsValue += e.value;
                });
                metrics.cls = parseFloat(clsValue.toFixed(4));
            }).observe({type: 'layout-shift', buffered: true});
        } catch(e) {}
    }

    window.addEventListener('load', reportOnce, { once: true });
    document.addEventListener('DOMContentLoaded', reportOnce, { once: true });

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(reportOnce, 50);
    }

    let reportCount = 0;
    intervalId = setInterval(() => {
        reportCount++;
        reportOnce();
        if (reportCount >= 8) stopTimers();
    }, 3000);

    window.onerror = function(msg) {
        if (window.WebViewMonitor && window.WebViewMonitor.reportError) {
            window.WebViewMonitor.reportError(msg || 'unknown js error');
        }
    };
})();
""".trimIndent()
}
