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
        
        // 添加日志
        LogUtils.logEvent("APP", "应用程序启动");
        
        // 如果有登录状态，直接启动服务
        if (CookieManager.hasCookies()) {
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
} 