package com.example.acdemo.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ReceiverCallNotAllowedException;
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
import android.view.ViewParent;
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
import java.util.Calendar;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.ComponentCallbacks2;
import java.io.File;
import com.example.acdemo.manager.StatsManager;

public class LiveWatchService extends Service {
    private static final String TAG = "LiveWatchService";
    private Map<String, Timer> watchingTimers = new HashMap<>();
    private Map<String, WebView> webviews = new HashMap<>();
    private Map<String, Long> lastHeartbeatTimes = new HashMap<>();
    private static final long HEARTBEAT_TIMEOUT = 180000;//直播间心跳超时时间
    public static Map<String, Boolean> roomStatuses = new HashMap<>();
    private android.os.PowerManager.WakeLock wakeLock;
    private OkHttpClient client;
    private ConnectivityManager.NetworkCallback networkCallback;
    private static final int NOTIFICATION_ID = 1;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long NOTIFICATION_UPDATE_INTERVAL = 120000; // 120秒更新一次
    private Map<String, Long> lastHeartbeatLogTimes = new HashMap<>(); // 添加这个变量来跟踪上次记录日志的时间
    private static final long HEARTBEAT_LOG_INTERVAL = 60000; // 60秒的日志记录间隔
    private static final String MEDAL_LIST_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/list";
    private static final String LIVE_LIST_API = "https://live.acfun.cn/api/channel/list?count=100&pcursor=&filters=[%7B%22filterType%22:3,+%22filterId%22:0%7D]";
    private static final String MEDAL_INFO_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/extraInfo?uperId=%s";
    private Set<String> medalUperIds = new HashSet<>(); // 存储所有粉丝牌UP主ID
    private static final long REFRESH_INTERVAL = 3 * 60 * 1000; // 3分钟

    private long lastNotificationUpdateTime = 0;
    private long lastRefreshTime = 0;
    
    //合并定时器
    private final Runnable periodicCheckRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                long currentTime = System.currentTimeMillis();
                
                // 获取当前小时和分钟
                Calendar now = Calendar.getInstance();
                int currentHour = now.get(Calendar.HOUR_OF_DAY);
                
                // 0点后重置统计
                if (currentHour == 0) {
                    StatsManager.getInstance().checkAndResetStats();
                }
                
                // 更新通知
                if (shouldUpdateNotification(currentTime)) {
                    updateNotification();
                    lastNotificationUpdateTime = currentTime;
                }
                
                // 刷新直播列表（每3分钟）
                if (shouldRefreshList(currentTime)) {
                    LogUtils.logEvent("LIVE_CHECK", "刷新直播列表");
                    fetchMedalList();
                    lastRefreshTime = currentTime;
                }
                
