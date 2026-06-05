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
        @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT })
    }
)
public class RyderComNativeAudioPlugin extends Plugin
        implements RyderComForegroundService.StateChangeListener {

    private static final String TAG = "RyderComPlugin";
    public static final String EVENT_CONNECTION_STATE = "connectionStateChange";

    private RyderComForegroundService boundService = null;
    private boolean serviceBound = false;
    private PluginCall pendingJoinCall = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RyderComForegroundService.LocalBinder lb = (RyderComForegroundService.LocalBinder) binder;
            boundService = lb.getService();
            boundService.setStateChangeListener(RyderComNativeAudioPlugin.this);
            serviceBound = true;
            if (pendingJoinCall != null) {
                PluginCall call = pendingJoinCall;
                pendingJoinCall = null;
                resolveJoinCall(call);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            boundService = null;
        }
    };

    @Override
    public void load() {
        super.load();
        bindToServiceIfRunning();
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
        if (wsUrl == null || wsUrl.isEmpty()) { call.reject("wsUrl requis"); return; }
        if (token == null || token.isEmpty()) { call.reject("token requis"); return; }
        call.setKeepAlive(true);
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingJoinCall = call;
            requestPermissionForAlias("microphone", call, "microphonePermissionCallback");
            return;
        }
        startForegroundServiceAndBind(wsUrl, token);
        call.resolve();
    }

    @PermissionCallback
    private void microphonePermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            PluginCall joinCall = pendingJoinCall != null ? pendingJoinCall : call;
            pendingJoinCall = null;
            resolveJoinCall(joinCall);
        } else {
            if (pendingJoinCall != null) {
                pendingJoinCall.reject("Permission microphone refusée");
                pendingJoinCall = null;
            }
        }
    }

    private void resolveJoinCall(PluginCall call) {
        String wsUrl = call.getString("wsUrl");
        String token = call.getString("token");
        if (wsUrl == null || token == null) { call.reject("Paramètres perdus"); return; }
        startForegroundServiceAndBind(wsUrl, token);
        call.resolve();
    }

    @PluginMethod
    public void leaveNativeRoom(PluginCall call) {
        if (serviceBound && boundService != null) {
            boundService.disconnectFromRoom(true);
        } else {
            Intent i = new Intent(getContext(), RyderComForegroundService.class);
            i.setAction(RyderComForegroundService.ACTION_LEAVE);
            getContext().startService(i);
        }
        call.resolve();
    }

    private void startForegroundServiceAndBind(String wsUrl, String token) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, RyderComForegroundService.class);
        i.setAction(RyderComForegroundService.ACTION_JOIN);
        i.putExtra(RyderComForegroundService.EXTRA_WS_URL, wsUrl);
        i.putExtra(RyderComForegroundService.EXTRA_TOKEN, token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
        if (!serviceBound) {
            ctx.bindService(new Intent(ctx, RyderComForegroundService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void bindToServiceIfRunning() {
        try {
            getContext().bindService(new Intent(getContext(), RyderComForegroundService.class),
                serviceConnection, 0);
        } catch (Exception e) {}
    }

    @Override
    public void onStateChanged(String state, String errorMessage) {
        JSObject payload = new JSObject();
        payload.put("state", state);
        if (errorMessage != null && !errorMessage.isEmpty()) payload.put("error", errorMessage);
        notifyListeners(EVENT_CONNECTION_STATE, payload);
    }
}