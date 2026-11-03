package com.rehearsal.app.ui.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rehearsal.app.data.TrackStatus
import com.rehearsal.app.player.PlaybackPolicy
import com.rehearsal.app.ui.theme.Daw
import com.rehearsal.app.work.SplitChrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    vm: ProcessingViewModel = viewModel(),
) {
    val track by vm.track.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    LaunchedEffect(track) {
        val item = track ?: return@LaunchedEffect
        if (PlaybackPolicy.opensPlayer(item)) {
            onOpenPlayer(item.id)
        }
    }

    Scaffold(
        containerColor = Daw.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        track?.title ?: "Разделение",
                        fontWeight = FontWeight.SemiBold,
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Daw.cardShape)
                    .background(Daw.surface)
                    .border(1.dp, Daw.line, Daw.cardShape)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (SplitChrome.windowStatusLine().isNotEmpty()) {
                    Text(SplitChrome.windowStatusLine(), color = Daw.muted)
                }
                when (track?.status) {
                    TrackStatus.QUEUED, TrackStatus.PROCESSING -> {
                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Daw.accent,
                                trackColor = Daw.line,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Daw.accent,
                                trackColor = Daw.line,
                            )
                        }
                        Text(
                            SplitChrome.progressCaption(progress),
                            color = Daw.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TrackStatus.READY -> Text("Готово. Четыре WAV в stems/.", color = Daw.bass)
                    TrackStatus.ERROR -> Text(
                        track?.errorMessage ?: "Ошибка разделения",
                        color = Daw.muteOn,
                    )
                    else -> Text("Готовим копию исходника…", color = Daw.muted)
                }
            }

            Spacer(Modifier.height(4.dp))
            if (track?.let { PlaybackPolicy.opensPlayer(it) } == true) {
                DawAction(
                    label = "Открыть плеер",
                    filled = true,
                    onClick = { onOpenPlayer(vm.trackId) },
                )
            }
            if (track?.status == TrackStatus.ERROR) {
                DawAction(
                    label = "Повторить разделение",
                    filled = false,
                    onClick = vm::retry,
                )
            }
        }
    }
}

@Composable
private fun DawAction(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(Daw.pillShape)
            .background(if (filled) Daw.play else Daw.surface)
            .border(1.dp, if (filled) Daw.play else Daw.accent, Daw.pillShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) Color.White else Daw.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
