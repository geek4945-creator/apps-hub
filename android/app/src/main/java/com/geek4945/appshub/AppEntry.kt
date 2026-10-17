package com.geek4945.appshub

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Download(val label: String, val url: String)

data class AppEntry(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val statusNote: String,
    val platform: String,
    val version: String,
    val apkUrl: String?,
    val apkPrivate: Boolean,
    val webUrl: String?,
    val repoUrl: String?,
    val lastUpdated: String?,
    val color: String,
    val notes: String,
    val tags: List<String>,
    val extras: List<Download>,
) {
    val isLive get() = status == "Live"
    val initial: String get() = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
}

data class AppsDoc(val generatedAt: String?, val apps: List<AppEntry>)

object AppsParser {
    val STATUS_ORDER = listOf("Live", "In progress", "Idea", "Held")

    private fun JSONObject.str(k: String): String? =
        if (isNull(k) || !has(k)) null else optString(k).takeIf { it.isNotBlank() }

    fun parse(json: String): AppsDoc {
        val root = JSONObject(json)
        val arr: JSONArray = root.getJSONArray("apps")
        val apps = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description"),
                status = o.str("status") ?: "Live",
                statusNote = o.optString("status_note"),
                platform = o.optString("platform"),
                version = o.optString("version"),
                apkUrl = o.str("apk_url"),
                apkPrivate = o.optBoolean("apk_private", false),
                webUrl = o.str("web_url"),
                repoUrl = o.str("repo_url"),
                lastUpdated = o.str("last_updated"),
                color = o.str("color") ?: "#4F46E5",
                notes = o.optString("notes"),
                tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
                extras = o.optJSONArray("extra_downloads")?.let { t ->
                    (0 until t.length()).map { Download(t.getJSONObject(it).getString("label"), t.getJSONObject(it).getString("url")) }
                } ?: emptyList(),
            )
        }
        return AppsDoc(root.str("generated_at"), apps.sortedBy { STATUS_ORDER.indexOf(it.status).let { r -> if (r < 0) 99 else r } })
    }

    enum class Platform { ANY, APK, WEB, BOTH }

    fun matches(a: AppEntry, query: String, status: String?, platform: Platform): Boolean {
        if (status != null && a.status != status) return false
        when (platform) {
            Platform.APK -> if (a.apkUrl == null) return false
            Platform.WEB -> if (a.webUrl == null) return false
            Platform.BOTH -> if (a.apkUrl == null || a.webUrl == null) return false
            Platform.ANY -> {}
        }
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = (listOf(a.name, a.description, a.platform, a.version, a.status) + a.tags).joinToString(" ").lowercase()
        return words.all { hay.contains(it) }
    }

    /** ISO-8601 UTC ("2026-10-17T05:34:21Z") -> "Oct 16, 2026, 10:34 PM PT". */
    fun formatPacific(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = inFmt.parse(iso) ?: return ""
            SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(d) + " PT"
        } catch (e: Exception) { "" }
    }
}
