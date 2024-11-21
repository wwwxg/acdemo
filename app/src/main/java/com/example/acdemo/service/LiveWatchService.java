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

public class LiveWatchService extends Service {
    private static final String TAG = "AcDemo_LiveWatchService";
    private Map<String, Timer> watchingTimers = new HashMap<>();
    private Map<String, WebView> webviews = new HashMap<>();
    private Map<String, Long> lastHeartbeatTimes = new HashMap<>();
    private static final long HEARTBEAT_TIMEOUT = 90000;
    public static Map<String, Boolean> roomStatuses = new HashMap<>();
    private android.os.PowerManager.WakeLock wakeLock;
    private OkHttpClient client;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // 获取 WakeLock
        android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "AcDemo:LiveWatchWakeLock");
        wakeLock.acquire();
        
        client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        startForeground(1, createNotification());
        
        registerNetworkCallback();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String uperId = intent.getStringExtra("uperId");
            String action = intent.getStringExtra("action");
            
            if ("stop".equals(action)) {
                stopWatching(uperId);
                Log.d(TAG, "Stopped watching for uperId: " + uperId);
            } else {
                String liveId = intent.getStringExtra("liveId");
                if (liveId != null && !watchingTimers.containsKey(uperId)) {
                    startWatching(liveId, uperId);
                }
            }
        }
        return START_STICKY;
    }
    
    private void startWatching(String liveId, String uperId) {
        // 检查是否已经有这个直播间的WebView
        if (webviews.containsKey(uperId)) {
            Log.d(TAG, "WebView already exists for uperId: " + uperId);
            return;
        }
        
        // 直接创建WebView并加载页面
        startPlayWithToken(liveId, uperId);
    }
    
    private void startPlayWithToken(String liveId, String uperId) {
        if (webviews.containsKey(uperId)) {
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
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
            
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    String message = consoleMessage.message();
                    if (message.contains("liveCsCmd ZtLiveCsHeartbeat")) {
                        String uperName = MainActivity.uperNames.getOrDefault(uperId, uperId);
                        Log.d(TAG, String.format("收到直播间心跳: %s(%s)", uperName, uperId));
                        lastHeartbeatTimes.put(uperId, System.currentTimeMillis());
                    }
                    return true;
                }
            });
            
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "WebView loaded for uperId: " + uperId);
                    
                    // 更新状态
                    roomStatuses.put(uperId, true);
                    
                    // 隐藏视频元素
                    String js = "var style = document.createElement('style');" +
                               "style.innerHTML = 'video, iframe { display: none !important; }';" +
                               "document.head.appendChild(style);";
                    webView.evaluateJavascript(js, null);
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    Log.e(TAG, "WebView error for uperId " + uperId + ": " + description);
                    view.reload();
                }
            });
            
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            );
            
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.addView(webView, params);
            webviews.put(uperId, webView);
            String liveUrl = String.format("https://live.acfun.cn/room/%s?theme=default&showAuthorclubOnly=", uperId);
            webView.loadUrl(liveUrl);
            
            // 启动心跳检测定时器
            Timer heartbeatTimer = new Timer();
            heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    Long lastHeartbeat = lastHeartbeatTimes.get(uperId);
                    if (lastHeartbeat != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeat > HEARTBEAT_TIMEOUT) {
                            Log.d(TAG, "直播间心跳超时，判定为已下播: " + uperId);
                            new Handler(Looper.getMainLooper()).post(() -> stopWatching(uperId));
                        }
                    }
                }
            }, 30000, 30000); // 每30秒检查一次心跳状态
            watchingTimers.put(uperId, heartbeatTimer);
        });
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
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
        super.onDestroy();
    }

    private Notification createNotification() {
        NotificationChannel channel = new NotificationChannel(
            "acdemo_service", 
            "AcFun挂机服务",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);

        return new NotificationCompat.Builder(this, "acdemo_service")
            .setContentTitle("AcFun挂机手")
            .setContentText("正在运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build();
    }

    private void stopWatching(String uperId) {
        Log.d(TAG, "停止观看直播间: " + uperId);
        
        // 停止心跳定时器
        Timer timer = watchingTimers.remove(uperId);
        if (timer != null) {
            timer.cancel();
        }
        
        // 除心跳记录
        lastHeartbeatTimes.remove(uperId);
        
        // 移除WebView
        WebView webView = webviews.remove(uperId);
        if (webView != null) {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.removeView(webView);
            webView.destroy();
        }
        
        // 清除状态
        roomStatuses.remove(uperId);
    }

    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = 
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                Log.d(TAG, "网络断开，30秒后尝试重连");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    for (Map.Entry<String, WebView> entry : webviews.entrySet()) {
                        entry.getValue().reload();
                    }
                }, 30000);
            }
        };
    }
} 