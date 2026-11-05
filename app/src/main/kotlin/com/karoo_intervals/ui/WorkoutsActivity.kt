package com.karoo_intervals.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karoo_intervals.BuildConfig
import com.karoo_intervals.data.WorkoutLibrary
import com.karoo_intervals.data.WorkoutRepository
import com.karoo_intervals.engine.WorkoutCommand
import com.karoo_intervals.engine.WorkoutRunner
import com.karoo_intervals.extension.http
import com.karoo_intervals.intervals.IntervalsApi
import com.karoo_intervals.intervals.SyncOutcome
import com.karoo_intervals.workout.Profile
import com.karoo_intervals.workout.Workout
import com.karoo_intervals.workout.formatClock
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

/**
 * Workout picker (launcher, Karoo extension list via CONFIGURE_EXTENSION, and the ⚙ tap zone).
 * Owns its own [KarooSystemService] so Sync can use the Karoo's network (Wi-Fi or phone BT).
 */
class WorkoutsActivity : ComponentActivity() {
    private lateinit var karooSystem: KarooSystemService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        karooSystem = KarooSystemService(applicationContext).also { it.connect() }
        val repo = WorkoutRepository.get(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                WorkoutsScreen(repo, onBack = { finish() }, sync = { sync(repo) })
            }
        }
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    private suspend fun sync(repo: WorkoutRepository): String? {
        val outcome = withTimeoutOrNull(45_000) {
            IntervalsApi.sync(repo.apiKey(), LocalDate.now(), { karooSystem.http(it) })
        } ?: return "Timed out – no connection?"
        return when (outcome) {
            is SyncOutcome.Ok -> { repo.saveSync(outcome.payload, System.currentTimeMillis()); null }
            is SyncOutcome.Failed -> outcome.reason
        }
    }
}

private val Blue = Color(0xFF8AB8EC)
private val BlueText = Color(0xFF0B3A6E)

@Composable
private fun WorkoutsScreen(repo: WorkoutRepository, onBack: () -> Unit, sync: suspend () -> String?) {
    val lib by repo.library.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Workouts", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        "👤", fontSize = 24.sp,
                        modifier = Modifier.clip(CircleShape).clickable { showProfile = !showProfile }.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBack, modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = BlueText),
                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Back to ride", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    Button(
                        onClick = {
                            if (syncing) return@Button
                            syncing = true
                            scope.launch { syncError = sync(); syncing = false }
                        },
                        modifier = Modifier.height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = surface, contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                    ) { Text(if (syncing) "…" else "Sync", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
                Spacer(Modifier.height(6.dp))
                val l = lib
                val status = when {
                    syncing -> "Syncing…"
                    syncError != null -> syncError!!
                    l?.lastSyncedAt != null -> "Last synced " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(l.lastSyncedAt))
                    l?.apiKeyConfigured == false -> "Add your intervals.icu API key (👤)"
                    else -> "Not synced yet"
                }
                Text(status, fontSize = 14.sp, color = if (syncError != null) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant)
                if (showProfile && l != null) ProfileSection(repo, l)
            }
            val l = lib ?: return@LazyColumn
            val sel = l.selected

            item {
                Section("CURRENT WORKOUT")
                if (sel == null) {
                    Text("None selected – pick one below.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    WorkoutCard(sel, surface) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Remove", surface, Modifier.weight(1f)) { scope.launch { repo.select(null) } }
                            SmallButton("Restart", surface, Modifier.weight(1f)) { WorkoutRunner.command(WorkoutCommand.RESTART) }
                        }
                    }
                }
                Section("THIS WEEK")
                if (l.planned.isEmpty()) Text("No planned workouts in the next 7 days.", fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(l.planned.filter { it.id != sel?.id }, key = { it.id }) { w ->
                Spacer(Modifier.height(8.dp))
                WorkoutCard(w, surface, onClick = { scope.launch { repo.select(w.id) } })
            }

            item { Spacer(Modifier.height(80.dp)) } // keep clear of Karoo's bottom overlay buttons
        }
    }
}

@Composable
private fun ProfileSection(repo: WorkoutRepository, lib: WorkoutLibrary) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    Section("ACCOUNT")
    Text(
        (if (lib.apiKeyConfigured) "API key set" else "No API key") +
            (lib.power?.let { " · FTP ${it.ftp} W" } ?: ""),
        fontSize = 15.sp,
    )
    OutlinedTextField(
        value = key, onValueChange = { key = it }, singleLine = true,
        label = { Text("intervals.icu API key") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    SmallButton("Save key", MaterialTheme.colorScheme.surfaceVariant, onClick = {
        scope.launch { repo.setApiKey(key); key = "" }
    })
    if (BuildConfig.DEBUG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Simulator (5× speed)", modifier = Modifier.weight(1f), fontSize = 15.sp)
            Switch(checked = lib.simulate, onCheckedChange = { on -> scope.launch { repo.setSimulate(on) } })
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SmallButton(text: String, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(10.dp), modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg.copy(alpha = 1f), contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
}

@Composable
private fun WorkoutCard(w: Workout, bg: Color, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg.copy(alpha = 0.6f))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp),
    ) {
        Text(w.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ProfileGraph(w, Modifier.fillMaxWidth().height(70.dp))
        Spacer(Modifier.height(6.dp))
        Text("${formatClock(w.totalSec)} · ${dayLabel(w.date)}", fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        actions()
    }
}

@Composable
private fun ProfileGraph(w: Workout, modifier: Modifier) {
    Canvas(modifier) {
        Profile.bars(w, size.width).forEach { b ->
            val path = Path().apply {
                moveTo(b.left, size.height)
                lineTo(b.left, size.height * (1 - b.startFrac))
                lineTo(b.right, size.height * (1 - b.endFrac))
                lineTo(b.right, size.height)
                close()
            }
            drawPath(path, Color(b.color))
        }
        drawLine(Color.Gray, Offset(0f, size.height), Offset(size.width, size.height), 1f)
    }
}

private fun dayLabel(date: String?): String {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "Library"
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + d.dayOfMonth + "." + d.monthValue + "."
    }
}
