// Central Chat — Android.
//
//   CentralChat.init(context, entry)   warms everything
//   CentralChat.show(context)          reveals it
//   CentralChat.hide()                 puts it away
//
// `entry` is the one thing the app supplies, and it names both the line and the
// visitor. See [CentralChat.init].
//
// The WebView is created once per process and never destroyed while the app
// lives. init() loads the page, the assets, the session, the keys and the
// conversation; show() only presents what is already there. The first show()
// should cost what the tenth does.
package chat.central.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

enum class CentralChatErrorCode { ENTRY_INVALID, TOKEN_EXPIRED, NETWORK, INTERNAL }

data class CentralChatError(val code: CentralChatErrorCode, val message: String)

private const val DEFAULT_CONTAINER_URL = "https://web.central.chat/widget/container.html"
private const val BRIDGE_NAME = "CentralChatNative"

// Generous against a cold renderer on a slow network. It is the backstop for
// every failure the layers below cannot see: without it a dropped handshake
// leaves the host with no success and no failure, forever.
private const val READY_TIMEOUT_MS = 20_000L

// Quick twice, then back off to every half minute. The early attempts catch the
// common case — one stalled fetch — and the tail keeps a backgrounded app from
// spending a battery on a network that is not coming back. A device that
// reconnects does not wait for any of it; see watchNetwork.
private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 8_000L, 15_000L, 30_000L)

/**
 * The whole public surface.
 *
 * Every method hops to the main thread if it is not already there — WebViews may
 * only be touched there, and calling init() straight from the background request
 * that fetched the token is the obvious mistake to make.
 */
object CentralChat {

    /** Optional. Set it once if you want to hear about failures. */
    @JvmStatic
    var onError: ((CentralChatError) -> Unit)? = null

    /** Optional. Fires once per init(), when the chat is warm and ready to show. */
    @JvmStatic
    var onReady: (() -> Unit)? = null

    fun interface ReadyListener { fun onReady() }

    fun interface ErrorListener { fun onError(error: CentralChatError) }

    /** The Java spelling of [onReady]. */
    @JvmStatic
    fun setOnReady(listener: ReadyListener?) {
        onReady = listener?.let { l -> { l.onReady() } }
    }

    /** The Java spelling of [onError]. */
    @JvmStatic
    fun setOnError(listener: ErrorListener?) {
        onError = listener?.let { l -> { error -> l.onError(error) } }
    }

    /**
     * The page the WebView loads. Set it before the first [init] to point at a
     * staging tier; the default is the production one and needs no configuration.
     */
    @JvmStatic
    var containerUrl: String = DEFAULT_CONTAINER_URL

    private val main = Handler(Looper.getMainLooper())

    internal var webView: WebView? = null
        private set

    // Captured at init(). The storage bridge answers on the JavaScript thread,
    // where no Activity is in reach.
    private var appContext: Context? = null

    private var entryInUse: String? = null
    private var pendingEntry: String? = null
    private var pageLoaded = false
    private var resolved = false

    /** How many times the current entry has been retried, for the backoff. */
    private var attempt = 0
    private var retryPending = false

    /** onError is announced ONCE per entry, however many retries follow it. */
    private var reported = false

    /**
     * The chat gave up on the current entry. Separate from `resolved`, which is
     * also true on success: this is the one that decides whether show() has
     * anything worth presenting.
     */
    private var failed = false

    // What show()/hide() asked for, kept here because the page is reloaded on
    // retry and the Activity can be recreated under it.
    private var shown = false

    private val timeout = Runnable {
        fail(CentralChatError(CentralChatErrorCode.INTERNAL, "the chat never became ready"))
    }

