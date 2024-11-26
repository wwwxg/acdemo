package com.example.acdemo.utils;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.Process;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import android.app.ActivityManager;

public class LogUtils {
    private static final String TAG = "AcDemo_LogUtils";
    private static final int KEEP_DAYS = 2;  // 保存2天的日志
    private static String logFilePath;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyy-MM-dd_HH", Locale.getDefault());
    
    private static Timer memoryLogTimer;
    private static final long MEMORY_LOG_INTERVAL = 20 * 60 * 1000; // 20分钟
    
    public static void init(Context context) {
        File logDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        // 清理旧日志
        cleanOldLogs(logDir);
        
        // 创建当前时段的日志文件
        updateLogFile(logDir);
        
        // 启动定时更新日志文件的任务
        startLogFileUpdateTask(logDir);
        
        // 启动内存状态记录定时器
        startMemoryLogging(context);
    }

    private static void updateLogFile(File logDir) {
        // 使用年月日_小时来命名文件，这样每两小时会创建一个新文件
        String timestamp = fileNameFormat.format(new Date());
        int hour = Integer.parseInt(timestamp.substring(timestamp.lastIndexOf("_") + 1));
        // 向下取整到最近的偶数小时
        hour = (hour / 2) * 2;
        timestamp = timestamp.substring(0, timestamp.lastIndexOf("_") + 1) + String.format("%02d", hour);
        
        logFilePath = new File(logDir, "acdemo_" + timestamp + ".log").getAbsolutePath();
    }

    private static void startLogFileUpdateTask(final File logDir) {
        new Thread(() -> {
            while (true) {
                try {
                    // 计算距离下一个2小时时段的时间
                    Calendar next = Calendar.getInstance();
                    next.add(Calendar.HOUR_OF_DAY, 1);
                    next.set(Calendar.MINUTE, 0);
                    next.set(Calendar.SECOND, 0);
                    next.set(Calendar.MILLISECOND, 0);
                    if (next.get(Calendar.HOUR_OF_DAY) % 2 != 0) {
                        next.add(Calendar.HOUR_OF_DAY, 1);
                    }
                    
                    long delay = next.getTimeInMillis() - System.currentTimeMillis();
                    Thread.sleep(delay);
                    
                    // 更新日志文件
                    updateLogFile(logDir);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private static void cleanOldLogs(File logDir) {
        File[] files = logDir.listFiles();
        if (files != null) {
            long now = System.currentTimeMillis();
            long keepTimeMillis = KEEP_DAYS * 24 * 60 * 60 * 1000L;
            
            for (File file : files) {
                if (now - file.lastModified() > keepTimeMillis) {
                    file.delete();
                }
            }
        }
    }

    public static void logEvent(String event, String details) {
        if (logFilePath == null) return;
        
        String timeStamp = dateFormat.format(new Date());
        String logMessage = String.format("%s [%s] %s\n", timeStamp, event, details);
        
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true));
            writer.write(logMessage);
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "写入日志失败", e);
        }
    }

    // 添加系统信息日志方法
    public static void logSystemInfo(String type, String message) {
        if (logFilePath == null) return;
        
        String timeStamp = dateFormat.format(new Date());
        String threadInfo = String.format("%d-%d", Process.myPid(), Process.myTid());
        String logMessage = String.format("%s [%s] [%s] %s\n", 
            timeStamp, type, threadInfo, message);
        
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true));
            writer.write(logMessage);
            writer.close();
        } catch (Exception e) {
            Log.e(TAG, "写入日志失败", e);
        }
    }
    
    private static void startMemoryLogging(Context context) {
        if (memoryLogTimer != null) {
            memoryLogTimer.cancel();
        }
        
        memoryLogTimer = new Timer();
        memoryLogTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                logMemoryStatus(context);
            }
        }, 0, MEMORY_LOG_INTERVAL);
    }
    
    private static void logMemoryStatus(Context context) {
        Runtime runtime = Runtime.getRuntime();
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long availMemory = memoryInfo.availMem;
        
        logSystemInfo("MEMORY", String.format(
            "已用: %dMB, 可用: %dMB, 总计: %dMB, 低内存: %b, 使用率: %.1f%%", 
            usedMemory / 1024 / 1024,
            availMemory / 1024 / 1024,
            maxMemory / 1024 / 1024,
            memoryInfo.lowMemory,
            (usedMemory * 100.0f) / maxMemory
        ));
    }
    
    // 在服务停止时调用此方法
    public static void stopMemoryLogging() {
        if (memoryLogTimer != null) {
            memoryLogTimer.cancel();
            memoryLogTimer = null;
        }
    }
} 