package com.example.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

class PlayerController(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "PlayerController"
        private const val SYNC_TOLERANCE_MS = 80L
    }

    val videoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
    }

    private var vocalPlayer: ExoPlayer? = null
    private var accompanimentPlayer: ExoPlayer? = null

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var isVocalOnlyMode = false
    private var vocalVolumeLevel = 1.0f
    private var bgmVolumeLevel = 0.0f
    private var playbackSpeed = 1.0f

    private var positionTickerJob: Job? = null

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                syncSecondaryPlayersPlayState(playing)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = videoPlayer.duration.coerceAtLeast(0L)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                syncSecondaryPlayersSeek(newPosition.positionMs)
            }
        })

        startPositionTicker()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = coroutineScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (videoPlayer.playbackState == Player.STATE_READY || videoPlayer.isPlaying) {
                    val pos = videoPlayer.currentPosition.coerceAtLeast(0L)
                    _playbackPositionMs.value = pos
                    val dur = videoPlayer.duration
                    if (dur > 0L) {
                        _durationMs.value = dur
                    }

                    // Enforce audio sync periodically
                    if (isVocalOnlyMode && vocalPlayer != null && videoPlayer.isPlaying) {
                        val vocalPos = vocalPlayer?.currentPosition ?: 0L
                        val diff = abs(pos - vocalPos)
                        if (diff > SYNC_TOLERANCE_MS) {
                            vocalPlayer?.seekTo(pos)
                            accompanimentPlayer?.seekTo(pos)
                        }
                    }
                }
                delay(200)
            }
        }
    }

    fun loadVideo(videoUri: Uri) {
        releaseSecondaryPlayers()
        isVocalOnlyMode = false
        videoPlayer.volume = 1.0f

        val mediaItem = MediaItem.fromUri(videoUri)
        videoPlayer.setMediaItem(mediaItem)
        videoPlayer.prepare()
        videoPlayer.playWhenReady = true
    }

    fun setupIsolatedTracks(vocalFile: File, accompanimentFile: File?) {
        releaseSecondaryPlayers()

        // Create vocal player
        vocalPlayer = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(vocalFile)))
            prepare()
            volume = if (isVocalOnlyMode) vocalVolumeLevel else 0.0f
            playbackParameters = PlaybackParameters(playbackSpeed)
            seekTo(videoPlayer.currentPosition)
        }

        // Create accompaniment player if available
        if (accompanimentFile != null && accompanimentFile.exists()) {
            accompanimentPlayer = ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    false
                )
                setMediaItem(MediaItem.fromUri(Uri.fromFile(accompanimentFile)))
                prepare()
                volume = if (isVocalOnlyMode) bgmVolumeLevel else 0.0f
                playbackParameters = PlaybackParameters(playbackSpeed)
                seekTo(videoPlayer.currentPosition)
            }
        }

        applyAudioMode(isVocalOnlyMode)
    }

    fun setVocalOnlyMode(enabled: Boolean) {
        isVocalOnlyMode = enabled
        applyAudioMode(enabled)
    }

    private fun applyAudioMode(vocalOnly: Boolean) {
        if (vocalOnly && vocalPlayer != null) {
            // Mute original video audio
            videoPlayer.volume = 0.0f
            vocalPlayer?.volume = vocalVolumeLevel
            accompanimentPlayer?.volume = bgmVolumeLevel

            // Resync positions and play states
            val currentPos = videoPlayer.currentPosition
            vocalPlayer?.seekTo(currentPos)
            accompanimentPlayer?.seekTo(currentPos)
            if (videoPlayer.isPlaying) {
                vocalPlayer?.play()
                accompanimentPlayer?.play()
            }
        } else {
            // Unmute original video
            videoPlayer.volume = 1.0f
            vocalPlayer?.volume = 0.0f
            accompanimentPlayer?.volume = 0.0f
        }
    }

    fun setVocalVolume(volume: Float) {
        vocalVolumeLevel = volume.coerceIn(0f, 1.5f)
        if (isVocalOnlyMode) {
            vocalPlayer?.volume = vocalVolumeLevel
        }
    }

    fun setBgmVolume(volume: Float) {
        bgmVolumeLevel = volume.coerceIn(0f, 1.5f)
        if (isVocalOnlyMode) {
            accompanimentPlayer?.volume = bgmVolumeLevel
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        videoPlayer.playbackParameters = PlaybackParameters(speed)
        vocalPlayer?.playbackParameters = PlaybackParameters(speed)
        accompanimentPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun togglePlayPause() {
        if (videoPlayer.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        videoPlayer.play()
        syncSecondaryPlayersPlayState(true)
    }

    fun pause() {
        videoPlayer.pause()
        syncSecondaryPlayersPlayState(false)
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, _durationMs.value)
        videoPlayer.seekTo(target)
        syncSecondaryPlayersSeek(target)
    }

    fun seekRelative(offsetMs: Long) {
        val target = (videoPlayer.currentPosition + offsetMs).coerceIn(0L, _durationMs.value)
        seekTo(target)
    }

    private fun syncSecondaryPlayersPlayState(playing: Boolean) {
        if (playing) {
            vocalPlayer?.play()
            accompanimentPlayer?.play()
        } else {
            vocalPlayer?.pause()
            accompanimentPlayer?.pause()
        }
    }

    private fun syncSecondaryPlayersSeek(positionMs: Long) {
        vocalPlayer?.seekTo(positionMs)
        accompanimentPlayer?.seekTo(positionMs)
    }

    private fun releaseSecondaryPlayers() {
        try {
            vocalPlayer?.stop()
            vocalPlayer?.release()
            vocalPlayer = null

            accompanimentPlayer?.stop()
            accompanimentPlayer?.release()
            accompanimentPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing secondary players", e)
        }
    }

    fun release() {
        positionTickerJob?.cancel()
        releaseSecondaryPlayers()
        try {
            videoPlayer.stop()
            videoPlayer.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing video player", e)
        }
    }
}
