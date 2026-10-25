package com.rehearsal.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rehearsal.app.player.PlaybackMath
import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.data.StemId
import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.data.TrackStatus
import com.rehearsal.app.ui.theme.Daw
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    vm: PlayerViewModel = viewModel(),
) {
    val track by vm.track.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Daw.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        track?.title ?: "Плеер",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Daw.bg,
                    titleContentColor = Daw.text,
                    navigationIconContentColor = Daw.text,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Transport(
                ui = ui,
                onToggle = vm::togglePlay,
                onSeek = vm::seek,
            )

            if (track?.isReady == true) {
                StemMixer(
                    ui = ui,
                    canFourStems = track?.hasFourStems() == true,
                    onUseStems = vm::setUseStems,
                    onVolume = vm::setVolume,
                    onMute = vm::toggleMute,
                    onSolo = vm::toggleSolo,
                )
            }

            MetronomeBlock(
                ui = ui,
                onEnabled = vm::setMetronomeOn,
                onBpm = vm::setMetronomeBpm,
            )

            OriginalBpmField(
                text = ui.originalBpmText,
                onChange = vm::setOriginalBpmText,
            )

            track?.let { SplitBanner(it, onRetry = vm::retrySplit) }
        }
    }
}

@Composable
private fun SplitBanner(track: TrackEntity, onRetry: () -> Unit) {
    DawCard {
        when (track.status) {
            TrackStatus.QUEUED, TrackStatus.PROCESSING -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Daw.accent,
                    trackColor = Daw.line,
                )
                Text(
                    "HT-Demucs считает 4 дорожки. Первая песня ещё скачает модель (~166 МБ). " +
                        "Дальше — минуты, телефон греется. Оригинал можно слушать сейчас.",
                    color = Daw.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TrackStatus.ERROR -> {
                Text(
                    track.errorMessage ?: "Разделение не удалось",
                    color = Daw.muteOn,
                )
                DawTextButton("Повторить", onRetry)
            }
            TrackStatus.READY -> when (track.splitKind) {
                SplitKind.HTDEMUCS_4STEM -> Text(
                    "4 дорожки HT-Demucs: вокал, ударные, бас, музыка. " +
                        "Гитара не отдельная — она в «Музыке», пока нет 6-stem модели.",
                    color = Daw.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SplitKind.MID_SIDE_2STEM_NOT_ML, SplitKind.MONO_DUPLICATE_STUB -> {
                    Text(
                        "Это старый mid/side, не нейросеть. Mute вокала голос не уберёт.",
                        color = Daw.muteOn,
                    )
                    DawTextButton("Пересчитать HT-Demucs", onRetry)
                }
                else -> Text("Стемы готовы.", color = Daw.muted)
            }
        }
    }
}

@Composable
private fun Transport(
    ui: PlayerUi,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = max(ui.durationMs, 1L)
    DawCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Daw.play)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (ui.playing) "Пауза" else "Играть",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Транспорт",
                    color = Daw.muted,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.2.sp,
                )
                Slider(
                    value = (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { onSeek((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Daw.accent,
                        activeTrackColor = Daw.accent,
                        inactiveTrackColor = Daw.line,
                    ),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(ui.positionMs), color = Daw.text, style = MaterialTheme.typography.bodySmall)
                    Text(formatMs(ui.durationMs), color = Daw.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun OriginalBpmField(text: String, onChange: (String) -> Unit) {
    var draft by remember { mutableStateOf(text) }
    var everFocused by remember { mutableStateOf(false) }
    LaunchedEffect(text) { draft = text }
    val focus = LocalFocusManager.current
    fun commit() {
        onChange(draft)
    }
    DawCard {
        Text("Темп (оригинальный BPM)", fontWeight = FontWeight.SemiBold, color = Daw.text)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = PlaybackMath.sanitizeBpmDraft(it) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        everFocused = true
                    } else if (everFocused) {
                        commit()
                    }
                },
            supportingText = {
                Text(
                    "30–240. Пишите число, применится по Done или когда уйдёте из строки. Пока набираете — плеер не трогаем.",
                    color = Daw.muted,
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focus.clearFocus()
                },
            ),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Daw.accent,
                unfocusedBorderColor = Daw.line,
                focusedTextColor = Daw.text,
                unfocusedTextColor = Daw.text,
                cursorColor = Daw.accent,
            ),
        )
    }
}

