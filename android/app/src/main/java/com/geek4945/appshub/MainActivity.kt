package com.geek4945.appshub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HubApp(Repository(applicationContext), Updater(applicationContext)) }
    }
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun parseColor(hex: String): Color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color(0xFF4F46E5) }

@Composable
fun HubApp(repo: Repository, updater: Updater) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hub", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF818CF8), onPrimary = Color(0xFF0B1020), background = Color(0xFF0B1020), surface = Color(0xFF151B2E), onSurface = Color(0xFFE5E7EB), onBackground = Color(0xFFE5E7EB), surfaceVariant = Color(0xFF1E2540))
    else lightColorScheme(primary = Color(0xFF4F46E5), background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF0F172A), onBackground = Color(0xFF0F172A), surfaceVariant = Color(0xFFEEF2FF))
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HubScreen(repo, updater, theme) { t -> theme = t; prefs.edit().putString("theme", t).apply() }
        }
    }
}

sealed interface UpdateUi {
    data object None : UpdateUi
    data class Downloading(val info: UpdateInfo, val pct: Int) : UpdateUi
    data class Ready(val info: UpdateInfo) : UpdateUi
    data class NeedPermission(val info: UpdateInfo) : UpdateUi
    data class Failed(val info: UpdateInfo, val msg: String) : UpdateUi
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HubScreen(repo: Repository, updater: Updater, theme: String, setTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(repo.loadLocal()) }
    var refreshing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var platform by remember { mutableStateOf(AppsParser.Platform.ANY) }
    var showAbout by remember { mutableStateOf(false) }
    var upd by remember { mutableStateOf<UpdateUi>(UpdateUi.None) }
    var downloading by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var lastCheck by remember { mutableStateOf(updater.lastCheck()) }

    fun startDownload(info: UpdateInfo) {
        if (downloading) return
        downloading = true
        upd = UpdateUi.Downloading(info, 0)
        scope.launch {
            val ok = try { updater.download(info) { p -> upd = UpdateUi.Downloading(info, p) } } catch (e: Exception) { false }
            downloading = false
            upd = if (ok) UpdateUi.Ready(info) else UpdateUi.Failed(info, "The download didn't finish.")
        }
    }

    fun install(info: UpdateInfo) {
        if (!updater.canInstall()) {
            awaitingPermission = true
            upd = UpdateUi.NeedPermission(info)
            updater.openInstallPermissionSettings()
            return
        }
        if (!updater.launchInstaller(info)) upd = UpdateUi.Failed(info, "Couldn't open the installer.")
    }

    /** Re-fetches apps.json, then (at most once a day unless [force]) looks for a newer release and downloads it in the background. */
    suspend fun fullRefresh(force: Boolean) {
        if (refreshing) return
        refreshing = true
        val r = repo.refresh()
        if (r != null && (r.generatedAt ?: "") >= (doc.generatedAt ?: "")) doc = r
        var msg = if (r != null) "List is up to date." else "Couldn't refresh the list; showing the saved copy."
        // An update found on an earlier day may be waiting (downloaded or not).
        updater.pending()?.let { p ->
            if (upd is UpdateUi.None) {
                if (withContext(Dispatchers.IO) { updater.isDownloaded(p) }) upd = UpdateUi.Ready(p) else startDownload(p)
            }
        }
        if (updater.shouldCheck(force)) {
            try {
                val n = updater.check(doc)
                lastCheck = updater.lastCheck()
                if (n != null) {
                    msg += " Update ${n.version} available."
                    if (upd !is UpdateUi.Downloading) {
                        if (withContext(Dispatchers.IO) { updater.isDownloaded(n) }) upd = UpdateUi.Ready(n) else startDownload(n)
                    }
                } else {
                    msg += " App is up to date."
                    if (upd !is UpdateUi.Downloading) upd = UpdateUi.None
                }
            } catch (e: Exception) { msg += " Couldn't check for app updates." }
        }
        refreshing = false
        if (force) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) { fullRefresh(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (awaitingPermission && updater.canInstall()) {
            awaitingPermission = false
            (upd as? UpdateUi.NeedPermission)?.let { install(it.info) }
        }
    }

    val shown = doc.apps.filter { AppsParser.matches(it, query, status, platform) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JD's Apps", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { fullRefresh(true) } }) { Text(if (refreshing) "Refreshing…" else "Refresh") }
            TextButton(onClick = { showAbout = true }) { Text("About") }
        }
        UpdateBanner(upd, onInstall = { install(it) }, onRetry = { startDownload(it) })
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps…") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = status == null, onClick = { status = null }, label = { Text("All") })
            AppsParser.STATUS_ORDER.forEach { s -> FilterChip(selected = status == s, onClick = { status = if (status == s) null else s }, label = { Text(s) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Any platform" to AppsParser.Platform.ANY, "Has APK" to AppsParser.Platform.APK, "Has web" to AppsParser.Platform.WEB, "APK + Web" to AppsParser.Platform.BOTH)
                .forEach { (label, p) -> FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(label) }) }
        }
        Text(
            "${shown.size} of ${doc.apps.size} apps" + (AppsParser.formatPacific(doc.generatedAt).takeIf { it.isNotEmpty() }?.let { " · list refreshed $it" } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { fullRefresh(true) } }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown.isEmpty()) item { Text("No apps match your search.", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                items(shown, key = { it.id }) { AppCard(it) }
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
            title = { Text("About JD's Apps") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Version ${BuildConfig.VERSION_NAME}. Lists every live app and APK from geek4945-creator, plus what's planned.")
                    Text("Every time you open the app (and when you pull down or tap Refresh) it re-downloads apps.json, so the list is always current. Once a day it also checks for a newer version of this app, downloads it in the background and asks you to install it.")
                    Text(
                        if (lastCheck > 0) "Last update check: " + SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(Date(lastCheck)) + " PT"
                        else "No update check yet.",
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = { showAbout = false; scope.launch { fullRefresh(true) } }) { Text("Check for updates now") }
                    Text("Theme", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system", "light", "dark").forEach { t -> FilterChip(selected = theme == t, onClick = { setTheme(t) }, label = { Text(t.replaceFirstChar { it.uppercase() }) }) }
                    }
                    TextButton(onClick = { openUrl(ctx, BuildConfig.REPO_URL) }) { Text("Source on GitHub") }
                    TextButton(onClick = { openUrl(ctx, BuildConfig.WEB_URL) }) { Text("Open the web version") }
                }
            },
        )
    }
}