                // 检查并更新日志文件
                File logDir = new File(getExternalFilesDir(null), "logs");
                LogUtils.checkAndUpdateLogFile(logDir);
                
            } catch (Exception e) {
                Logger.e(TAG, "定期检查任务失败", e);
            }
            
            // 继续下一次循环
            mainHandler.postDelayed(this, 60 * 1000); // 每分钟检查一次
        }
    };
    
    private static final String PREF_NAME = "service_prefs";
    private static final String KEY_SERVICE_RUNNING = "service_running";
    private static final String KEY_LAST_PID = "last_pid";

    private static final String KEY_LAST_RESET_DATE = "last_reset_date";

    private static final String KEY_SERVICE_STATE = "service_state";
    
    private static final String CHANNEL_ID = "live_watch_service";
  
    private static final String KEY_TEMP_STOP = "temporary_stop";
    private static final long SERVICE_HEARTBEAT_INTERVAL = 60 * 1000;
    private static final long SERVICE_LOG_INTERVAL = 3 * 60 * 1000; // 3 minutes
    private long lastHeartbeatLogTime = 0;
    private final Handler serviceHandler = new Handler(Looper.getMainLooper());
 
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
        // 重置工作状态
        isWorking = false;
        
 
        LogUtils.logEvent("SERVICE", "服务从最近任务移除");
        Logger.d(TAG, "服务从最近任务移除");
        // 确保日志写入
        LogUtils.shutdown();
        super.onTaskRemoved(rootIntent);
    }
    
    // 添加静态变量跟踪服务状态
    private static volatile boolean isWorking = false;

    // 添加静态方法检查服务是否在工作
    public static boolean isActuallyWorking() {
        return isWorking;
    }
    // 修改设置临时停止的方法
    public static void setTemporaryStop(Context context, boolean stop) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TEMP_STOP, stop)
            .apply();
        
        LogUtils.logEvent("SERVICE", "临时停止状态已设置为: " + stop);
        Logger.d(TAG, "临时停止状态已设置为: " + stop);
    }
    
    // 修改检查临时停止状态的方法
    public static boolean isTemporaryStopped(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TEMP_STOP, false);
    }
    
    // 修改服务检查逻辑
    public static boolean shouldRestartService(Context context) {
        return !isTemporaryStopped(context);
    }
     // 简化的心跳任务，只记录服务状态
    private final Runnable serviceHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                long currentTime = System.currentTimeMillis();
                // 三分钟记录一次
                if (currentTime - lastHeartbeatLogTime >= SERVICE_LOG_INTERVAL) {
                    LogUtils.logEvent("SERVICE_STATUS", String.format(
                        "服务心跳 - 时间: %s, 活跃房间数: %d",
                        new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()),
                        webviews.size()
                    ));
                    Logger.d(TAG, "服务心跳 - 时间: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + ", 活跃房间数: " + webviews.size());
                    lastHeartbeatLogTime = currentTime;
                }
                
                // 继续下一次心跳
                serviceHandler.postDelayed(this, SERVICE_HEARTBEAT_INTERVAL);
                
            } catch (Exception e) {
                LogUtils.logEvent("ERROR", "服务心跳失败: " + e.getMessage());
                // 即使失败，也继续下一次心跳
                serviceHandler.postDelayed(this, SERVICE_HEARTBEAT_INTERVAL);
            }
        }
    };
    
    private void startServiceHeartbeat() {
        serviceHandler.removeCallbacks(serviceHeartbeatRunnable);
        serviceHandler.post(serviceHeartbeatRunnable);
        LogUtils.logEvent("SERVICE", "服务心跳已启动");
    }
    @Override
    public void onCreate() {
        super.onCreate();
        LogUtils.logEvent("SERVICE", "服务开始创建");
        Logger.d(TAG, "服务开始创建");
        isWorking = true;
        // 添加服务心跳启动
        startServiceHeartbeat();
        // 记录服务启动状态
        saveServiceState(true);
        // 启动前台服务
        startForeground();
        
        // 检查是否是被系统杀死后重启
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int lastPid = prefs.getInt(KEY_LAST_PID, -1);
        int currentPid = Process.myPid();
        
        
        // 当前进程ID
        prefs.edit().putInt(KEY_LAST_PID, currentPid).apply();
        
        setServiceRunning(this, true);
        
        
        // 添加更多的日检查
        Logger.i(TAG, "服务开始创建");
        LogUtils.logEvent("SERVICE", "服务创建开始");
        
        try {
        
        
            
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
                    .connectTimeout(30, TimeUnit.SECONDS)//连接超时
                    .readTimeout(30, TimeUnit.SECONDS)//读取超时
                    .writeTimeout(30, TimeUnit.SECONDS)//写入超时
                    .retryOnConnectionFailure(true)//连接失败重试
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
            mainHandler.post(periodicCheckRunnable);
  
            Logger.i(TAG, "服务监控启动完成");
            
            // 立即请求一次粉丝牌列表
            fetchMedalList();
            
            Logger.i(TAG, "服务创建完成");
            
        } catch (Exception e) {
            Logger.e(TAG, "服务创建过程中发生错误", e);
            LogUtils.logEvent("ERROR", "服务创建失败: " + e.getMessage());
            stopSelf(); // 发生错误时停止服务
        }
        

        
        StatsManager.getInstance().setResetCallback(() -> {
            // 统计重置后更新通知
            updateNotification();
            // 发送广播通知 MainActivity 更新显示
            Intent intent = new Intent("com.example.acdemo.STATS_RESET");
            sendBroadcast(intent);
        });
        
        // 注册Cookie失效广播接收器
        registerReceiver(cookieReceiver, new IntentFilter(CookieManager.ACTION_COOKIE_INVALID));
    }
    
    private BroadcastReceiver cookieReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CookieManager.ACTION_COOKIE_INVALID.equals(intent.getAction())) {
                LogUtils.logEvent("COOKIE", "LiveWatchService收到Cookie失效广播");
                 // 设置临时停止标志
                setTemporaryStop(context, true);
                // 保存服务状态为非运行
                saveServiceState(false);
                stopSelf();
                 // 发送广播通知MainActivity关闭
                Intent closeIntent = new Intent("com.example.acdemo.ACTION_FINISH");
                closeIntent.setPackage(context.getPackageName());
                context.sendBroadcast(closeIntent);
            }
        }
    };
    
    private void saveServiceState(boolean running) {
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_SERVICE_STATE, running);
        editor.putLong("last_update_time", System.currentTimeMillis());
        editor.apply();
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getStringExtra("action") : null;
        
        if ("restart".equals(action)) {
            LogUtils.logEvent("SERVICE", "收到重启请求");
            
            // 清除临时停止状态
            setTemporaryStop(this, false);
            
            // 重置所有状态
            isWorking = true;
           
            lastNotificationUpdateTime = 0;
            lastRefreshTime = 0;
        }
        
        return START_STICKY;  // 使用 START_STICKY 确保服务被系统杀死后会尝试重启
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
        settings.setDomStorageEnabled(false);//禁用dom缓存
        // 禁用数据库存储
        settings.setDatabaseEnabled(false);
        // 禁用缩放控件
        settings.setDisplayZoomControls(false);
        // 禁用内置缩放功能（双指缩放）
        settings.setBuiltInZoomControls(false);
        // 禁用图片加载以节省资源
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        webView.setVerticalScrollBarEnabled(false);    // 禁用垂直滚动条
        webView.setHorizontalScrollBarEnabled(false);  // 禁用水平滚动条
        settings.setUseWideViewPort(false);// 禁用viewport支持
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
        // 保在主线程中执行
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
        // 停止心跳
        serviceHandler.removeCallbacks(serviceHeartbeatRunnable);
        LogUtils.logEvent("SERVICE", "服务心跳已停止");
  
        try {
            // 1. 先停止所有定时任务和回调，防止新的操作
            mainHandler.removeCallbacksAndMessages(null);
            for (Timer timer : new HashSet<>(watchingTimers.values())) {
                timer.cancel();
            }
            watchingTimers.clear();
            
            // 2. 标记服务状态，防止新的操作
            isWorking = false;
            setServiceRunning(this, false);
            
            // 3. 同步清理 WebView
            for (String uperId : new HashSet<>(webviews.keySet())) {
                WebView webView = webviews.get(uperId);
                if (webView != null) {
                    try {
                        webView.stopLoading();
                        webView.clearHistory();
                        webView.removeAllViews();
                        webView.destroy();
                        webviews.remove(uperId);
                    } catch (Exception e) {
                        Logger.e(TAG, "清理WebView失败: " + e.getMessage());
                    }
                }
            }
            
            // 4. 清理其他资源
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            
            // 5. 清理数据结构
            lastHeartbeatTimes.clear();
            roomStatuses.clear();
            lastHeartbeatLogTimes.clear();
            medalUperIds.clear();
            
            // 6. 记录服务销毁日志
            LogUtils.logEvent("SERVICE", "服务资源清理完成");
            
            // 7. 等待日志写入完成
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 8. 最后关闭日志系统
            LogUtils.shutdown();
            
        } catch (Exception e) {
            Logger.e(TAG, "服务销毁过程出错: " + e.getMessage());
        } finally {
            super.onDestroy();
            // 注销广播接收器
            unregisterReceiver(cookieReceiver);
        }
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
        

        
        // 添加主播列表到展开内容
        List<Map.Entry<String, WebView>> entries = new ArrayList<>(webviews.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, WebView> entry = entries.get(i);
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
            .setContentText("正在运行中")  // 未展开时显示的文本
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true);

        // 只在有内容时添加展开样式
        if (contentBuilder.length() > 0) {
            builder.setStyle(new NotificationCompat.BigTextStyle()
                .setBigContentTitle("AcFun粉丝牌助手")  // 展开后的标题保持一致
                .bigText(contentBuilder.toString()));
        }

        return builder.build();
    }

  
    // 添加线程池
    private final ExecutorService webViewCleanupExecutor = Executors.newSingleThreadExecutor();

    private void stopWatching(String uperId) {
        WebView webView = webviews.remove(uperId);
        if (webView != null) {
            mainHandler.post(() -> {
                webView.stopLoading();
                webView.loadUrl("about:blank");
            });

            webViewCleanupExecutor.execute(() -> {
                try {
                    mainHandler.post(() -> {
                        try {
                            // 先检查父视图
                            ViewParent parent = webView.getParent();
                            if (parent instanceof ViewGroup) {
                                ((ViewGroup) parent).removeView(webView);
                            }
                            
                            // 清理 WebView
                            webView.clearHistory();
                            webView.clearCache(true);
                            webView.clearFormData();
                            webView.clearSslPreferences();
                            webView.removeJavascriptInterface("android");
                            webView.removeAllViews();
                            webView.destroy();
                            LogUtils.logEvent("WEBVIEW", "清理WebView: " + uperId);
                        } catch (Exception e) {
                            Logger.e(TAG, "清理WebView失败: " + e.getMessage());
                        }
                    });

                    // 清理其他资源...
                } catch (Exception e) {
                    Logger.e(TAG, "清理资源失败: " + e.getMessage());
                }
            });
        }
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

    // 定义为类成员变量
    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {

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
            } catch (Exception e) {
                Logger.e(TAG, "Watchdog检查失败: " + e.getMessage());
            }
            
            // 每2分钟检查一次
            mainHandler.postDelayed(this, 2 * 60 * 1000);
        }
    };

    private void startServiceWatchdog() {
        // 移除之前的回调（如果有）
        mainHandler.removeCallbacks(watchdogRunnable);
        // 延迟5分钟后开始第一次检查
        mainHandler.postDelayed(watchdogRunnable, 5 * 60 * 1000);
        LogUtils.logEvent("SERVICE", "Watchdog启动");
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
            // 在主线程中执行WebView的清理
            mainHandler.post(() -> {
                // 停并清理 WebView
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
                
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
            LogUtils.logEvent("WEBVIEW", "WebView already exists for uperId: " + uperId);
            return;
        }
        
        webView.setWebChromeClient(new WebChromeClient() {
            private volatile long lastProcessTime = 0;
            private static final long PROCESS_INTERVAL = 30000; // 30秒处理一次
            
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                String message = consoleMessage.message();
                
                // 处理心跳消息
                if (message.contains("ZtLiveCsHeartbeat")) {
                    long now = System.currentTimeMillis();
                    // 每次心跳都更新时间戳
                    lastHeartbeatTimes.put(uperId, now);
                    
                    // 只在间隔时间后执行完整处理 30秒创建一次线程池
                    if (now - lastProcessTime >= PROCESS_INTERVAL) {
                        lastProcessTime = now;
                        webViewCleanupExecutor.execute(() -> {
                            try {
                                WatchStatsManager.WatchData data = WatchStatsManager.getInstance(LiveWatchService.this)
                                    .getAllStats().get(uperId);
                                String nickname = data != null ? data.name : uperId;
                                
                                Logger.i(TAG, "收到直播间心跳: " + nickname + "(" + uperId + ")");
                                LogUtils.logEvent("HEARTBEAT", "收到直播间心跳: " + nickname + "(" + uperId + ")");
                                
                                lastHeartbeatLogTimes.put(uperId, now);
                            } catch (Exception e) {
                                Logger.e(TAG, "处理心跳失败: " + e.getMessage());
                            }
                        });
                    }
                    return true;
                }
                
                // 处理 retryTicket 消息
                if (message.contains("retryTicket")) {
                    Logger.i(TAG, "检测到retryTicket信息，准备重建直播间: " + uperId);
                    LogUtils.logEvent("WEBVIEW", "检测到retryTicket，重建直播间: " + uperId);
                    
                    WatchStatsManager.WatchData data = WatchStatsManager.getInstance(LiveWatchService.this)
                        .getAllStats().get(uperId);
                    String nickname = data != null ? data.name : uperId;
                    
                    mainHandler.post(() -> {
                        try {
                            closeWebView(uperId);
                            mainHandler.postDelayed(() -> {
                                LogUtils.logEvent("WEBVIEW", String.format(
                                    "开始重建直播间: %s(%s)", nickname, uperId));
                                checkLiveStatus(uperId);
                            }, 2000);
                        } catch (Exception e) {
                            Logger.e(TAG, "重建直播间失败: " + e.getMessage());
                            LogUtils.logEvent("ERROR", String.format(
                                "重建直播间失败: %s(%s) - %s", 
                                nickname, uperId, e.getMessage()));
                        }
                    });
                    return true;
                }
                
                // 其他控制台消息
                Logger.d(TAG, "控制台消息: " + message);
                return super.onConsoleMessage(consoleMessage);
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
    // 心跳处理移到后台线程
    private void handleHeartbeat(String message) {
        // 心跳处理逻辑保持在后台线程
        
        // 只在真正需要时才更新通知
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL) {
            mainHandler.post(() -> {
                updateNotification();
                lastNotificationUpdateTime = currentTime;
            });
        }
    }


    private void startPeriodicRefresh() {
        // 立即执行一次
        fetchMedalList();
        
        // 然后开始定时任务
        mainHandler.post(periodicCheckRunnable);
    }
    
    private boolean isInitialFetchInProgress = false;
    
    // 添加线程池用于处理网络响应
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(3);

    private void fetchMedalList() {
        if (isInitialFetchInProgress) {
            Logger.d(TAG, "跳过重复的请求");
            return;
        }

        Request request = new Request.Builder()
            .url(MEDAL_LIST_API)
            .header("Cookie", CookieManager.getCookies())
            .build();

        // 异步网络请求
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                // 使用线程池处理响应
                networkExecutor.execute(() -> {
                    try {
                        String json = response.body().string();
                        JSONObject jsonObject = new JSONObject(json);
                        
                        if (jsonObject.getInt("result") == 0) {
                            // 在后台线程处理数据
                            Set<String> newMedalUperIds = new HashSet<>();
                            JSONArray medalList = jsonObject.getJSONArray("medalList");
                            
                            for (int i = 0; i < medalList.length(); i++) {
                                JSONObject medal = medalList.getJSONObject(i);
                                newMedalUperIds.add(medal.getString("uperId"));
                            }
                            
                            // 更新数据
                            medalUperIds = newMedalUperIds;
                            
                            // 获取直播列表
                            mainHandler.post(() -> fetchLiveList());
                        }
                    } catch (Exception e) {
                        Logger.e(TAG, "处理粉丝牌数据失败", e);
                    }
                });
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
                if (CookieManager.handleResponseUnauthorized(response)) {
                    return;
                }
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
                        String nickname = user.getString("name");  // 获取昵称
                        String liveId = live.getString("liveId");
                        
                        if (user.getBoolean("isFollowing") && medalUperIds.contains(uperId)) {
                            uniqueWatchList.add(new LiveUperInfo(uperId, nickname, liveId));
                        }
                    }
                    // 即使没有主播在线也要记录状态
                    if (uniqueWatchList.isEmpty()) {
                        LogUtils.logEvent("LIVE_CHECK", "当前没有需要挂机的直播间");
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
    
    private final Object watchLock = new Object();  // 添加锁对象

    private void processBatchWatchList(List<LiveUperInfo> watchList) {
        // 使用线程池并行处理请求
        List<Future<LiveUperInfo>> futures = new ArrayList<>();
        
        for (LiveUperInfo info : watchList) {
            Future<LiveUperInfo> future = networkExecutor.submit(() -> {
                try {
                    String url = String.format(MEDAL_INFO_API, info.uperId);
                    Request request = new Request.Builder()
                        .url(url)
                        .header("Cookie", CookieManager.getCookies())
                        .build();

                    Response response = client.newCall(request).execute();
                    if (CookieManager.handleResponseUnauthorized(response)) {
                        return null;
                    }
                    String json = response.body().string();
                    JSONObject jsonObject = new JSONObject(json);
                    
                    if (jsonObject.getInt("result") == 0) {
                        JSONObject medalDegreeLimit = jsonObject.getJSONObject("medalDegreeLimit");
                        int watchDegree = medalDegreeLimit.getInt("liveWatchDegree");
                        info.degree = watchDegree;
                        
                        // 更新统计数据
                        mainHandler.post(() -> {
                            WatchStatsManager.getInstance(LiveWatchService.this)
                                .updateWatchDegree(info.uperId, info.nickname, watchDegree);
                        });
                    }
                    return info;
                } catch (Exception e) {
                    Logger.e(TAG, "获取直播间息失败: " + info.uperId, e);
                    return null;
                }
            });
            futures.add(future);
        }
        
        // 收集结果
        List<LiveUperInfo> needWatchList = new ArrayList<>();
        StringBuilder watchInfoBuilder = new StringBuilder("需要挂机的直播间: ");
        
        for (Future<LiveUperInfo> future : futures) {
            try {
                LiveUperInfo info = future.get(5, TimeUnit.SECONDS);
                if (info != null && info.degree < 360) {
                    needWatchList.add(info);
                    if (watchInfoBuilder.toString().endsWith(": ")) {
                        watchInfoBuilder.append(String.format("%s (%d/360)", 
                            info.nickname, info.degree));
                    } else {
                        watchInfoBuilder.append(String.format(", %s (%d/360)", 
                            info.nickname, info.degree));
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, "处理直播间信息失败", e);
            }
        }
        
        // 添加这两行日志输出
        Logger.i(TAG, watchInfoBuilder.toString());
        LogUtils.logEvent("WATCH_LIST", watchInfoBuilder.toString());
        
        synchronized (watchLock) {  // 同样加锁保护
            // 检查现有的WebView是否需要停止
            for (Map.Entry<String, WebView> entry : new HashMap<>(webviews).entrySet()) {
                String uperId = entry.getKey();
                WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this)
                    .getAllStats().get(uperId);
                if (data != null && data.degree >= 360) {
                    LogUtils.logEvent("STOP_WATCH", "经验已满: " + uperId);
                    stopWatching(uperId);
                }
            }
            
            // 处理需要新增的观看
            for (LiveUperInfo info : needWatchList) {
                startWatching(info.liveId, info.uperId, info.nickname);
            }
        }
        
        // 添加WebView统计信到日志
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

 
    
    private String getCurrentDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(new Date());
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

    // 添加广播动作常量
    public static final String ACTION_WATCH_STATUS_CHANGED = "com.example.acdemo.ACTION_WATCH_STATUS_CHANGED";
    
    private void notifyWatchStatusChanged(String uperId, boolean isWatching) {
        Intent intent = new Intent(ACTION_WATCH_STATUS_CHANGED);
        intent.putExtra("uperId", uperId);
        intent.putExtra("isWatching", isWatching);
        sendBroadcast(intent);
    }

    private void updateWatchStatus(String uperId, boolean isWatching) {
        if (isWatching) {
            roomStatuses.put(uperId, true);
        } else {
            roomStatuses.remove(uperId);
            stopWatching(uperId);
        }
        notifyWatchStatusChanged(uperId, isWatching);
    }

    public static final String ACTION_UPDATE_WATCH_LIST = "com.example.acdemo.ACTION_UPDATE_WATCH_LIST";
    
    private BroadcastReceiver watchListReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_WATCH_LIST.equals(intent.getAction())) {
                @SuppressWarnings("unchecked")
                HashMap<String, String> newLiveMap = 
                    (HashMap<String, String>) intent.getSerializableExtra("liveMap");
                    
                updateWatchList(newLiveMap);
            }
        }
    };
    
    private void updateWatchList(Map<String, String> newLiveMap) {
        synchronized (watchLock) {  // 加锁保护
            // 清理不在直播列表中的直播间
            for (String uperId : new HashSet<>(roomStatuses.keySet())) {
                if (!newLiveMap.containsKey(uperId)) {
                    stopWatching(uperId);
                }
            }
            
            // 启动新的直播间观看
            for (Map.Entry<String, String> entry : newLiveMap.entrySet()) {
                String uperId = entry.getKey();
                if (!roomStatuses.containsKey(uperId)) {
                    startWatching(entry.getValue(), uperId, 
                        WatchStatsManager.getInstance(this).getNickname(uperId));
                }
            }
        }
    }

    private void recoverFromCrash() {
        SharedPreferences prefs = getSharedPreferences("crash_recovery", MODE_PRIVATE);
        long crashTime = prefs.getLong("crash_time", 0);
        Set<String> watchingRooms = prefs.getStringSet("watching_rooms", new HashSet<>());
        
        if (System.currentTimeMillis() - crashTime < 5 * 60 * 1000) { // 5分钟内的崩溃
            // 恢复观看状态
            for (String uperId : watchingRooms) {
                checkLiveStatus(uperId);  // 重新检查直播状态并恢复观看
            }
            
            // 清理恢复数据
            prefs.edit().clear().apply();
        }
        
        // 记录恢复事件
        LogUtils.logEvent("RECOVERY", "服务从崩溃中恢复");
    }

    private boolean isServiceActuallyRunning() {
        return isServiceActuallyRunning(this);
    }

    private boolean isServiceActuallyRunning(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            
            // 首先检查服务是否在运行
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (LiveWatchService.class.getName().equals(service.service.getClassName())) {
                    // 进一步验证服务状态
                    if (service.pid > 0 && service.foreground) {
                        // 额外检查服务是否真正在工作
                        if (LiveWatchService.isActuallyWorking()) { // 需要在Service中实现此方法
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            LogUtils.logEvent("ERROR", "服务状态检查失败: " + e.getMessage());
            return false; // 检失败时假定服务未运行
        }
    }

    // 统一的服务状态检查方法
    public static boolean isServiceFullyRunning(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            
            // 1. 检查临时停止状态
            if (isTemporaryStopped(context)) {
                LogUtils.logEvent("SERVICE", "服务处于临时停止状态");
                return false;
            }
            
            // 2. 检查服务进程状态
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (LiveWatchService.class.getName().equals(service.service.getClassName())) {
                    // 3. 检查前台服务状态
                    if (service.foreground && service.pid > 0) {
                        return true;
                    }
                }
            }
            LogUtils.logEvent("SERVICE", "服务未在运行");
            return false;
        } catch (Exception e) {
            LogUtils.logEvent("ERROR", "服务状态检查失败: " + e.getMessage());
            return false;
        }
    }

    private boolean shouldUpdateNotification(long currentTime) {
        return currentTime - lastNotificationUpdateTime >= NOTIFICATION_UPDATE_INTERVAL;
    }

    private boolean shouldRefreshList(long currentTime) {
        return currentTime - lastRefreshTime >= REFRESH_INTERVAL;
    }
    // 添加系统低内存回调
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            LogUtils.logEvent("SYSTEM", "系统内存不足，等级: " + level);
            LogUtils.shutdown();
            
        }
    }


} 