@Composable
private fun MetronomeBlock(
    ui: PlayerUi,
    onEnabled: (Boolean) -> Unit,
    onBpm: (Float) -> Unit,
) {
    DawCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Метроном", fontWeight = FontWeight.SemiBold, color = Daw.text)
                Text(
                    "Клики на часах плеера, не по таймеру",
                    color = Daw.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = ui.metronomeOn,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Daw.accent,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Daw.line,
                ),
            )
        }
        Text("Желаемый BPM: ${ui.metronomeBpm.toInt()}", color = Daw.text)
        val original = ui.originalBpm
        val range = PlaybackMath.metronomeSliderRange(original)
        val sliderValue = PlaybackMath.clampMetronomeBpm(ui.metronomeBpm, original)
        Slider(
            value = sliderValue,
            onValueChange = onBpm,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Daw.drums,
                activeTrackColor = Daw.drums,
                inactiveTrackColor = Daw.line,
            ),
        )
        Text(
            String.format(
                Locale.US,
                "Скорость = %d / %d = ×%.2f. Тон не меняется.",
                ui.metronomeBpm.toInt(),
                original.toInt(),
                ui.speed,
            ),
            color = Daw.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StemMixer(
    ui: PlayerUi,
    canFourStems: Boolean,
    onUseStems: (Boolean) -> Unit,
    onVolume: (StemId, Float) -> Unit,
    onMute: (StemId) -> Unit,
    onSolo: (StemId) -> Unit,
) {
    DawCard {
        Text("Микшер", fontWeight = FontWeight.SemiBold, color = Daw.text, fontSize = 18.sp)
        SourceToggle(
            useStems = ui.useStems,
            canFourStems = canFourStems,
            onUseStems = onUseStems,
        )
        if (ui.useStems && canFourStems) {
            Text(
                "Mute вокала = фейдер 0, голоса быть не должно. Гитара — в «Музыке».",
                color = Daw.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            StemId.entries.forEach { id ->
                val fader = ui.faders.getValue(id)
                StemFaderRow(
                    title = id.labelRu,
                    color = Daw.stem(id),
                    fader = fader,
                    onVolume = { onVolume(id, it) },
                    onMute = { onMute(id) },
                    onSolo = { onSolo(id) },
                )
            }
        } else if (!canFourStems) {
            Text(
                "Стемы ещё не готовы — звучит оригинал.",
                color = Daw.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceToggle(
    useStems: Boolean,
    canFourStems: Boolean,
    onUseStems: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Daw.pillShape)
            .background(Daw.bg)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SourceTab(
            title = "Оригинал",
            selected = !useStems,
            modifier = Modifier.weight(1f),
            onClick = { onUseStems(false) },
        )
        SourceTab(
            title = "4 стемы",
            selected = useStems,
            enabled = canFourStems,
            modifier = Modifier.weight(1f),
            onClick = { onUseStems(true) },
        )
    }
}

@Composable
private fun SourceTab(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (selected) Daw.accent else Color.Transparent
    val fg = when {
        !enabled -> Daw.muted.copy(alpha = 0.4f)
        selected -> Color.White
        else -> Daw.muted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun StemFaderRow(
    title: String,
    color: Color,
    fader: StemFader,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
            Text(
                title,
                modifier = Modifier.weight(1f),
                color = Daw.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${(fader.volume * 100).roundToInt()} %",
                color = color,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            DawLatch("M", selected = fader.muted, onColor = Daw.muteOn, onClick = onMute)
            DawLatch("S", selected = fader.solo, onColor = Daw.soloOn, onClick = onSolo)
        }
        Slider(
            value = fader.volume,
            onValueChange = onVolume,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Daw.line,
            ),
        )
    }
}

@Composable
private fun DawLatch(
    label: String,
    selected: Boolean,
    onColor: Color,
    onClick: () -> Unit,
) {
    val bg = if (selected) onColor else Daw.surfaceHigh
    val fg = when {
        !selected -> Daw.muted
        onColor == Daw.muteOn -> Color.White
        else -> Color(0xFF1A1200)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, if (selected) onColor else Daw.line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun DawCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Daw.cardShape)
            .background(Daw.surface)
            .border(1.dp, Daw.line, Daw.cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun DawTextButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(Daw.pillShape)
            .background(Daw.surfaceHigh)
            .border(1.dp, Daw.accent, Daw.pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, color = Daw.accent, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
