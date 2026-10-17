package com.geek4945.appshub

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppsParserTest {
    private fun bundled(): AppsDoc {
        val f = listOf(File("src/main/assets/apps.json"), File("app/src/main/assets/apps.json")).first { it.exists() }
        return AppsParser.parse(f.readText())
    }

    @Test fun bundledFileParsesAndLiveComesFirst() {
        val d = bundled()
        assertTrue(d.apps.size >= 8)
        val order = d.apps.map { AppsParser.STATUS_ORDER.indexOf(it.status) }
        assertEquals(order.sorted(), order)
        assertEquals("Live", d.apps.first().status)
        assertEquals(d.apps.map { it.id }.toSet().size, d.apps.size)
    }

    @Test fun liveAppsHaveLinksAndPlannedHaveNone() {
        bundled().apps.forEach {
            if (it.isLive) assertTrue(it.id, it.apkUrl != null || it.webUrl != null)
            else { assertNull(it.id, it.apkUrl); assertNull(it.id, it.webUrl) }
            it.apkUrl?.let { u -> assertTrue(u.startsWith("https://")) }
        }
    }

    @Test fun searchAndFilters() {
        val d = bundled().apps
        assertTrue(d.any { AppsParser.matches(it, "toolkit", null, AppsParser.Platform.ANY) })
        assertTrue(d.none { AppsParser.matches(it, "zzzznotthere", null, AppsParser.Platform.ANY) })
        assertTrue(d.filter { AppsParser.matches(it, "", "Live", AppsParser.Platform.APK) }.all { it.isLive && it.apkUrl != null })
        assertTrue(d.filter { AppsParser.matches(it, "", "Held", AppsParser.Platform.ANY) }.all { it.status == "Held" })
        assertTrue(d.filter { AppsParser.matches(it, "", null, AppsParser.Platform.BOTH) }.all { it.apkUrl != null && it.webUrl != null })
        assertTrue(d.any { AppsParser.matches(it, "reader books", null, AppsParser.Platform.ANY) })
    }

    @Test fun parsesNullsAndDefaults() {
        val d = AppsParser.parse("""{"apps":[{"id":"a","name":"Alpha","description":"d","platform":"Web","version":"web","apk_url":null,"web_url":"https://x.test/","repo_url":null,"last_updated":null}]}""")
        val a = d.apps.single()
        assertEquals("Live", a.status); assertNull(a.apkUrl); assertNull(a.repoUrl); assertEquals("A", a.initial)
    }

    @Test fun pacificTime() {
        assertEquals("Oct 16, 2026, 10:34 PM PT", AppsParser.formatPacific("2026-10-17T05:34:21Z"))
        assertEquals("", AppsParser.formatPacific(null))
        assertEquals("", AppsParser.formatPacific("garbage"))
    }
}
