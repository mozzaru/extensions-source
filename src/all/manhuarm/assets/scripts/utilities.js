// Manhuarm utilities.js
//
// This script is injected into the chapter page WebView. The website uses an
// anti-scraping check that calls `Function.prototype.toString.call(fn)` on a
// number of globals (XMLHttpRequest, setTimeout, setInterval, Worker) and
// expects the result to contain "[native code]". If any of those checks fail,
// the page assumes a scraper is in play and only sends a fake OCR call -
// so we have to fool every one of those checks.
//
// The trick is to patch `Function.prototype.toString` itself to return
// "function <name>() { [native code] }" for any non-native function we
// install. That way every toString check returns the expected string and the
// anti-scraping code thinks we are a clean browser.

// --- 1. Patch Function.prototype.toString first ---------------------------
// This MUST be the very first thing we do, before any of our other patches,
// otherwise the website can spot that Function.prototype.toString is
// not native by calling it on itself.
(function patchFunctionToString() {
    const NATIVE_NAME_OVERRIDES = new WeakMap();
    NATIVE_NAME_OVERRIDES.set(window.setTimeout, 'setTimeout');
    NATIVE_NAME_OVERRIDES.set(window.setInterval, 'setInterval');
    NATIVE_NAME_OVERRIDES.set(window.XMLHttpRequest, 'XMLHttpRequest');
    NATIVE_NAME_OVERRIDES.set(window.Worker, 'Worker');
    NATIVE_NAME_OVERRIDES.set(window.fetch, 'fetch');

    const PatchedToString = function toString() {
        // 'this' is the function we are being asked to stringify. We
        // want to return "function <name>() { [native code] }" for
        // everything we (or anyone else) have patched. We have no
        // reliable way to tell patched from native, so we just
        // return native for everything.
        let name;
        try {
            name = this && this.name ? this.name : '';
        } catch (e) { name = ''; }
        if (!name) {
            name = NATIVE_NAME_OVERRIDES.get(this) || 'anonymous';
        }
        return 'function ' + name + '() { [native code] }';
    };
    // Make PatchedToString itself look native so the website's check on
    // Function.prototype.toString returns "[native code]".
    Object.defineProperty(PatchedToString, 'name', { value: 'toString' });
    Function.prototype.toString = PatchedToString;
})();

// --- 2. fetch patch --------------------------------------------------------
// We delegate to the real fetch so the page can still make network requests.
const nativeFetch = window.fetch ? window.fetch.bind(window) : null;

function serializeHeaders(h) {
    if (!h) return '{}';
    if (h instanceof Headers) {
        const obj = {};
        h.forEach((v, k) => { obj[k] = v; });
        return JSON.stringify(obj);
    }
    return JSON.stringify(h);
}

if (nativeFetch) {
    window.fetch = function () {
        const input = arguments[0];
        const options = arguments[1] || {};
        const url = typeof input === 'string' ? input : (input.url || '');

        if (url && url.indexOf('fetch-ocr.php') !== -1) {
            let body = options.body;
            if (body && typeof body !== 'string') {
                try { body = JSON.stringify(body); } catch (e) { body = String(body); }
            }
            try {
                window.__manhuarmBridge && window.__manhuarmBridge.onFetch(
                    url, body || '', JSON.stringify(options.headers || {})
                );
            } catch (e) { /* ignore */ }
        }
        return nativeFetch.apply(window, arguments);
    };
    Object.defineProperty(window.fetch, 'name', { value: 'fetch' });
}

// --- 3. XHR patch ----------------------------------------------------------
// We wrap the native XMLHttpRequest with a Proxy that uses fetch() under
// the hood, and we expose setRequestHeader/open/send so the page can still
// talk to the OCR endpoint.
const NativeXHR = window.XMLHttpRequest;

function XHRProxy() {
    const xhr = new NativeXHR();
    const state = { method: '', url: '', body: null, headers: {} };

    return new Proxy(xhr, {
        get(target, prop) {
            if (prop === 'setRequestHeader') {
                return function (header, value) {
                    state.headers[header] = value;
                    return target.setRequestHeader(header, value);
                };
            }
            if (prop === 'open') {
                return function (method, url) {
                    state.method = method;
                    state.url = String(url || '');
                    return target.open(method, url);
                };
            }
            if (prop === 'send') {
                return function (body) {
                    state.body = body;
                    if (state.url && state.url.indexOf('fetch-ocr.php') !== -1) {
                        let payload = body;
                        if (payload && typeof payload !== 'string') {
                            try { payload = JSON.stringify(payload); } catch (e) { payload = String(payload); }
                        }
                        try {
                            // Pass the *captured* headers (not the empty
                            // default) - the server validates X-Gate-Token,
                            // X-Gate-Nonce and X-Gate-Timestamp and returns
                            // 403 if any are missing. The previous version
                            // of this script always sent '{}' here, which
                            // caused every captured request to be rejected
                            // with 403 by the server.
                            window.__manhuarmBridge && window.__manhuarmBridge.onFetch(
                                state.url, payload || '', JSON.stringify(state.headers || {})
                            );
                        } catch (e) { /* ignore */ }
                    }
                    return target.send(body);
                };
            }
            const val = target[prop];
            return typeof val === 'function' ? val.bind(target) : val;
        },
        set(target, prop, value) {
            target[prop] = value;
            return true;
        },
    });
}

XHRProxy.prototype = NativeXHR.prototype;
window.XMLHttpRequest = XHRProxy;
Object.defineProperty(window.XMLHttpRequest, 'name', { value: 'XMLHttpRequest' });

// --- 4. setTimeout / setInterval patches ----------------------------------
// The website checks that setTimeout/setInterval report "[native code]".
// Function.prototype.toString is patched, so these wrappers will look
// native. We still want to keep the timing-acceleration behaviour the
// upstream code had, in case the page relies on it.
const origSetTimeout = window.setTimeout;
const origSetInterval = window.setInterval;
window.setTimeout = function (callback, delay) {
    const args = Array.prototype.slice.call(arguments, 2);
    return origSetTimeout.apply(window, [callback, Math.max(1, Math.floor((delay || 0) * 0.01))].concat(args));
};
Object.defineProperty(window.setTimeout, 'name', { value: 'setTimeout' });
window.setInterval = function (callback, delay) {
    const args = Array.prototype.slice.call(arguments, 2);
    return origSetInterval.apply(window, [callback, Math.max(1, Math.floor((delay || 0) * 0.01))].concat(args));
};
Object.defineProperty(window.setInterval, 'name', { value: 'setInterval' });

// --- 5. Worker mock -------------------------------------------------------
// The page checks that Worker reports "[native code]" too. We replace it
// with a tiny mock that just runs the worker script inline (if it can be
// fetched) and stubs out postMessage/onmessage.
const NativeWorker = window.Worker;
function WorkerMock(scriptURL) {
    if (typeof scriptURL === 'string' && scriptURL.indexOf('blob:') === 0) {
        try {
            const xhr = new NativeXHR();
            xhr.open('GET', scriptURL, false);
            xhr.send();
            try { new Function(xhr.responseText)(); } catch (e) { /* ignore */ }
        } catch (e) { /* ignore */ }
    }
    return {
        onmessage: null,
        onerror: null,
        postMessage: function () { /* no-op */ },
        terminate: function () { /* no-op */ },
    };
}
WorkerMock.prototype = NativeWorker.prototype;
window.Worker = WorkerMock;
Object.defineProperty(window.Worker, 'name', { value: 'Worker' });
