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

public class LiveWatchService extends Service {
    private static final String TAG = "AcDemo_LiveWatchService";
    private Map<String, Timer> watchingTimers = new HashMap<>();
    private Map<String, String> tickets = new HashMap<>();
    private OkHttpClient client;
    private Map<String, WebView> webviews = new HashMap<>();
    
    // 添加静态Map来存储直播间状态
    public static Map<String, Boolean> roomStatuses = new HashMap<>();
    
    private android.os.PowerManager.WakeLock wakeLock;
    
    private Map<String, Long> ticketTimestamps = new HashMap<>();  // 记录ticket获取时间
    private Map<String, Long> liveIdTimestamps = new HashMap<>();  // 记录liveId获取时间
    private static final long TICKET_EXPIRE_TIME = 3600000; // ticket有效期1小时
    private static final long LIVEID_EXPIRE_TIME = 1800000; // liveId有效期30分钟
    
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
                Timer timer = watchingTimers.remove(uperId);
                if (timer != null) {
                    timer.cancel();
                    Log.d(TAG, "Stopped watching for uperId: " + uperId);
                }
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
        // 检查是否已有有效的ticket和liveId
        if (isTicketValid(uperId) && isLiveIdValid(liveId, uperId)) {
            startPlayWithToken(liveId, uperId, null);
            return;
        }
        
        // 获取新的token和ticket
        String cookies = com.example.acdemo.utils.CookieManager.getCookies();
        Log.d(TAG, "Using cookies for token request: " + cookies);
        
        Request tokenRequest = new Request.Builder()
            .url("https://id.app.acfun.cn/rest/web/token/get")
            .header("Cookie", cookies)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://live.acfun.cn")
            .header("Referer", "https://live.acfun.cn/")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
            .post(new FormBody.Builder()
                .add("sid", "acfun.midground.api")
                .build())
            .build();
            
        client.newCall(tokenRequest).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                Log.d(TAG, "Token response: " + json);
                
