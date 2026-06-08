package com.rydercom.nativeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule

class PersistentAudioDeviceModule(private val delegate: AudioDeviceModule) : AudioDeviceModule {
    override fun getNativeAudioDeviceModulePointer(): Long = delegate.nativeAudioDeviceModulePointer
    override fun release() { delegate.release() }
    override fun setSpeakerMute(muted: Boolean) { delegate.setSpeakerMute(muted) }
    override fun setMicrophoneMute(muted: Boolean) {
        Log.w("RyderCom", "[ADM] setMicrophoneMute($muted) intercepte - micro force OUVERT")
        delegate.setMicrophoneMute(false)
    }
}

class RyderComForegroundService : Service() {

    companion object {
        private const val TAG = "RyderComFgService"
        private const val CHANNEL_ID = "RyderComServiceChannel"
        private const val NOTIFICATION_ID = 888
        const val EXTRA_SESSION_NAME = "sessionName"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RyderComForegroundService = this@RyderComForegroundService
    }

    private val binder = LocalBinder()
    private var keepAliveAudioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startKeepAliveAudio()
        Log.i(TAG, "Service cree")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionName = intent?.getStringExtra(EXTRA_SESSION_NAME) ?: "Ryde en cours"
        startForeground(NOTIFICATION_ID, buildNotification(sessionName))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved - arret propre du service")
        stopKeepAliveAudio()
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopKeepAliveAudio()
        stopForeground(true)
        Log.i(TAG, "Service detruit")
        super.onDestroy()
    }

    private fun startKeepAliveAudio() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            keepAliveAudioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize * 2)
            keepAliveAudioRecord?.startRecording()
            Log.i(TAG, "KeepAlive AudioRecord ouvert")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur KeepAlive AudioRecord: ${e.message}")
        }
    }

    private fun stopKeepAliveAudio() {
        try {
            keepAliveAudioRecord?.stop()
            keepAliveAudioRecord?.release()
            keepAliveAudioRecord = null
            Log.i(TAG, "KeepAlive AudioRecord ferme")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur fermeture KeepAlive: ${e.message}")
        }
    }

    fun stopService() {
        stopKeepAliveAudio()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "RyderCom Intercom", NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RyderCom actif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
