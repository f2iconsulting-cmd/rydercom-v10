package com.rydercom.nativeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrackOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RyderComForegroundService : Service() {

    companion object {
        private const val TAG = "RyderComFgService"
        private const val CHANNEL_ID = "RyderComServiceChannel"
        private const val NOTIFICATION_ID = 888
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
    private var eventsJob: Job? = null

    private var savedWsUrl: String? = null
    private var savedToken: String? = null
    private var savedSessionName: String? = null
    private var isZoneBlanche = false

    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            super.onLost(network)
            if (!isZoneBlanche) {
                isZoneBlanche = true
                Log.w(TAG, "[RESEAU] Perte signal — Zone Blanche")
                updateState("RECONNECTING")
                updateNotification("Zone blanche - Recherche signal...")
            }
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (isZoneBlanche && savedWsUrl != null && savedToken != null) {
                isZoneBlanche = false
                Log.i(TAG, "[RESEAU] Signal retabli — Reconnexion LiveKit")
                serviceScope.launch {
                    try {
                        updateNotification("Reconnexion au groupe...")
                        room?.connect(savedWsUrl!!, savedToken!!)
                        Log.i(TAG, "[LiveKit] Reconnexion reussie")
                        room?.localParticipant?.setMicrophoneEnabled(true)
                        updateState("CONNECTED")
                        updateNotification("Connecte - ${savedSessionName ?: ""}")
                    } catch (e: Exception) {
                        Log.e(TAG, "[ERREUR] Echec reconnexion: ${e.message}")
                        isZoneBlanche = true
                        updateState("RECONNECTING")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.i(TAG, "Service cree")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Intercom inactif"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}
        serviceJob.cancel()
        room?.disconnect()
        room = null
        super.onDestroy()
    }

    fun setLiveKitStateListener(listener: LiveKitStateListener?) {
        stateListener = listener
    }

    fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        Log.i(TAG, "connectToLiveKit wsUrl=$wsUrl room=$sessionName")
        savedWsUrl = wsUrl
        savedToken = token
        savedSessionName = sessionName
        isZoneBlanche = false
        updateState("CONNECTING")

        val localAudioOptions = LocalAudioTrackOptions(
            noiseSuppression = true,
            echoCancellation = true,
            autoGainControl = true,
            highPassFilter = true,
            typingNoiseDetection = false
        )
        val roomOptions = RoomOptions(audioTrackCaptureDefaults = localAudioOptions)
        val newRoom = LiveKit.create(applicationContext, roomOptions)
        room = newRoom

        // Ecoute des events jusqu a CONNECTED puis SDK rendu aveugle
        eventsJob = serviceScope.launch {
            newRoom.events.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.i(TAG, "RoomEvent.Connected — SDK rendu aveugle")
                        updateState("CONNECTED")
                        updateNotification("Connecte - $sessionName")
                        enableMicrophone()
                        // SDK aveugle : on annule l ecoute des events
                        eventsJob?.cancel()
                    }
                    is RoomEvent.FailedToConnect -> {
                        Log.e(TAG, "RoomEvent.FailedToConnect")
                        updateState("ERROR")
                        updateNotification("Echec connexion")
                    }
                    else -> {}
                }
            }
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "Connexion vers $wsUrl...")
                newRoom.connect(wsUrl, token)
                Log.d(TAG, "Connexion WebSocket validee")
            } catch (e: Exception) {
                Log.e(TAG, "ECHEC CONNEXION: ${e.message}")
                updateState("ERROR")
                updateNotification("Echec connexion serveur")
            }
        }
    }

    fun disconnectFromLiveKit() {
        eventsJob?.cancel()
        savedWsUrl = null
        savedToken = null
        savedSessionName = null
        isZoneBlanche = false
        room?.disconnect()
        room = null
        updateState("DISCONNECTED")
        updateNotification("Intercom inactif")
        stopForeground(true)
        stopSelf()
    }

    fun getCurrentStatus(): String = currentStatus

    private fun enableMicrophone() {
        serviceScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(true)
                Log.i(TAG, "Microphone active!")
                stateListener?.onStateChanged("MICRO_ACTIVE")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur micro: ${e.message}")
                stateListener?.onStateChanged("MICRO_ERROR")
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
            .setContentTitle("RyderCom")
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
