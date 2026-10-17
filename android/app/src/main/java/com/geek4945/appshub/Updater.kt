package com.geek4945.appshub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer release of this app. */
data class UpdateInfo(val version: String, val apkUrl: String, val size: Long, val pageUrl: String)

object Versions {
    /** "v1.0.10-debug" -> [1, 0, 10]. Anything non-numeric after the first dash is ignored. */
    fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parse(remote); val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

object ReleaseParser {
    private val APK = Regex("^jds-apps-hub.*\\.apk$")

    /** Parses GitHub's releases/latest JSON; returns null if it has no matching APK or is a draft/prerelease. */
    fun parse(json: String): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (APK.matches(a.optString("name"))) {
                val url = a.optString("browser_download_url")
                if (!url.startsWith("https://")) continue
                return UpdateInfo(
                    version = o.getString("tag_name").removePrefix("v"),
                    apkUrl = url, size = a.optLong("size", -1), pageUrl = o.optString("html_url"),
                )
            }
        }
        return null
    }

    /** Fallback source: the apps-hub entry in apps.json (kept fresh by the nightly job). */
    fun fromCatalog(doc: AppsDoc): UpdateInfo? {
        val a = doc.apps.firstOrNull { it.id == "apps-hub" } ?: return null
        val url = a.apkUrl ?: return null
        if (!a.isLive || !url.startsWith("https://")) return null
        return UpdateInfo(a.version, url, -1, a.repoUrl?.let { "$it/releases" } ?: "")
    }
}

/** Daily update check, background download and hand-off to the system installer (the user always confirms the install). */
class Updater(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir get() = File(ctx.cacheDir, "updates").apply { mkdirs() }

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    fun shouldCheck(force: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        force || now - prefs.getLong("last_check", 0L) >= DAY_MS

    fun markChecked(now: Long = System.currentTimeMillis()) { prefs.edit().putLong("last_check", now).apply() }

    fun lastCheck(): Long = prefs.getLong("last_check", 0L)

    private fun apkFor(info: UpdateInfo) = File(dir, "jds-apps-hub-v${info.version}.apk")

    /** Update found earlier (survives restarts) that is still newer than what is installed. */
    fun pending(): UpdateInfo? {
        val v = prefs.getString("pending_version", null) ?: return null
        val url = prefs.getString("pending_url", null) ?: return null
        if (!Versions.isNewer(v, currentVersion)) { clearPending(); return null }
        return UpdateInfo(v, url, prefs.getLong("pending_size", -1), prefs.getString("pending_page", "") ?: "")
    }

    private fun savePending(i: UpdateInfo) {
        prefs.edit().putString("pending_version", i.version).putString("pending_url", i.apkUrl)
            .putLong("pending_size", i.size).putString("pending_page", i.pageUrl).apply()
    }

    fun clearPending() {
        prefs.edit().remove("pending_version").remove("pending_url").remove("pending_size").remove("pending_page").apply()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Network check. Returns the newer release, null if up to date, or throws if the check itself failed. */
    suspend fun check(catalog: AppsDoc?): UpdateInfo? = withContext(Dispatchers.IO) {
        var info: UpdateInfo? = null
        var failed: Exception? = null
        try {
            val c = URL(BuildConfig.RELEASE_API_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "JDsApps/$currentVersion")
            try {
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                info = ReleaseParser.parse(c.inputStream.bufferedReader().readText())
            } finally { c.disconnect() }
        } catch (e: Exception) {
            failed = e
            info = catalog?.let { ReleaseParser.fromCatalog(it) }   // API rate-limited or blocked: use the catalog instead
        }
        if (info == null && failed != null) throw failed
        markChecked()
        val newer = info?.takeIf { Versions.isNewer(it.version, currentVersion) }
        if (newer != null) savePending(newer) else clearPending()
        newer
    }

    /** True if the APK for [info] is already downloaded and valid. */
    fun isDownloaded(info: UpdateInfo): Boolean = apkFor(info).let { it.exists() && validate(it, info) }

    /** Downloads to the app's cache; reports 0..100. Returns false if the file failed validation. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = apkFor(info)
        if (target.exists() && validate(target, info)) { onProgress(100); return@withContext true }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, target.name + ".part")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "JDsApps/$currentVersion")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            var done = 0L; var last = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; onProgress(pct) } }
                    }
                }
            }
        } finally { c.disconnect() }
        if (!part.renameTo(target)) { part.delete(); return@withContext false }
        if (!validate(target, info)) { target.delete(); return@withContext false }
        onProgress(100)
        true
    }

    /** Must be our package, a higher versionCode than installed, and the expected size when known. */
    private fun validate(f: File, info: UpdateInfo): Boolean {
        if (info.size > 0 && f.length() != info.size) return false
        val pi = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0) ?: return false
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return pi.packageName == ctx.packageName && code > currentCode
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" screen for this app. */
    fun openInstallPermissionSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Hands the downloaded APK to the system package installer. Android shows its own Install/Update confirmation. */
    fun launchInstaller(info: UpdateInfo): Boolean {
        val f = apkFor(info)
        if (!f.exists()) return false
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (e: Exception) { false }
    }

    companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
