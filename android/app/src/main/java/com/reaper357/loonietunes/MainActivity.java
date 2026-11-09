package com.reaper357.loonietunes;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaControlsPlugin.class); // <--- Make sure this is here
        super.onCreate(savedInstanceState);
    }
}
