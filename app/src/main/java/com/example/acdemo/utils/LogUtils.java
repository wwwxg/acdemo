package com.example.acdemo.utils;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.Process;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import android.app.ActivityManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

public class LogUtils {
    private static final String TAG = "AcDemo_LogUtils";
    private static final int KEEP_DAYS = 2;  // 保存2天的日志
    private static String logFilePath;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyy-MM-dd_HH", Locale.getDefault());
    
    private static Timer memoryLogTimer;
    private static final long MEMORY_LOG_INTERVAL = 30 * 60 * 1000; // 20分钟
    
    private static final int BUFFER_SIZE = 16384; // 16KB缓冲区
    private static final StringBuilder logBuffer = new StringBuilder(BUFFER_SIZE);
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    private static Timer flushTimer;
    private static final long FLUSH_INTERVAL = 120000; // 120秒刷新一次缓冲区
    
    private static boolean isMemoryLoggingStarted = false;
    private static volatile boolean isShuttingDown = false;
    
    public static void init(Context context) {
        File logDir = new File(context.getExternalFilesDir(null), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        // 清理旧日志
        cleanOldLogs(logDir);
        
        // 创建当前时段的日志文件
        updateLogFile(logDir);
        

        

        // 启动定时刷新
        startFlushTimer();
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
        
        final String logMessage = String.format("%s [%s] %s\n", 
            dateFormat.format(new Date()), event, details);
        
        logExecutor.execute(() -> bufferLog(logMessage));
    }
    
    private static void bufferLog(String message) {
        synchronized (logBuffer) {
            logBuffer.append(message);
            // 如果缓冲区快满了，立即刷新
            if (logBuffer.length() >= BUFFER_SIZE * 0.8) { // 80%阈值
                flushBuffer();
            }
        }
    }
    
    private static void flushBuffer() {
        synchronized (logBuffer) {
            if (logBuffer.length() == 0) return;
            
            try {
                int bufferSize = logBuffer.length();
                
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                        new FileOutputStream(logFilePath, true), 
                        StandardCharsets.UTF_8
                    ),
                    BUFFER_SIZE
                );
                writer.write(logBuffer.toString());
                writer.flush();
                writer.close();
                
                logBuffer.setLength(0);
                
                String sizeStr = bufferSize >= 1024 ? 
                    String.format("%.2fKB", bufferSize / 1024.0) : 
                    bufferSize + "字节";
                Log.d(TAG, "日志写入完成: " + sizeStr);
                
            } catch (Exception e) {
                Log.e(TAG, "刷新日志缓冲失败", e);
            }
        }
    }
    
    
    private static void startFlushTimer() {
        if (flushTimer != null) {
            flushTimer.cancel();
        }
        flushTimer = new Timer("LogFlushTimer");
        flushTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                flushBuffer();
            }
        }, FLUSH_INTERVAL, FLUSH_INTERVAL);
    }
    
    // 在服务停止时调用
    public static synchronized void shutdown() {
        // 防止重复关闭
        if (isShuttingDown) {
            Log.d(TAG, "日志系统已经在关闭过程中");
            return;
        }
        isShuttingDown = true;
        
        try {
            // 1. 先写入关闭事件到缓冲区
            synchronized (logBuffer) {
                String timestamp = dateFormat.format(new Date());
                logBuffer.append(timestamp)
                        .append(" [SERVICE] 日志系统开始关闭\n");
            }
            
            // 2. 停止所有定时器
            if (memoryLogTimer != null) {
                memoryLogTimer.cancel();
                memoryLogTimer = null;
            }
            if (flushTimer != null) {
                flushTimer.cancel();
                flushTimer = null;
            }
            
            // 3. 立即写入缓冲区内容
            writeBufferToDisk();
            
            // 4. 写入最终状态
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(
                        new FileOutputStream(logFilePath, true),
                        StandardCharsets.UTF_8
                    ))) {
                writer.write(String.format("%s [FINAL] 日志系统已关闭\n", 
                    dateFormat.format(new Date())));
                writer.flush();
            }
            
            // 5. 最后才关闭执行器
            if (logExecutor != null && !logExecutor.isShutdown()) {
                logExecutor.shutdown();
                try {
                    logExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (!logExecutor.isTerminated()) {
                        logExecutor.shutdownNow();
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "日志系统关闭过程出错", e);
        }
    }

    // 添加一个辅助方法来写入缓冲区
    private static void writeBufferToDisk() {
        if (logFilePath == null) return;
        
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(logFilePath, true),
                    StandardCharsets.UTF_8
                ),
                BUFFER_SIZE
            )) {
            writer.write(logBuffer.toString());
            writer.flush();
            logBuffer.setLength(0);
        } catch (IOException e) {
            Log.e(TAG, "写入缓冲区失败", e);
        }
    }

    // 添加一个公共方法供服务调用
    public static void checkAndUpdateLogFile(File logDir) {
        // 清理旧日志
        cleanOldLogs(logDir);
        
        // 检查当前时间，看是否需要创建新文件
        String timestamp = fileNameFormat.format(new Date());
        int hour = Integer.parseInt(timestamp.substring(timestamp.lastIndexOf("_") + 1));
        hour = (hour / 2) * 2;
        timestamp = timestamp.substring(0, timestamp.lastIndexOf("_") + 1) + String.format("%02d", hour);
        
        String newLogPath = new File(logDir, "acdemo_" + timestamp + ".log").getAbsolutePath();
        
        // 如果路径变化了，说明需要创建新文件
        if (!newLogPath.equals(logFilePath)) {
            // 先刷新旧文件的缓冲区
            flushBuffer();
            logFilePath = newLogPath;
            Log.i(TAG, "创建新日志文件: " + logFilePath);
        }
    }
} 