package com.example.acdemo.workers;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.acdemo.service.LiveWatchService;
import com.example.acdemo.utils.LogUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.example.acdemo.utils.Logger;


public class ServiceCheckWorker extends Worker {
    private static final String TAG = "ServiceCheckWorker";
    private static final String PREF_RESTART_COUNT = "restart_count";
    private static final String PREF_LAST_RESTART_TIME = "last_restart_time";
    private static final int MAX_RESTARTS_PER_DAY = 30;  // 每天最大重启次数
    
    public ServiceCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        LogUtils.logEvent("WORKER", "WorkManager 开始检查服务状态");
        
        try {
            if (!LiveWatchService.isServiceFullyRunning(context)) {
                LogUtils.logEvent("WORKER", "服务未运行，尝试重启");
                
                if (canRestartService(context)) {
                    Intent intent = new Intent(context, LiveWatchService.class);
                    intent.putExtra("action", "restart");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent);
                    } else {
                        context.startService(intent);
                    }
                    
                    // 等待服务启动
                    Thread.sleep(10000);
                    
                    // 再次检查服务是否成功启动
                    boolean serviceStarted = LiveWatchService.isServiceFullyRunning(context);
                    LogUtils.logEvent("WORKER", "服务重启" + (serviceStarted ? "成功" : "失败"));
                    Logger.d(TAG, "服务重启" + (serviceStarted ? "成功" : "失败"));
                }
            }else{
                LogUtils.logEvent("WORKER", "服务正常运行");
                Logger.d(TAG, "服务正常运行");
            }
            
            return Result.success();
        } catch (Exception e) {
            LogUtils.logEvent("ERROR", "WorkManager 检查失败: " + e.getMessage());
            return Result.retry();
        }
    }

    private boolean canRestartService(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE);
        long lastRestartTime = prefs.getLong(PREF_LAST_RESTART_TIME, 0);
        int restartCount = prefs.getInt(PREF_RESTART_COUNT, 0);
        
        // 检查是否是新的一天
        if (!isSameDay(lastRestartTime, System.currentTimeMillis())) {
            prefs.edit()
                .putInt(PREF_RESTART_COUNT, 0)
                .putLong(PREF_LAST_RESTART_TIME, System.currentTimeMillis())
                .apply();
            return true;
        }
        
        // 检查重启次数
        if (restartCount >= MAX_RESTARTS_PER_DAY) {
            LogUtils.logEvent("SERVICE", "达到每日重启上限，停止重启");
            return false;
        }
        
        // 更新重启计数
        prefs.edit()
            .putInt(PREF_RESTART_COUNT, restartCount + 1)
            .putLong(PREF_LAST_RESTART_TIME, System.currentTimeMillis())
            .apply();
            
        return true;
    }

    private boolean isSameDay(long time1, long time2) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return dateFormat.format(new Date(time1)).equals(dateFormat.format(new Date(time2)));
    }
}