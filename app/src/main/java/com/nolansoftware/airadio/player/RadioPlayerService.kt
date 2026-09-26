package com.nolansoftware.airadio.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.nolansoftware.airadio.MainActivity
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class RadioPlayerService : Service(), LifecycleOwner {

    @Inject
    lateinit var exoPlayer: ExoPlayer

    @Inject
    lateinit var mediaSession: MediaSession

    private val binder = RadioPlayerBinder()

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var currentStation: Station? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    currentStation?.let { station ->
                        _playerState.value = PlayerState.Loading
                    }
                }
                Player.STATE_READY -> {
                    if (exoPlayer.playWhenReady) {
                        currentStation?.let { station ->
                            _playerState.value = PlayerState.Playing(station)
                            ServiceCompat.startForeground(
                                this@RadioPlayerService,
                                NOTIFICATION_ID,
                                createNotification(station),
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                                } else {
                                    0
                                }
                            )
                        }
                    } else {
                        currentStation?.let { station ->
                            _playerState.value = PlayerState.Paused(station)
                            stopForeground(STOP_FOREGROUND_DETACH)
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    _playerState.value = PlayerState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                Player.STATE_IDLE -> {
                    _playerState.value = PlayerState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            val errorMessage = error.message ?: "Unknown error"
            _playerState.value = PlayerState.Error(errorMessage)
            Log.e(TAG, "Playback error: $errorMessage", error)
        }
    }

    inner class RadioPlayerBinder : Binder() {
        fun getService(): RadioPlayerService = this@RadioPlayerService
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        exoPlayer.addListener(playerListener)
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> {
                    val station = intent.getParcelableExtra<Station>(EXTRA_STATION)
                    station?.let { play(it) }
                }
                ACTION_PAUSE -> pause()
                ACTION_STOP -> stop()
                else -> {
                    Log.w(TAG, "Unhandled action: $action")
                }
            }
        }
        return START_STICKY
    }

    fun play(station: Station) {
        if (currentStation?.stationuuid == station.stationuuid && exoPlayer.isPlaying) {
            return
        }

        currentStation = station
        _playerState.value = PlayerState.Loading

        try {
            val mediaItem = MediaItem.fromUri(station.urlResolved)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            _playerState.value = PlayerState.Error(e.message ?: "Failed to play station")
        }
    }

    fun pause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            currentStation?.let { station ->
                _playerState.value = PlayerState.Paused(station)
            }
        }
    }

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentStation = null
        _playerState.value = PlayerState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(station: Station): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        } ?: Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (exoPlayer.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                getString(R.string.pause),
                createPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                getString(R.string.play),
                createPendingIntent(ACTION_PLAY)
            )
        }

        val stopAction = NotificationCompat.Action(
            R.drawable.ic_stop,
            getString(R.string.stop),
            createPendingIntent(ACTION_STOP)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(station.name)
            .setContentText(station.country)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RadioPlayerService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RadioPlayerService"
        private const val CHANNEL_ID = "radio_playback_channel"
        private const val CHANNEL_NAME = "Radio Playback"
        private const val CHANNEL_DESCRIPTION = "Radio playback controls"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.nolansoftware.airadio.action.PLAY"
        const val ACTION_PAUSE = "com.nolansoftware.airadio.action.PAUSE"
        const val ACTION_STOP = "com.nolansoftware.airadio.action.STOP"
        const val EXTRA_STATION = "com.nolansoftware.airadio.extra.STATION"

        fun newPlayIntent(context: Context, station: Station): Intent {
            return Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_STATION, station)
            }
        }

        fun newPauseIntent(context: Context): Intent {
            return Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PAUSE
            }
        }

        fun newStopIntent(context: Context): Intent {
            return Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}