package com.tasteradar.app

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Everything you do by hand, which is deliberately very little: say where the
 * server is, grant the permissions, type a want. The app's real work happens
 * while this screen is closed.
 *
 * Views built in code - no Compose, no XML layouts. A settings form is not worth
 * a compiler plugin.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var wants: TextView
    private lateinit var serverField: EditText
    private lateinit var tokenField: EditText
    private lateinit var wantField: EditText
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        root.addView(heading("Taste Radar"))

        status = body()
        root.addView(status)

        root.addView(label("Server"))
        serverField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(Config.serverUrl(this@MainActivity))
        }
        root.addView(serverField)

        root.addView(label("Token"))
        tokenField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "the server's AUTH_TOKEN"
            setText(Config.authToken(this@MainActivity))
        }
        root.addView(tokenField)
        root.addView(button("Save and check") { saveServer() })

        warning = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.rgb(180, 60, 0))
        }
        root.addView(warning)

        root.addView(label("Permissions"))
        root.addView(button("Grant location and notifications") { askPermissions() })

        root.addView(label("What do you want?"))
        wantField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "craving a proper beef fry, under 500 a head"
        }
        root.addView(wantField)
        root.addView(button("Add want") { addWant() })

        wants = body()
        root.addView(wants)

        root.addView(label("Fences"))
        root.addView(button("Redraw around me now") { refreshNow() })
        root.addView(button("Re-register from cache") {
            thread {
                val ok = Fences.reregisterFromCache(this)
                runOnUiThread { toast(if (ok) "re-registered" else "nothing cached yet") }
            }
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        RefreshWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        loadWants()
        // Opening the app is a good moment to try the queue again.
        if (FeedbackQueue.size(this) > 0) FeedbackWorker.schedule(this)
    }

    // ---- views ----

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 40, 0, 8)
    }

    private fun body() = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Color.DKGRAY)
        setPadding(0, 16, 0, 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    // ---- actions ----

    private fun refreshStatus() {
        val fences = FenceCache.fences(this)
        val umbrella = FenceCache.umbrella(this)
        val savedAt = FenceCache.savedAt(this)
        val alertsToday = FenceCache.alertTimes(this).size

        status.text = buildString {
            append("Location: ").append(if (Fences.hasLocationPermission(this@MainActivity)) "granted" else "NOT granted").append('\n')
            append("Background: ").append(if (Fences.hasBackgroundPermission(this@MainActivity)) "granted" else "NOT granted").append('\n')
            append("Fences held: ").append(fences.size)
            if (umbrella != null) append(" + umbrella ").append(umbrella.radiusM.toInt()).append("m")
            append('\n')
            append("Last redrawn: ")
            append(if (savedAt == 0L) "never" else android.text.format.DateUtils.getRelativeTimeSpanString(savedAt))
            append('\n')
            append("Alerts in the last 2 days: ").append(alertsToday).append('\n')
            warning.text = when {
                Config.authToken(this@MainActivity).isBlank() ->
                    "No token set. The server refuses every request without one, " +
                        "and with no AUTH_TOKEN it only listens on loopback."
                Config.exposure(this@MainActivity) == Config.Exposure.PUBLIC_CLEARTEXT ->
                    "Plain HTTP to a PUBLIC address. The token is readable by anyone " +
                        "between you and it. Put the server behind HTTPS before using this."
                Config.exposure(this@MainActivity) == Config.Exposure.PRIVATE_CLEARTEXT ->
                    "Plain HTTP on your own network: the token is sniffable by anyone " +
                        "on the same wifi. Fine at home, worth a tunnel anywhere else."
                else -> ""
            }
            val queued = FeedbackQueue.size(this@MainActivity)
            if (queued > 0) {
                append("Feedback waiting to send: ").append(queued).append('\n')
            }
            if (fences.isNotEmpty()) {
                append('\n')
                fences.take(5).forEach { append("  ").append(it.score).append("  ").append(it.name).append('\n') }
                if (fences.size > 5) append("  ... and ").append(fences.size - 5).append(" more\n")
            }
        }
    }

    private fun saveServer() {
        Config.setServerUrl(this, serverField.text.toString())
        Config.setAuthToken(this, tokenField.text.toString())
        refreshStatus()
        thread {
            val message = runCatching { Api.health(this) }.fold(
                { "server reachable" },
                { e ->
                    // 401 is worth naming: it means reachable but the token is
                    // wrong, which is a completely different fix from unreachable.
                    when {
                        e is Api.ApiException && e.status == 401 ->
                            "reached it, but the token is wrong"
                        e is Api.ApiException && e.status == 429 ->
                            "locked out after too many wrong tokens - wait a few minutes"
                        else -> "cannot reach it: ${e.message}"
                    }
                },
            )
            runOnUiThread { toast(message) }
        }
    }

    private fun askPermissions() {
        if (!Fences.hasLocationPermission(this)) {
            val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            requestPermissions(wanted.toTypedArray(), 1)
            return
        }
        // Background location must be its own request, and from Android 11 the
        // system shows no dialog at all - it sends you to Settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Fences.hasBackgroundPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            toast("Choose \"Allow all the time\"")
        } else {
            toast("Already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == 1) askPermissions()
    }

    private fun addWant() {
        val text = wantField.text.toString().trim()
        if (text.length < 3) {
            toast("Say a bit more than that")
            return
        }
        thread {
            runCatching { Api.createWant(this, text) }
                .onSuccess { restated ->
                    runOnUiThread {
                        wantField.setText("")
                        toast("Added: $restated")
                        loadWants()
                    }
                    // A new want has no scores until the worker has run, so there
                    // is nothing to redraw yet. Say so rather than appear broken.
                }
                .onFailure { e -> runOnUiThread { toast("Failed: ${e.message}") } }
        }
    }

    private fun loadWants() {
        thread {
            val text = runCatching { Api.listWants(this) }.fold(
                { list ->
                    if (list.isEmpty()) "No wants yet. The app will stay silent until there is one."
                    else list.joinToString("\n") { "  - $it" }
                },
                { e -> "Could not load wants: ${e.message}" },
            )
            runOnUiThread { wants.text = text }
        }
    }

    private fun refreshNow() {
        if (!Fences.hasLocationPermission(this)) {
            toast("Grant location first")
            return
        }
        toast("Asking the server...")
        thread {
            val message = Fences.refreshHere(this).fold(
                { set ->
                    if (set.fences.isEmpty()) {
                        "Nothing matches nearby. Has the scoring worker run?"
                    } else {
                        "Watching ${set.fences.size} places"
                    }
                },
                { e -> "Failed: ${e.message}" },
            )
            runOnUiThread {
                toast(message)
                refreshStatus()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
