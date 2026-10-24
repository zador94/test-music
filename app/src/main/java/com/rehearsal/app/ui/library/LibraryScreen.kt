package com.rehearsal.app.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rehearsal.app.data.SplitKind
import com.rehearsal.app.data.TrackEntity
import com.rehearsal.app.data.TrackStatus
import com.rehearsal.app.ui.theme.Daw

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenProcessing: (String) -> Unit,
    onOpenPlayer: (String) -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::importUri)
    }

    val permission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* уведомление необязательно для выбора файла */ }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.openProcessing.collect(onOpenProcessing)
    }

    Scaffold(
        containerColor = Daw.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Репетиция", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Стемы и метроном",
                            style = MaterialTheme.typography.bodySmall,
                            color = Daw.muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Daw.bg,
                    titleContentColor = Daw.text,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    picker.launch(arrayOf("audio/*"))
                },
                containerColor = Daw.play,
                contentColor = Color.White,
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Добавить трек")
                }
            }
        },
    ) { padding ->
        if (tracks.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        onClick = {
                            if (track.isWorking) onOpenProcessing(track.id)
                            else onOpenPlayer(track.id)
                        },
                        onDelete = { vm.delete(track.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Daw.surface)
                .border(1.dp, Daw.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = Daw.accent,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Добавьте трек с телефона", color = Daw.text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Системный выбор файла скопирует оригинал в папку приложения. " +
                "Широкие права на всю память не нужны.",
            color = Daw.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TrackCard(
    track: TrackEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Daw.cardShape)
            .background(Daw.surface)
            .border(1.dp, Daw.line, Daw.cardShape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                track.title,
                color = Daw.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(track.status)
                track.originalBpm?.let {
                    MetaPill("${it.toInt()} BPM")
                }
            }
            splitHint(track.splitKind)?.let {
                Text(it, color = Daw.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Daw.muted)
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color) = when (status) {
        TrackStatus.QUEUED -> "В очереди" to Daw.drums
        TrackStatus.PROCESSING -> "Разделение…" to Daw.accent
        TrackStatus.READY -> "Готово" to Daw.bass
        TrackStatus.ERROR -> "Ошибка" to Daw.muteOn
        else -> status to Daw.muted
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MetaPill(text: String) {
    Text(
        text,
        color = Daw.muted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Daw.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun splitHint(kind: String?): String? = when (kind) {
    SplitKind.HTDEMUCS_4STEM -> "HT-Demucs: вокал / ударные / бас / музыка"
    SplitKind.MID_SIDE_2STEM_NOT_ML -> "Старый mid/side — пересчитайте"
    SplitKind.MONO_DUPLICATE_STUB -> "Старая заглушка — пересчитайте"
    else -> null
}
