// The Central Chat example — two screens, and the only file worth reading.
//
// Screen 1 takes one key — a channel key or a mint user key, the same single
// argument the library takes. Screen 2 stands in for your app, and opens the
// chat. The whole integration is three lines;
// everything else here is the box around them:
//
//   CentralChat.init(this, entry)
//   CentralChat.show(this)
//   CentralChat.hide()
//
// YOUR app has no sign-in screen like this one. It asks its own backend for a
// mint user key on every start — the key names the person and expires in
// minutes — and calls init() with it. Screen 1 exists so you can paste one by
// hand and watch it work before you write any of that.
package chat.central.widget.example

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import chat.central.widget.CentralChat
import chat.central.widget.CentralChatErrorCode

// Plain prefs, and plaintext on purpose: this is a demo convenience so the
// second launch skips the typing. A real app stores NOTHING — it mints a fresh
// key every start, which is both safer and simpler than keeping one.
private const val PREFS = "centralchat.example"
private const val ENTRY_KEY = "entry"

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private var status: TextView? = null

    /**
     * The chat's own button, kept off until the chat says it is warm.
     *
     * Enabled by onReady and disabled again by onError, so it is never offered
     * for a chat that cannot open. That is the demo's half of the rule the
     * library enforces on its side: show() refuses a failed chat rather than
     * presenting a browser error page.
     */
    private var openButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        CentralChat.onReady = {
            Log.d("CentralChat", "warm")
            runOnUiThread {
                openButton?.isEnabled = true
                status?.text = ""
            }
        }
        CentralChat.onError = { error ->
            Log.e("CentralChat", "${error.code}: ${error.message}")
            runOnUiThread {
                openButton?.isEnabled = false
                status?.text = "${error.code}: ${error.message}"
                // A refused key is the only failure this screen acts on: your
                // app answers it by minting a new one, and this demo has no
                // backend, so it asks for another by hand.
                //
                // Everything else — no network, a stalled load — the library
                // retries by itself until it works, and onReady re-enables the
                // button. There is nothing for an app to do about it.
                if (error.code == CentralChatErrorCode.ENTRY_INVALID ||
                    error.code == CentralChatErrorCode.TOKEN_EXPIRED
                ) {
                    logOut()
                }
            }
        }

        // An entry handed in without typing:
        //   adb shell am start -n chat.central.widget.example/.MainActivity \
        //     --es entry "<channel key or mint user key>"
        val handed = intent.getStringExtra(ENTRY_KEY)?.trim().orEmpty()
        if (handed.isNotEmpty()) logIn(handed) else {
            val remembered = prefs().getString(ENTRY_KEY, "").orEmpty()
            if (remembered.isNotEmpty()) logIn(remembered) else showLogin()
        }
    }

    /**
     * Step 1 of the integration. Warms everything — the page, the session, the
     * keys and the conversation — so that show() has nothing left to fetch.
     *
     * Safe to call on every start: the same entry twice does nothing.
     */
    private fun logIn(entry: String) {
        prefs().edit().putString(ENTRY_KEY, entry).apply()
        // The screen goes up FIRST: a refused entry answers onError before
        // init() returns, and whatever that handler puts on screen has to be
        // the last word — not something this line then paints over.
        showApp()
        CentralChat.init(this, entry)
    }

    /**
     * Back to the sign-in screen, as a different visitor.
     *
     * reset() rather than hide(): the session lives on the device, so without it
     * the same channel key walks straight back into the conversation that is
     * already there. "Another experience" has to mean another visitor.
     */
    private fun logOut() {
        prefs().edit().remove(ENTRY_KEY).apply()
        CentralChat.reset(this)
        openButton = null
        showLogin()
    }

    // ---- screen 1: sign in ---------------------------------------------------

    private fun showLogin() {
        // One field, because the library takes one argument. A channel key and a
        // mint user key are told apart by the page itself — `|` is not in the
        // alphabet a key is spelled with — so asking twice would invent a
        // distinction the API does not have.
        val entryField = field("Channel key or mint user key")

        status = label("", 14f)
        root.replaceWith(
            label("Central.chat test demo app", 26f, bold = true),
            label("Channel key or mint user key", 13f, bold = true),
            entryField,
            Button(this).apply {
                text = "Test"
                setOnClickListener {
                    val entry = entryField.text.toString().trim()
                    if (entry.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Paste a key", Toast.LENGTH_SHORT).show()
                    } else {
                        logIn(entry)
                    }
                }
            },
            status!!,
        )
    }

    // ---- screen 2: your app --------------------------------------------------

    private fun showApp() {
        status = label("", 14f)
        val open = Button(this).apply {
            text = "Open central.chat"
            // Off until onReady says otherwise: a button that opens nothing is
            // worse than no button. The library retries a failed load by itself,
            // so there is nothing here to drive that.
            isEnabled = false
            // Step 2. Nothing is fetched here; init() already did it.
            setOnClickListener { CentralChat.show(this@MainActivity) }
        }
        openButton = open
        root.replaceWith(
            label("Your app will be here", 24f, bold = true).apply { gravity = Gravity.CENTER },
            label(
                "The chat is already loaded and waiting behind this screen, " +
                    "which is what makes opening it instant.",
                14f,
            ).apply { gravity = Gravity.CENTER },
            spacer(),
            open,
            Button(this).apply {
                text = "Test another experience"
                setOnClickListener { logOut() }
            },
            status!!,
        )
    }

    // ---- the box -------------------------------------------------------------

    private fun LinearLayout.replaceWith(vararg children: View) {
        removeAllViews()
        val pad = dp(24)
        setPadding(pad, dp(48), pad, pad)
        for (child in children) {
            // A child that brought its own params keeps them; everything else
            // gets the default row.
            val params = child.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            addView(child, params)
        }
    }

    private fun field(hint: String) = EditText(this).apply {
        contentDescription = hint
        // Neither value has words in it: no autocorrect, no capitals.
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setSingleLine(false)
        maxLines = 3
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    // An explicit height, not a minimum: a bare View's default onMeasure ignores
    // wrap_content and takes every pixel the parent offers, which puts the
    // buttons under this one off the bottom of the screen.
    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(24))
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
