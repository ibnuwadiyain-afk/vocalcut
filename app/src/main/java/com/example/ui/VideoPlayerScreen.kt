package com.example.ui

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.PlayerView
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerController.isPlaying.collectAsStateWithLifecycle()
    val positionMs by viewModel.playerController.playbackPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.playerController.durationMs.collectAsStateWithLifecycle()

    var showInfoDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.playerController.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onVideoSelected(uri)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short,
                withDismissAction = true
            )
            viewModel.dismissError()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short,
                withDismissAction = true
            )
            viewModel.dismissInfo()
        }
    }

    if (showInfoDialog) {
        InfoDialog(onDismiss = { showInfoDialog = false })
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!uiState.isFullscreen) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(CyanAccent, PurpleAccent))
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = Navy900,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "VocalKeep Player",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = TextPrimaryDark
                                )
                                Text(
                                    text = "مشغل وعازل الصوت بدون إنترنت",
                                    fontSize = 11.sp,
                                    color = CyanAccent
                                )
                            }
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            modifier = Modifier
                                .testTag("open_video_picker_button")
                                .padding(end = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Navy700,
                                contentColor = CyanAccent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = "فتح فيديو",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("فتح فيديو", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(
                            onClick = { showInfoDialog = true },
                            modifier = Modifier.testTag("info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "معلومات",
                                tint = TextSecondaryDark
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy900)
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Navy900
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (uiState.isFullscreen) PaddingValues(0.dp) else paddingValues)
        ) {
            if (uiState.isFullscreen) {
                // Fullscreen Video View
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = viewModel.playerController.videoPlayer
                                useController = false
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    VideoPlayerControlsOverlay(
                        isPlaying = isPlaying,
                        playbackPositionMs = positionMs,
                        durationMs = durationMs,
                        playbackSpeed = uiState.playbackSpeed,
                        isFullscreen = true,
                        onPlayPauseToggle = { viewModel.playerController.togglePlayPause() },
                        onSeek = { viewModel.playerController.seekTo(it) },
                        onSeekRelative = { viewModel.playerController.seekRelative(it) },
                        onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                        onFullscreenToggle = { viewModel.toggleFullscreen() }
                    )
                }
            } else {
                // Standard Portrait Screen Layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Video View Area
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .testTag("video_player_container"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Navy700)
                    ) {
                        if (uiState.isVideoLoaded) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = viewModel.playerController.videoPlayer
                                            useController = false
                                            layoutParams = FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                VideoPlayerControlsOverlay(
                                    isPlaying = isPlaying,
                                    playbackPositionMs = positionMs,
                                    durationMs = durationMs,
                                    playbackSpeed = uiState.playbackSpeed,
                                    isFullscreen = false,
                                    onPlayPauseToggle = { viewModel.playerController.togglePlayPause() },
                                    onSeek = { viewModel.playerController.seekTo(it) },
                                    onSeekRelative = { viewModel.playerController.seekRelative(it) },
                                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                                    onFullscreenToggle = { viewModel.toggleFullscreen() }
                                )
                            }
                        } else {
                            // Empty state placeholder
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(listOf(Navy800, Navy900))
                                    )
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(Navy700),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Movie,
                                            contentDescription = null,
                                            tint = CyanAccent,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "لم يتم اختيار أي فيديو بعد",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextPrimaryDark
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "اختر فيديو من جهازك أو جرب المقطع التوضيحي",
                                        fontSize = 12.sp,
                                        color = TextSecondaryDark,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { videoPickerLauncher.launch("video/*") },
                                            modifier = Modifier.testTag("empty_pick_video_btn"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = CyanAccent,
                                                contentColor = Navy900
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("اختيار فيديو", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.loadDemoSample() },
                                            modifier = Modifier.testTag("empty_demo_video_btn"),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleAccent),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("تشغيل Demo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Video Info Header
                    if (uiState.isVideoLoaded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Navy800)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = uiState.videoTitle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (uiState.isVocalOnly) CyanAccent.copy(alpha = 0.18f) else Navy700
                            ) {
                                Text(
                                    text = if (uiState.isVocalOnly) "الصوت البشري فقط" else "الصوت الأصلي",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isVocalOnly) CyanAccent else TextSecondaryDark,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    // PRIMARY FEATURE: Audio Playback Options (Option 1: Normal Native Audio, Option 2: Vocal Only / Mute Instruments)
                    PlaybackModeSelectorCard(
                        currentMode = uiState.playbackMode,
                        isProcessing = uiState.isProcessing,
                        isSeparated = uiState.isSeparated,
                        onSelectMode = { mode ->
                            viewModel.selectPlaybackMode(mode)
                        }
                    )

                    // Processing Progress Card
                    if (uiState.isProcessing) {
                        ProcessingCard(
                            stage = uiState.processingStage,
                            progress = uiState.processingProgress
                        )
                    }

                    // Live Audio Waveform Visualizer
                    if (uiState.isVideoLoaded) {
                        AudioWaveformVisualizer(
                            isPlaying = isPlaying,
                            isVocalOnly = uiState.isVocalOnly
                        )
                    }

                    // Track Mixer (shown when vocals are isolated)
                    if (uiState.isSeparated && uiState.isVocalOnly) {
                        TrackMixerCard(
                            vocalVolume = uiState.vocalVolume,
                            bgmVolume = uiState.bgmVolume,
                            onVocalVolumeChange = { viewModel.setVocalVolume(it) },
                            onBgmVolumeChange = { viewModel.setBgmVolume(it) }
                        )
                    }

                    // Quick Action Buttons Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { videoPickerLauncher.launch("video/*") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("footer_choose_video_btn"),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanDark),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اختيار فيديو آخر", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        FilledTonalButton(
                            onClick = { viewModel.loadDemoSample() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("footer_demo_sample_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Navy700,
                                contentColor = PurpleAccent
                            ),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مقطع تجريبي Demo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
