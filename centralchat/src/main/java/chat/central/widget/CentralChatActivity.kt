package chat.central.widget

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * The only screen this library ever shows.
 *
 * It owns the presentation so the host never has to: a WebView kept warm across
 * hide()/show() outlives whichever Activity displayed it, Android throws on a
 * naive reattach, and the picker launchers must be registered before the
 * Activity starts. All of that is solved once here instead of in every app.
 *
 * The library's own manifest declares it — nothing to add to yours.
 */
class CentralChatActivity : ComponentActivity() {

    // The page blocks on each of these, and each must be answered EXACTLY once:
    // never, and its composer hangs; twice, and the framework throws.
    private var files: ValueCallback<Array<Uri>>? = null

    // The MediaStore row handed to a camera app as its output, kept until the
    // result comes back so it can be delivered or cleaned up.
    private var capture: Uri? = null
    private var microphone: PermissionRequest? = null
    private var microphoneResources: Array<String> = emptyArray()
    private var location: GeolocationPermissions.Callback? = null
    private var locationOrigin: String = ""

    private lateinit var chooser: ActivityResultLauncher<Intent>
    private lateinit var micPermission: ActivityResultLauncher<String>
    private lateinit var locationPermission: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both registered unconditionally and before any early return below:
        // registerForActivityResult must run before the Activity is STARTED.
        chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val shot = capture
            capture = null
            val ok = result.resultCode == Activity.RESULT_OK
            // A camera app signals success by filling the row it was given; it
            // returns no data of its own, so parseResult would answer "nothing".
            if (ok && shot != null && result.data?.data == null) {
                deliverFiles(arrayOf(shot))
                return@registerForActivityResult
            }
            // Cancelled, or a pick after all: the empty row would linger in the
            // gallery as a 0-byte photo.
            if (shot != null) runCatching { contentResolver.delete(shot, null, null) }
            deliverFiles(
                if (ok) WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data) else null,
            )
        }
        micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            answerMicrophone(granted)
        }
        // Either grade of location will do — the page asks for a position, not
        // for a precision, and a person who offers only the approximate one has
        // still said yes.
        locationPermission =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                answerLocation(grants.values.any { it })
            }

        val view = CentralChat.webView
        if (view == null) {
            // show() before init(), or a teardown raced it. Finishing is honest.
            finish()
            return
        }

        // Content painted under the system bars, then padded clear of them —
        // without the padding the page's own 100dvh composer renders exactly
        // where the navigation bar sits. The keyboard is the same class of inset.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        (view.parent as? ViewGroup)?.removeView(view)
        // The page fills the screen and has no chrome of its own, so the way out
        // is the host's to provide. Top end, where the chat's header is empty.
        val close = TextView(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            contentDescription = "Close the chat"
            // Explicit: the chat's header is light whatever the host app's theme
            // is, and an inherited dark-theme text colour disappears into it.
            setTextColor(0xFF3C4043.toInt())
            setOnClickListener { CentralChat.hide() }
        }
        val touchTarget = (48 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            addView(
                view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                close,
                FrameLayout.LayoutParams(touchTarget, touchTarget, Gravity.TOP or Gravity.END),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { padded, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            padded.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(container)

        // Back closes the top layer inside the chat — a photo, the message
        // actions — and only leaves when there is none. The WebView is pinned to
        // one URL, so the only history entry that can exist is the guard the
        // page pushes while a layer is open: canGoBack() answers "is something
        // open", not "is there a previous page".
        onBackPressedDispatcher.addCallback(this) {
            if (view.canGoBack()) view.goBack() else CentralChat.hide()
        }

        CentralChat.applyVisibility()
        current = this
    }

    override fun onDestroy() {
        // Detached, never destroyed: the next show() reattaches this same warm
        // instance, which is what makes re-entry instant.
        (CentralChat.webView?.parent as? ViewGroup)?.removeView(CentralChat.webView)
        if (current === this) current = null
        super.onDestroy()
    }

    internal fun pickFiles(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean {
        deliverFiles(null)                 // a second request answers the first
        files = callback
        return try {
            // createIntent() honours accept types and multiple but NOT capture,
            // so the composer's camera button opens the gallery instead.
            chooser.launch(captureIntent(params) ?: params.createIntent())
            true
        } catch (notFound: android.content.ActivityNotFoundException) {
            deliverFiles(null)             // no app on the device answers this intent
            true
        }
    }

    /**
     * The camera button's intent, or null when this request is an ordinary pick.
     *
     * The photo is written to a MediaStore row rather than through a
     * FileProvider: a provider would need a manifest entry and an XML resource
     * in the *host app*, which is exactly the integration cost this library
     * exists to remove. A device that refuses the row (below API 29 it wants a
     * storage permission) falls back to the gallery.
     */
    private fun captureIntent(params: WebChromeClient.FileChooserParams): Intent? {
        if (!params.isCaptureEnabled) return null
        val accepts = params.acceptTypes.filter { it.isNotEmpty() }
        if (accepts.isNotEmpty() && accepts.all { it.startsWith("video/") }) {
            return Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        }
        val row = runCatching {
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply { put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") },
            )
        }.getOrNull() ?: return null
        capture = row
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, row)
    }

    internal fun requestLocation(origin: String, callback: GeolocationPermissions.Callback) {
        answerLocation(false)              // never abandon a previous request
        location = callback
        locationOrigin = origin
        locationPermission.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    internal fun requestMicrophone(request: PermissionRequest, resources: Array<String>) {
        answerMicrophone(false)            // never abandon a previous request
        microphone = request
        microphoneResources = resources
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun deliverFiles(uris: Array<Uri>?) {
        files?.onReceiveValue(uris)
        files = null
    }

    private fun answerLocation(granted: Boolean) {
        val callback = location ?: return
        location = null
        // `retain` stays false: remembering the answer is the page's business,
        // and a WebView that outlives the screen must not carry a decision the
        // person made for one visit into every later one.
        callback.invoke(locationOrigin, granted, false)
    }

    private fun answerMicrophone(granted: Boolean) {
        val request = microphone ?: return
        microphone = null
        if (granted) request.grant(microphoneResources) else request.deny()
    }

    companion object {
        private var current: CentralChatActivity? = null

        internal fun currentOrNull(): CentralChatActivity? = current

        internal fun finishCurrent() {
            current?.finish()
        }
    }
}
