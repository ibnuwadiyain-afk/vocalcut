package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlin.math.sin

@Composable
fun AudioWaveformVisualizer(
    isPlaying: Boolean,
    isVocalOnly: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val activeColor = if (isVocalOnly) CyanAccent else PurpleAccent
    val secondaryColor = if (isVocalOnly) CyanDark else MagentaAccent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Navy800)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val numBars = 36
            val barSpacing = size.width / numBars
            val barWidth = barSpacing * 0.55f
            val centerY = size.height / 2f

            for (i in 0 until numBars) {
                val x = i * barSpacing + barWidth / 2f
                val dynamicFactor = if (isPlaying) {
                    val wave1 = sin(phase + i * 0.35f) * 0.4f
                    val wave2 = sin(phase * 1.5f + i * 0.7f) * 0.3f
                    (0.3f + (wave1 + wave2).coerceAtLeast(0f) * 0.7f)
                } else {
                    0.15f
                }

                val barHeight = (size.height * 0.8f * dynamicFactor).coerceAtLeast(4.dp.toPx())
                val top = centerY - barHeight / 2f

                val brush = Brush.verticalGradient(
                    colors = listOf(activeColor, secondaryColor),
                    startY = top,
                    endY = top + barHeight
                )

                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }

        // Overlay status label
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .background(Navy900.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) EmeraldSuccess else AmberWarning)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isVocalOnly) "عزل الصوت البشري (Vocal)" else "المسار الصوتي الكامل",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isVocalOnly) CyanAccent else TextSecondaryDark
            )
        }
    }
}

