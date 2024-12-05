package com.example.acdemo;

import android.app.Application;
import android.content.Intent;
import android.content.Context;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.BackoffPolicy;
import com.example.acdemo.utils.CookieManager;
import com.example.acdemo.utils.LogUtils;
import com.example.acdemo.utils.Logger;
import com.example.acdemo.service.LiveWatchService;
import com.example.acdemo.workers.ServiceCheckWorker;
import java.util.concurrent.TimeUnit;
import java.util.List;
import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import androidx.work.WorkInfo;
import android.os.Handler;
import androidx.work.OneTimeWorkRequest;

public class App extends Application {
    private static final String TAG = "App";
    
    @Override
    public void onCreate() {
        super.onCreate();
        CookieManager.init(this);
        LogUtils.init(this);
        
        // 初始化 WorkManager
        initializeWorkManager();
        
        // 设置服务监控
        setupServiceMonitor();
        
        LogUtils.logEvent("APP", "应用程序启动");
        
        if (CookieManager.hasCookies()) {
            startService();
        }
    }

    private void initializeWorkManager() {
        try {
            // 检查是否已经初始化
            WorkManager.getInstance(getApplicationContext());
            LogUtils.logEvent("APP", "WorkManager 已经初始化");
        } catch (IllegalStateException e) {
            // 未初始化，进行初始化
            androidx.work.Configuration config = new androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setWorkerFactory(new androidx.work.WorkerFactory() {
                    @Override
                    public ListenableWorker createWorker(
                        @NonNull Context context,
                        @NonNull String workerClassName,
                        @NonNull WorkerParameters workerParameters) {
                        
                        LogUtils.logEvent("WORKER", "创建工作器: " + workerClassName);
                        Logger.d(TAG, "创建工作器: " + workerClassName);
                        if (workerClassName.equals(ServiceCheckWorker.class.getName())) {
                            return new ServiceCheckWorker(context, workerParameters);
                        }
                        return null;
                    }
                })
                .build();

            try {
                WorkManager.initialize(this, config);
                LogUtils.logEvent("APP", "WorkManager 初始化完成");
                Logger.d(TAG, "WorkManager 初始化完成");
            } catch (Exception ex) {
                LogUtils.logEvent("ERROR", "WorkManager 初始化失败: " + ex.getMessage());
                Logger.d(TAG, "WorkManager 初始化失败: " + ex.getMessage());
            }
        }
    }

    private void setupServiceMonitor() {
        // 添加延迟初始化
        new Handler().postDelayed(() -> {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)//低电量也执行
            .setRequiresCharging(false)//不需要充电
            .setRequiresDeviceIdle(false)//不需要空闲
                .build();

            // 创建周期性任务
            PeriodicWorkRequest serviceCheckRequest =
                new PeriodicWorkRequest.Builder(
                    ServiceCheckWorker.class, 
                    15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .addTag("service_check")
                .build();

            try {
                // 注册周期性任务
                WorkManager.getInstance(this)
                    .enqueueUniquePeriodicWork(
                        "ServiceCheck",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        serviceCheckRequest);
                
                // 添加一次性立即检查任务
                OneTimeWorkRequest immediateCheck = 
                    new OneTimeWorkRequest.Builder(ServiceCheckWorker.class)
                        .addTag("immediate_check")
                        .build();
                        
                WorkManager.getInstance(this)
                    .enqueue(immediateCheck);
                
                LogUtils.logEvent("APP", "WorkManager 任务已注册");
                Logger.d(TAG, "WorkManager 任务已注册");
            } catch (Exception e) {
                LogUtils.logEvent("ERROR", "WorkManager 注册失败: " + e.getMessage());
                Logger.d(TAG, "WorkManager 注册失败: " + e.getMessage());
            }
        }, 5000);  // 延迟5秒初始化
    }

    private void startService() {
        Intent serviceIntent = new Intent(this, LiveWatchService.class);
        serviceIntent.putExtra("action", "start");
        try {
            startForegroundService(serviceIntent);
            LogUtils.logEvent("APP", "服务启动请求已发送");
            Logger.d(TAG, "服务启动请求已发送");
        } catch (Exception e) {
            LogUtils.logEvent("ERROR", "服务启动失败: " + e.getMessage());
            Logger.d(TAG, "服务启动失败: " + e.getMessage());
        }
    }
} 