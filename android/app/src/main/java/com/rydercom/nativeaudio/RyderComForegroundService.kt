package com.rydercom.nativeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

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
        private const val PREFS_NAME = "RyderComPrefs"
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var persistentModule: PersistentAudioDeviceModule? = null
    private var keepAliveAudioRecord: AudioRecord? = null
    private var currentSessionName: String = "Ryde en cours"
    private var cachedRoomName: String = ""
    private var cachedIdentity: String = ""
    private var isExplicitQuitByUser: Boolean = false
    private var isRetryPending = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startKeepAliveAudio()
        Log.i(TAG, "[LIFECYCLE] Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sessionName: String
        val roomName: String
        val identity: String

        if (intent == null) {
            roomName = prefs.getString("roomName", "") ?: ""
            identity = prefs.getString("identity", "") ?: ""
            sessionName = prefs.getString("sessionName", "Ryde restauré") ?: "Ryde restauré"
            Log.w(TAG, "[LIFECYCLE] Redémarrage START_STICKY (Intent NULL). Restauration disque : room=$roomName")
        } else {
            roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: ""
            identity = intent.getStringExtra(EXTRA_IDENTITY) ?: ""
            sessionName = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Ryde en cours"
            if (roomName.isNotEmpty() && identity.isNotEmpty()) {
                prefs.edit().apply {
                    putString("roomName", roomName)
                    putString("identity", identity)
                    putString("sessionName", sessionName)
                    apply()
                }
            }
        }

        currentSessionName = sessionName
        if (roomName.isNotEmpty() && identity.isNotEmpty()) {
            isExplicitQuitByUser = false
            cachedRoomName = roomName
            cachedIdentity = identity
            Log.i(TAG, "[LIFECYCLE] Lancement sessionName=$sessionName roomName=$roomName identity=$identity")
            startForeground(NOTIFICATION_ID, buildNotification(sessionName))
            serviceScope.launch { fetchTokenAndConnect(roomName, identity, sessionName) }
        } else {
            Log.e(TAG, "[LIFECYCLE] onStartCommand ignoré : pas de coordonnées de session.")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "[LIFECYCLE] onTaskRemoved — swipe utilisateur. Nettoyage total.")
        isExplicitQuitByUser = true
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        shutdownServiceInternal()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "[LIFECYCLE] onDestroy — SharedPreferences CONSERVÉES pour START_STICKY")
        shutdownServiceInternal()
        super.onDestroy()
    }

    private fun shutdownServiceInternal() {
        stopKeepAliveAudio()
        serviceJob.cancel()
        try { room?.disconnect() } catch (e: Exception) {}
        room = null
        persistentModule = null
        stopForeground(true)
        stopSelf()
    }

    private fun startKeepAliveAudio() {
        try {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            keepAliveAudioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize * 2)
            keepAliveAudioRecord?.startRecording()
            Log.i(TAG, "[KEEPALIVE] AudioRecord ouvert")
        } catch (e: Exception) { Log.e(TAG, "[KEEPALIVE] Erreur: ${e.message}") }
    }

    private fun stopKeepAliveAudio() {
        try {
            keepAliveAudioRecord?.stop()
            keepAliveAudioRecord?.release()
            keepAliveAudioRecord = null
            Log.i(TAG, "[KEEPALIVE] AudioRecord fermé")
        } catch (e: Exception) { Log.e(TAG, "[KEEPALIVE] Erreur fermeture: ${e.message}") }
    }

    fun setLiveKitStateListener(listener: LiveKitStateListener?) { stateListener = listener }

    private suspend fun fetchTokenAndConnect(roomName: String, identity: String, sessionName: String) {
        if (isExplicitQuitByUser) return
        try {
            Log.i(TAG, "[TOKEN] Fetch début OkHttp — room=$roomName identity=$identity")
            updateState("TOKEN_FETCHING")
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val bodyStr = """{"roomName":"$roomName","participantIdentity":"$identity"}"""
            val requestBody = bodyStr.toRequestBody(mediaType)
            val request = Request.Builder().url(TOKEN_URL).post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Code HTTP : ${response.code}")
                val responseData = response.body?.string() ?: throw java.io.IOException("Corps vide")
                val token = JSONObject(responseData).getString("token")
                Log.i(TAG, "[TOKEN] Token reçu OK longueur=${token.length}")
                updateState("TOKEN_OK")
                connectToLiveKit(WS_URL, token, sessionName)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) { Log.w(TAG, "[TOKEN] Coroutine annulée par Android."); throw e }
            Log.e(TAG, "[TOKEN] Échec fetch token: ${e.message}")
            updateState("TOKEN_ERROR")
            updateNotification("Recherche réseau...")
            scheduleHardRetry()
        }
    }

    fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        if (isExplicitQuitByUser) return
        Log.i(TAG, "[LIVEKIT] connectToLiveKit wsUrl=$wsUrl")
        updateState("CONNECTING")
        updateNotification("Connexion...")
        val baseAudioModule = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        persistentModule = PersistentAudioDeviceModule(baseAudioModule)
        val localAudioOptions = LocalAudioTrackOptions(noiseSuppression=true, echoCancellation=true, autoGainControl=true, highPassFilter=true, typingNoiseDetection=false)
        val roomOptions = RoomOptions(audioTrackCaptureDefaults = localAudioOptions)
        val overrides = LiveKitOverrides(audioOptions = AudioOptions(audioDeviceModule = persistentModule))
        val newRoom = LiveKit.create(applicationContext, roomOptions, overrides)
        room = newRoom

        serviceScope.launch {
            newRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> { Log.i(TAG, "[LIVEKIT] Connected"); updateState("CONNECTED"); updateNotification("Connecté — $sessionName"); enableMicrophone() }
                    is RoomEvent.Disconnected -> { Log.w(TAG, "[LIVEKIT] Disconnected"); updateState("DISCONNECTED"); updateNotification("Déconnecté"); scheduleHardRetry() }
                    is RoomEvent.Reconnecting -> { Log.w(TAG, "[LIVEKIT] Reconnecting"); updateState("RECONNECTING"); updateNotification("Reconnexion...") }
                    is RoomEvent.Reconnected -> { Log.i(TAG, "[LIVEKIT] Reconnected"); updateState("CONNECTED"); updateNotification("Reconnecté — $sessionName"); enableMicrophone() }
                    is RoomEvent.FailedToConnect -> { Log.e(TAG, "[LIVEKIT] FailedToConnect"); updateState("ERROR"); updateNotification("Échec connexion"); scheduleHardRetry() }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            try {
                newRoom.connect(wsUrl, token)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "[LIVEKIT] ÉCHEC connect: ${e.message}")
                updateState("ERROR")
                updateNotification("Échec connexion serveur")
                scheduleHardRetry()
            }
        }
    }

    private fun scheduleHardRetry() {
        if (isExplicitQuitByUser) return
        if (isRetryPending) return
        isRetryPending = true
        serviceScope.launch {
            try {
                Log.w(TAG, "[HARD-RETRY] Attente 5s...")
                delay(5000)
                if (!isExplicitQuitByUser && cachedRoomName.isNotEmpty() && cachedIdentity.isNotEmpty()) {
                    Log.i(TAG, "[HARD-RETRY] Relance forcée...")
                    try { room?.disconnect() } catch (e: Exception) {}
                    room = null
                    isRetryPending = false
                    fetchTokenAndConnect(cachedRoomName, cachedIdentity, currentSessionName)
                } else { isRetryPending = false }
            } catch (e: Exception) { isRetryPending = false; Log.e(TAG, "[HARD-RETRY] Erreur: ${e.message}") }
        }
    }

    fun stopService() {
        Log.i(TAG, "[LIFECYCLE] stopService — bouton Quitter. Purge disque.")
        isExplicitQuitByUser = true
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        shutdownServiceInternal()
    }

    private fun enableMicrophone() {
        serviceScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(true)
                Log.i(TAG, "[MICRO] Microphone activé!")
                stateListener?.onStateChanged("MICRO_ACTIVE")
            } catch (e: Exception) { Log.e(TAG, "[MICRO] Erreur: ${e.message}"); stateListener?.onStateChanged("MICRO_ERROR") }
        }
    }

    private fun updateState(state: String) {
        Log.i(TAG, "[STATE] $currentStatus -> $state")
        currentStatus = state
        stateListener?.onStateChanged(state)
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

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
