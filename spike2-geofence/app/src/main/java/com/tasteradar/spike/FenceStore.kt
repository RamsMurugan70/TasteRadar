package com.tasteradar.spike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Fence(val id: String, val name: String, val lat: Double, val lng: Double)

/** Fifteen fences, kept in SharedPreferences. Deliberately dumb. */
object FenceStore {

    private const val PREFS = "fences"
    private const val KEY = "list"
    const val MAX = 15

    fun all(context: Context): List<Fence> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Fence(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
        }
    }

    fun add(context: Context, fence: Fence): Boolean {
        val current = all(context).toMutableList()
        if (current.size >= MAX) return false
        current.add(fence)
        save(context, current)
        return true
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, fences: List<Fence>) {
        val arr = JSONArray()
        fences.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("lat", f.lat)
                    .put("lng", f.lng)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