    /**
     * Warms the chat. Call it as early as you have an entry — app start is right.
     *
     * [entry] is either door:
     *  - `"<businessId>|<chatAccountId>"` — an **anonymous** visitor on that line.
     *    Both ids are public; nothing is signed and no backend of yours is in the
     *    loop. A line configured `onlyValidatedUsers` refuses this door.
     *  - an **App Entry JWT** your backend minted — a **verified** visitor. The
     *    line comes out of the token's `bid`/`cid` protected header, so the ids
     *    are not passed separately. A token always wins over the ids.
     *
     * `|` is not in the base64url alphabet a JWT is spelled with, so the two can
     * never be confused.
     *
     * The same entry twice is a no-op: throwing away a warm thread to arrive at
     * the identical session buys nothing. A different one is a different person,
     * and does re-enter.
     *
     * [context] may be anything; only its application context is kept, because
     * the WebView outlives every Activity that shows it.
     */
    @JvmStatic
    fun init(context: Context, entry: String): Unit = onMain {
        // Refused, never thrown: an entry is a value that arrives at runtime —
        // a mint your backend returned, a key somebody pasted — and a library
        // that crashes its host over one turns their bad input into your
        // stack trace. ENTRY_INVALID is the same answer the page gives for
        // every other entry it will not take, so a host handles one case.
        if (entry.isEmpty() || entry.startsWith("|") || entry.endsWith("|")) {
            refuse("not an App Entry jwt or a businessId|chatAccountId")
            return@onMain
        }
        if (webView != null && entry == entryInUse) {
            if (resolved) onReady?.invoke()
            return@onMain
        }

        entryInUse = entry
        pendingEntry = entry
        resolved = false
        failed = false
        attempt = 0
        reported = false
        cancelRetry()
        watchNetwork(context)
        restartTimeout()

        appContext = context.applicationContext
        val view = webView ?: create(context.applicationContext).also { webView = it }
        if (pageLoaded) sendInit(view) else view.loadUrl(containerUrl)
    }

    /** The anonymous door, spelled out: the same as `init(context, "$businessId|$chatAccountId")`. */
    @JvmStatic
    fun init(context: Context, businessId: String, chatAccountId: String): Unit =
        init(context, "$businessId|$chatAccountId")

