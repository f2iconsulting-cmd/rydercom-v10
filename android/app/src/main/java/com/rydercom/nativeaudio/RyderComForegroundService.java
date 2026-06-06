package com.rydercom.nativeaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.room.Room;
import io.livekit.android.room.RoomListener;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.SupervisorKt;

public class RyderComForegroundService extends Service {

    private static final String TAG = "RyderComFgService";
    private static final String CHANNEL_ID = "RyderComServiceChannel";
    private static final int NOTIFICATION_ID = 888;

    private final IBinder binder = new LocalBinder();
    private String currentStatus = "DISCONNECTED";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Room liveKitRoom;
    private final CoroutineScope roomScope = CoroutineScopeKt.CoroutineScope(
        Dispatchers.getMain().plus(SupervisorKt.Supervisor(null))
    );

    public interface LiveKitStateListener {
        void onStateChanged(String state);
    }

    private LiveKitStateListener stateListener;

    public class LocalBinder extends Binder {
        public RyderComForegroundService getService() {
            return RyderComForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification("DÉCONNECTÉ — Console Labo"));
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setLiveKitStateListener(LiveKitStateListener listener) {
        this.stateListener = listener;
    }

    public void connectToLiveKit(final String wsUrl, final String token, final String sessionName) {
        Log.i(TAG, "connectToLiveKit → " + wsUrl);
        updateServiceStatus("CONNECTING");
        notifyStateChange("CONNECTING");

        liveKitRoom = LiveKit.create(getApplicationContext(), new LiveKitOverrides(), roomScope);

        liveKitRoom.setListener(new RoomListener() {
            @Override
            public void onConnected(@NonNull Room room) {
                Log.i(TAG, "✅ onConnected");
                handler.post(() -> {
                    updateServiceStatus("CONNECTED");
                    updateNotification("CONNECTÉ — " + sessionName);
                    notifyStateChange("CONNECTED");
                    enableLocalAudio();
                });
            }

            @Override
            public void onDisconnect(@NonNull Room room, @NonNull Throwable error) {
                Log.w(TAG, "onDisconnect : " + error.getMessage());
                handler.post(() -> {
                    updateServiceStatus("DISCONNECTED");
                    updateNotification("DÉCONNECTÉ — Erreur de session");
                    notifyStateChange("DISCONNECTED");
                });
            }

            @Override
            public void onReconnecting(@NonNull Room room) {
                Log.i(TAG, "onReconnecting");
                handler.post(() -> {
                    updateServiceStatus("RECONNECTING");
                    updateNotification("RECONNEXION (Zone Blanche)...");
                    notifyStateChange("RECONNECTING");
                });
            }

            @Override
            public void onReconnected(@NonNull Room room) {
                Log.i(TAG, "onReconnected ✅");
                handler.post(() -> {
                    updateServiceStatus("CONNECTED");
                    updateNotification("CONNECTÉ — " + sessionName);
                    notifyStateChange("CONNECTED");
                });
            }
        });

        BuildersKt.launch(
            roomScope,
            Dispatchers.getIO(),
            CoroutineStart.DEFAULT,
            new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                @Override
                public Object invoke(CoroutineScope scope, Continuation<? super Unit> continuation) {
                    try {
                        Object result = liveKitRoom.connect(wsUrl, token, null, continuation);
                        if (result == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return result;
                        return Unit.INSTANCE;
                    } catch (Exception e) {
                        Log.e(TAG, "Erreur connect : " + e.getMessage());
                        handler.post(() -> {
                            updateServiceStatus("DISCONNECTED");
                            updateNotification("ÉCHEC CONNEXION");
                            notifyStateChange("ERROR");
                        });
                        return Unit.INSTANCE;
                    }
                }
            }
        );
    }

    private void enableLocalAudio() {
        if (liveKitRoom == null) return;
        try {
            liveKitRoom.getLocalParticipant().setMicrophoneEnabled(
                true, null,
                new Continuation<Unit>() {
                    @NonNull
                    @Override
                    public CoroutineContext getContext() {
                        return roomScope.getCoroutineContext();
                    }
                    @Override
                    public void resumeWith(@NonNull Object o) {
                        if (o instanceof kotlin.Result.Failure) {
                            Log.e(TAG, "Erreur micro");
                            notifyStateChange("MICRO_ERROR");
                        } else {
                            Log.i(TAG, "🎤 Micro actif !");
                            notifyStateChange("MICRO_ACTIVE");
                        }
                    }
                }
            );
        } catch (Exception e) {
            Log.e(TAG, "enableLocalAudio exception : " + e.getMessage());
        }
    }

    public void disconnectFromLiveKit() {
        if (liveKitRoom != null) {
            try { liveKitRoom.disconnect(); } catch (Exception e) { Log.e(TAG, "disconnect err", e); }
            liveKitRoom = null;
        }
        updateServiceStatus("DISCONNECTED");
        updateNotification("DÉCONNECTÉ — Quitté proprement");
        notifyStateChange("DISCONNECTED");
        stopForeground(true);
        stopSelf();
    }

    public String getCurrentStatus() { return currentStatus; }

    private void updateServiceStatus(String status) { this.currentStatus = status; }

    private void notifyStateChange(String state) {
        if (stateListener != null) handler.post(() -> stateListener.onStateChanged(state));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RyderCom Intercom", NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RyderCom Intercom")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, createNotification(text));
    }

    @Override
    public void onDestroy() {
        if (liveKitRoom != null) {
            try { liveKitRoom.disconnect(); } catch (Exception e) {}
            liveKitRoom = null;
        }
        super.onDestroy();
    }
}