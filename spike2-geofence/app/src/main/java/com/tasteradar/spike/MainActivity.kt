package com.tasteradar.spike

import android.Manifest
import android.annotation.SuppressLint
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * No layouts, no Compose, one dependency. The spike answers two questions:
 *   1. do geofence alerts fire at all, on YOUR phone, with YOUR launcher killed
 *   2. how late are they
 * Everything else is noise.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Geofence spike"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 20, 0, 20)
        }
        root.addView(status)

        root.addView(button("1. Grant location permissions") { askPermissions() })
        root.addView(button("2. Drop a fence here") { dropFenceHere() })
        root.addView(button("3. Register all fences") { registerAll() })
        root.addView(button("Show log") { showLog() })
        root.addView(button("Clear fences and log") { clearAll() })

        log = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(0, 30, 0, 0)
        }
        root.addView(log)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        refreshStatus()
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun refreshStatus() {
        val fences = FenceStore.all(this)
        status.text = buildString {
            append("Fine location: ").append(if (Fences.hasLocationPermission(this@MainActivity)) "yes" else "NO").append("\n")
            append("Background location: ").append(if (Fences.hasBackgroundPermission(this@MainActivity)) "yes" else "NO").append("\n")
            append("Fences: ").append(fences.size).append(" of ").append(FenceStore.MAX).append("\n")
            fences.forEach { append("  ").append(it.name).append("\n") }
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!Fences.hasLocationPermission(this)) {
            requestPermissions(wanted.toTypedArray(), 1)
            return
        }
        // Background location must be a SEPARATE request, and from Android 11 the
        // system shows no dialog at all - it sends the user to Settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Fences.hasBackgroundPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            toast("Choose \"Allow all the time\"")
        } else {
            toast("Permissions already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == 1) askPermissions()
    }

    @SuppressLint("MissingPermission")
    private fun dropFenceHere() {
        if (!Fences.hasLocationPermission(this)) {
            toast("Grant location first")
            return
        }
        toast("Getting a fix...")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    toast("No fix. Try outdoors.")
                    return@addOnSuccessListener
                }
                promptForName { name ->
                    val fence = Fence("f${System.currentTimeMillis()}", name, loc.latitude, loc.longitude)
                    if (FenceStore.add(this, fence)) {
                        FenceLog.note(this, "dropped fence $name")
                        refreshStatus()
                        toast("Dropped. Press Register when you have a few.")
                    } else {
                        toast("Already at ${FenceStore.MAX} fences")
                    }
                }
            }
            .addOnFailureListener { e -> toast("Location failed: ${e.message}") }
    }

    private fun promptForName(onName: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g. office, the Kerala place"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this fence")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "unnamed" }
                onName(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun registerAll() {
        if (!Fences.hasBackgroundPermission(this)) {
            toast("Background location not granted - alerts will stop when you close the app")
        }
        Fences.registerAll(this) { msg ->
            toast(msg)
            refreshStatus()
        }
    }

    private fun showLog() {
        log.text = FenceLog.read(this).takeLast(4000)
    }

    private fun clearAll() {
        FenceStore.clear(this)
        FenceLog.clear(this)
        log.text = ""
        refreshStatus()
        toast("Cleared")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
