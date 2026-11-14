package com.athkeya.routine

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private fun parseHm(s: String): Int {
    val p = s.trim().split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return 450
    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    return if (h in 0..23 && m in 0..59) h * 60 + m else 450
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Store.prefs(ctx) }
    val tasks = remember {
        val now = System.currentTimeMillis()
        mutableStateListOf<RoutineTask>().apply {
            addAll(Store.load(ctx).filter { it.daily || it.timeMillis > now - 3_600_000 }.sortedBy { it.timeMillis })
        }
    }
    var text by remember { mutableStateOf("") }
    var bangla by remember { mutableStateOf(false) }
    var daily by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var smart by remember { mutableStateOf(prefs.getBoolean("smart_on", false)) }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var sumOn by remember { mutableStateOf(prefs.getBoolean("sum_on", false)) }
    var sumTime by remember {
        val m = prefs.getInt("sum_min", 450)
        mutableStateOf("%02d:%02d".format(m / 60, m % 60))
    }
    val fmt = remember { SimpleDateFormat("EEE, hh:mm a", Locale.getDefault()) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val s = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (s != null) text = if (text.isBlank()) s else "$text, $s"
    }

    fun addItems(items: List<ParsedItem>, prefix: String = "") {
        val now = System.currentTimeMillis()
        val ok = items.filter { it.time > now }
        if (ok.isEmpty()) {
            info = prefix + "Time bujhte parini. Example: kal 8 tay uthbo, 19 tay meeting"
            return
        }
        ok.forEach { p ->
            val t = RoutineTask(Random.nextInt(10_000, Int.MAX_VALUE), p.title, p.time, p.daily || daily)
            tasks.add(t)
            Alarms.schedule(ctx, t)
        }
        tasks.sortBy { it.timeMillis }
        Store.save(ctx, tasks)
        info = prefix + "${ok.size} ta alarm set hoyeche ✅"
        text = ""
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Routine Voice", style = MaterialTheme.typography.headlineSmall) }

        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Kal er kaj bolo ba likho") },
                placeholder = { Text("kal sokal 8 tay uthbo, 19 tay meeting") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (bangla) "bn-BD" else "en-US")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Kal er kaj bolo")
                    }
                    try { speech.launch(i) } catch (e: ActivityNotFoundException) {
                        info = "Voice input paini. Google app install/update koro."
                    }
                }) { Text("🎤 Bolo") }
                Text("English")
                Switch(checked = bangla, onCheckedChange = { bangla = it })
                Text("বাংলা")
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = daily, onCheckedChange = { daily = it })
                Text("Sob gulo roj repeat hobe")
            }
        }

        item {
            Button(
                enabled = !busy,
                onClick = {
                    val input = text
                    if (smart && apiKey.isNotBlank()) {
                        busy = true
                        info = "Claude bujhche..."
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { runCatching { Smart.parse(input, apiKey.trim()) } }
                            val items = res.getOrNull()
                            if (items == null) {
                                addItems(Parser.parse(input), "Smart mode fail (${res.exceptionOrNull()?.message}). Normal parser: ")
                            } else addItems(items)
                            busy = false
                        }
                    } else addItems(Parser.parse(input))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Alarm set koro") }
        }

        item {
            OutlinedButton(
                onClick = {
                    val t = RoutineTask(Random.nextInt(10_000, Int.MAX_VALUE), "Test alarm", System.currentTimeMillis() + 10_000)
                    tasks.add(t); Alarms.schedule(ctx, t)
                    tasks.sortBy { it.timeMillis }; Store.save(ctx, tasks)
                    info = "10 sec por test alarm bajbe"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test alarm (10 sec)") }
        }

        if (info.isNotEmpty()) item { Text(info, style = MaterialTheme.typography.bodyMedium) }

        item { HorizontalDivider() }

        // ---- Morning summary ----
        item { Text("Morning summary", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sumTime,
                    onValueChange = { sumTime = it },
                    label = { Text("HH:mm") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
                Switch(checked = sumOn, onCheckedChange = { on ->
                    sumOn = on
                    prefs.edit().putBoolean("sum_on", on).putInt("sum_min", parseHm(sumTime)).apply()
                    if (on) {
                        Alarms.scheduleSummary(ctx)
                        info = "Roj $sumTime e summary bolbe"
                    } else Alarms.cancelSummary(ctx)
                })
                Text("On")
            }
        }
        item {
            Text("Time change korle switch off then on koro.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                val i = Intent(ctx, AlarmService::class.java).putExtra("id", Alarms.SUMMARY_ID).putExtra("title", "__summary__")
                ContextCompat.startForegroundService(ctx, i)
            }) { Text("Summary test koro") }
        }

        item { HorizontalDivider() }

        // ---- Smart mode ----
        item { Text("Smart mode (Claude API)", style = MaterialTheme.typography.titleMedium) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = smart, onCheckedChange = {
                    smart = it
                    prefs.edit().putBoolean("smart_on", it).apply()
                })
                Text("Smart parsing on")
            }
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    prefs.edit().putString("api_key", it).apply()
                },
                label = { Text("Anthropic API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item { HorizontalDivider() }
        item { Text("Schedule", style = MaterialTheme.typography.titleMedium) }

        items(tasks.toList(), key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text((if (t.daily) "🔁 " else "") + t.title, style = MaterialTheme.typography.bodyLarge)
                        Text(fmt.format(Date(t.timeMillis)), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        Alarms.cancel(ctx, t)
                        tasks.remove(t)
                        Store.save(ctx, tasks)
                    }) { Text("✕") }
                }
            }
        }
    }
}
