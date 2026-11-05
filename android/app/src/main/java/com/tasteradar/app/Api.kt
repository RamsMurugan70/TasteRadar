package com.tasteradar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class Umbrella(val lat: Double, val lng: Double, val radiusM: Float)

data class Fence(
    val placeId: String,
    val wantId: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val score: Int,
    val reason: String,
    val seenBefore: Boolean,
    /** Null means the hours are unknown, which never suppresses an alert. */
    val hours: List<Hours.Period>?,
    /** The IANA zone the hours are in; null means judge them in the device's. */
    val timezone: String?,
)

data class FenceSet(val umbrella: Umbrella, val fenceRadiusM: Float, val fences: List<Fence>)

/**
 * The server, over HttpURLConnection. No Retrofit, no OkHttp: four endpoints and
 * a flat JSON shape do not justify either.
 *
 * Every method here blocks. None may be called from the main thread or from a
 * BroadcastReceiver's onReceive without goAsync().
 */
object Api {

    private const val TIMEOUT_MS = 15_000

    /**
     * `status` is the HTTP code, or 0 when the request never reached the server.
     * The retry queue needs that difference: a 404 will never succeed however
     * many times it is tried, a connection failure very likely will.
     */
    class ApiException(message: String, val status: Int = 0) : Exception(message) {
        /**
         * Worth retrying or not.
         *
         * 429 and 408 are 4xx but explicitly mean "try again later", so they
         * must not be treated as permanent - a rate-limit lockout would
         * otherwise make the feedback queue throw away everything it holds,
         * which is precisely the data the queue exists to protect.
         */
        val isPermanent: Boolean
            get() = status in 400..499 && status != 429 && status != 408
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): String {
        val url = URL(Config.serverUrl(context) + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doInput = true
            val token = Config.authToken(context)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw ApiException("HTTP $code from $path: ${text.take(200)}", code)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** The one call the whole app is built around. */
    fun fenceSet(context: Context, lat: Double, lng: Double): FenceSet {
        val body = JSONObject().put("lat", lat).put("lng", lng)
        val json = JSONObject(request(context, "POST", "/fence-set", body))

        val u = json.getJSONObject("umbrella")
        val umbrella = Umbrella(
            lat = u.getDouble("lat"),
            lng = u.getDouble("lng"),
            radiusM = u.getDouble("radiusM").toFloat(),
        )

        val array: JSONArray = json.getJSONArray("fences")
        val fences = (0 until array.length()).map { i ->
            val f = array.getJSONObject(i)
            Fence(
                placeId = f.getString("placeId"),
                wantId = f.getString("wantId"),
                name = f.getString("name"),
                lat = f.getDouble("lat"),
                lng = f.getDouble("lng"),
                score = f.getInt("score"),
                reason = f.optString("reason"),
                seenBefore = f.optBoolean("seenBefore", false),
                hours = Hours.parse(f.optJSONObject("hours")),
                timezone = f.optString("timezone").ifBlank { null },
            )
        }
        return FenceSet(umbrella, json.optDouble("fenceRadiusM", 150.0).toFloat(), fences)
    }

    /** Reports an alert already shown. Returns the server's id for it. */
    fun postAlert(context: Context, fence: Fence, lat: Double?, lng: Double?): String {
        val body = JSONObject()
            .put("placeId", fence.placeId)
            .put("wantId", fence.wantId)
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return JSONObject(request(context, "POST", "/alerts", body)).getString("alertId")
    }

    fun feedback(context: Context, alertId: String, action: String) {
        request(context, "POST", "/alerts/$alertId/feedback", JSONObject().put("action", action))
    }

    fun createWant(context: Context, text: String): String {
        val body = JSONObject().put("text", text).put("kind", "need")
        return JSONObject(request(context, "POST", "/wants", body)).optString("restated", text)
    }

    fun listWants(context: Context): List<String> {
        val json = JSONObject(request(context, "GET", "/wants"))
        val array = json.getJSONArray("wants")
        return (0 until array.length()).map { i ->
            val w = array.getJSONObject(i)
            w.optString("restated").ifBlank { w.getString("text") }
        }
    }

    fun health(context: Context): String = request(context, "GET", "/health")
}