                try {
                    JSONObject result = new JSONObject(json);
                    if (result.getInt("result") == 0) {
                        String midgroundToken = result.getString("acfun.midground.api_st");
                            
                        startPlayWithToken(liveId, uperId, midgroundToken);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Get token failed", e);
                }
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Get token request failed", e);
            }
        });
    }
    
    private boolean isTicketValid(String uperId) {
        if (!tickets.containsKey(uperId)) return false;
        Long timestamp = ticketTimestamps.get(uperId);
        if (timestamp == null) return false;
        return System.currentTimeMillis() - timestamp < TICKET_EXPIRE_TIME;
    }
    
    private boolean isLiveIdValid(String liveId, String uperId) {
        Long timestamp = liveIdTimestamps.get(uperId);
        if (timestamp == null) return false;
        return System.currentTimeMillis() - timestamp < LIVEID_EXPIRE_TIME;
    }
    
    private void onHeartbeatError(String uperId, String liveId) {
        // 心跳失败时，清除ticket并重新获取
        tickets.remove(uperId);
        ticketTimestamps.remove(uperId);
        liveIdTimestamps.remove(uperId);
        startWatching(liveId, uperId);
    }
    
    private void startPlayWithToken(String liveId, String uperId, String midgroundToken) {
        // 检查是否已经有这个直播间的WebView
        if (webviews.containsKey(uperId)) {
            Log.d(TAG, "WebView already exists for uperId: " + uperId);
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            Log.d(TAG, "Starting watch with liveId: " + liveId + ", uperId: " + uperId);
            
            WebView webView = new WebView(this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            
            webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
            
            webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
            webView.getSettings().setDatabaseEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            
            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                    String message = consoleMessage.message();
                    // 检测直播间状态
                    if (message.contains("直播已结束") || message.contains("主播未开播")) {
                        Log.d(TAG, "直播已结束或未开播，停止服务: " + uperId);
                        stopWatching(uperId);
                        return true;
                    }
                    
                    if (message.contains("liveCsCmd ZtLiveCsHeartbeat")) {
                        Log.d(TAG, "Detected heartbeat for uperId: " + uperId);
                        resetKeepAliveTimer(webView, uperId);
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
                    
                    // 只隐藏视频元素
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
            webView.loadUrl("https://live.acfun.cn/live/" + uperId);
            
            startKeepAliveTimer(webView, uperId);
        });
    }

    private void startKeepAliveTimer(WebView webView, String uperId) {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "(function() { return document.visibilityState; })();",
                            value -> {
                                if (!"visible".equals(value)) {
                                    Log.d(TAG, "检查直播间状态: " + uperId);
                                    webView.evaluateJavascript(
                                        "(function() { return document.querySelector('.live-closed') !== null; })();",
                                        isClosed -> {
                                            if ("true".equals(isClosed)) {
                                                Log.d(TAG, "直播已结束，停止服务: " + uperId);
                                                stopWatching(uperId);
                                            } else {
                                                webView.reload();
                                            }
                                        }
                                    );
                                }
                            }
                        );
                    }
                });
            }
        }, 60000, 60000);  // 改为1分钟检查一次
        watchingTimers.put(uperId + "_keepalive", timer);
    }

    private void resetKeepAliveTimer(WebView webView, String uperId) {
        Timer oldTimer = watchingTimers.remove(uperId + "_keepalive");
        if (oldTimer != null) {
            oldTimer.cancel();
        }
        startKeepAliveTimer(webView, uperId);
    }

    private void connectToStream(String streamUrl, String uperId) {
        Request streamRequest = new Request.Builder()
            .url(streamUrl)
            .header("Origin", "https://live.acfun.cn")
            .header("Referer", "https://live.acfun.cn/live/" + uperId)
            .build();
            
        client.newCall(streamRequest).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
            
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Stream connection failed", e);
            }
        });
    }
    
    private void startHeartbeat(String uperId, String liveId) {
        Log.d(TAG, "Starting heartbeat for uperId: " + uperId);
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendHeartbeat(uperId, liveId);
            }
        }, 0, 30000);
        
        watchingTimers.put(uperId, timer);
    }
    
    private void sendHeartbeat(String uperId, String liveId) {
        String cookies = com.example.acdemo.utils.CookieManager.getCookies();
        String ticket = tickets.get(uperId);
        
        if (ticket == null) {
            Log.w(TAG, "No ticket available for uperId: " + uperId);
            return;
        }
        
        Log.d(TAG, "Sending heartbeat for uperId: " + uperId);
        
        FormBody formBody = new FormBody.Builder()
            .add("liveId", liveId)
            .add("liveCsCmd", "ZtLiveCsHeartbeat")
            .add("ticket", ticket)
            .add("csrfToken", extractToken(cookies, "csrfToken"))
            .build();

        Request request = new Request.Builder()
            .url("https://api.kuaishouzt.com/rest/zt/live/web/heartbeat")
            .header("Cookie", cookies)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://live.acfun.cn")
            .header("Referer", "https://live.acfun.cn/live/" + uperId)
            .post(formBody)
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Heartbeat failed for uperId: " + uperId, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String json = response.body().string();
                    Log.d(TAG, "Heartbeat response for uperId " + uperId + ": " + json);
                    JSONObject result = new JSONObject(json);
                    if (result.getInt("result") == 1) {
                        Log.d(TAG, "Heartbeat success for uperId: " + uperId);
                    } else {
                        Log.e(TAG, "Heartbeat error for uperId " + uperId + ": " + json);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse heartbeat response failed for uperId: " + uperId, e);
                }
            }
        });
    }
    
    private String getLowestQualityStream(JSONObject playRes) {
        try {
            String videoPlayResStr = playRes.getString("videoPlayRes");
            JSONObject videoPlayRes = new JSONObject(videoPlayResStr);
            JSONArray manifests = videoPlayRes.getJSONArray("liveAdaptiveManifest");
            
            if (manifests.length() > 0) {
                JSONObject manifest = manifests.getJSONObject(0);
                JSONObject adaptationSet = manifest.getJSONObject("adaptationSet");
                JSONArray representations = adaptationSet.getJSONArray("representation");
                
                // 找到level值最小的流
                JSONObject lowestQuality = representations.getJSONObject(0);
                int lowestLevel = lowestQuality.getInt("level");
                
                for (int i = 1; i < representations.length(); i++) {
                    JSONObject rep = representations.getJSONObject(i);
                    int level = rep.getInt("level");
                    if (level < lowestLevel) {
                        lowestLevel = level;
                        lowestQuality = rep;
                    }
                }
                
                Log.d(TAG, "选择画质: " + lowestQuality.getString("name") + 
                          ", level: " + lowestQuality.getInt("level") + 
                          ", bitrate: " + lowestQuality.getInt("bitrate"));
                          
                return lowestQuality.getString("url");
            }
        } catch (Exception e) {
            Log.e(TAG, "解析流地址失败: " + e.getMessage());
        }
        return null;
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
        tickets.clear();
        roomStatuses.clear();
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

    private String getTicketFromStartPlayResponse(String json) {
        try {
            JSONObject result = new JSONObject(json);
            if (result.getInt("result") == 1) {
                JSONObject data = result.getJSONObject("data");
                JSONArray tickets = data.getJSONArray("availableTickets");
                return tickets.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse ticket failed", e);
        }
        return null;
    }

    private void stopWatching(String uperId) {
        Log.d(TAG, "停止观看直播间: " + uperId);
        
        // 停止心跳定时器
        Timer timer = watchingTimers.remove(uperId);
        if (timer != null) {
            timer.cancel();
        }
        
        // 停止保活定时器
        Timer keepAliveTimer = watchingTimers.remove(uperId + "_keepalive");
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
        }
        
        // 移除WebView
        WebView webView = webviews.remove(uperId);
        if (webView != null) {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            windowManager.removeView(webView);
            webView.destroy();
        }
        
        // 清除状态
        roomStatuses.remove(uperId);
        tickets.remove(uperId);
        ticketTimestamps.remove(uperId);
    }
} 