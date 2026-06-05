package com.rydercom.nativeaudio;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(RyderComNativeAudioPlugin.class);
        super.onCreate(savedInstanceState);
    }
}