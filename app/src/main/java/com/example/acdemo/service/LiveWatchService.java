package com.example.acdemo.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.view.View;
import java.util.Timer;
import java.util.TimerTask;
import java.io.IOException;
import android.os.Process;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.FormBody;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import okhttp3.HttpUrl;
import com.example.acdemo.R;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.content.Context;
import com.example.acdemo.MainActivity;
import android.net.ConnectivityManager;
import android.net.Network;
import com.example.acdemo.utils.WatchStatsManager;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.os.SystemClock;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import android.app.ActivityManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.Build;
import com.example.acdemo.utils.LogUtils;
import com.example.acdemo.utils.Logger;
import java.util.HashSet;
import java.util.Set;
import com.example.acdemo.utils.CookieManager;
import java.util.ArrayList;
import java.util.List;
import android.view.ViewGroup;
import org.json.JSONException;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.net.NetworkCapabilities;
import android.widget.FrameLayout;

public class LiveWatchService extends Service {
    private static final String TAG = "LiveWatchService";
    private Map<String, Timer> watchingTimers = new HashMap<>();
    private Map<String, WebView> webviews = new HashMap<>();
    private Map<String, Long> lastHeartbeatTimes = new HashMap<>();
    private static final long HEARTBEAT_TIMEOUT = 90000;
    public static Map<String, Boolean> roomStatuses = new HashMap<>();
    private android.os.PowerManager.WakeLock wakeLock;
    private OkHttpClient client;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final int NOTIFICATION_ID = 1;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long NOTIFICATION_UPDATE_INTERVAL = 60000; // 60秒更新一次
    private Map<String, Long> lastHeartbeatLogTimes = new HashMap<>(); // 添加这个变量来跟踪上次记录日志的时间
    private static final long HEARTBEAT_LOG_INTERVAL = 30000; // 30秒的日志记录间隔
    private static final String MEDAL_LIST_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/list";
    private static final String LIVE_LIST_API = "https://live.acfun.cn/api/channel/list?count=100&pcursor=&filters=[%7B%22filterType%22:3,+%22filterId%22:0%7D]";
    private static final String MEDAL_INFO_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/extraInfo?uperId=%s";
    private Set<String> medalUperIds = new HashSet<>(); // 存储所有粉丝牌UP主ID
    private static final long REFRESH_INTERVAL = 120000; // 2分钟刷新一次
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            Logger.d(TAG, "执行定时刷新任务");
            fetchMedalList();
            
