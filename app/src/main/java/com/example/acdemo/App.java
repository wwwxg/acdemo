package com.example.acdemo;

import android.app.Application;
import android.content.Intent;
import com.example.acdemo.utils.CookieManager;
import com.example.acdemo.utils.LogUtils;
import com.example.acdemo.service.LiveWatchService;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CookieManager.init(this);
        LogUtils.init(this);
        
        LogUtils.logEvent("APP", "应用程序启动");
        
        if (CookieManager.hasCookies() && !LiveWatchService.wasManuallyRemoved(this)) {
            startService();
        }
    }

    private void startService() {
        Intent serviceIntent = new Intent(this, LiveWatchService.class);
        serviceIntent.putExtra("action", "start");
        try {
            startForegroundService(serviceIntent);
            LogUtils.logEvent("APP", "服务启动请求已发送");
        } catch (Exception e) {
            LogUtils.logEvent("ERROR", "服务启动失败: " + e.getMessage());
        }
    }
} 