@Composable
fun VideoPlayerControlsOverlay(
    isPlaying: Boolean,
    playbackPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isFullscreen: Boolean,
    onPlayPauseToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }

    // Auto hide controls after 4 seconds
    LaunchedEffect(isVisible, isPlaying) {
        if (isVisible && isPlaying) {
            kotlinx.coroutines.delay(4000)
            isVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = interactionSource, indication = null) {
                isVisible = !isVisible
            }
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            ) {
                // Center Controls (Rewind 10s, Play/Pause, Fast Forward 10s)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onSeekRelative(-10000L) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("rewind_10s_button")
                            .background(Navy800.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "ترجيع 10 ثواني",
                            tint = Color.White
                        )
                    }

                    FilledIconButton(
                        onClick = onPlayPauseToggle,
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("play_pause_button")
                            .shadow(8.dp, CircleShape),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = CyanAccent,
                            contentColor = Navy900
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { onSeekRelative(10000L) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("forward_10s_button")
                            .background(Navy800.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "تقديم 10 ثواني",
                            tint = Color.White
                        )
                    }
                }

                // Bottom Timeline and Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Time and Speed bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(playbackPositionMs)} / ${formatTime(durationMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Speed Chip
                            var showSpeedMenu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(
                                    onClick = { showSpeedMenu = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = CyanAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false }
                                ) {
                                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = { Text("${speed}x") },
                                            onClick = {
                                                onSpeedChange(speed)
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Fullscreen Button
                            IconButton(
                                onClick = onFullscreenToggle,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = "شاشة كاملة",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    // Seek Slider
                    val sliderValue = if (durationMs > 0) {
                        (playbackPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = sliderValue,
                        onValueChange = { frac ->
                            onSeek((frac * durationMs).toLong())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("video_seek_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = CyanAccent,
                            activeTrackColor = CyanAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackModeSelectorCard(
    currentMode: AudioPlaybackMode,
    isProcessing: Boolean,
    isSeparated: Boolean,
    isCached: Boolean = false,
    delayPlaybackUntilBuffer: Boolean = true,
    onToggleDelayPlayback: (Boolean) -> Unit = {},
    onSelectMode: (AudioPlaybackMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("playback_mode_selector_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        border = androidx.compose.foundation.BorderStroke(1.dp, Navy600)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(CyanAccent, PurpleAccent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = Navy900,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "خيارات تشغيل الصوت (Audio Playback)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                if (isCached) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CyanAccent.copy(alpha = 0.16f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "مخزّن مؤقتاً (Cached)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Option 1: Normal Native Audio
            val isNativeSelected = (currentMode == AudioPlaybackMode.NATIVE_ORIGINAL)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectMode(AudioPlaybackMode.NATIVE_ORIGINAL) }
                    .testTag("option_native_audio"),
                shape = RoundedCornerShape(14.dp),
                color = if (isNativeSelected) Navy700 else Navy900.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isNativeSelected) 1.5.dp else 1.dp,
                    color = if (isNativeSelected) CyanAccent else Navy700
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isNativeSelected,
                            onClick = { onSelectMode(AudioPlaybackMode.NATIVE_ORIGINAL) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = CyanAccent,
                                unselectedColor = TextSecondaryDark
                            ),
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isNativeSelected) CyanAccent.copy(alpha = 0.2f) else Navy700),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = if (isNativeSelected) CyanAccent else TextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "1. الصوت الأصلي (Normal Native Audio)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isNativeSelected) CyanAccent else TextPrimaryDark
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = EmeraldSuccess.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "فوري",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldSuccess,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "تشغيل فوري لمسار الصوت الأصلي للفيديو بدون معالجة أو انتظار",
                                fontSize = 11.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Option 2: Vocal Only with Mute Instruments
            val isVocalSelected = (currentMode == AudioPlaybackMode.VOCAL_ONLY)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectMode(AudioPlaybackMode.VOCAL_ONLY) }
                    .testTag("option_vocal_only"),
                shape = RoundedCornerShape(14.dp),
                color = if (isVocalSelected) Navy700 else Navy900.copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isVocalSelected) 1.5.dp else 1.dp,
                    color = if (isVocalSelected) PurpleAccent else Navy700
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isVocalSelected,
                            onClick = { onSelectMode(AudioPlaybackMode.VOCAL_ONLY) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = PurpleAccent,
                                unselectedColor = TextSecondaryDark
                            ),
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isVocalSelected) PurpleAccent.copy(alpha = 0.2f) else Navy700),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isVocalSelected) PurpleAccent else TextSecondaryDark,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "2. صوت بشري فقط (Vocal Only / Mute Instruments)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isVocalSelected) PurpleAccent else TextPrimaryDark
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSeparated) EmeraldSuccess.copy(alpha = 0.18f) else AmberWarning.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = if (isCached) "جاهز (Cache)" else if (isSeparated) "جاهز" else "تخزين مؤقت",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSeparated) EmeraldSuccess else AmberWarning,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isCached)
                                    "محفوظ بالذاكرة المحلية - جاهز للتشغيل الفوري بدون إعادة معالجة"
                                else if (isSeparated)
                                    "تم كتم الآلات الموسيقية وعزل الصوت البشري بجودة عالية"
                                else
                                    "كتم الموسيقى وعزل الصوت - يحتاج وقتاً للتخزين المؤقت لأول مرة فقط",
                                fontSize = 11.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Delay Playback Until Buffer Cache Switch
            HorizontalDivider(color = Navy700, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleDelayPlayback(!delayPlaybackUntilBuffer) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = if (delayPlaybackUntilBuffer) CyanAccent else TextSecondaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "تأخير بدء التشغيل حتى اكتمال التخزين المؤقت",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (delayPlaybackUntilBuffer) TextPrimaryDark else TextSecondaryDark
                        )
                        Text(
                            text = "إيقاف مؤقت حتى انتهاء عزل الصوت ويبدأ تلقائياً فور الجاهزية",
                            fontSize = 10.sp,
                            color = TextSecondaryDark
                        )
                    }
                }

                Switch(
                    checked = delayPlaybackUntilBuffer,
                    onCheckedChange = onToggleDelayPlayback,
                    modifier = Modifier.testTag("toggle_delay_playback_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanAccent,
                        checkedTrackColor = CyanDark,
                        uncheckedThumbColor = TextSecondaryDark,
                        uncheckedTrackColor = Navy700
                    )
                )
            }
        }
    }
}

@Composable
fun ProcessingCard(
    stage: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("processing_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "معالجة عزل الصوت (Offline)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .testTag("processing_progress_bar"),
                color = CyanAccent,
                trackColor = Navy600
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = CyanAccent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stage,
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }
        }
    }
}