    /**
     * Presents the warm chat. Nothing is fetched here — that already happened.
     * Safe to call before the chat is ready: the screen appears and fills in.
     */
    @JvmStatic
    fun show(context: Context): Unit = onMain {
        checkNotNull(webView) { "CentralChat.init() must be called before show()" }
        // A chat that failed has nothing to present but the WebView's own error
        // page — a browser screen, inside an app, with no way back and no reload.
        // Refusing is the honest answer: onError already told the host, and the
        // host's own screen is the right place to offer another go.
        //
        // Still loading is NOT failed: that case presents and fills in, which is
        // the whole point of warming.
        if (failed) {
            onError?.invoke(CentralChatError(CentralChatErrorCode.NETWORK, "the chat is not available"))
            return@onMain
        }
        shown = true
        val intent = Intent(context, CentralChatActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Puts it away, keeping the session, the thread and the scroll position.
     * The WebView is detached, never destroyed — which is what makes the next
     * show() instant.
     */
    @JvmStatic
    fun hide(): Unit = onMain {
        shown = false
        if (pageLoaded) webView?.evaluateJavascript("CentralChat.hide()", null)
        CentralChatActivity.finishCurrent()
    }

    /**
     * Ends the session and forgets everything about this visitor.
     *
     * The next [init] starts a stranger: a new anonymous visitor on the same
     * channel key, or a clean slate for another person's token. Without this,
     * re-entering the SAME entry resumes the session that is already on the
     * device — which is right for an app restart and wrong for a sign-out.
     *
     * Call it from your own logout. Everything goes: the keystore this library
     * keeps the session and the device's encryption keys in, and the WebView's
     * own storage and cookies.
     *
     * It is not undoable, and it is not a way to "refresh" — an anonymous
     * visitor's history lives on the device, so this discards their thread.
     */
    @JvmStatic
    fun reset(context: Context): Unit = onMain {
        hide()
        entryInUse = null
        pendingEntry = null
        resolved = false
        failed = false
        attempt = 0
        reported = false
        pageLoaded = false
        cancelRetry()
        unwatchNetwork()
        main.removeCallbacks(timeout)

        // Destroyed rather than reloaded: a WebView carries the session in
        // memory as well as on disk, and only a fresh one is honestly signed
        // out. This is the single place in the library that destroys it.
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.loadUrl("about:blank")
            view.clearHistory()
            view.clearCache(true)
            view.destroy()
        }
        webView = null

        android.webkit.WebStorage.getInstance().deleteAllData()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        SecureStorage.clear(context)
    }

    // ---- machinery ----------------------------------------------------------

    /** The one origin this WebView is allowed to stay on. */
    private val containerHost: String get() = Uri.parse(containerUrl).host.orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView = WebView(context).apply {
        // chrome://inspect, but only from a debuggable build of the host app —
        // never in the one their customers install.
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true               // the session survives restarts
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        addJavascriptInterface(Bridge, BRIDGE_NAME)
        webChromeClient = ChromeClient()
        webViewClient = PageClient()
    }

    // onPageFinished, not onPageStarted: CentralChat.init is defined by the
    // page's own inline script, so the document has to have been parsed.
    private fun sendInit(view: WebView) {
        val entry = pendingEntry ?: return
        pendingEntry = null
        // The capability goes in the SAME evaluate, ahead of init: the page reads
        // it when it mounts, and a second evaluate could land after that.
        //
        // Guarded, and not optionally so: the page ships on the CDN
        // independently of this app, so a library is ALWAYS newer than some
        // container out there. Calling a method an older page does not define
        // throws before init() on the same line, and the chat never starts —
        // which is a blank screen and a 20-second INTERNAL, from a feature that
        // was meant to be optional.
        //
        // JSONObject.quote does the escaping; nothing is spliced into JavaScript.
        view.evaluateJavascript(
            "if(CentralChat.useNativeStorage)CentralChat.useNativeStorage();" +
                "CentralChat.init(${JSONObject.quote(entry)})",
            null,
        )
    }

    /** Answer one storage request. Runs on the JavaScript thread; replies on main. */
    private fun answerStorage(event: JSONObject) {
        val rid = event.optString("rid")
        if (rid.isEmpty()) return
        val context = appContext
        val payload = event.optJSONObject("payload")
        if (context == null || payload == null) {
            replyStorage(rid, ok = false, body = JSONObject.quote("no storage on this host"))
            return
        }
        val body = runCatching {
            when (payload.optString("op")) {
                "get" -> JSONObject().put("value", SecureStorage.get(context, payload.getString("key")))
                // The batch a boot actually sends: sixty keys as one frame
                // rather than sixty round trips before the first paint.
                "getMany" -> {
                    val keys = payload.getJSONArray("keys")
                    val wanted = (0 until keys.length()).map { keys.getString(it) }
                    // Positional and exactly as long as what was asked for —
                    // JSONArray.put(null) would shorten the array, so a miss is
                    // JSONObject.NULL.
                    val values = JSONArray()
                    for (value in SecureStorage.getMany(context, wanted)) {
                        values.put(value ?: JSONObject.NULL)
                    }
                    JSONObject().put("values", values)
                }
                "setMany" -> {
                    val entries = payload.getJSONArray("entries")
                    SecureStorage.setMany(
                        context,
                        (0 until entries.length()).map {
                            val pair = entries.getJSONArray(it)
                            pair.getString(0) to pair.getString(1)
                        },
                    )
                    JSONObject()
                }
                "set" -> {
                    SecureStorage.set(context, payload.getString("key"), payload.getString("value"))
                    JSONObject()
                }
                "remove" -> {
                    SecureStorage.remove(context, payload.getString("key"))
                    JSONObject()
                }
                "list" -> JSONObject().put(
                    "keys",
                    JSONArray(SecureStorage.list(context, payload.optString("prefix"))),
                )
                else -> throw IllegalArgumentException("unknown storage op")
            }
        }.getOrElse { failure ->
            // A keystore that cannot answer has told the truth, and the widget
            // treats a refusal as an answer. Swallowing it into an empty value
            // would look like "no session" and silently sign the visitor out.
            replyStorage(rid, ok = false, body = JSONObject.quote(failure.message ?: "storage failed"))
            return
        }
        replyStorage(rid, ok = true, body = body.toString())
    }

    private fun replyStorage(rid: String, ok: Boolean, body: String): Unit = onMain {
        webView?.evaluateJavascript(
            "CentralChat.storageResult(${JSONObject.quote(rid)},$ok,$body)",
            null,
        )
    }

    /**
     * Replays the current visibility into the page.
     *
     * show() is allowed before the chat is ready, and the Activity can be
     * recreated at any time, so the page is never the record of what is shown —
     * [shown] is, and this is how the page catches up with it.
     */
    internal fun applyVisibility() {
        val view = webView ?: return
        if (!pageLoaded) return
        view.evaluateJavascript(if (shown) "CentralChat.show()" else "CentralChat.hide()", null)
    }

    private fun restartTimeout() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, READY_TIMEOUT_MS)
    }

    private fun ready(): Unit = onMain {
        main.removeCallbacks(timeout)
        if (resolved) return@onMain
        resolved = true
        failed = false
        attempt = 0
        reported = false
        cancelRetry()
        onReady?.invoke()
    }

    /**
     * Turn away an entry this library can see is malformed, without loading
     * anything. Nothing is retried and nothing is warmed: the next [init] with
     * a usable entry starts clean.
     */
    private fun refuse(message: String) {
        main.removeCallbacks(timeout)
        cancelRetry()
        entryInUse = null
        pendingEntry = null
        resolved = true
        failed = true
        reported = true
        onError?.invoke(CentralChatError(CentralChatErrorCode.ENTRY_INVALID, message))
    }

    /**
     * A transport failure is retried, on a backoff, until it works.
     *
     * The page's HTML and its scripts come from two origins, each with its own
     * DNS and TLS; a stalled fetch leaves a document that looks healthy and
     * never boots. Most of the time the next attempt just works — and when it
     * does not, the cause is usually the phone rather than us, so the library
     * keeps trying instead of making the app write a retry button. That is what
     * [watchNetwork] shortens: a device that comes back online retries at once
     * rather than waiting out the backoff.
     *
     * A REFUSED ENTRY is never retried. The same ids are rejected identically
     * and the same expired token stays expired; minting another is the host's
     * call, not ours, and a loop around it would be a loop that never ends.
     *
     * [onError] is announced once per entry, not once per attempt — a chat that
     * is quietly reconnecting should not shout every thirty seconds.
     */
    internal fun fail(error: CentralChatError): Unit = onMain {
        main.removeCallbacks(timeout)
        if (resolved) return@onMain
        resolved = true
        failed = true
        pageLoaded = false
        // The renderer is showing the WebView's own error page by now. Nothing
        // presents it — show() refuses a failed chat — but the next attempt
        // reuses this same view, so it must not start from somebody else's error.
        webView?.loadUrl("about:blank")

        val permanent = error.code == CentralChatErrorCode.ENTRY_INVALID ||
            error.code == CentralChatErrorCode.TOKEN_EXPIRED
        if (!reported) {
            reported = true
            onError?.invoke(error)
        }
        if (!permanent) scheduleRetry()
    }

    private val retry = Runnable {
        retryPending = false
        val view = webView ?: return@Runnable
        val entry = entryInUse ?: return@Runnable
        resolved = false
        pageLoaded = false
        pendingEntry = entry
        view.loadUrl(containerUrl)
        restartTimeout()
    }

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        val delay = RETRY_BACKOFF_MS[minOf(attempt, RETRY_BACKOFF_MS.lastIndex)]
        attempt++
        main.postDelayed(retry, delay)
    }

    private fun cancelRetry() {
        retryPending = false
        main.removeCallbacks(retry)
    }

    /**
     * Retry the moment the device has a network again.
     *
     * Without this a visitor who was in a lift waits out whatever the backoff
     * had reached — up to half a minute of a chat that could already be open.
     * `ACCESS_NETWORK_STATE` is what it costs: a normal permission, never
     * prompted for, declared by this library so no app has to.
     */
    private fun watchNetwork(context: Context) {
        if (networkCallback != null) return
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onMain {
                    if (!failed || entryInUse == null) return@onMain
                    // Back to the front of the backoff: this is new information,
                    // not another tick of the same wait.
                    cancelRetry()
                    attempt = 0
                    scheduleRetry()
                }
            }
        }
        // Registration itself can throw on a device with the service missing or
        // a callback limit reached; neither is worth taking the chat down for.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Every chat event arrives here as one JSON string. */
    private object Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val event = runCatching { JSONObject(json) }.getOrNull() ?: return
            val payload = event.optJSONObject("payload")
            when (event.optString("type")) {
                // Not an event: a REQUEST, and the only frame this bridge has to
                // answer rather than observe.
                "storage" -> answerStorage(event)
                "ready" -> ready()
                // The page never navigates itself away from the bundle: it
                // turns a tapped link into this event and waits for the host
                // to do something about it. Nothing here means a dead link.
                "linkActivated" -> {
                    val url = payload?.optString("url").orEmpty()
                    val context = appContext
                    if (url.isNotEmpty() && context != null) {
                        openExternally(context, Uri.parse(url))
                    }
                }
                "error" -> fail(
                    CentralChatError(
                        runCatching { CentralChatErrorCode.valueOf(payload?.optString("code").orEmpty()) }
                            .getOrDefault(CentralChatErrorCode.INTERNAL),
                        payload?.optString("message").orEmpty(),
                    ),
                )
            }
        }
    }

    /**
     * Hand a URL to the browser. Wrapped because a device can be without one —
     * a kiosk, a stripped image — and an ActivityNotFoundException thrown out
     * of a WebView callback would take the host app down over a tapped link.
     */
    private fun openExternally(context: Context, url: Uri) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private class PageClient : WebViewClient() {
        // Pinned to one origin: anything else is a link inside a message, and
        // belongs in the browser, not in a WebView holding a live session.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == containerHost) return false
            openExternally(view.context, request.url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageLoaded = true
            sendInit(view)
            applyVisibility()
        }

        // A font or an image failing is not the page failing; only the document is.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) fail(CentralChatError(CentralChatErrorCode.NETWORK, "could not load the chat"))
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse,
        ) {
            if (request.isForMainFrame) {
                fail(CentralChatError(CentralChatErrorCode.NETWORK, "could not load the chat"))
                return
            }
            // The chat itself is a SUBFRAME, and the line it is asked for is in
            // its path — so this is where a channel key that names no line
            // shows up, as a 404 on an inner document nobody else is watching.
            // Without it the entry is only caught by the ready timeout: twenty
            // seconds, reported as INTERNAL, and retried forever because
            // nothing said it was permanent.
            if (request.url.host != containerHost) return
            if (!request.url.path.orEmpty().endsWith("/host.html")) return
            if (response.statusCode !in 400..499) return
            fail(CentralChatError(CentralChatErrorCode.ENTRY_INVALID, "no line answers this entry"))
        }

        // Returning true says this was handled — without it a renderer killed
        // under memory pressure takes the whole host app down with it.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            pageLoaded = false
            fail(CentralChatError(CentralChatErrorCode.NETWORK, "the chat renderer stopped"))
            return true
        }
    }

    /**
     * What the page asks of its host rather than of the bridge: the file chooser
     * behind `<input type="file">`, and the microphone behind `getUserMedia`.
     * Without a WebChromeClient a WebView answers both with nothing — the tap
     * simply dies.
     */
    private class ChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            val activity = CentralChatActivity.currentOrNull()
            if (activity == null) {
                // Nothing on screen to host a picker — the WebView outlives any one
                // Activity. Answering "no files" is the deterministic end: the page
                // waits on an input whose promise settles only on change or cancel,
                // and an unanswered request hangs its composer for good.
                callback.onReceiveValue(null)
                return true
            }
            return activity.pickFiles(params, callback)
        }

        // Sharing a location. Unanswered, the page's own promise never settles
        // and its composer waits for good — the same contract as the two above.
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            val activity = CentralChatActivity.currentOrNull()
            if (activity == null) {
                callback.invoke(origin, false, false)
                return
            }
            activity.requestLocation(origin, callback)
        }

        // The microphone, and only the microphone: a camera photo arrives through
        // the file chooser above, which needs no permission at all.
        override fun onPermissionRequest(request: PermissionRequest) {
            val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            val activity = CentralChatActivity.currentOrNull()
            if (audio.isEmpty() || activity == null) {
                request.deny()
                return
            }
            activity.requestMicrophone(request, audio.toTypedArray())
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
