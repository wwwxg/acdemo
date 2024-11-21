package com.example.acdemo;

import android.app.Application;
import com.example.acdemo.utils.CookieManager;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CookieManager.init(this);
    }
} 