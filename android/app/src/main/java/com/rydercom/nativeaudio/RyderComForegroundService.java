package com.rydercom.nativeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class RyderComForegroundService extends Service {

    private static final String TAG = "RyderComFgService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "rydercom_intercom_channel";
    private static final String CHANNEL_NAME = "RyderCom Intercom";
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    public static final String ACTION_JOIN = "com.rydercom.action.JOIN";
    public static final String ACTION_LEAVE = "com.rydercom.action.LEAVE";
    public static final String EXTRA_WS_URL = "wsUrl";
    public static final String EXTRA_TOKEN = "token";

    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_RECONNECTING = "RECONNECTING";
    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_ERROR = "ERROR";

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RyderComForegroundService getService() { return RyderComForegroundService.this; }
    }

    private String wsUrl;
    private String token;
    private String currentState = STATE_DISCONNECTED;
    private boolean isJoining = false;
    private int reconnectAttempts = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StateChangeListener stateChangeListener;

    public interface StateChangeListener {
        void onStateChanged(String state, @Nullable String errorMessage);
    }

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (wsUrl != null && token != null) scheduleReconnect();
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_JOIN.equals(action)) {
            wsUrl = intent.getStringExtra(EXTRA_WS_URL);
            token = intent.getStringExtra(EXTRA_TOKEN);
            if (wsUrl == null || token == null) {
                notifyState(STATE_ERROR, "wsUrl ou token manquant");
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, buildNotification(STATE_CONNECTING));
            simulateConnection();
        } else if (ACTION_LEAVE.equals(action)) {
            disconnectFromRoom(true);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void simulateConnection() {
        if (isJoining) return;
        isJoining = true;
        notifyState(STATE_CONNECTING, null);
        updateNotification(STATE_CONNECTING);
        handler.postDelayed(() -> {
            isJoining = false;
            reconnectAttempts = 0;
            notifyState(STATE_CONNECTED, null);
            updateNotification(STATE_CONNECTED);
            Log.i(TAG, "LiveKit ready — wsUrl=" + wsUrl);
        }, 1500);
    }

    public void disconnectFromRoom(boolean stopService) {
        handler.removeCallbacksAndMessages(null);
        reconnectAttempts = 0;
        isJoining = false;
        notifyState(STATE_DISCONNECTED, null);
        if (stopService) { stopForeground(true); stopSelf(); }
    }

    private void scheduleReconnect() {
        if (wsUrl == null || token == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            notifyState(STATE_ERROR, "Reconnexion impossible après " + MAX_RECONNECT_ATTEMPTS + " tentatives");
            stopForeground(true); stopSelf(); return;
        }
        long delayMs = Math.min(RECONNECT_DELAY_MS * (long) Math.pow(2, reconnectAttempts), 30000L);
        reconnectAttempts++;
        handler.postDelayed(() -> { if (wsUrl != null && token != null) simulateConnection(); }, delayMs);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String state) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text;
        switch (state) {
            case STATE_CONNECTED: text = "Intercom actif"; break;
            case STATE_CONNECTING: text = "Connexion..."; break;
            case STATE_RECONNECTING: text = "Reconnexion..."; break;
            default: text = "Intercom inactif"; break;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RyderCom").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build();
    }

    private void updateNotification(String state) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(state));
    }

    private void notifyState(String state, @Nullable String errorMessage) {
        this.currentState = state;
        if (stateChangeListener != null) {
            handler.post(() -> stateChangeListener.onStateChanged(state, errorMessage));
        }
    }

    public String getCurrentState() { return currentState; }
}