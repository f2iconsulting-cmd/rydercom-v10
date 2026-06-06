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
    name = "RyderComNativeAudio",
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
    private PluginCall pendingJoinCall = null;
    private String pendingWsUrl = null;
    private String pendingToken = null;
    private String pendingSessionName = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Service connecté");
            RyderComForegroundService.LocalBinder lb =
                (RyderComForegroundService.LocalBinder) binder;
            boundService = lb.getService();
            boundService.setLiveKitStateListener(RyderComNativeAudioPlugin.this);
            serviceBound = true;

            if (pendingWsUrl != null && pendingToken != null) {
                boundService.connectToLiveKit(pendingWsUrl, pendingToken,
                    pendingSessionName != null ? pendingSessionName : "RyderCom Labo");
                pendingWsUrl = null;
                pendingToken = null;
                pendingSessionName = null;
            }

            if (pendingJoinCall != null) {
                pendingJoinCall.resolve();
                pendingJoinCall = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Service déconnecté");
            serviceBound = false;
            boundService = null;
        }
    };

    @Override
    public void load() {
        super.load();
        Log.i(TAG, "Plugin RyderComNativeAudio chargé");
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
    public void joinNativeRoom(PluginCall call) {
        String wsUrl = call.getString("wsUrl");
        String token = call.getString("token");
        String sessionName = call.getString("sessionName", "RyderCom Labo");

        if (wsUrl == null || wsUrl.isEmpty()) { call.reject("wsUrl requis"); return; }
        if (token == null || token.isEmpty())  { call.reject("token requis"); return; }

        call.setKeepAlive(true);

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingJoinCall   = call;
            pendingWsUrl      = wsUrl;
            pendingToken      = token;
            pendingSessionName = sessionName;
            requestPermissionForAlias("microphone", call, "microphonePermissionCallback");
            return;
        }

        startAndBind(wsUrl, token, sessionName, call);
    }

    @PermissionCallback
    private void microphonePermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            String wsUrl = pendingWsUrl;
            String token = pendingToken;
            String sessionName = pendingSessionName;
            pendingWsUrl = null; pendingToken = null; pendingSessionName = null;
            PluginCall c = pendingJoinCall != null ? pendingJoinCall : call;
            pendingJoinCall = null;
            if (wsUrl != null && token != null) startAndBind(wsUrl, token, sessionName, c);
        } else {
            if (pendingJoinCall != null) {
                pendingJoinCall.reject("Permission microphone refusée");
                pendingJoinCall = null;
            }
        }
    }

    private void startAndBind(String wsUrl, String token, String sessionName, PluginCall call) {
        Context ctx = getContext();

        Intent serviceIntent = new Intent(ctx, RyderComForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(serviceIntent);
        } else {
            ctx.startService(serviceIntent);
        }

        if (!serviceBound) {
            pendingWsUrl      = wsUrl;
            pendingToken      = token;
            pendingSessionName = sessionName;
            pendingJoinCall   = call;
            ctx.bindService(new Intent(ctx, RyderComForegroundService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        } else if (boundService != null) {
            boundService.connectToLiveKit(wsUrl, token, sessionName);
            call.resolve();
        }
    }

    @PluginMethod
    public void checkAndRequestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean micOk = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean notifOk = ContextCompat.checkSelfPermission(getContext(), "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            if (!micOk || !notifOk) {
                requestAllPermissions(call, "permissionsCallback");
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionForAlias("microphone", call, "permissionsCallback");
                return;
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
    public void leaveNativeRoom(PluginCall call) {
        Log.i(TAG, "leaveNativeRoom");
        if (serviceBound && boundService != null) {
            boundService.disconnectFromLiveKit();
        }
        call.resolve();
    }

    @Override
    public void onStateChanged(String state) {
        Log.i(TAG, "État LiveKit → JS : " + state);
        JSObject payload = new JSObject();
        payload.put("state", state);
        notifyListeners(EVENT_CONNECTION_STATE, payload);
    }
}