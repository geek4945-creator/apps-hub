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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HubApp(Repository(applicationContext)) }
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
fun HubApp(repo: Repository) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hub", Context.MODE_PRIVATE) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF818CF8), onPrimary = Color(0xFF0B1020), background = Color(0xFF0B1020), surface = Color(0xFF151B2E), onSurface = Color(0xFFE5E7EB), onBackground = Color(0xFFE5E7EB), surfaceVariant = Color(0xFF1E2540))
    else lightColorScheme(primary = Color(0xFF4F46E5), background = Color(0xFFF8FAFC), surface = Color.White, onSurface = Color(0xFF0F172A), onBackground = Color(0xFF0F172A), surfaceVariant = Color(0xFFEEF2FF))
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HubScreen(repo, theme) { t -> theme = t; prefs.edit().putString("theme", t).apply() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HubScreen(repo: Repository, theme: String, setTheme: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(repo.loadLocal()) }
    var refreshing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var platform by remember { mutableStateOf(AppsParser.Platform.ANY) }
    var showAbout by remember { mutableStateOf(false) }

    fun refresh(manual: Boolean) {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val r = repo.refresh()
            refreshing = false
            if (r != null) { if ((r.generatedAt ?: "") >= (doc.generatedAt ?: "")) doc = r; if (manual) Toast.makeText(ctx, "List is up to date", Toast.LENGTH_SHORT).show() }
            else if (manual) Toast.makeText(ctx, "Couldn't refresh. Showing the saved list.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) { refresh(false) }

    val shown = doc.apps.filter { AppsParser.matches(it, query, status, platform) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JD's Apps", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh(true) }) { Text(if (refreshing) "Refreshing…" else "Refresh") }
            TextButton(onClick = { showAbout = true }) { Text("About") }
        }
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
        if (shown.isEmpty()) {
            Text("No apps match your search.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.id }) { AppCard(it) }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } },
            title = { Text("About JD's Apps") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${BuildConfig.VERSION_NAME}. Lists every live app and APK from geek4945-creator, plus what's planned.")
                    Text("The list is a file (apps.json) rebuilt every night at midnight Pacific from the latest GitHub releases. Tap Refresh to pull it now; new versions show up without updating this app.")
                    Text("APKs install after you allow \"Install unknown apps\" for your browser.")
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
