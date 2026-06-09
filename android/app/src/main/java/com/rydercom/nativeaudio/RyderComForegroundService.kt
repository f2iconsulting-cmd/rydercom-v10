package com.rydercom.nativeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.AudioOptions
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import java.net.URL
import org.json.JSONObject

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
        const val EXTRA_ROOM_NAME = "roomName"
        const val EXTRA_IDENTITY = "identity"
        const val WS_URL = "wss://testwave-zq4b8zeh.livekit.cloud"
        const val TOKEN_URL = "https://helpful-flower-4129.puter.work/api/get-token"
    }

    interface LiveKitStateListener {
        fun onStateChanged(state: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RyderComForegroundService = this@RyderComForegroundService
    }

    private val binder = LocalBinder()
    private var stateListener: LiveKitStateListener? = null
    private var currentStatus = "DISCONNECTED"
    private var room: Room? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var persistentModule: PersistentAudioDeviceModule? = null
    private var keepAliveAudioRecord: AudioRecord? = null
    private var currentRoomName: String? = null
    private var currentIdentity: String? = null
    private var currentSessionName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startKeepAliveAudio()
        Log.i(TAG, "Service cree")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionName = intent?.getStringExtra(EXTRA_SESSION_NAME) ?: "Ryde en cours"
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME)
        val identity = intent?.getStringExtra(EXTRA_IDENTITY)
        currentSessionName = sessionName
        if (roomName != null) currentRoomName = roomName
        if (identity != null) currentIdentity = identity
        startForeground(NOTIFICATION_ID, buildNotification(sessionName))
        if (roomName != null && identity != null) {
            serviceScope.launch { fetchTokenAndConnect(roomName, identity, sessionName) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved - arret propre du service")
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        persistentModule = null
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
            keepAliveAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize * 2
            )
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

    fun setLiveKitStateListener(listener: LiveKitStateListener?) {
        stateListener = listener
    }

    private suspend fun fetchTokenAndConnect(roomName: String, identity: String, sessionName: String) {
        try {
            Log.i(TAG, "Fetch token pour room=$roomName identity=$identity")
            val url = URL(TOKEN_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"roomName":"$roomName","participantIdentity":"$identity"}"""
            conn.outputStream.write(body.toByteArray())
            val response = conn.inputStream.bufferedReader().readText()
            val token = JSONObject(response).getString("token")
            Log.i(TAG, "Token recu OK")
            connectToLiveKit(WS_URL, token, sessionName)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur fetch token: ${e.message}")
            updateState("ERROR")
        }
    }

    private fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        Log.i(TAG, "connectToLiveKit wsUrl=$wsUrl")
        updateState("CONNECTING")

        val baseAudioModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        persistentModule = PersistentAudioDeviceModule(baseAudioModule)

        val localAudioOptions = LocalAudioTrackOptions(
            noiseSuppression = true,
            echoCancellation = true,
            autoGainControl = true,
            highPassFilter = true,
            typingNoiseDetection = false
        )
        val roomOptions = RoomOptions(audioTrackCaptureDefaults = localAudioOptions)
        val overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioDeviceModule = persistentModule)
        )
        val newRoom = LiveKit.create(applicationContext, roomOptions, overrides)
        room = newRoom

        serviceScope.launch {
            newRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        updateState("CONNECTED")
                        updateNotification("Connecte - $sessionName")
                        enableMicrophone()
                    }
                    is RoomEvent.Disconnected -> {
                        updateState("DISCONNECTED")
                        updateNotification("Deconnecte")
                        // Reconnexion automatique
                        val rn = currentRoomName
                        val id = currentIdentity
                        val sn = currentSessionName ?: "Ryde"
                        if (rn != null && id != null) {
                            Log.i(TAG, "Reconnexion automatique dans 2s...")
                            kotlinx.coroutines.delay(2000)
                            fetchTokenAndConnect(rn, id, sn)
                        }
                    }
                    is RoomEvent.Reconnecting -> {
                        updateState("RECONNECTING")
                        updateNotification("Reconnexion...")
                    }
                    is RoomEvent.Reconnected -> {
                        updateState("CONNECTED")
                        updateNotification("Reconnecte - $sessionName")
                        enableMicrophone()
                    }
                    is RoomEvent.FailedToConnect -> {
                        updateState("ERROR")
                        updateNotification("Echec connexion")
                        // Retry
                        val rn = currentRoomName
                        val id = currentIdentity
                        val sn = currentSessionName ?: "Ryde"
                        if (rn != null && id != null) {
                            Log.i(TAG, "Retry apres echec dans 3s...")
                            kotlinx.coroutines.delay(3000)
                            fetchTokenAndConnect(rn, id, sn)
                        }
                    }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            try {
                newRoom.connect(wsUrl, token)
            } catch (e: Exception) {
                Log.e(TAG, "ECHEC CONNEXION: ${e.message}")
                updateState("ERROR")
            }
        }
    }

    fun stopService() {
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        persistentModule = null
        currentRoomName = null
        currentIdentity = null
        stopForeground(true)
        stopSelf()
    }

    private fun enableMicrophone() {
        serviceScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(true)
                Log.i(TAG, "Microphone active!")
                stateListener?.onStateChanged("MICRO_ACTIVE")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur micro: ${e.message}")
            }
        }
    }

    private fun updateState(state: String) {
        currentStatus = state
        stateListener?.onStateChanged(state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RyderCom Intercom", NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