@Composable
fun TrackMixerCard(
    vocalVolume: Float,
    bgmVolume: Float,
    onVocalVolumeChange: (Float) -> Unit,
    onBgmVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("track_mixer_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مكسر المسارات الصوتية (Mixer)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CyanAccent.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "مفصول 2-Stems",
                        fontSize = 11.sp,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Vocal Track Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "صوت بشري",
                    tint = CyanAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "الصوت البشري (Vocals):",
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    modifier = Modifier.width(130.dp)
                )
                Slider(
                    value = vocalVolume,
                    onValueChange = onVocalVolumeChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("vocal_volume_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = CyanAccent,
                        activeTrackColor = CyanAccent,
                        inactiveTrackColor = Navy600
                    )
                )
                Text(
                    text = "${(vocalVolume * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = CyanAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Accompaniment / Music Track Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "الموسيقى التصويرية",
                    tint = MagentaAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "الموسيقى (Instruments):",
                    fontSize = 12.sp,
                    color = TextPrimaryDark,
                    modifier = Modifier.width(130.dp)
                )
                Slider(
                    value = bgmVolume,
                    onValueChange = onBgmVolumeChange,
                    valueRange = 0f..1.5f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("bgm_volume_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MagentaAccent,
                        activeTrackColor = MagentaAccent,
                        inactiveTrackColor = Navy600
                    )
                )
                Text(
                    text = "${(bgmVolume * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MagentaAccent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
fun ExportVideoCard(
    isExporting: Boolean,
    exportProgress: Float,
    exportStage: String,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("export_video_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Navy800),
        border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PurpleAccent, MagentaAccent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SaveAlt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "حفظ الفيديو بدون موسيقى في الهاتف",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "تصدير فيديو MP4 بالصوت البشري فقط إلى مجلد الأفلام (Movies)",
                            fontSize = 11.sp,
                            color = TextSecondaryDark
                        )
                    }
                }
            }

            if (isExporting) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = exportStage.ifBlank { "جارٍ التصدير..." },
                        fontSize = 11.sp,
                        color = PurpleAccent,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        text = "${(exportProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PurpleAccent
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("export_progress_bar"),
                    color = PurpleAccent,
                    trackColor = Navy600
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onExportClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_video_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PurpleAccent,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تصدير وحفظ الفيديو في ذاكرة الهاتف",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ExportSuccessDialog(
    fileName: String,
    videoUri: Uri?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = EmeraldSuccess,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تم تصدير الفيديو بنجاح!",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "تم حفظ الفيديو المكتوم الموسيقى (بصوت بشري نقي فقط) في ذاكرة هاتفك داخل مجلد الأفلام (Movies/VocalKeep).",
                    fontSize = 13.sp,
                    color = TextPrimaryDark,
                    lineHeight = 18.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Navy900,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Navy700),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fileName,
                        fontSize = 11.sp,
                        color = CyanAccent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (videoUri != null) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, videoUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "مشاركة الفيديو المكتوم الموسيقى"))
                            } catch (e: Exception) {
                                android.util.Log.e("ExportSuccessDialog", "Cannot share video", e)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleAccent)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاركة")
                    }

                    Button(
                        onClick = {
                            try {
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(videoUri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(viewIntent)
                            } catch (e: Exception) {
                                android.util.Log.e("ExportSuccessDialog", "Cannot open video", e)
                            }
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = Navy900)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("فتح الفيديو")
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900)
                    ) {
                        Text("تم")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = TextSecondaryDark)
            }
        },
        containerColor = Navy800
    )
}

@Composable
fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = CyanAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("عن VocalKeep Player", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "تطبيق VocalKeep Player يقوم بتشغيل مقاطع الفيديو مع ميزة فصل الصوت (Voice Isolation) وكتم الموسيقى التصويرية والآلات الموسيقية والإبقاء على الصوت البشري فقط.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                HorizontalDivider(color = Navy600)
                Text(
                    text = "المزايا والتقنيات:\n" +
                            "• يعمل 100% بدون اتصال بالإنترنت (Offline)\n" +
                            "• مشغل ExoPlayer المتقدم مع تزامن ملي-ثانية\n" +
                            "• نموذج Spleeter بالذكاء الاصطناعي على الجهاز لعزل الترددات الصوتية البشرية\n" +
                            "• إمكانية التحكم في مستوى صوت الغناء والآلات بشكل منفصل\n" +
                            "• حفظ ومشاركة الفيديو بعد كتم الموسيقى وحفظ الصوت البشري",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = TextSecondaryDark
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Navy900)
            ) {
                Text("حسناً")
            }
        },
        containerColor = Navy800
    )
}

fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
