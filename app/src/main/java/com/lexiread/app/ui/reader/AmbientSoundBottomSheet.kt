package com.lexiread.app.ui.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lexiread.app.domain.audio.AmbientPresets
import com.lexiread.app.domain.audio.PresetSound
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbientSoundBottomSheet(
    ambientState: AmbientState,
    onToggleEnabled: () -> Unit,
    onSelectSound: (String?, String?) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onCustomAudioSelected: (Uri) -> Unit,
    sleepTimerMinutes: Int,
    sleepRemainingSec: Int,
    onSleepTimerChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val context = LocalContext.current

    // Sync volume slider with device's system media stream volume (AudioManager.STREAM_MUSIC)
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    var systemVolumeFraction by remember(audioManager) {
        mutableStateOf(
            (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume).coerceIn(0f, 1f)
        )
    }

    // Listen to physical volume buttons (android.media.VOLUME_CHANGED_ACTION)
    DisposableEffect(context, audioManager, maxVolume) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val frac = (currentVol.toFloat() / maxVolume).coerceIn(0f, 1f)
                    systemVolumeFraction = frac
                    onVolumeChange(frac)
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            context.registerReceiver(receiver, filter)
        } catch (_: Exception) {}

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onCustomAudioSelected(it) }
    }

    var lastSetStreamVol by remember { mutableStateOf(-1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        // Top inset only, so the sheet paints past the gesture bar instead of leaving a
        // strip of page colour under it; the content below keeps navigationBarsPadding.
        windowInsets     = WindowInsets.statusBars,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            // Header with icon and master switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (ambientState.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Headphones,
                            contentDescription = null,
                            tint = if (ambientState.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Text(
                            text  = "Фоновые звуки",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text  = if (ambientState.isEnabled) "Воспроизводится" else "Выключено",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ambientState.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = ambientState.isEnabled,
                    onCheckedChange = { onToggleEnabled() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // System Media Volume Slider (STREAM_MUSIC)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "Громкость устройства",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Slider(
                    value = systemVolumeFraction,
                    onValueChange = { fraction ->
                        systemVolumeFraction = fraction
                        val targetVol = (fraction * maxVolume).roundToInt()
                        if (targetVol != lastSetStreamVol) {
                            lastSetStreamVol = targetVol
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            } catch (_: Exception) {}
                        }
                        onVolumeChange(fraction)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Text(
                    text = "${(systemVolumeFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Выбор звука",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // Preset grid (4 MP3 sounds)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmbientPresets.ALL.chunked(2).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { preset ->
                            val isSelected = ambientState.soundId == preset.id
                            PresetTile(
                                preset = preset,
                                isSelected = isSelected,
                                onClick = { onSelectSound(preset.id, null) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                // Custom file tile
                val isCustomSelected = ambientState.soundId == "custom"
                val customFileName = if (!ambientState.customPath.isNullOrBlank()) {
                    File(ambientState.customPath).name
                } else null

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isCustomSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            width = if (isCustomSelected) 1.dp else 0.5.dp,
                            color = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            if (customFileName != null) {
                                onSelectSound("custom", ambientState.customPath)
                            } else {
                                audioLauncher.launch("audio/*")
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📂", fontSize = 20.sp)
                            Column {
                                Text(
                                    text = "Свой локальный MP3 / WAV",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = customFileName ?: "Загрузить файл с устройства",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = { audioLauncher.launch("audio/*") },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Загрузить",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            SleepTimerSection(
                selectedMinutes = sleepTimerMinutes,
                remainingSec    = sleepRemainingSec,
                onSelect        = onSleepTimerChange
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Falling asleep over a book is the normal way this app gets used at night, so the
 * timer lives next to the sound that would otherwise play until morning. It silences
 * narration, ambient audio and auto-scroll together — one of them left running is
 * as disruptive as all three.
 */
@Composable
private fun SleepTimerSection(
    selectedMinutes: Int,
    remainingSec: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(0, 15, 30, 45, 60)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text  = "Таймер сна",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (remainingSec > 0) {
            Text(
                text  = "осталось %d:%02d".format(remainingSec / 60, remainingSec % 60),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { minutes ->
            val isSelected = selectedMinutes == minutes
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        width = if (isSelected) 1.dp else 0.5.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(minutes) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = if (minutes == 0) "Выкл" else "$minutes м",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PresetTile(
    preset: PresetSound,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(preset.icon, fontSize = 20.sp)
            Column {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
