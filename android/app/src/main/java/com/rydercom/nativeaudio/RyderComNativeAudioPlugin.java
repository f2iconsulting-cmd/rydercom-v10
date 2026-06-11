package com.rydercom.nativeaudio;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "RyderComService",
    permissions = {
        @Permission(alias = "microphone", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class RyderComNativeAudioPlugin extends Plugin
        implements RyderComForegroundService.LiveKitStateListener {

    private static final String TAG = "RyderComPlugin";
    public static final String EVENT_CONNECTION_STATE = "connectionStateChange";

    private RyderComForegroundService boundService = null;
    private boolean serviceBound = false;
    private PluginCall pendingStartCall = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Service connecte");
            RyderComForegroundService.LocalBinder lb = (RyderComForegroundService.LocalBinder) binder;
            boundService = lb.getService();
            boundService.setLiveKitStateListener(RyderComNativeAudioPlugin.this);
            serviceBound = true;
            if (pendingStartCall != null) {
                pendingStartCall.resolve();
                pendingStartCall = null;
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Service deconnecte");
            serviceBound = false;
            boundService = null;
        }
    };

    @Override
    public void load() {
        super.load();
        Log.i(TAG, "Plugin RyderComService charge");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        if (serviceBound) {
            try { getContext().unbindService(serviceConnection); } catch (Exception e) {}
            serviceBound = false;
        }
    }

    @PluginMethod
    public void startService(PluginCall call) {
        String sessionName = call.getString("sessionName", "Ryde en cours");
        String roomName = call.getString("roomName", "");
        String identity = call.getString("identity", "Rider");

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStartCall = call;
            requestPermissionForAlias("microphone", call, "microphonePermissionCallback");
            return;
        }
        startAndBind(sessionName, roomName, identity, call);
    }

    @PermissionCallback
    private void microphonePermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            PluginCall c = pendingStartCall != null ? pendingStartCall : call;
            pendingStartCall = null;
            String sessionName = c.getString("sessionName", "Ryde en cours");
            String roomName = c.getString("roomName", "");
            String identity = c.getString("identity", "Rider");
            startAndBind(sessionName, roomName, identity, c);
        } else {
            if (pendingStartCall != null) {
                pendingStartCall.reject("Permission microphone refusee");
                pendingStartCall = null;
            }
        }
    }

    private void startAndBind(String sessionName, String roomName, String identity, PluginCall call) {
        Context ctx = getContext();
        if (serviceBound && boundService != null) {
            // Service déjà actif — reconnexion directe sans nouvelle instance
            android.util.Log.i("RyderComPlugin", "[PLUGIN] Service déjà lié — reconnect() direct");
            boundService.reconnect();
            call.resolve();
            return;
        }
        Intent intent = new Intent(ctx, RyderComForegroundService.class);
        intent.putExtra(RyderComForegroundService.EXTRA_SESSION_NAME, sessionName);
        intent.putExtra(RyderComForegroundService.EXTRA_ROOM_NAME, roomName);
        intent.putExtra(RyderComForegroundService.EXTRA_IDENTITY, identity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
        pendingStartCall = call;
        ctx.bindService(new Intent(ctx, RyderComForegroundService.class),
            serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @PluginMethod
    public void stopService(PluginCall call) {
        Log.i(TAG, "stopService");
        if (serviceBound && boundService != null) {
            boundService.stopService();
        }
        if (serviceBound) {
            try { getContext().unbindService(serviceConnection); } catch (Exception e) {}
            serviceBound = false;
        }
        boundService = null;
        call.resolve();
    }

    @PluginMethod
    public void checkAndRequestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean micOk = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean notifOk = ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            if (!micOk || !notifOk) { requestAllPermissions(call, "permissionsCallback"); return; }
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("microphone", call, "permissionsCallback"); return;
            }
        }
        JSObject ret = new JSObject();
        ret.put("status", "all_granted");
        call.resolve(ret);
    }

    @PermissionCallback
    private void permissionsCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("status", "all_granted");
        call.resolve(ret);
    }

    @PluginMethod
    public void setSpeaker(PluginCall call) {
        if (boundService != null) boundService.selectSpeaker();
        call.resolve();
    }

    @PluginMethod
    public void setEarpiece(PluginCall call) {
        if (boundService != null) boundService.selectEarpiece();
        call.resolve();
    }

    @PluginMethod
    public void getMediaVolume(PluginCall call) {
        android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int percent = max > 0 ? Math.round(current * 100f / max) : 0;
        JSObject ret = new JSObject();
        ret.put("level", percent);
        call.resolve(ret);
    }

    @PluginMethod
    public void setMediaVolume(PluginCall call) {
        int level = call.getInt("level", 50);
        android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int index = Math.round(level * max / 100f);
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0);
        call.resolve();
    }

    public void onStateChanged(String state) {
        Log.i(TAG, "Etat LiveKit -> JS : " + state);
        JSObject payload = new JSObject();
        payload.put("state", state);
        notifyListeners(EVENT_CONNECTION_STATE, payload);
        if (state.startsWith("AUDIO_DEVICE:")) {
            String device = state.substring("AUDIO_DEVICE:".length());
            JSObject audioPayload = new JSObject();
            audioPayload.put("device", device);
            notifyListeners("audioDeviceChanged", audioPayload);
        }
    }
}
