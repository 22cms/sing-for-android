package org.francasio.sing.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val currentVersion: String,
    val availableVersion: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val tagName: String
)

object UpdateChecker {
    private const val TAG = "SingUpdateChecker"
    private const val PRODUCTION_URL =
        "https://api.github.com/repos/22cms/sing-for-android/releases/latest"
    private const val DEBUG_URL = "http://localhost:18200/latest.json"
    private const val PREFS_NAME = "update_checker"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_CACHED_RESPONSE = "cached_response"
    private const val KEY_DEFERRED_TAG = "deferred_tag"
    private const val KEY_DEFERRED_UNTIL = "deferred_until"
    private const val CACHE_DURATION_MS = 12L * 60L * 60L * 1000L
    private const val DEFER_DURATION_MS = 24L * 60L * 60L * 1000L

    fun check(context: Context, currentVersion: String): UpdateInfo? {
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cached = prefs.getString(KEY_CACHED_RESPONSE, null)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)

        val json = if (debug) {
            fetch(DEBUG_URL) ?: return null
        } else if (cached != null && now - lastCheck < CACHE_DURATION_MS) {
            cached
        } else {
            fetch(PRODUCTION_URL)?.also { response ->
                prefs.edit()
                    .putString(KEY_CACHED_RESPONSE, response)
                    .putLong(KEY_LAST_CHECK, now)
                    .apply()
            } ?: cached ?: return null
        }

        val release = parseRelease(json, currentVersion) ?: return null
        val deferred = !debug && prefs.getString(KEY_DEFERRED_TAG, null) == release.tagName &&
            now < prefs.getLong(KEY_DEFERRED_UNTIL, 0L)
        return if (deferred) null else release
    }

    fun defer(context: Context, tagName: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFERRED_TAG, tagName)
            .putLong(KEY_DEFERRED_UNTIL, System.currentTimeMillis() + DEFER_DURATION_MS)
            .apply()
    }

    internal fun parseRelease(json: String, currentVersion: String): UpdateInfo? {
        return try {
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val tag = root.optString("tag_name")
            val availableVersion = normalizeVersion(tag) ?: return null
            val normalizedCurrent = normalizeVersion(currentVersion) ?: return null
            if (compareVersions(availableVersion, normalizedCurrent) <= 0) return null
            val releaseUrl = root.optString("html_url")
            if (releaseUrl.isBlank()) return null
            UpdateInfo(
                currentVersion = normalizedCurrent,
                availableVersion = availableVersion,
                releaseNotes = root.optString("body").trim().ifBlank { "No release notes were provided." },
                releaseUrl = releaseUrl,
                tagName = tag
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid release response: ${t.message}")
            null
        }
    }

    internal fun normalizeVersion(value: String): String? {
        val match = Regex("^v?(\\d+(?:\\.\\d+)*)(?:[-+].*)?$", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        return match.groupValues[1]
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').map { it.toLongOrNull() ?: 0L }
        val b = right.split('.').map { it.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(index) { 0L }).compareTo(b.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun fetch(endpoint: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Sing-for-Android")
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Update endpoint returned HTTP ${connection.responseCode}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Update check unavailable: ${t.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
