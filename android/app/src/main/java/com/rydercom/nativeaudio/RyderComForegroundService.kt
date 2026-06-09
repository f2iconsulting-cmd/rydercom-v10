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
        Log.w("RyderCom", "[ADM] setMicrophoneMute($muted) intercepte — micro force OUVERT")
        delegate.setMicrophoneMute(false)
    }
}

class RyderComForegroundService : Service() {

    companion object {
        private const val TAG = "RyderComFgService"
        private const val CHANNEL_ID = "RyderComServiceChannel"
        private const val NOTIFICATION_ID = 888
        const val EXTRA_SESSION_NAME = "sessionName"
        const val EXTRA_ROOM_NAME    = "roomName"
        const val EXTRA_IDENTITY     = "identity"
        const val WS_URL    = "wss://testwave-zq4b8zeh.livekit.cloud"
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var persistentModule: PersistentAudioDeviceModule? = null
    private var keepAliveAudioRecord: AudioRecord? = null

    // Paramètres session — conservés pour reconnexion interne LiveKit SDK
    private var currentSessionName: String = "Ryde en cours"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startKeepAliveAudio()
        Log.i(TAG, "[LIFECYCLE] Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionName = intent?.getStringExtra(EXTRA_SESSION_NAME) ?: "Ryde en cours"
        val roomName    = intent?.getStringExtra(EXTRA_ROOM_NAME)    ?: ""
        val identity    = intent?.getStringExtra(EXTRA_IDENTITY)     ?: "Rider"
        currentSessionName = sessionName
        Log.i(TAG, "[LIFECYCLE] onStartCommand sessionName=$sessionName roomName=$roomName identity=$identity")
        startForeground(NOTIFICATION_ID, buildNotification(sessionName))
        if (roomName.isNotEmpty() && identity.isNotEmpty()) {
            serviceScope.launch {
                Log.i(TAG, "[TOKEN] Lancement fetchTokenAndConnect room=$roomName identity=$identity")
                fetchTokenAndConnect(roomName, identity, sessionName)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "[LIFECYCLE] onTaskRemoved — arret propre")
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "[LIFECYCLE] onDestroy")
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        persistentModule = null
        super.onDestroy()
    }

    private fun startKeepAliveAudio() {
        try {
            val sampleRate   = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat  = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize   = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            keepAliveAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize * 2
            )
            keepAliveAudioRecord?.startRecording()
            Log.i(TAG, "[KEEPALIVE] AudioRecord ouvert")
        } catch (e: Exception) {
            Log.e(TAG, "[KEEPALIVE] Erreur: ${e.message}")
        }
    }

    private fun stopKeepAliveAudio() {
        try {
            keepAliveAudioRecord?.stop()
            keepAliveAudioRecord?.release()
            keepAliveAudioRecord = null
            Log.i(TAG, "[KEEPALIVE] AudioRecord ferme")
        } catch (e: Exception) {
            Log.e(TAG, "[KEEPALIVE] Erreur fermeture: ${e.message}")
        }
    }

    fun setLiveKitStateListener(listener: LiveKitStateListener?) {
        stateListener = listener
    }

    private suspend fun fetchTokenAndConnect(roomName: String, identity: String, sessionName: String) {
        try {
            Log.i(TAG, "[TOKEN] Fetch debut — room=$roomName identity=$identity url=$TOKEN_URL")
            val url  = URL(TOKEN_URL)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"roomName":"$roomName","participantIdentity":"$identity"}"""
            conn.outputStream.write(body.toByteArray())
            val responseCode = conn.responseCode
            Log.i(TAG, "[TOKEN] HTTP responseCode=$responseCode")
            val response = conn.inputStream.bufferedReader().readText()
            val token = JSONObject(response).getString("token")
            Log.i(TAG, "[TOKEN] Token recu OK longueur=${token.length}")
            connectToLiveKit(WS_URL, token, sessionName)
        } catch (e: Exception) {
            Log.e(TAG, "[TOKEN] Erreur fetch token: ${e.message}")
            updateState("TOKEN_ERROR")
            updateNotification("Erreur token")
        }
    }

    fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        Log.i(TAG, "[LIVEKIT] connectToLiveKit wsUrl=$wsUrl sessionName=$sessionName")
        updateState("CONNECTING")
        updateNotification("Connexion...")

        val baseAudioModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        persistentModule = PersistentAudioDeviceModule(baseAudioModule)

        val localAudioOptions = LocalAudioTrackOptions(
            noiseSuppression  = true,
            echoCancellation  = true,
            autoGainControl   = true,
            highPassFilter    = true,
            typingNoiseDetection = false
        )
        val roomOptions = RoomOptions(audioTrackCaptureDefaults = localAudioOptions)
        val overrides   = LiveKitOverrides(
            audioOptions = AudioOptions(audioDeviceModule = persistentModule)
        )
        val newRoom = LiveKit.create(applicationContext, roomOptions, overrides)
        room = newRoom
        Log.i(TAG, "[LIVEKIT] Room creee — lancement collect events + connect")

        serviceScope.launch {
            newRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.i(TAG, "[LIVEKIT] RoomEvent.Connected")
                        updateState("CONNECTED")
                        updateNotification("Connecte — $sessionName")
                        enableMicrophone()
                    }
                    is RoomEvent.Disconnected -> {
                        Log.w(TAG, "[LIVEKIT] RoomEvent.Disconnected")
                        updateState("DISCONNECTED")
                        updateNotification("Deconnecte")
                    }
                    is RoomEvent.Reconnecting -> {
                        Log.w(TAG, "[LIVEKIT] RoomEvent.Reconnecting")
                        updateState("RECONNECTING")
                        updateNotification("Reconnexion...")
                    }
                    is RoomEvent.Reconnected -> {
                        Log.i(TAG, "[LIVEKIT] RoomEvent.Reconnected")
                        updateState("CONNECTED")
                        updateNotification("Reconnecte — $sessionName")
                        enableMicrophone()
                    }
                    is RoomEvent.FailedToConnect -> {
                        Log.e(TAG, "[LIVEKIT] RoomEvent.FailedToConnect")
                        updateState("ERROR")
                        updateNotification("Echec connexion")
                    }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            try {
                Log.i(TAG, "[LIVEKIT] newRoom.connect() debut")
                newRoom.connect(wsUrl, token)
                Log.i(TAG, "[LIVEKIT] newRoom.connect() termine")
            } catch (e: Exception) {
                Log.e(TAG, "[LIVEKIT] ECHEC connect: ${e.message}")
                updateState("ERROR")
                updateNotification("Echec connexion serveur")
            }
        }
    }

    fun stopService() {
        Log.i(TAG, "[LIFECYCLE] stopService appele")
        stopKeepAliveAudio()
        serviceJob.cancel()
        room?.disconnect()
        room = null
        persistentModule = null
        stopForeground(true)
        stopSelf()
    }

    private fun enableMicrophone() {
        serviceScope.launch {
            try {
                Log.i(TAG, "[MICRO] setMicrophoneEnabled(true) debut")
                room?.localParticipant?.setMicrophoneEnabled(true)
                Log.i(TAG, "[MICRO] Microphone active!")
                stateListener?.onStateChanged("MICRO_ACTIVE")
            } catch (e: Exception) {
                Log.e(TAG, "[MICRO] Erreur: ${e.message}")
                stateListener?.onStateChanged("MICRO_ERROR")
            }
        }
    }

    private fun updateState(state: String) {
        Log.i(TAG, "[STATE] $currentStatus -> $state")
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
