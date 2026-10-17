package com.geek4945.appshub

import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    @Test fun versionCompare() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("v1.0.10", "1.0.9"))
        assertTrue(Versions.isNewer("1.1", "1.0.9"))
        assertTrue(Versions.isNewer("2.0.0", "1.9.9"))
        assertFalse(Versions.isNewer("1.0.1", "1.0.1"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
        assertFalse(Versions.isNewer("garbage", "1.0.0"))
        assertEquals(listOf(1, 0, 10), Versions.parse("v1.0.10-debug"))
    }

    private val release = """{"tag_name":"v1.0.2","draft":false,"prerelease":false,"html_url":"https://github.com/o/r/releases/tag/v1.0.2",
        "assets":[{"name":"notes.txt","size":5,"browser_download_url":"https://x/notes.txt"},
                  {"name":"jds-apps-hub-v1.0.2.apk","size":1234,"browser_download_url":"https://github.com/o/r/releases/download/v1.0.2/jds-apps-hub-v1.0.2.apk"}]}"""

    @Test fun parsesReleaseAndPicksApk() {
        val i = ReleaseParser.parse(release)!!
        assertEquals("1.0.2", i.version); assertEquals(1234L, i.size)
        assertTrue(i.apkUrl.endsWith("jds-apps-hub-v1.0.2.apk"))
    }

    @Test fun ignoresDraftsPrereleasesAndMissingApk() {
        assertNull(ReleaseParser.parse(release.replace("\"draft\":false", "\"draft\":true")))
        assertNull(ReleaseParser.parse(release.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseParser.parse("""{"tag_name":"v1","assets":[{"name":"a.zip","browser_download_url":"https://x/a.zip"}]}"""))
        assertNull(ReleaseParser.parse(release.replace("https://github.com/o/r/releases/download", "http://evil/x")))
    }

    @Test fun catalogFallback() {
        val doc = AppsParser.parse("""{"apps":[{"id":"apps-hub","name":"Hub","status":"Live","platform":"x","version":"1.0.3","apk_url":"https://h/apk.apk","web_url":null,"repo_url":"https://github.com/o/r","last_updated":null}]}""")
        val i = ReleaseParser.fromCatalog(doc)!!
        assertEquals("1.0.3", i.version)
        assertNull(ReleaseParser.fromCatalog(AppsParser.parse("""{"apps":[]}""")))
    }
}
