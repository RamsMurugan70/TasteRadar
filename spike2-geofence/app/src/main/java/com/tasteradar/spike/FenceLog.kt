package com.tasteradar.spike

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole point of the spike: a CSV you can read afterwards.
 *
 * Lands in Android/data/com.tasteradar.spike/files/fence-log.csv, which you can
 * pull with:  adb pull /sdcard/Android/data/com.tasteradar.spike/files/fence-log.csv
 */
object FenceLog {

    private const val FILE = "fence-log.csv"
    private const val HEADER =
        "time,fence_id,fence_name,transition,report_delay_ms,accuracy_m,lat,lng\n"

    private fun file(context: Context): File {
        val f = File(context.getExternalFilesDir(null), FILE)
        if (!f.exists()) f.writeText(HEADER)
        return f
    }

    fun append(
        context: Context,
        fenceId: String,
        fenceName: String,
        transition: String,
        reportDelayMs: Long,
        accuracyM: Float,
        lat: Double,
        lng: Double
    ) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val safeName = fenceName.replace(",", " ")
        file(context).appendText(
            "$stamp,$fenceId,$safeName,$transition,$reportDelayMs," +
                "${"%.0f".format(accuracyM)},${"%.6f".format(lat)},${"%.6f".format(lng)}\n"
        )
    }

    fun note(context: Context, what: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).appendText("$stamp,-,-,$what,,,,\n")
    }

    fun read(context: Context): String = file(context).readText()

    fun clear(context: Context) {
        file(context).writeText(HEADER)
    }
}
