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
import io.livekit.android.room.track.AudioTrackPublishDefaults
import io.livekit.android.audio.AudioSwitchHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var activeAudioHandler: AudioSwitchHandler? = null

    fun selectSpeaker() {
        val handler = activeAudioHandler ?: return
        val device = handler.availableAudioDevices.firstOrNull {
            it.javaClass.simpleName == "Speakerphone"
        }
        if (device != null) handler.selectDevice(device)
        else Log.w(TAG, "[AUDIO] selectSpeaker — Speakerphone non disponible")
    }

    fun selectEarpiece() {
        val handler = activeAudioHandler ?: return
        val device = handler.availableAudioDevices.firstOrNull {
            it.javaClass.simpleName == "Earpiece"
        }
        if (device != null) handler.selectDevice(device)
        else Log.w(TAG, "[AUDIO] selectEarpiece — Earpiece non disponible")
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
    private var isRetryPending: Boolean = false
    private var retryJob: Job? = null
    private var localTrackPublished: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startKeepAliveAudio()
        Log.i(TAG, "[LIFECYCLE] Service onCreate")
        updateState("LIFECYCLE:onCreate")
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
        updateState("LIFECYCLE:onStartCommand|room=$roomName|intent=${if(intent==null) "NULL-STICKY" else "OK"}")
        updateState("LIFECYCLE:onStartCommand|room=$roomName|intent=${if(intent==null) "NULL-STICKY" else "OK"}")
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
        updateState("LIFECYCLE:onTaskRemoved")
        isExplicitQuitByUser = true
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            Log.i(TAG, "[AUDIO] Mode audio remis a NORMAL via onTaskRemoved")
        } catch (e: Exception) { Log.e(TAG, "[AUDIO] Erreur reset: ${e.message}") }
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
        updateState("LIFECYCLE:onDestroy")
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "[TOKEN] Erreur fetch token background: ${e.message}")
            updateState("TOKEN_ERROR")
            updateNotification("Erreur token")
            
            // ── MÉTHODE HARD : Échec du Fetch Token -> Retry dans 5s ──
            isRetryPending = false
            retryJob = null
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
            .setUseHardwareNoiseSuppressor(false)   // NS hardware désactivé — redondant + pollue
            .createAudioDeviceModule()
        persistentModule = PersistentAudioDeviceModule(baseAudioModule)

        val localAudioOptions = LocalAudioTrackOptions(
            noiseSuppression     = false,  // NS WebRTC désactivé — casque moto fait mieux
            echoCancellation     = true,   // AEC maintenu — propre en test HP nu
            autoGainControl      = false,  // AGC désactivé — niveau naturel
            highPassFilter       = false,  // HPF désactivé — basses préservées
            typingNoiseDetection = false
        )
        val roomOptions = RoomOptions(
            audioTrackCaptureDefaults = localAudioOptions,
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 128_000,    // 128 kbps HD (défaut voix ~32k)
                dtx = false                // pas de coupure discontinue
            )
        )
        val audioHandler = AudioSwitchHandler(applicationContext).apply {
            audioDeviceChangeListener = { audioDevices, selectedDevice ->
                val name = selectedDevice?.javaClass?.simpleName ?: ""
                val deviceKey = when {
                    name == "BluetoothHeadset" -> "bt"
                    name == "Speakerphone"     -> "hp"
                    name == "WiredHeadset"     -> "ear"
                    name == "Earpiece"         -> "ear"
                    else                       -> "hp"
                }
                Log.i(TAG, "[AUDIO] Device actif -> $deviceKey ($name)")
                updateState("AUDIO_DEVICE:$deviceKey")
            }
        }
        activeAudioHandler = audioHandler
        val overrides   = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioDeviceModule = persistentModule,
                audioHandler = audioHandler
            )
        )
        val newRoom = LiveKit.create(applicationContext, roomOptions, overrides)
        room = newRoom
        Log.i(TAG, "[LIVEKIT] Room creee — lancement collect events + connect")
        updateState("AUDIO_CONFIG:128k|AEC|noNS|noAGC|noHPF")  // v11.4.1

        serviceScope.launch {
            newRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.i(TAG, "[LIVEKIT] RoomEvent.Connected")
                        updateState("CONNECTED")
                        updateNotification("Connecte — $sessionName")
                        retryJob?.cancel()
                        retryJob = null
                        isRetryPending = false
                        localTrackPublished = false
                        enableMicrophone()
                        // Watchdog 7s — si TRACK_PUBLISHED pour notre identity n'arrive pas → réactiver micro
                        serviceScope.launch {
                            delay(7000)
                            if (!localTrackPublished && !isExplicitQuitByUser) {
                                Log.w(TAG, "[WATCHDOG] TRACK_PUBLISHED absent après 7s — réactivation micro")
                                updateState("MICRO_WATCHDOG:RETRY")
                                enableMicrophone()
                            }
                        }
                        // Participants déjà présents dans la room au moment de la connexion
                        for (participant in newRoom.remoteParticipants.values) {
                            val identity = participant.identity?.value ?: "unknown"
                            Log.i(TAG, "[LIVEKIT] Participant deja present: $identity")
                            updateState("PARTICIPANT_CONNECTED:$identity")
                            // Tracks déjà souscrites — démarrage audio immédiat
                            for (trackPub in participant.trackPublications.values) {
                                val track = trackPub.track
                                if (track != null && track.kind == io.livekit.android.room.track.Track.Kind.AUDIO) {
                                    Log.i(TAG, "[AUDIO] Track deja presente pour $identity — demarrage")
                                    try { track.start() } catch (e: Exception) { Log.e(TAG, "[AUDIO] Erreur track deja presente: ${e.message}") }
                                }
                            }
                        }
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
                    is RoomEvent.ParticipantConnected -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        Log.i(TAG, "[LIVEKIT] ParticipantConnected: $identity")
                        updateState("PARTICIPANT_CONNECTED:$identity")
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        Log.i(TAG, "[LIVEKIT] ParticipantDisconnected: $identity")
                        updateState("PARTICIPANT_DISCONNECTED:$identity")
                    }
                    is RoomEvent.TrackSubscribed -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val track = event.track
                        if (track.kind == io.livekit.android.room.track.Track.Kind.AUDIO) {
                            Log.i(TAG, "[AUDIO] Flux audio souscrit pour l'invite : $identity, demarrage du track natif")
                            try {
                                track.start()
                            } catch (e: Exception) {
                                Log.e(TAG, "[AUDIO] Erreur lors du demarrage du track natif : ${e.message}")
                            }
                            // Carte invité — signal JS au cas où PARTICIPANT_CONNECTED n'a pas été reçu
                            Log.i(TAG, "[LIVEKIT] TrackSubscribed audio → PARTICIPANT_CONNECTED:$identity")
                            updateState("PARTICIPANT_CONNECTED:$identity")
                        }
                    }
                    is RoomEvent.TrackPublished -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val kind = event.publication.kind
                        Log.i(TAG, "[LIVEKIT] TrackPublished: kind=$kind identity=$identity")
                        updateState("TRACK_PUBLISHED:$identity:$kind")
                        if (identity == cachedIdentity && kind == io.livekit.android.room.track.Track.Kind.AUDIO) {
                            localTrackPublished = true
                            Log.i(TAG, "[WATCHDOG] TRACK_PUBLISHED local confirmé — micro actif")
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val kind = event.track.kind
                        Log.w(TAG, "[LIVEKIT] TrackUnsubscribed: kind=$kind identity=$identity")
                        updateState("TRACK_UNSUBSCRIBED:$identity:$kind")
                    }
                    is RoomEvent.ConnectionQualityChanged -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val quality = event.quality
                        Log.w(TAG, "[LIVEKIT] ConnectionQualityChanged: $quality identity=$identity")
                        updateState("CONN_QUALITY:$identity:$quality")
                    }
                    is RoomEvent.TrackStreamStateChanged -> {
                        val state = event.streamState
                        val kind = event.trackPublication.kind
                        Log.w(TAG, "[LIVEKIT] TrackStreamStateChanged: $state kind=$kind")
                        updateState("TRACK_STREAM:$state:$kind")
                    }
                    is RoomEvent.TrackSubscriptionFailed -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        Log.e(TAG, "[LIVEKIT] TrackSubscriptionFailed: identity=$identity")
                        updateState("TRACK_SUB_FAILED:$identity")
                    }
                    is RoomEvent.TrackMuted -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val kind = event.publication.kind
                        Log.w(TAG, "[LIVEKIT] TrackMuted: kind=$kind identity=$identity")
                        updateState("TRACK_MUTED:$identity:$kind")
                    }
                    is RoomEvent.TrackUnmuted -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val kind = event.publication.kind
                        Log.i(TAG, "[LIVEKIT] TrackUnmuted: kind=$kind identity=$identity")
                        updateState("TRACK_UNMUTED:$identity:$kind")
                    }
                    is RoomEvent.TrackUnpublished -> {
                        val identity = event.participant.identity?.value ?: "unknown"
                        val kind = event.publication.kind
                        Log.w(TAG, "[LIVEKIT] TrackUnpublished: kind=$kind identity=$identity")
                        updateState("TRACK_UNPUBLISHED:$identity:$kind")
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "[LIVEKIT] ECHEC connect: ${e.message}")
                updateState("ERROR")
                updateNotification("Echec connexion serveur")
                
                // ── MÉTHODE HARD : Le connect à crashé -> On planifie la relance ──
                scheduleHardRetry()
            }
        }
    }

    // ── MÉTHODE HARD : Fonction de planification de Retry automatique ──
    fun reconnect() {
        Log.i(TAG, "[LIFECYCLE] reconnect() appelé depuis plugin — forçage HARD-RETRY")
        updateState("RECONNECT:FORCE")
        scheduleHardRetry()
    }

    private fun scheduleHardRetry() {
        Log.i(TAG, "[HARD-RETRY] Entree | isExplicitQuit=$isExplicitQuitByUser | isRetryPending=$isRetryPending | room=${room?.state} | cached=$cachedRoomName")
        if (isExplicitQuitByUser) {
            Log.w(TAG, "[HARD-RETRY] BLOQUE — isExplicitQuitByUser=true")
            updateState("HARD-RETRY:BLOQUE-QUIT")
            return
        }
        if (isRetryPending) {
            Log.w(TAG, "[HARD-RETRY] BLOQUE — isRetryPending=true, retry deja en cours")
            updateState("HARD-RETRY:BLOQUE-PENDING")
            return
        }
        isRetryPending = true
        updateState("HARD-RETRY:PLANIFIE")
        retryJob = serviceScope.launch {
            try {
                Log.w(TAG, "[HARD-RETRY] Attente 5s avant relance...")
                delay(5000)
                Log.i(TAG, "[HARD-RETRY] Reveil apres delay | isExplicitQuit=$isExplicitQuitByUser | cached=$cachedRoomName | identity=$cachedIdentity")
                if (!isExplicitQuitByUser && cachedRoomName.isNotEmpty() && cachedIdentity.isNotEmpty()) {
                    Log.i(TAG, "[HARD-RETRY] Lancement fetchTokenAndConnect...")
                    updateState("HARD-RETRY:EXECUTE")
                    try { room?.disconnect() } catch (e: Exception) { Log.e(TAG, "[HARD-RETRY] Erreur disconnect: ${e.message}") }
                    room = null
                    fetchTokenAndConnect(cachedRoomName, cachedIdentity, currentSessionName)
                    isRetryPending = false
                } else {
                    Log.e(TAG, "[HARD-RETRY] ABANDON | isExplicitQuit=$isExplicitQuitByUser | cached=$cachedRoomName")
                    updateState("HARD-RETRY:ABANDON")
                    isRetryPending = false
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "[HARD-RETRY] Coroutine annulee par Android")
                    isRetryPending = false
                    throw e
                }
                Log.e(TAG, "[HARD-RETRY] Erreur critique: ${e.message}")
                isRetryPending = false
            }
        }
    }

    fun stopService() {
        Log.i(TAG, "[LIFECYCLE] stopService appele")
        isExplicitQuitByUser = true
        isRetryPending = false
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
            Log.i(TAG, "[AUDIO] Mode audio remis a NORMAL via stopService")
        } catch (e: Exception) { Log.e(TAG, "[AUDIO] Erreur reset: ${e.message}") }
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