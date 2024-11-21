package com.example.acdemo.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.example.acdemo.service.LiveWatchService;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AcDemo_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);
        
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) ||
             Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) 
            && !LiveWatchService.wasManuallyRemoved(context)) {
            Intent serviceIntent = new Intent(context, LiveWatchService.class);
            serviceIntent.putExtra("action", "restart");
            try {
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "Service started after " + action);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service after " + action, e);
            }
        }
    }
} 