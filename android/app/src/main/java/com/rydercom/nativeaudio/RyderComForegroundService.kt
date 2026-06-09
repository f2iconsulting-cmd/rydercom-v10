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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import livekit.org.webrtc.audio.AudioDeviceModule
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject

// ── NOUVEAUX IMPORTS POUR OKHTTP (RÉSILIENT EN BACKGROUND) ──
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

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
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var persistentModule: PersistentAudioDeviceModule? = null
    private var keepAliveAudioRecord: AudioRecord? = null

    // ── MÉTHODE HARD : Variables de mémorisation de session ──
    private var currentSessionName: String = "Ryde en cours"
    private var cachedRoomName: String = ""
    private var cachedIdentity: String = ""
    private var isExplicitQuitByUser: Boolean = false

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
        isExplicitQuitByUser = false // Reset à chaque démarrage propre de l'UI
        
        // Sauvegarde en mémoire pour les futures relances de la boucle
        if (roomName.isNotEmpty() && identity.isNotEmpty()) {
            cachedRoomName = roomName
            cachedIdentity = identity
        }

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
        isExplicitQuitByUser = true
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
        isExplicitQuitByUser = true
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
        if (isExplicitQuitByUser) return
        try {
            Log.i(TAG, "[TOKEN] Fetch debut (OkHttp résilient background) — room=$roomName identity=$identity")
            updateState("TOKEN_FETCHING")
            
            // Configuration d'un client OkHttp robuste avec Timeouts
            val client = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val bodyStr = """{"roomName":"$roomName","participantIdentity":"$identity"}"""
            val requestBody = bodyStr.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .build()

            // Exécution de la requête réseau forcée
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Code HTTP inattendu : ${response.code}")
                }
                
                val responseData = response.body?.string() ?: throw java.io.IOException("Corps de réponse vide")
                val token = JSONObject(responseData).getString("token")
                
                Log.i(TAG, "[TOKEN] Token recu OK longueur=${token.length}")
                updateState("TOKEN_OK")
                connectToLiveKit(WS_URL, token, sessionName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TOKEN] Erreur fetch token background: ${e.message}")
            updateState("TOKEN_ERROR")
            updateNotification("Erreur token")
            
            // ── MÉTHODE HARD : Échec du Fetch Token -> Retry dans 5s ──
            scheduleHardRetry()
        }
    }

    fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        if (isExplicitQuitByUser) return
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
                        
                        // ── MÉTHODE HARD : Déconnexion subie -> On relance la machine ──
                        scheduleHardRetry()
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
                        
                        // ── MÉTHODE HARD : Échec d'entrée initial dans le salon -> Retry ──
                        scheduleHardRetry()
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
                
                // ── MÉTHODE HARD : Le connect à crashé -> On planifie la relance ──
                scheduleHardRetry()
            }
        }
    }

    // ── MÉTHODE HARD : Fonction de planification de Retry automatique ──
    private fun scheduleHardRetry() {
        if (isExplicitQuitByUser) {
            Log.i(TAG, "[HARD-RETRY] Annulé : Déconnexion volontaire de l'utilisateur.")
            return
        }
        
        serviceScope.launch {
            Log.w(TAG, "[HARD-RETRY] Déconnexion ou échec détecté. Attente de 5 secondes avant reconnexion...")
            delay(5000)
            
            if (!isExplicitQuitByUser && cachedRoomName.isNotEmpty() && cachedIdentity.isNotEmpty()) {
                Log.i(TAG, "[HARD-RETRY] Exécution du bouton virtuel 'Rejoindre' natif...")
                
                // Nettoyage de l'ancienne room instanciée pour repartir sur une base 100% propre
                try {
                    room?.disconnect()
                } catch (e: Exception) {}
                room = null
                
                fetchTokenAndConnect(cachedRoomName, cachedIdentity, currentSessionName)
            }
        }
    }

    fun stopService() {
        Log.i(TAG, "[LIFECYCLE] stopService appele")
        isExplicitQuitByUser = true // Bloque instantanément toute boucle de retry en arrière-plan
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