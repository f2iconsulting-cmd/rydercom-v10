package com.rydercom.nativeaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.RoomListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service cree")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Intercom inactif"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceJob.cancel()
        room?.disconnect()
        room = null
        super.onDestroy()
    }

    fun setLiveKitStateListener(listener: LiveKitStateListener?) {
        stateListener = listener
    }

    fun connectToLiveKit(wsUrl: String, token: String, sessionName: String) {
        Log.i(TAG, "connectToLiveKit wsUrl=$wsUrl")
        updateState("CONNECTING")
        room = LiveKit.create(applicationContext)
        room?.addListener(object : RoomListener {
            override fun onConnected(room: Room) {
                Log.i(TAG, "onConnected OK")
                updateState("CONNECTED")
                updateNotification("Connecte - $sessionName")
                enableMicrophone()
            }
            override fun onDisconnected(room: Room, error: Exception?) {
                Log.w(TAG, "onDisconnected: ${error?.message}")
                updateState("DISCONNECTED")
                updateNotification("Deconnecte")
            }
            override fun onReconnecting(room: Room) {
                Log.i(TAG, "onReconnecting")
                updateState("RECONNECTING")
                updateNotification("Reconnexion zone blanche...")
            }
            override fun onReconnected(room: Room) {
                Log.i(TAG, "onReconnected OK")
                updateState("CONNECTED")
                updateNotification("Reconnecte - $sessionName")
            }
            override fun onFailedToConnect(room: Room, error: Exception) {
                Log.e(TAG, "onFailedToConnect: ${error.message}")
                updateState("ERROR")
                updateNotification("Echec connexion")
            }
        })
        serviceScope.launch {
            try {
                room?.connect(wsUrl, token)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur connect: ${e.message}")
                updateState("ERROR")
                updateNotification("Erreur: ${e.message}")
            }
        }
    }

    fun disconnectFromLiveKit() {
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
