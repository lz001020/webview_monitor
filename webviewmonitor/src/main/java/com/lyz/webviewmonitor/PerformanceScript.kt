package com.lyz.webviewmonitor

fun buildPerformanceMonitorJs(
    enableResourceTiming: Boolean,
    enablePaintTiming: Boolean,
    enableSpaRouteMonitoring: Boolean,
    maxResourceCount: Int
): String {
    return """
(function() {
    'use strict';

    if (window.__WebViewMonitorInjected) return;
    window.__WebViewMonitorInjected = true;

    const ENABLE_RESOURCE_TIMING = ${enableResourceTiming};
    const ENABLE_PAINT_TIMING = ${enablePaintTiming};
    const ENABLE_SPA_ROUTE_MONITORING = ${enableSpaRouteMonitoring};
    const MAX_RESOURCE_COUNT = ${maxResourceCount};

    const navStart = performance.timing && performance.timing.navigationStart
        ? performance.timing.navigationStart
        : Date.now() - Math.round(performance.now());

    const baseMetrics = {
        fp: 0,
        fcp: 0,
        lcp: 0,
        fid: 0,
        cls: 0.0,
        tti: 0,
        redirect: 0,
        dns: 0,
        tcp: 0,
        ssl: 0,
        ttfb: 0,
        trans: 0,
        totalNetwork: 0,
        domContentLoaded: 0,
        loadEventEnd: 0,
        spaRouteDuration: 0,
        resourceTimings: []
    };

    let loadReported = false;
    let pollTimerId = null;
    let lastLongTaskEnd = 0;
    let clsValue = 0;
    let routeChangeStartedAt = 0;
    let routeReportTimerId = null;
    let routeMutationObserver = null;
    let routeLoadHookAttached = false;

    function cloneMetrics() {
        const next = {};
        Object.keys(baseMetrics).forEach(key => {
            const value = baseMetrics[key];
            next[key] = Array.isArray(value) ? value.slice() : value;
        });
        return next;
    }

    function sanitizeNumericFields(target) {
        Object.keys(target).forEach(key => {
            if (typeof target[key] === 'number' && target[key] < 0) {
                target[key] = 0;
            }
        });
    }

    function collectNavigation(target) {
        const nav = (performance.getEntriesByType && performance.getEntriesByType('navigation')[0]) || performance.timing;
        if (!nav) return;

        const start = nav.navigationStart || nav.startTime || 0;
        target.redirect = Math.round((nav.redirectEnd || 0) - (nav.redirectStart || 0));
        target.dns = Math.round((nav.domainLookupEnd || 0) - (nav.domainLookupStart || 0));
        target.tcp = Math.round((nav.connectEnd || 0) - (nav.connectStart || 0));
        target.ssl = nav.secureConnectionStart ? Math.round((nav.connectEnd || 0) - nav.secureConnectionStart) : 0;
        target.ttfb = Math.round((nav.responseStart || 0) - (nav.requestStart || 0));
        target.trans = Math.round((nav.responseEnd || 0) - (nav.responseStart || 0));
        target.totalNetwork = Math.round(nav.duration || (performance.now() - (nav.startTime || 0)));
        target.domContentLoaded = Math.round((nav.domContentLoadedEventEnd || 0) - start);
        target.loadEventEnd = Math.round(performance.now());
        target.tti = Math.round((nav.domInteractive || 0) - start);
        if (lastLongTaskEnd > 0) {
            target.tti = Math.round(Math.max(target.tti, lastLongTaskEnd));
        }
    }

    function collectResources(target) {
        if (!ENABLE_RESOURCE_TIMING || !performance.getEntriesByType) return;

        const res = performance.getEntriesByType('resource').slice(0, MAX_RESOURCE_COUNT);
        target.resourceTimings = res.map(r => ({
            name: r.name.split('?')[0],
            duration: Math.round(r.duration),
            dns: Math.round((r.domainLookupEnd || 0) - (r.domainLookupStart || 0)),
            connect: Math.round((r.connectEnd || 0) - (r.connectStart || 0)),
            ttfb: Math.round((r.responseStart || 0) - (r.requestStart || 0)),
            transfer: Math.round((r.responseEnd || 0) - (r.responseStart || 0))
        }));
    }

    function buildSnapshot(extra) {
        const snapshot = cloneMetrics();
        collectNavigation(snapshot);
        collectResources(snapshot);
        snapshot.cls = parseFloat(clsValue.toFixed(4));
        if (extra) {
            Object.keys(extra).forEach(key => {
                snapshot[key] = extra[key];
            });
        }
        sanitizeNumericFields(snapshot);
        return snapshot;
    }

    function reportMetrics(snapshot) {
        if (window.WebViewMonitor && window.WebViewMonitor.reportMetrics) {
            window.WebViewMonitor.reportMetrics(JSON.stringify(snapshot));
            return true;
        }
        return false;
    }

    function clearPollTimer() {
        if (pollTimerId !== null) {
            clearInterval(pollTimerId);
            pollTimerId = null;
        }
    }

    function reportInitialLoad() {
        if (loadReported) return;
        if (reportMetrics(buildSnapshot())) {
            loadReported = true;
            clearPollTimer();
        }
    }

    function routeReadySignal() {
        if (!routeChangeStartedAt) return;
        const duration = Math.round(Date.now() - routeChangeStartedAt);
        const snapshot = buildSnapshot({ spaRouteDuration: duration });
        if (reportMetrics(snapshot)) {
            routeChangeStartedAt = 0;
        }
    }

    function scheduleRouteReadyCheck() {
        if (!routeChangeStartedAt) return;
        if (routeReportTimerId !== null) {
            clearTimeout(routeReportTimerId);
        }
        routeReportTimerId = setTimeout(() => {
            routeReportTimerId = null;
            routeReadySignal();
        }, 120);
    }

    function notifyNativeRouteChange() {
        if (window.WebViewMonitor && window.WebViewMonitor.reportRouteChange) {
            window.WebViewMonitor.reportRouteChange(location.href);
        }
    }

    function installSpaRouteHooks() {
        if (!ENABLE_SPA_ROUTE_MONITORING || !window.history) return;

        const wrapHistoryMethod = methodName => {
            const original = window.history[methodName];
            if (typeof original !== 'function') return;
            window.history[methodName] = function() {
                const result = original.apply(this, arguments);
                handleSpaRouteChange(methodName);
                return result;
            };
        };

        wrapHistoryMethod('pushState');
        wrapHistoryMethod('replaceState');
        window.addEventListener('popstate', () => handleSpaRouteChange('popstate'));
        window.addEventListener('hashchange', () => handleSpaRouteChange('hashchange'));
    }

    function handleSpaRouteChange(source) {
        routeChangeStartedAt = Date.now();
        notifyNativeRouteChange();

        if (document.readyState === 'complete' || document.readyState === 'interactive') {
            scheduleRouteReadyCheck();
        }

        if (!routeLoadHookAttached) {
            routeLoadHookAttached = true;
            window.addEventListener('load', scheduleRouteReadyCheck);
        }

        if ('MutationObserver' in window) {
            if (routeMutationObserver) {
                routeMutationObserver.disconnect();
            }
            routeMutationObserver = new MutationObserver(() => {
                scheduleRouteReadyCheck();
            });
            const target = document.body || document.documentElement;
            if (target) {
                routeMutationObserver.observe(target, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    characterData: true
                });
                scheduleRouteReadyCheck();
            }
        } else {
            scheduleRouteReadyCheck();
        }

        if (window.console && console.debug) {
            console.debug('WebViewMonitor SPA route change:', source, location.href);
        }
    }

    if (ENABLE_PAINT_TIMING && 'PerformanceObserver' in window) {
        try {
            new PerformanceObserver(list => {
                list.getEntries().forEach(entry => {
                    if (entry.name === 'first-paint') baseMetrics.fp = Math.round(entry.startTime);
                    if (entry.name === 'first-contentful-paint') baseMetrics.fcp = Math.round(entry.startTime);
                });
            }).observe({ type: 'paint', buffered: true });
        } catch (e) {}

        try {
            new PerformanceObserver(list => {
                const entries = list.getEntries();
                if (entries.length) {
                    baseMetrics.lcp = Math.round(entries[entries.length - 1].startTime);
                }
            }).observe({ type: 'largest-contentful-paint', buffered: true });
        } catch (e) {}

        try {
            new PerformanceObserver(list => {
                const first = list.getEntries()[0];
                if (first) {
                    baseMetrics.fid = Math.round(first.processingStart - first.startTime);
                }
            }).observe({ type: 'first-input', buffered: true });
        } catch (e) {}

        try {
            new PerformanceObserver(list => {
                list.getEntries().forEach(entry => {
                    if (!entry.hadRecentInput) clsValue += entry.value;
                });
            }).observe({ type: 'layout-shift', buffered: true });
        } catch (e) {}

        try {
            new PerformanceObserver(list => {
                list.getEntries().forEach(entry => {
                    lastLongTaskEnd = Math.max(lastLongTaskEnd, entry.startTime + entry.duration);
                });
            }).observe({ type: 'longtask', buffered: true });
        } catch (e) {}
    }

    window.addEventListener('load', reportInitialLoad, { once: true });
    document.addEventListener('DOMContentLoaded', reportInitialLoad, { once: true });

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(reportInitialLoad, 50);
    }

    let reportCount = 0;
    pollTimerId = setInterval(() => {
        reportCount += 1;
        reportInitialLoad();
        if (reportCount >= 8) {
            clearPollTimer();
        }
    }, 3000);

    installSpaRouteHooks();

    window.onerror = function(msg) {
        if (window.WebViewMonitor && window.WebViewMonitor.reportError) {
            window.WebViewMonitor.reportError(msg || 'unknown js error');
        }
    };
})();
""".trimIndent()
}
