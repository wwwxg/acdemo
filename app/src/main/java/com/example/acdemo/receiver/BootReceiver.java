package com.example.acdemo.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.example.acdemo.service.LiveWatchService;
import com.example.acdemo.utils.LogUtils;
import com.example.acdemo.utils.Logger;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AcDemo_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Logger.d(TAG, "收到广播: " + action);
        
        if (!LiveWatchService.isTemporaryStopped(context) && 
            (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
             Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))) {
            
            Intent serviceIntent = new Intent(context, LiveWatchService.class);
            serviceIntent.putExtra("action", "restart");
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                LogUtils.logEvent("BOOT", "系统启动/应用更新后服务启动成功");
                Logger.d(TAG, "服务启动成功: " + action);
            } catch (Exception e) {
                LogUtils.logEvent("ERROR", "系统启动/应用更新后服务启动失败: " + e.getMessage());
                Logger.e(TAG, "服务启动失败: " + action, e);
            }
        }
    }
} 