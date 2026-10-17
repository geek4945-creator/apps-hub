package com.geek4945.appshub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Loads apps.json: cached copy (from the last successful refresh) or the bundled snapshot, then refreshes from GitHub. */
class Repository(private val ctx: Context) {
    private val cacheFile get() = File(ctx.filesDir, "apps.json")

    fun loadLocal(): AppsDoc {
        val bundled = runCatching { AppsParser.parse(ctx.assets.open("apps.json").bufferedReader().readText()) }.getOrNull()
        val cached = runCatching { AppsParser.parse(cacheFile.readText()) }.getOrNull()
        // Whichever was generated later wins (a fresh install may be newer than an old cache).
        return listOfNotNull(bundled, cached).maxByOrNull { it.generatedAt ?: "" } ?: AppsDoc(null, emptyList())
    }

    /** Returns the refreshed doc, or null if offline / the file is bad. The cache is only replaced after a successful parse. */
    suspend fun refresh(): AppsDoc? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(BuildConfig.APPS_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "JDsApps/" + BuildConfig.VERSION_NAME)
            try {
                if (c.responseCode != 200) return@runCatching null
                val text = c.inputStream.bufferedReader().readText()
                val doc = AppsParser.parse(text)
                if (doc.apps.isEmpty()) return@runCatching null
                cacheFile.writeText(text)
                doc
            } finally { c.disconnect() }
        }.getOrNull()
    }
}
