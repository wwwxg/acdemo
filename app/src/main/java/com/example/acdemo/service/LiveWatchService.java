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
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.os.SystemClock;
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
    private static final long NOTIFICATION_UPDATE_INTERVAL = 30000; // 30秒更新一次
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
            fetchMedalList();
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
            if (!isServiceRunning()) {
                Intent intent = new Intent(getApplicationContext(), LiveWatchService.class);
                intent.putExtra("action", "restart");
                startForegroundService(intent);
            }
            mainHandler.postDelayed(this, 5 * 60 * 1000);
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 添加更多的日志检查
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
            Notification notification = createNotification("服务正在启动...");
            startForeground(NOTIFICATION_ID, notification);
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
    }
    
    private void startTimers() {
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
        LogUtils.logEvent("SERVICE", "服务启动");
        if (intent != null) {
            String action = intent.getStringExtra("action");
            String uperId = intent.getStringExtra("uperId");
            String liveId = intent.getStringExtra("liveId");
            String nickname = intent.getStringExtra("nickname");  // 获取传入的昵称
            
            // 记录服务操作
            LogUtils.logEvent("SERVICE", String.format("操作: %s, 主播: %s(%s)", 
                action != null ? action : "start", 
                nickname != null ? nickname : uperId,
                uperId));
            
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
            Logger.e(TAG, String.format("无效的参数 - liveId: %s, uperId: %s, nickname: %s", 
                liveId, uperId, nickname));
            return;
        }
        
        // 检查是否已经在观看
        if (webviews.containsKey(uperId)) {
            // 已存在时不再记录日志，因为不是新建的WebView
            return;
        }
        
        // 在主线程中创建WebView
        new Handler(Looper.getMainLooper()).post(() -> {
            WebView webView = createWebView();
            if (webView != null) {
                setupWebView(webView, uperId);
                webviews.put(uperId, webView);
                loadLiveRoom(webView, liveId, uperId);
                
                // 只在真正创建新WebView时记录日志
                Logger.i(TAG, String.format("创建新WebView并开始观看: %s(%s)", nickname, uperId));
                LogUtils.logEvent("WATCH", String.format("创建WebView开始观看: %s(%s)", nickname, uperId));
                
                // 更新通知栏
                updateNotification();
            }
        });
    }
    
    private WebView createWebView() {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        
        // 禁用图片载
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        
        // 禁用缓存
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        
        // 添加到窗口
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            1, 1,  // 设置最小尺寸
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            windowManager.addView(webView, params);
        } catch (Exception e) {
            Logger.e(TAG, "添加WebView到窗口失败", e);
            return null;
        }
        
        return webView;
    }
    
    private void loadLiveRoom(WebView webView, String liveId, String uperId) {
        String liveUrl = String.format("https://live.acfun.cn/room/%s?theme=default&showAuthorclubOnly=", uperId);
        webView.loadUrl(liveUrl);
        updateNotification();//更新通知栏

        // 启动心跳检测定时器
        Timer heartbeatTimer = new Timer();
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Long lastHeartbeat = lastHeartbeatTimes.get(uperId);
                if (lastHeartbeat != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat > HEARTBEAT_TIMEOUT) {
                        Log.d(TAG, "播间心跳超时，判定为已下: " + uperId);
                        new Handler(Looper.getMainLooper()).post(() -> stopWatching(uperId));
                    }
                }
            }
        }, 30000, 30000); // 每30秒检查一次心跳状态
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
        LogUtils.stopMemoryLogging();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception e) {
                Log.e(TAG, "释放WakeLock失败", e);
            }
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
        
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        for (WebView webView : webviews.values()) {
            windowManager.removeView(webView);
            webView.destroy();
        }
        webviews.clear();
        
        for (Timer timer : watchingTimers.values()) {
            timer.cancel();
        }
        watchingTimers.clear();
        mainHandler.removeCallbacks(notificationUpdateRunnable);
        
        // 清理心跳日志记录时间Map
        lastHeartbeatLogTimes.clear();
        
        super.onDestroy();
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private Notification createNotification() {
        NotificationChannel channel = new NotificationChannel(
            "acdemo_service", 
            "AcFun挂机服务",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        // 获取当前正在挂机的数量和主播名字列表
        int watchingCount = webviews.size();
        StringBuilder namesBuilder = new StringBuilder();
        
        // 使用正确的包路径
        WatchStatsManager statsManager = WatchStatsManager.getInstance(this);
        Map<String, WatchStatsManager.WatchData> allStats = statsManager.getAllStats();
        
        for (String uperId : webviews.keySet()) {
            WatchStatsManager.WatchData data = allStats.get(uperId);
            String name = data != null ? data.name : uperId;
            namesBuilder.append(name).append("\n");
        }
        
        String contentText = String.format("正在挂%d个牌子", watchingCount);
        String expandedText;
        if (watchingCount > 0) {
            expandedText = String.format("正在挂%d个牌子:\n%s", watchingCount, namesBuilder.toString());
        } else {
            expandedText = "当前没挂牌子";
        }

        return new NotificationCompat.Builder(this, "acdemo_service")
            .setContentTitle("AcFun粉丝牌助手")
            .setContentText(contentText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build();
    }

    private void stopWatching(String uperId) {
        WebView webView = webviews.remove(uperId);
        if (webView != null) {
            // 在主线程中安全地清理和销毁WebView
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    // 先停止所有加载和JavaScript
                    webView.stopLoading();
                    webView.getSettings().setJavaScriptEnabled(false);
                    
                    // 清理WebView内容
                    webView.loadUrl("about:blank");
                    webView.clearHistory();
                    webView.clearCache(true);
                    webView.clearFormData();
                    webView.clearSslPreferences();
                    
                    // 移除所有JavaScript接口
                    webView.removeJavascriptInterface("android");
                    
                    // 从窗口移除
                    WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                    windowManager.removeView(webView);
                    
                    // 移除所有View
                    webView.removeAllViews();
                    
                    // 最后销毁
                    webView.destroy();
                    
                    // 记录日志
                    WatchStatsManager statsManager = WatchStatsManager.getInstance(LiveWatchService.this);
                    WatchStatsManager.WatchData data = statsManager.getAllStats().get(uperId);
                    String nickname = data != null ? data.name : uperId;
                    Logger.i(TAG, String.format("已销毁WebView并停止观看: %s(%s)", nickname, uperId));
                    LogUtils.logEvent("WATCH", String.format("销毁WebView停止观看: %s(%s)", nickname, uperId));
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
        
        // 更新通知栏
        updateNotification();
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

    private void updateNotification() {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            
        // 获取当前时间
        String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(new Date());
            
        // 获取正在看的直播数量
        int watchingCount = webviews.size();
        
        StringBuilder namesBuilder = new StringBuilder();
        WatchStatsManager statsManager = WatchStatsManager.getInstance(this);
        Map<String, WatchStatsManager.WatchData> allStats = statsManager.getAllStats();
        
        for (String uperId : webviews.keySet()) {
            WatchStatsManager.WatchData data = allStats.get(uperId);
            String name = data != null ? data.name : uperId;
            namesBuilder.append(name).append("\n");
        }
        
        // 修改这里，确保时间总是显示
        String contentText = String.format("正在挂牌子 [%s]", currentTime);
        String expandedText;
        if (watchingCount > 0) {
            expandedText = String.format("正在挂%d个牌子:\n%s\n更新时间: %s", 
                watchingCount, namesBuilder.toString(), currentTime);
        } else {
            expandedText = String.format("当前没有挂牌子\n更新时间: %s", currentTime);
        }

        // 创建通知
        Notification notification = new NotificationCompat.Builder(this, "acdemo_service")
            .setContentTitle("AcFun粉丝牌助手")
            .setContentText(contentText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)  // 设置为持续通知
            .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
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
                        Logger.e(TAG, "检测到WebView心跳超时，重新加载: " + uperId);
                        webView.reload();
                    }
                }
                
                // 每2分钟检查一次
                new Handler(Looper.getMainLooper()).postDelayed(this, 2 * 60 * 1000);
            }
        }, 5 * 60 * 1000);
    }

    // 添加重启恢复机制
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service task removed, scheduling restart");
        Intent restartService = new Intent(getApplicationContext(), LiveWatchService.class);
        restartService.setPackage(getPackageName());
        PendingIntent restartServicePI = PendingIntent.getService(
            getApplicationContext(), 1, restartService,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager alarmService = (AlarmManager) getApplicationContext()
            .getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000, restartServicePI);

        super.onTaskRemoved(rootIntent);
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
        LogUtils.logSystemInfo("SERVICE", "Service checker started");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 检查服务状态
                if (!isServiceRunning()) {
                    Intent intent = new Intent(getApplicationContext(), LiveWatchService.class);
                    intent.putExtra("action", "restart");
                    startForegroundService(intent);
                }
                
                // 每5分钟检查一次
                new Handler(Looper.getMainLooper()).postDelayed(this, 5 * 60 * 1000);
            }
        }, 5 * 60 * 1000);
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

    private Notification createNotification(String content) {
        // 创建通知渠道（Android 8.0及以上需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "live_watch_service",
                "直播观看服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于保持直播观看服务运行");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        // 创建点击通知时的意图
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        // 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "live_watch_service")
            .setContentTitle("AcDemo 运行")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher) // 确保您有这个图标资源
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true);

        return builder.build();
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
                
                // 从WindowManager中移除WebView
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
                
                Log.d(TAG, "成功关闭直播间: " + uperId);
            });
        }
    }

    // 网络重连
    private void handleNetworkReconnect() {
        LogUtils.logEvent("NETWORK", "网络重连尝试");
        fetchMedalList();
        for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
            entry.getValue().reload();
        }
        LogUtils.logEvent("NETWORK", "网络重连处理完成");
    }

    // WebView重启
    private void restartWebView(String uperId) {
        LogUtils.logEvent("WEBVIEW", "重启WebView: " + uperId);
        WebView webView = webviews.get(uperId);
        if (webView != null) {
            webView.reload();
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
                        
                        Logger.i(TAG, String.format("收到直播间心跳: %s(%s)", displayName, uperId));
                        LogUtils.logEvent("HEARTBEAT", String.format("%s(%s)", displayName, uperId));
                        
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
    
    private void fetchMedalList() {
        String cookies = com.example.acdemo.utils.CookieManager.getCookies();
        if (cookies == null || !cookies.contains("acPasstoken")) {
            Logger.e(TAG, "Cookie无效，跳过请求");
            return;
        }
        
        Request request = new Request.Builder()
            .url(MEDAL_LIST_API)
            .header("Cookie", cookies)
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String json = response.body().string();
                    JSONObject jsonObject = new JSONObject(json);
                    if (jsonObject.getInt("result") == 0) {
                        JSONArray medalList = jsonObject.getJSONArray("medalList");
                        medalUperIds.clear();
                        
                        for (int i = 0; i < medalList.length(); i++) {
                            JSONObject medal = medalList.getJSONObject(i);
                            String uperId = medal.getString("uperId");
                            medalUperIds.add(uperId);
                        }
                        
                        Logger.i(TAG, String.format("获取%d个粉丝牌", medalUperIds.size()));
                        // 获取完粉丝牌列表后获取直播列表
                        fetchLiveList();
                    } else {
                        Logger.e(TAG, "获取粉丝牌列表失败: " + jsonObject.getString("msg"));
                    }
                } catch (Exception e) {
                    Logger.e(TAG, "解析粉丝牌列表失败", e);
                }
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                Logger.e(TAG, "获取粉丝牌列表失败", e);
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
                    
                    // 使用Set来去重
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
            // 检查是否已经处理过这个主播
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
                Logger.e(TAG, "获取粉丝牌信息失败: " + info.nickname, e);
            }
        }
        
        // 第二步：输出所有需要挂机的直播间信息
        if (!needWatchList.isEmpty()) {
            // 修改这里，添加更详细的日志记录
            String watchInfo = watchInfoBuilder.toString();
            Logger.i(TAG, watchInfo);
            LogUtils.logEvent("WATCH_LIST", watchInfo);  // 添加这行，写入日志文件
            
            // 添加统计信息
            String statsInfo = String.format("共发现 %d 个直播间需要挂机", needWatchList.size());
            Logger.i(TAG, statsInfo);
            LogUtils.logEvent("WATCH_LIST", statsInfo);  // 添加这行，写入统计信息
        } else {
            String noWatchInfo = "没有需要挂机的直播间";
            Logger.i(TAG, noWatchInfo);
            LogUtils.logEvent("WATCH_LIST", noWatchInfo);  // 添加这行，记录无需挂机的情况
        }
        
        // 第三步：在主线程中启动观看
        for (LiveUperInfo info : needWatchList) {
            startWatching(info.liveId, info.uperId, info.nickname);
        }
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
} 