@Composable
fun UpdateBanner(u: UpdateUi, onInstall: (UpdateInfo) -> Unit, onRetry: (UpdateInfo) -> Unit) {
    if (u is UpdateUi.None) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (u) {
                is UpdateUi.Downloading -> {
                    Text("Downloading update ${u.info.version}… ${u.pct}%", fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { u.pct / 100f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onPrimary, trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f))
                }
                is UpdateUi.Ready -> {
                    Text("Update ${u.info.version} is ready", fontWeight = FontWeight.SemiBold)
                    Text("Tap Install and confirm in the Android installer. Your list and settings are kept.", fontSize = 12.sp)
                    Button(onClick = { onInstall(u.info) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary)) { Text("Install update") }
                }
                is UpdateUi.NeedPermission -> {
                    Text("Allow installs from JD's Apps", fontWeight = FontWeight.SemiBold)
                    Text("Turn on \"Allow from this source\" in the settings screen, then come back. Android asks once.", fontSize = 12.sp)
                    Button(onClick = { onInstall(u.info) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary)) { Text("Open settings") }
                }
                is UpdateUi.Failed -> {
                    Text("Update ${u.info.version} available. ${u.msg}", fontWeight = FontWeight.SemiBold)
                    Button(onClick = { onRetry(u.info) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary)) { Text("Try again") }
                }
                UpdateUi.None -> {}
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppCard(a: AppEntry) {
    val ctx = LocalContext.current
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(parseColor(a.color)), contentAlignment = Alignment.Center) {
                    Text(a.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (a.isLive) Badge(if (a.version.firstOrNull()?.isDigit() == true) "v${a.version}" else a.version, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                        else StatusBadge(a.status)
                        Badge(a.platform, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text(a.description, fontSize = 14.sp)
            val note = listOf(a.notes, a.statusNote).filter { it.isNotBlank() }.joinToString(" ")
            if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFB45309))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.webUrl?.let { Button(onClick = { openUrl(ctx, it) }) { Text("Open web") } }
                a.apkUrl?.let { u ->
                    val label = if (a.apkPrivate) "Get APK (GitHub login)" else if (a.webUrl != null) "Download APK" else "Install APK"
                    if (a.webUrl != null) OutlinedButton(onClick = { openUrl(ctx, u) }) { Text(label) } else Button(onClick = { openUrl(ctx, u) }) { Text(label) }
                }
                a.extras.forEach { x -> OutlinedButton(onClick = { openUrl(ctx, x.url) }) { Text(x.label) } }
                a.repoUrl?.let { TextButton(onClick = { openUrl(ctx, it) }) { Text("GitHub") } }
            }
            AppsParser.formatPacific(a.lastUpdated).takeIf { it.isNotEmpty() }?.let {
                Text("Updated $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
fun Badge(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun StatusBadge(status: String) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = when (status) {
        "In progress" -> if (dark) Color(0xFF78350F) to Color(0xFFFDE68A) else Color(0xFFFEF3C7) to Color(0xFF92400E)
        "Idea" -> if (dark) Color(0xFF0C4A6E) to Color(0xFFBAE6FD) else Color(0xFFE0F2FE) to Color(0xFF075985)
        else -> if (dark) Color(0xFF374151) to Color(0xFFE5E7EB) else Color(0xFFE5E7EB) to Color(0xFF374151)
    }
    Badge(status, bg, fg)
}