            // 添加延迟统计，确保在处理完列表后执行
            mainHandler.postDelayed(() -> {
                String webviewStats = String.format("定时刷新后WebView数量: %d, WebView列表: ", webviews.size());
                StringBuilder statsBuilder = new StringBuilder(webviewStats);
                
                for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
                    String uperId = entry.getKey();
                    WatchStatsManager.WatchData data = WatchStatsManager.getInstance(LiveWatchService.this)
                        .getAllStats().get(uperId);
                    String nickname = data != null ? data.name : uperId;
                    statsBuilder.append(nickname).append("(").append(uperId).append("), ");
                }
                
                Logger.i(TAG, statsBuilder.toString());
                LogUtils.logEvent("WEBVIEW_STATS", statsBuilder.toString());
            }, 10000); // 延迟10s确保在处理完列表后执行
            
            mainHandler.postDelayed(this, REFRESH_INTERVAL);
        }
    };
    
    private final Runnable notificationUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL);
        }
    };
    
    private final Runnable serviceCheckRunnable = new Runnable() {
        @Override
        public void run() {
            // 检查服务状态，但只在非手动移除的情况下重启
            if (!isServiceRunning() && !wasManuallyRemoved(getApplicationContext())) {
                Logger.d(TAG, "服务意外停止，尝试重启");
                Intent intent = new Intent(getApplicationContext(), LiveWatchService.class);
                intent.putExtra("action", "restart");
                startForegroundService(intent);
            }
            mainHandler.postDelayed(this, 5 * 60 * 1000);
        }
    };
    
    private static final String PREF_NAME = "service_prefs";
    private static final String KEY_MANUALLY_REMOVED = "manually_removed";
    private static final String KEY_SERVICE_RUNNING = "service_running";
    private static final String KEY_LAST_PID = "last_pid";
    private static final String TRAFFIC_PREF_NAME = "traffic_stats";
    private static final String KEY_MOBILE_RX = "mobile_rx";
    private static final String KEY_MOBILE_TX = "mobile_tx";
    private static final String KEY_LAST_RESET_DATE = "last_reset_date";
    private long lastMobileRx = 0;
    private long lastMobileTx = 0;
    
    private static final String CHANNEL_ID = "live_watch_service";
    private static final long UPDATE_TRAFFIC_INTERVAL = 60000; // 60秒更新一次
    
    private final Runnable trafficUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateTrafficStats();
            mainHandler.postDelayed(this, UPDATE_TRAFFIC_INTERVAL);
        }
    };
    
    public static boolean wasManuallyRemoved(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_MANUALLY_REMOVED, false);
    }
    
    private static void setManuallyRemoved(Context context, boolean removed) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_MANUALLY_REMOVED, removed).apply();
    }
    
    private static void setServiceRunning(Context context, boolean running) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply();
    }
    
    public static boolean isServiceRunning(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_SERVICE_RUNNING, false);
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 设置手动移除标记
        setManuallyRemoved(this, true);
        
        // 停止所有观看
        for (String uperId : new HashSet<>(webviews.keySet())) {
            stopWatching(uperId);
        }
        
        // 移除所有回调
        mainHandler.removeCallbacksAndMessages(null);
        
        // 取消所有定时器
        if (watchingTimers != null) {
            for (Timer timer : watchingTimers.values()) {
                timer.cancel();
            }
            watchingTimers.clear();
        }
        
        // 停止内存日志记录
        LogUtils.stopMemoryLogging();
        
        // 释放 WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        // 注销网络回调
        if (networkCallback != null) {
            try {
                ConnectivityManager connectivityManager = 
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "注销网络回调失败", e);
            }
        }
        
        LogUtils.logEvent("SERVICE", "用户手动移除服务");
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        // 启动前台服务
        startForeground();
        
        // 检查是否是被系统杀死后重启
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int lastPid = prefs.getInt(KEY_LAST_PID, -1);
        int currentPid = Process.myPid();
        
        if (lastPid != -1 && lastPid != currentPid && wasManuallyRemoved(this)) {
            // 如果是手动移除状态下被系统重启，则立即停止
            LogUtils.logEvent("SERVICE", "检测到手动移除状态下被系统重启，停止服务");
            stopSelf();
            return;
        }
        
        // 记录当前进程ID
        prefs.edit().putInt(KEY_LAST_PID, currentPid).apply();
        
        setServiceRunning(this, true);
        // 服务创建时重置状态
        setManuallyRemoved(this, false);
        
        // 添加更多的日检查
        Logger.i(TAG, "服务开始创建");
        LogUtils.logEvent("SERVICE", "服务创建开始");
        
        try {
            // 记录系统信息
            LogUtils.logSystemInfo("SYSTEM", String.format("Android SDK: %d, Brand: %s, Model: %s", 
                Build.VERSION.SDK_INT, Build.BRAND, Build.MODEL));
            
            // 记录更详细的内存信息
            Runtime runtime = Runtime.getRuntime();
            LogUtils.logSystemInfo("MEMORY", String.format(
                "Max: %dMB, Total: %dMB, Free: %dMB, Used: %dMB", 
                runtime.maxMemory() / 1024 / 1024,
                runtime.totalMemory() / 1024 / 1024,
                runtime.freeMemory() / 1024 / 1024,
                (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            ));
            Logger.i(TAG, "内存信息记录完成");
            
            // 记录应用内存限制
            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            LogUtils.logSystemInfo("MEMORY", String.format(
                "Memory Class: %dMB, Large Memory Class: %dMB", 
                activityManager.getMemoryClass(),
                activityManager.getLargeMemoryClass()
            ));
            Logger.i(TAG, "应用内存限制记录完成");
            
            // 提高服务进程优先级
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            Logger.i(TAG, "进程优先级设置完成");
            
            // 修改 WakeLock 配置
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK | 
                android.os.PowerManager.ON_AFTER_RELEASE,
                "AcDemo:LiveWatchWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(24 * 60 * 60 * 1000L);
            Logger.i(TAG, "WakeLock 配置完成");
            LogUtils.logEvent("SERVICE", "WakeLock已配置");
            
            // 初始化 OkHttpClient
            client = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
            Logger.i(TAG, "OkHttpClient 初始化完成");
            LogUtils.logEvent("SERVICE", "网络客户端已初始化");
            
            // 启动前台服务
            startForeground();
            Logger.i(TAG, "前台服务启动完成");
            
            // 注册网络回调
            registerNetworkCallback();
            Logger.i(TAG, "网络回调注册完成");
            
            // 启动服务监控
            startServiceWatchdog();
            startServiceChecker();
            startNotificationUpdate();
            Logger.i(TAG, "服务监控启动完成");
            
            // 立即请求一次粉丝牌列表
            fetchMedalList();
            
            
            // 启动定时刷新
            startTimers();
            Logger.i(TAG, "定时刷新启动完成");
            
            Logger.i(TAG, "服务创建完成");
            
        } catch (Exception e) {
            Logger.e(TAG, "服务创建过程中发生错误", e);
            LogUtils.logEvent("ERROR", "服务创建失败: " + e.getMessage());
            stopSelf(); // 发生错误时停止服务
        }
        
        // 初始化流量统计
        initTrafficStats();
    }
    
    private void startTimers() {
        // 停止现有的定时器
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.removeCallbacks(notificationUpdateRunnable);
        mainHandler.removeCallbacks(serviceCheckRunnable);
        
        // 重新启动定时器
        mainHandler.post(refreshRunnable);
        mainHandler.post(notificationUpdateRunnable);
        mainHandler.post(serviceCheckRunnable);
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (wasManuallyRemoved(this)) {
            LogUtils.logEvent("SERVICE", "服务处于手动移除状态，停止启动");
            stopSelf();
            return START_NOT_STICKY;
        }
        // LogUtils.logEvent("SERVICE", "服务启动");
        if (intent != null) {
            String action = intent.getStringExtra("action");
            String uperId = intent.getStringExtra("uperId");
            String liveId = intent.getStringExtra("liveId");
            String nickname = intent.getStringExtra("nickname");  // 获取传入的昵称
            
            // 记录服务操作
            // LogUtils.logEvent("SERVICE", String.format("操作: %s, 主播: %s(%s)", 
            //     action != null ? action : "start", 
            //     nickname != null ? nickname : uperId,
            //     uperId));
            
            if ("start".equals(action) || (liveId != null && uperId != null)) {
                startWatching(liveId, uperId, nickname);  // 修改这里的调用
            } else if ("stop".equals(action) && uperId != null) {
                stopWatching(uperId);
            }
        }
        if (intent == null) {
            LogUtils.logEvent("SERVICE", "服务被系统杀死后重启");
            return START_STICKY;
        }
        return START_STICKY;
    }
    
    private void startWatching(String liveId, String uperId, String nickname) {
        // 参数验证
        if (liveId == null || uperId == null || nickname == null) {
            Logger.e(TAG, "无效的参数 - liveId: " + liveId + ", uperId: " + uperId + ", nickname: " + nickname);
            return;
        }
        
        // 检查是否已经在观看
        if (webviews.containsKey(uperId)) {
            Logger.d(TAG, "已存在WebView，跳过创建: " + nickname + "(" + uperId + ")");
            return;
        }
        
        // 检查是否在主线程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createAndSetupWebView(liveId, uperId, nickname);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> 
                createAndSetupWebView(liveId, uperId, nickname));
        }
    }
    
    // 将WebView创建逻辑抽取到单独的方法
    private void createAndSetupWebView(String liveId, String uperId, String nickname) {
        // 再次检查，防止在切换线程期间创建了WebView
        if (webviews.containsKey(uperId)) {
            Logger.d(TAG, "切换线程后已存在WebView，跳过创建: " + nickname + "(" + uperId + ")");
            return;
        }
        
        WebView webView = createWebView();
        if (webView != null) {
            setupWebView(webView, uperId);
            webviews.put(uperId, webView);
            loadLiveRoom(webView, liveId, uperId);
            
            Logger.i(TAG, "创建新WebView并开始观看: " + nickname + "(" + uperId + ")");
            LogUtils.logEvent("WATCH", "创建WebView开始观看: " + nickname + "(" + uperId + ")");
            
            updateNotification();
        }
    }
    
    private WebView createWebView() {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        
        // 禁用图片加载以节省资源
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        
        // 禁用缓存
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        
        // 设置为软件渲染
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        
        // 添加到一个不可见的ViewGroup中
        FrameLayout container = new FrameLayout(this);
        container.setVisibility(View.GONE);
        container.addView(webView, 1, 1); // 最小尺寸
        
        return webView;
    }
    
    private void loadLiveRoom(WebView webView, String liveId, String uperId) {
        // 确保在主线程中执行
        mainHandler.post(() -> {
            String liveUrl = String.format("https://live.acfun.cn/room/%s?theme=default&showAuthorclubOnly=", uperId);
            webView.loadUrl(liveUrl);
            updateNotification();//更新通知栏
        });

        // 启动心跳检测定时器
        Timer heartbeatTimer = new Timer();
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Long lastHeartbeat = lastHeartbeatTimes.get(uperId);
                if (lastHeartbeat != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat > HEARTBEAT_TIMEOUT) {
                        Log.d(TAG, "播间心跳超时，判定为已下播: " + uperId);
                        new Handler(Looper.getMainLooper()).post(() -> stopWatching(uperId));
                    }
                }
            }
        }, HEARTBEAT_TIMEOUT, HEARTBEAT_TIMEOUT);
        
        watchingTimers.put(uperId, heartbeatTimer);
    }

    private String extractToken(String cookies, String tokenName) {
        String[] pairs = cookies.split(";");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.startsWith(tokenName + "=")) {
                return pair.substring(tokenName.length() + 1);
            }
        }
        return null;
    }
    
    @Override
    public void onDestroy() {
        LogUtils.logEvent("SERVICE", "服务停止");
        
        // 先移除所有回调
        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.removeCallbacks(notificationUpdateRunnable);
        mainHandler.removeCallbacks(serviceCheckRunnable);
        
        // 停止所有观看
        for (String uperId : new HashSet<>(webviews.keySet())) {
            stopWatching(uperId);
        }
        
        // 清理其他资源
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                Logger.e(TAG, "释放WakeLock失败: " + e.getMessage());
            }
        }
        
        // 注销网络回调
        if (networkCallback != null) {
            try {
                ConnectivityManager connectivityManager = 
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Logger.e(TAG, "注销网络回调失败: " + e.getMessage());
            }
        }
        
        LogUtils.stopMemoryLogging();
        super.onDestroy();
        setServiceRunning(this, false);
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "live_watch_service",
                "AcFun挂机服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于显示服务运行状态");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        // 创建展开后显示的内容
        StringBuilder contentBuilder = new StringBuilder();
        
        // 只在移动数据时显示流量统计
        if (isMobileDataActive()) {
            SharedPreferences prefs = getSharedPreferences(TRAFFIC_PREF_NAME, Context.MODE_PRIVATE);
            long totalRx = prefs.getLong(KEY_MOBILE_RX, 0);
            long totalTx = prefs.getLong(KEY_MOBILE_TX, 0);
            contentBuilder.append("移动数据：")
                .append(formatTraffic(totalRx + totalTx))
                .append("\n\n");
        }
        
        // 添加主播列表到展开内容
        for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
            String uperId = entry.getKey();
            WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this)
                .getAllStats().get(uperId);
            String nickname = data != null ? data.name : uperId;
            
            contentBuilder.append(nickname)
                .append("(")
                .append(data != null ? data.degree : 0)
                .append("/360)")
                .append("\n");
        }

        // 创建点击通知时的意图
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, 
            notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // 创建通知构建器
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AcFun粉丝牌助手")
            .setContentText("正在运行中") // 未展开时只显示运行状态
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true);

        // 只有在有内容时才添加展开样式
        if (contentBuilder.length() > 0) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                .setBigContentTitle("AcFun粉丝牌助手")
                .bigText(contentBuilder.toString()));
        }

        return builder.build();
    }

    // 移除之前的 updateNotificationWithTraffic 方法，改用 updateNotification
    private void updateTrafficStats() {
        // 只在移动数据时统计流量
        if (!isMobileDataActive()) {
            return;
        }

        // 检查是否需要重置
        checkAndResetDailyTraffic();
        
        // 获取应用的 UID
        int uid = Process.myUid();
        
        // 获取当前应用的流量
        long currentRx = TrafficStats.getUidRxBytes(uid);
        long currentTx = TrafficStats.getUidTxBytes(uid);
        
        // 计算新增流量
        long rxDiff = currentRx - lastMobileRx;
        long txDiff = currentTx - lastMobileTx;
        
        if (rxDiff >= 0 && txDiff >= 0) {  // 防止负数
            // 更新累计流量
            SharedPreferences prefs = getSharedPreferences(TRAFFIC_PREF_NAME, Context.MODE_PRIVATE);
            long totalRx = prefs.getLong(KEY_MOBILE_RX, 0) + rxDiff;
            long totalTx = prefs.getLong(KEY_MOBILE_TX, 0) + txDiff;
            
            // 保存累计流量
            prefs.edit()
                .putLong(KEY_MOBILE_RX, totalRx)
                .putLong(KEY_MOBILE_TX, totalTx)
                .apply();
            
            // 记录日志
            Logger.d(TAG, String.format("移动数据流量更新 - 新增接收: %s, 新增发送: %s, 总计: %s", 
                formatTraffic(rxDiff), 
                formatTraffic(txDiff),
                formatTraffic(totalRx + totalTx)));
        }
        
        // 更新基准值
        lastMobileRx = currentRx;
        lastMobileTx = currentTx;
        
        // 更新通知
        updateNotification();
    }

    private void stopWatching(String uperId) {
        WebView webView = webviews.remove(uperId);  // 先从Map中移除
        if (webView != null) {
            // 在主线程中安全地清理和销毁WebView
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // 先从窗口移除
                    WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                    try {
                        windowManager.removeView(webView);
                    } catch (Exception e) {
                        Logger.e(TAG, "移除WebView视失败: " + e.getMessage());
                    }
                    
                    // 然后清理WebView
                    webView.stopLoading();
                    webView.getSettings().setJavaScriptEnabled(false);
                    webView.loadUrl("about:blank");
                    webView.clearHistory();
                    webView.clearCache(true);
                    webView.clearFormData();
                    webView.clearSslPreferences();
                    webView.removeJavascriptInterface("android");
                    webView.removeAllViews();
                    webView.destroy();
                    
                    // 记录日志
                    WatchStatsManager statsManager = WatchStatsManager.getInstance(LiveWatchService.this);
                    WatchStatsManager.WatchData data = statsManager.getAllStats().get(uperId);
                    String nickname = data != null ? data.name : uperId;
                    Logger.i(TAG, "已销毁WebView并停止观看: " + nickname + "(" + uperId + ")");
                    LogUtils.logEvent("WATCH", "销毁WebView停止观看: " + nickname + "(" + uperId + ")");
                } catch (Exception e) {
                    Logger.e(TAG, "清理WebView失败: " + e.getMessage());
                }
            });
        }
        
        // 清理相关资源
        Timer timer = watchingTimers.remove(uperId);
        if (timer != null) {
            timer.cancel();
        }
        lastHeartbeatTimes.remove(uperId);
        lastHeartbeatLogTimes.remove(uperId);
        roomStatuses.remove(uperId);
        
        // 更新通知，确保WebView已经完全清理
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                updateNotification();
            } catch (Exception e) {
                Logger.e(TAG, "更新通知败: " + e.getMessage());
            }
        }, 500);
    }

    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = 
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "注销网络回调失败", e);
            }
        }
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                LogUtils.logEvent("NETWORK", "网络已断开");
            }
            
            @Override
            public void onAvailable(Network network) {
                LogUtils.logEvent("NETWORK", "网络已连接");
                handleNetworkReconnect();
            }
        };
        
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "注册网络回调失败", e);
        }
    }

    // 添加服务状检查
    private void startServiceWatchdog() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查所有WebView状态
                for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
                    String uperId = entry.getKey();
                    WebView webView = entry.getValue();
                    
                    // 检查心跳
                    Long lastHeartbeat = lastHeartbeatTimes.get(uperId);
                    long now = System.currentTimeMillis();
                    if (lastHeartbeat == null || now - lastHeartbeat > HEARTBEAT_TIMEOUT) {
                        Logger.e(TAG, "检测WebView心跳超时，重新加载: " + uperId);
                        // 需要在主线程中执行 reload
                        mainHandler.post(() -> webView.reload());
                    }
                }
                
                // 每2分钟检查一次
                new Handler(Looper.getMainLooper()).postDelayed(this, 2 * 60 * 1000);
            }
        }, 5 * 60 * 1000);
    }

 

    private void checkLiveStatus(String uperId) {
        String url = "https://live.acfun.cn/api/channel/list?count=1&filters=[%7B%22filterType%22:3,%22filterId%22:" + uperId + "%7D]";
        Request request = new Request.Builder()
            .url(url)
            .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "检查直播失败: " + uperId, e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String json = response.body().string();
                    JSONObject jsonObject = new JSONObject(json);
                    JSONArray liveList = jsonObject.getJSONArray("liveList");
                    
                    if (liveList.length() > 0) {
                        JSONObject live = liveList.getJSONObject(0);
                        String liveId = live.getString("liveId");
                        String nickname = live.getJSONObject("user").getString("name");  // 获取昵称
                        // 修改这里的调用
                        startWatching(liveId, uperId, nickname);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析播状态失败: " + uperId, e);
                }
            }
        });
    }

    private void startServiceChecker() {
        // 移除现有的检查任务
        mainHandler.removeCallbacks(serviceCheckRunnable);
        
        mainHandler.postDelayed(serviceCheckRunnable, 5 * 60 * 1000);
        LogUtils.logSystemInfo("SERVICE", "Service checker started");
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LiveWatchService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startNotificationUpdate() {
        mainHandler.post(notificationUpdateRunnable);
    }

    // 添加检查经验值是否满的方法
    private boolean isExpFull(String uperId) {
        WatchStatsManager statsManager = WatchStatsManager.getInstance(this);
        Map<String, WatchStatsManager.WatchData> allStats = statsManager.getAllStats();
        WatchStatsManager.WatchData data = allStats.get(uperId);
        if (data != null) {
            return data.degree >= 360;
        }
        return false;
    }

    // 添加关闭WebView的方法
    private void closeWebView(String uperId) {
        WebView webView = webviews.get(uperId);
        if (webView != null) {
            // 在主线程中执行WebView的清
            new Handler(Looper.getMainLooper()).post(() -> {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
                
                // 从WindowManager移除WebView
                try {
                    WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                    windowManager.removeView(webView);
                } catch (Exception e) {
                    Log.e(TAG, "移除WebView失败: " + uperId, e);
                }
                
                // 清理相关资源
                webviews.remove(uperId);
                Timer timer = watchingTimers.remove(uperId);
                if (timer != null) {
                    timer.cancel();
                }
                lastHeartbeatTimes.remove(uperId);
                roomStatuses.remove(uperId);
                LogUtils.logEvent("WATCH", "closeWebView销毁WebView停止观看: " + "(" + uperId + ")");
                Log.d(TAG, "成功关闭直播间: " + uperId);
            });
        }
    }

    // 网络重连
    private void handleNetworkReconnect() {
        // 使用主线程Handler执行WebView操作
        mainHandler.post(() -> {
            try {
                Logger.i(TAG, "网络重连，刷新所有WebView");
                for (WebView webView : webviews.values()) {
                    webView.reload();
                }
            } catch (Exception e) {
                Logger.e(TAG, "刷新WebView失败: " + e.getMessage());
            }
        });
    }

    // WebView重启
    private void restartWebView(String uperId) {
        LogUtils.logEvent("WEBVIEW", "重启WebView: " + uperId);
        WebView webView = webviews.get(uperId);
        if (webView != null) {
            // 确保在主线程中执行
            mainHandler.post(() -> webView.reload());
        }
    }

    private void setupWebView(WebView webView, String uperId) {
        if (webviews.containsKey(uperId)) {
            LogUtils.logSystemInfo("WEBVIEW", "WebView already exists for uperId: " + uperId);
            return;
        }
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String message = consoleMessage.message();
                // 添加调试日志
                Logger.d(TAG, "收到控制台消息: " + message);  // 添加这行
                
                if (message.contains("liveCsCmd ZtLiveCsHeartbeat")) {
                    lastHeartbeatTimes.put(uperId, System.currentTimeMillis());
                    
                    long currentTime = System.currentTimeMillis();
                    Long lastLogTime = lastHeartbeatLogTimes.get(uperId);
                    if (lastLogTime == null || currentTime - lastLogTime >= HEARTBEAT_LOG_INTERVAL) {
                        WatchStatsManager statsManager = WatchStatsManager.getInstance(LiveWatchService.this);
                        WatchStatsManager.WatchData data = statsManager.getAllStats().get(uperId);
                        String displayName = data != null ? data.name : uperId;
                        
                        Logger.i(TAG, "收到直播间心跳: " + displayName + "(" + uperId + ")");
                        LogUtils.logEvent("HEARTBEAT", "收到直播间心跳: " + displayName + "(" + uperId + ")");
                        
                        lastHeartbeatLogTimes.put(uperId, currentTime);
                    }
                }
                return false;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Logger.i(TAG, "WebView loaded for uperId: " + uperId);
            }
        });
    }

    // 内存状态
    private void logMemoryInfo() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        
        LogUtils.logSystemInfo("MEMORY", String.format(
            "Available: %dMB, Total: %dMB, Low Memory: %b",
            memoryInfo.availMem / 1024 / 1024,
            memoryInfo.totalMem / 1024 / 1024,
            memoryInfo.lowMemory
        ));
    }

    private void startPeriodicRefresh() {
        // 立即执行一次
        fetchMedalList();
        
        // 然后开始定时任务
        mainHandler.post(refreshRunnable);
    }
    
    private boolean isInitialFetchInProgress = false;
    
    private void fetchMedalList() {
        Request request = new Request.Builder()
            .url(MEDAL_LIST_API)
            .header("Cookie", CookieManager.getCookies())
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String json = response.body().string();
                    JSONObject jsonObject = new JSONObject(json);
                    
                    if (jsonObject.optInt("result") == 0) {  // 使用 optInt 代替 getInt
                        JSONArray medalList = jsonObject.optJSONArray("medalList");  // 使用 optJSONArray
                        if (medalList != null) {
                            medalUperIds.clear();
                            
                            for (int i = 0; i < medalList.length(); i++) {
                                try {
                                    JSONObject medal = medalList.optJSONObject(i);  // 使用 optJSONObject
                                    if (medal != null) {
                                        String uperId = medal.optString("uperId");  // 使用 optString
                                        if (uperId != null && !uperId.isEmpty()) {
                                            medalUperIds.add(uperId);
                                        }
                                    }
                                } catch (Exception e) {
                                    Logger.e(TAG, "处理单个粉丝牌数据失败", e);
                                    continue;  // 继续处理下一个
                                }
                            }
                            
                            // 获取完粉丝牌列表后获取直播列
                            fetchLiveList();
                        }
                    } else {
                        String errorMsg = jsonObject.optString("msg", "未知错误");  // 使用 optString 并提供默认值
                        Logger.e(TAG, "获取粉丝牌列表失败: " + errorMsg);
                    }
                } catch (JSONException e) {
                    Logger.e(TAG, "JSON解析失败", e);
                } catch (IOException e) {
                    Logger.e(TAG, "读取响应数据失败", e);
                } catch (Exception e) {
                    Logger.e(TAG, "获取粉丝牌列表时发生未知错误", e);
                }
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                Logger.e(TAG, "网络请求失败", e);
            }
        });
    }
    
    private void fetchLiveList() {
        String cookies = CookieManager.getCookies();
        Request request = new Request.Builder()
            .url(LIVE_LIST_API)
            .header("Cookie", cookies)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Referer", "https://live.acfun.cn")
            .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String json = response.body().string();
                    JSONObject jsonObj = new JSONObject(json);
                    JSONObject channelListData = jsonObj.getJSONObject("channelListData");
                    JSONArray liveList = channelListData.getJSONArray("liveList");
                    
                    // 用Set来去重
                    Set<LiveUperInfo> uniqueWatchList = new HashSet<>();
                    for (int i = 0; i < liveList.length(); i++) {
                        JSONObject live = liveList.getJSONObject(i);
                        String uperId = live.getString("authorId");
                        JSONObject user = live.getJSONObject("user");
                        String nickname = user.getString("name");
                        String liveId = live.getString("liveId");
                        
                        if (user.getBoolean("isFollowing") && medalUperIds.contains(uperId)) {
                            uniqueWatchList.add(new LiveUperInfo(uperId, nickname, liveId));
                        }
                    }
                    
                    // 批量处理需要观看的直播间
                    if (!uniqueWatchList.isEmpty()) {
                        processBatchWatchList(new ArrayList<>(uniqueWatchList));
                    }
                    
                } catch (Exception e) {
                    Logger.e(TAG, "解析直播列表失败", e);
                }
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                Logger.e(TAG, "获取直播列表失败", e);
            }
        });
    }
    
    private void processBatchWatchList(List<LiveUperInfo> watchList) {
        Set<String> processedUperIds = new HashSet<>();
        List<LiveUperInfo> needWatchList = new ArrayList<>();
        StringBuilder watchInfoBuilder = new StringBuilder("需要挂机的直播间: ");
        
        // 第一步：收集所有需要挂机的直播间信息
        for (LiveUperInfo info : watchList) {
            // 检查是否经处理过这个主播
            if (processedUperIds.contains(info.uperId)) {
                continue;
            }
            processedUperIds.add(info.uperId);
            
            String url = String.format(MEDAL_INFO_API, info.uperId);
            Request request = new Request.Builder()
                .url(url)
                .header("Cookie", CookieManager.getCookies())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build();
                
            try {
                Response response = client.newCall(request).execute();
                String json = response.body().string();
                JSONObject jsonObject = new JSONObject(json);
                
                if (jsonObject.getInt("result") == 0) {
                    JSONObject medalDegreeLimit = jsonObject.getJSONObject("medalDegreeLimit");
                    int watchDegree = medalDegreeLimit.getInt("liveWatchDegree");
                    
                    WatchStatsManager.getInstance(LiveWatchService.this)
                        .updateWatchDegree(info.uperId, info.nickname, watchDegree);
                    
                    if (watchDegree < 360) {
                        info.degree = watchDegree;
                        needWatchList.add(info);
                        
                        // 添加到显示字符串
                        if (watchInfoBuilder.toString().endsWith(": ")) {
                            watchInfoBuilder.append(String.format("%s (%d/360)", 
                                info.nickname, watchDegree));
                        } else {
                            watchInfoBuilder.append(String.format(", %s (%d/360)", 
                                info.nickname, watchDegree));
                        }
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, "取粉丝牌信息失: " + info.nickname, e);
            }
        }
        Logger.i(TAG, watchInfoBuilder.toString());
        LogUtils.logEvent("WATCH", watchInfoBuilder.toString());
        
        // 检查现有的WebView是否需要停止
        for (Map.Entry<String, WebView> entry : new HashMap<>(webviews).entrySet()) {
            String uperId = entry.getKey();
            WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this).getAllStats().get(uperId);
            if (data != null && data.degree >= 360) {
                Logger.i(TAG, "检测到经验值已满，停止观看: " + data.name + "(" + uperId + ")");
                LogUtils.logEvent("WATCH", "经验值已满，停止观看: " + data.name + "(" + uperId + ")");
                stopWatching(uperId);
            }
        }
        
        // 然后再处理需要新增的观看
        for (LiveUperInfo info : needWatchList) {
            startWatching(info.liveId, info.uperId, info.nickname);
        }
        
        // 添加WebView统计信息到日志
        String webviewStats = String.format("当前WebView数量: %d, WebView列表: ", webviews.size());
        StringBuilder statsBuilder = new StringBuilder(webviewStats);
        
        for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
            String uperId = entry.getKey();
            WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this).getAllStats().get(uperId);
            String nickname = data != null ? data.name : uperId;
            statsBuilder.append(nickname).append("(").append(uperId).append("), ");
        }
        
        // 记录到日志
        Logger.i(TAG, statsBuilder.toString());
        LogUtils.logEvent("WEBVIEW_STATS", statsBuilder.toString());
    }

    // 修改数据类，添加 degree 字段
    private static class LiveUperInfo {
        String uperId;
        String nickname;
        String liveId;
        int degree;
        
        LiveUperInfo(String uperId, String nickname, String liveId) {
            this.uperId = uperId;
            this.nickname = nickname;
            this.liveId = liveId;
            this.degree = 0;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LiveUperInfo that = (LiveUperInfo) o;
            return uperId.equals(that.uperId);
        }
        
        @Override
        public int hashCode() {
            return uperId.hashCode();
        }
    }

    public static void stopWatchingStatic(Context context, String uperId) {
        Intent intent = new Intent(context, LiveWatchService.class);
        intent.putExtra("action", "stop");
        intent.putExtra("uperId", uperId);
        context.startService(intent);
    }

    private void checkAndResetDailyTraffic() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String lastResetDate = prefs.getString(KEY_LAST_RESET_DATE, "");
        String today = getCurrentDate();
        
        if (!today.equals(lastResetDate)) {
            // 新的一天，重置流统计
            prefs.edit()
                .putLong(KEY_MOBILE_RX, 0)
                .putLong(KEY_MOBILE_TX, 0)
                .putString(KEY_LAST_RESET_DATE, today)
                .apply();
            
            LogUtils.logEvent("TRAFFIC", "每日流量统计已重置");
        }
    }
    
    private String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(new Date());
    }
    
    private void initTrafficStats() {
        // 检查是否需要重置
        checkAndResetDailyTraffic();
        
        // 获取应用的 UID
        int uid = Process.myUid();
        
        // 获取应用的基准流量值
        lastMobileRx = TrafficStats.getUidRxBytes(uid);
        lastMobileTx = TrafficStats.getUidTxBytes(uid);
        
        // 启动定时更新
        mainHandler.postDelayed(trafficUpdateRunnable, UPDATE_TRAFFIC_INTERVAL);
        
        Logger.d(TAG, "应用流量统计初始化完成");
    }
    
    private boolean isMobileDataActive() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            return capabilities != null && 
                   capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        }
        return false;
    }

    public static void resetTrafficStats(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MOBILE_RX, 0)
            .putLong(KEY_MOBILE_TX, 0)
            .apply();
    }

    private String formatTraffic(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    // 添加 updateNotification 法
    private void updateNotification() {
        try {
            NotificationManager notificationManager = 
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification notification = createNotification();
            notificationManager.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Logger.e(TAG, "更新通知失败: " + e.getMessage());
        }
    }

    private void startForeground() {
        // 创建通知渠道（Android 8.0及以上需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "live_watch_service",
                "直播监控服务",
                NotificationManager.IMPORTANCE_LOW  // 使用低优先级，减少打扰
            );
            channel.setDescription("用于保持直播监控服务运行");
            channel.enableLights(false);
            channel.enableVibration(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        // 创建点击通知时的意图（打开主界面）
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        // 构建通知
        Notification notification = new NotificationCompat.Builder(this, "live_watch_service")
            .setContentTitle("AcFun直播监控")
            .setContentText("正在监控直播间")
            .setSmallIcon(R.drawable.ic_launcher_foreground)  // 确保这个图标存在
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 设置为不可清除
            .build();

        // 启动前台服务
        startForeground(1, notification);
    }
} 