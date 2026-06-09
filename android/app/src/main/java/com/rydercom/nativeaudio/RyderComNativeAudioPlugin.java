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
public class RyderComNativeAudioPlugin extends Plugin {

    private static final String TAG = "RyderComPlugin";
    private RyderComForegroundService boundService = null;
    private boolean serviceBound = false;
    private PluginCall pendingStartCall = null;
    private String pendingSessionName = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Service connecte");
            RyderComForegroundService.LocalBinder lb = (RyderComForegroundService.LocalBinder) binder;
            boundService = lb.getService();
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
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartCall = call;
            pendingSessionName = sessionName;
            requestPermissionForAlias("microphone", call, "microphonePermissionCallback");
            return;
        }
        startAndBind(sessionName, call);
    }

    @PermissionCallback
    private void microphonePermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            String sn = pendingSessionName != null ? pendingSessionName : "Ryde en cours";
            pendingSessionName = null;
            PluginCall c = pendingStartCall != null ? pendingStartCall : call;
            pendingStartCall = null;
            startAndBind(sn, c);
        } else {
            if (pendingStartCall != null) { pendingStartCall.reject("Permission microphone refusee"); pendingStartCall = null; }
        }
    }

    private void startAndBind(String sessionName, PluginCall call) {
        Context ctx = getContext();
        Intent intent = new Intent(ctx, RyderComForegroundService.class);
        intent.putExtra(RyderComForegroundService.EXTRA_SESSION_NAME, sessionName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
        if (!serviceBound) {
            pendingStartCall = call;
            ctx.bindService(new Intent(ctx, RyderComForegroundService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            call.resolve();
        }
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
}
