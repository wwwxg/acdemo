package com.example.acdemo;

import android.content.Intent;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.acdemo.utils.CookieManager;
import com.example.acdemo.service.LiveWatchService;
import okhttp3.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import android.view.View;
import com.example.acdemo.manager.StatsManager;
import com.example.acdemo.model.WatchStats;
import java.util.List;
import com.example.acdemo.utils.WatchStatsManager;
import java.util.ArrayList;
import java.util.Collections;
import android.app.ActivityManager;
import androidx.appcompat.app.AlertDialog;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import com.example.acdemo.utils.LogUtils;
import com.example.acdemo.utils.Logger;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private TextView userInfoText;
    private TextView liveListText;
    private TextView statsText;
    private TextView toggleStatsText;
    private OkHttpClient client;
    private static final String USER_INFO_API = "https://www.acfun.cn/rest/pc-direct/user/personalInfo";
    private static final String LIVE_LIST_API = "https://live.acfun.cn/api/channel/list?count=100&pcursor=&filters=[%7B%22filterType%22:3,+%22filterId%22:0%7D]";
    private static final String MEDAL_LIST_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/list";
    private static final String MEDAL_INFO_API = "https://www.acfun.cn/rest/pc-direct/fansClub/fans/medal/extraInfo?uperId=%s";
    private int pendingRequests = 0;
    private StringBuilder finalStringBuilder;
    private String currentTime;
    
    private Set<String> medalUperIds = new HashSet<>();
    private Handler refreshHandler = new Handler();
    private static final long REFRESH_INTERVAL = 120000; // 2分钟
    private Map<String, String> liveIdMap = new HashMap<>(); // 存储主播ID和直播间ID的映射
    
    private Map<String, Long> experienceFullUperIds = new HashMap<>(); // 记录经验值已满的ID和时间
    
    private boolean showingStats = false;
    
    private static final String PREFS_NAME = "AcDemoPrefs";
    private static final String KEY_AUTOSTART_CHECKED = "autostartChecked";
    
    private Set<String> processedUperIds = new HashSet<>();
    private long lastClearDay = 0;  // 添加这个变量
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtils.logEvent("ACTIVITY", "MainActivity onCreate");
        
        // 1. 基础权限检查
        checkNotificationPermission(); // 通知权限最基础

        
        // 2. 后台运行相关权限
        checkBackgroundPermission();  // 后台运行权限
        checkBatteryOptimization();   // 电池优化（封装成方法）
        checkAutoStartPermission();   // 自启动权限
        
        // 3. 最后检查服务状态
        checkServiceRunning();
        
        setContentView(R.layout.activity_main);
        
        userInfoText = findViewById(R.id.userInfoText);
        liveListText = findViewById(R.id.liveListText);
        statsText = findViewById(R.id.statsText);
        toggleStatsText = findViewById(R.id.toggleStatsText);
        
        client = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        
        String currentTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        liveListText.setText("加载时间: " + currentTime + "\n正在获取数据...\n");
                
        fetchUserInfo();
        fetchMedalList();
        
        // 启动定时刷新
        startPeriodicRefresh();
        
        // 添加点击事件监听
        toggleStatsText.setOnClickListener(v -> toggleStatsView());
    }
    
    private void startPeriodicRefresh() {
        refreshHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fetchMedalList(); // 重新获取数据
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        }, REFRESH_INTERVAL);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.logEvent("ACTIVITY", "MainActivity onDestroy");
        refreshHandler.removeCallbacksAndMessages(null);
    }
    
    private void fetchUserInfo() {
        String cookies = CookieManager.getCookies();
        Request request = new Request.Builder()
            .url(USER_INFO_API)
            .header("Cookie", cookies)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Referer", "https://www.acfun.cn")
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "获取用户信息失败", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                    "获取用户信息失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                try {
                    JSONObject result = new JSONObject(json);
                    if (result.getInt("result") == 0) {
                        JSONObject info = result.getJSONObject("info");
                        String userName = info.getString("userName");
                        String userId = info.getString("userId");
                        Logger.i(TAG, String.format("用户信息: %s (ID: %s)", userName, userId));
                        
                        runOnUiThread(() -> {
                            userInfoText.setText(String.format("用户: %s (ID: %s)", userName, userId));
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析用户信息失败", e);
                }
            }
        });
    }
    
    private void fetchMedalList() {
        String cookies = CookieManager.getCookies();
        
        Request request = new Request.Builder()
            .url(MEDAL_LIST_API)
            .header("Cookie", cookies)
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "获取粉丝牌列表失败", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                    "获取粉丝牌列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                
                try {
                    JSONObject jsonObject = new JSONObject(json);
                    if (jsonObject.getInt("result") == 0) {
                        JSONArray medalList = jsonObject.getJSONArray("medalList");
                        medalUperIds.clear();
                        
                        for (int i = 0; i < medalList.length(); i++) {
                            JSONObject medal = medalList.getJSONObject(i);
                            String uperId = medal.getString("uperId");
                            medalUperIds.add(uperId);
                        }
                        
                        // 获取完粉丝牌列表后再获取直播列表
                        fetchLiveList();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析粉丝牌列表失败", e);
                }
            }
        });
    }
    
    private void fetchLiveList() {
        clearProcessedUpersIfNeeded();  // 在获取直播列表前检查是否需要清理
        String cookies = CookieManager.getCookies();
        
        Request request = new Request.Builder()
            .url(LIVE_LIST_API)
            .header("Cookie", cookies)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Referer", "https://live.acfun.cn")
            .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "获取直播列表失败", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, 
                    "获取直播列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                // 解析json只为了日志显示
                try {
                    JSONObject jsonObj = new JSONObject(json);
                    JSONObject channelListData = jsonObj.getJSONObject("channelListData");
                    JSONArray liveList = channelListData.getJSONArray("liveList");
                    StringBuilder liveUpers = new StringBuilder("当前直播中: ");
                    for (int i = 0; i < liveList.length(); i++) {
                        JSONObject live = liveList.getJSONObject(i);
                        JSONObject user = live.getJSONObject("user");
                        String name = user.getString("name");
                        liveUpers.append(name);
                        if (i < liveList.length() - 1) {
                            liveUpers.append(", ");
                        }
                    }
                    Log.d(TAG, liveUpers.toString());
                } catch (Exception e) {
                    Log.e(TAG, "解析直播列表失败", e);
                }
                
                // 原始json完整传递给处理方法
                handleLiveListResponse(json);
            }
        });
    }
    
    private void handleLiveListResponse(String json) {
        try {
            JSONObject response = new JSONObject(json);
            JSONObject channelListData = response.getJSONObject("channelListData");
            if (channelListData.getInt("result") == 0) {
                JSONArray liveList = channelListData.getJSONArray("liveList");
                finalStringBuilder = new StringBuilder();
                currentTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", 
                    java.util.Locale.getDefault()).format(new java.util.Date());
                finalStringBuilder.append("刷新时间: ").append(currentTime).append("\n\n");
                
                pendingRequests = 0;
                liveIdMap.clear();
                
                // 记录当前在线的主播
                Set<String> currentLiveUpers = new HashSet<>();
                
                // 处理在线主播
                for (int i = 0; i < liveList.length(); i++) {
                    JSONObject live = liveList.getJSONObject(i);
                    String uperId = live.getString("authorId");
                    JSONObject user = live.getJSONObject("user");
                    
                    if (user.getBoolean("isFollowing") && medalUperIds.contains(uperId)) {
                        // 检查是否是同一天内已经满经验的主播
                        Long fullTime = experienceFullUperIds.get(uperId);
                        if (fullTime != null && isSameDay(fullTime, System.currentTimeMillis())) {
                            continue;
                        }
                        
                        currentLiveUpers.add(uperId);
                        String nickname = user.getString("name");
                        String title = live.getString("title");
                        String liveId = live.getString("liveId");
                        
                        pendingRequests++;
                        handleMedalInfo(uperId, nickname, title, liveId, pendingRequests - 1);
                    }
                }
                
                // 检查需要关闭的直播间
                for (String uperId : new HashSet<>(LiveWatchService.roomStatuses.keySet())) {
                    if (processedUperIds.contains(uperId)) {
                        continue;
                    }
                    
                    WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this).getAllStats().get(uperId);
                    if (data != null) {
                        if (!currentLiveUpers.contains(uperId) || data.degree >= 360) {
                            String reason = data.degree >= 360 ? 
                                String.format("经验已满(%d)", data.degree) : "已下播";
                            Log.d(TAG, String.format("主播 %s %s，关闭直播间", data.name, reason));
                            LiveWatchService.stopWatchingStatic(this, uperId);
                            processedUperIds.add(uperId);
                        }
                    }
                }
                
                // 如果没有需要处理的主播，直接更新UI
                if (pendingRequests == 0) {
                    runOnUiThread(() -> {
                        if (!showingStats) {
                            finalStringBuilder.append("当前没有正在直播已关注主播");
                            liveListText.setText(finalStringBuilder.toString());
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析直播列失败", e);
            runOnUiThread(() -> {
                if (!showingStats) {
                    liveListText.setText("获直播列表失败，请查网络连接\n" + e.getMessage());
                }
            });
        }
    }
    
    private void handleMedalInfo(String uperId, String nickname, String title, String liveId, int index) {
        // 先检查经验值是否已满
        WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this).getAllStats().get(uperId);
        if (data != null && data.degree >= 360) {
            // 经验已满，不再请求
            pendingRequests--;
            return;
        }
        
        // 继续请求粉丝牌信息
        String cookies = CookieManager.getCookies();
        String url = String.format(MEDAL_INFO_API, uperId);
        Request request = new Request.Builder()
            .url(url)
            .header("Cookie", cookies)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Cache-Control", "max-age=0")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "document")
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-site", "none")
            .header("sec-fetch-user", "?1")
            .header("upgrade-insecure-requests", "1")
            .build();
            
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "获取粉丝牌信息失败: " + uperId, e);
                checkAndUpdateUI(index, "");  // 失败时不显示
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String json = response.body().string();
                try {
                    JSONObject result = new JSONObject(json);
                    if (result.getInt("result") == 0) {
                        JSONObject medalDegreeLimit = result.getJSONObject("medalDegreeLimit");
                        int watchDegree = medalDegreeLimit.getInt("liveWatchDegree");
                        Log.d(TAG, String.format("粉丝牌信息: ID %s, 经验值 %d/360", uperId, watchDegree));
                        
                        // 更新统计信息
                        WatchStatsManager.getInstance(MainActivity.this)
                            .updateWatchDegree(uperId, nickname, watchDegree);
                        
                        if (watchDegree >= 360) {
                            // 经验值已满,记录时间并停止观看
                            experienceFullUperIds.put(uperId, System.currentTimeMillis());
                            Intent intent = new Intent(MainActivity.this, LiveWatchService.class);
                            intent.putExtra("uperId", uperId);
                            intent.putExtra("action", "stop");
                            startService(intent);
                            checkAndUpdateUI(index, "");
                            return;
                        }
                        
                        // 检查是否是新的一天
                        Long fullTime = experienceFullUperIds.get(uperId);
                        if (fullTime != null) {
                            if (isSameDay(fullTime, System.currentTimeMillis())) {
                                // 同一天内不再处理
                                checkAndUpdateUI(index, "");
                                return;
                            } else {
                                // 新的一天,移除记录
                                experienceFullUperIds.remove(uperId);
                            }
                        }
                        
                        if (watchDegree < 360) {
                            // 获取直播间状态
                            boolean isInRoom = LiveWatchService.roomStatuses.containsKey(uperId);
                            String status = isInRoom ? " (已进入直播)" : "";
                            
                            String info = String.format("☊ %s (%d/%d)%s %s\n",
                                nickname, watchDegree, 360, status, title);
                            liveIdMap.put(uperId, liveId);
                            checkAndUpdateUI(index, info);
                        }
                    } else {
                        checkAndUpdateUI(index, "");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析粉丝牌信息失败: " + uperId, e);
                    checkAndUpdateUI(index, "");
                }
            }
        });
    }
    
    private synchronized void checkAndUpdateUI(int index, String info) {
        finalStringBuilder.append(info);
        pendingRequests--;
        
        if (pendingRequests == 0) {
            runOnUiThread(() -> {
                // 只有在显示直播列表时才更新文本
                if (!showingStats) {
                    liveListText.setText(finalStringBuilder.toString());
                }
                
                // 获取当前在线的主播ID集合
                Set<String> currentLiveUperIds = new HashSet<>(liveIdMap.keySet());
                
                // 清理不在直播列表中的直播间
                for (String uperId : new HashSet<>(LiveWatchService.roomStatuses.keySet())) {
                    if (!currentLiveUperIds.contains(uperId)) {
                        Log.d(TAG, "主播已下播或经验已满，停止观看: " + uperId);
                        Intent intent = new Intent(this, LiveWatchService.class);
                        intent.putExtra("uperId", uperId);
                        intent.putExtra("action", "stop");
                        startService(intent);
                    }
                }
                
                // 启动服务
                for (Map.Entry<String, String> entry : liveIdMap.entrySet()) {
                    String uperId = entry.getKey();
                    
                    // 检查服务是否已经在运行
                    if (LiveWatchService.roomStatuses.containsKey(uperId)) {
                        continue;  // 如果已经在运行，跳过
                    }
                    
                    WatchStatsManager.WatchData data = WatchStatsManager.getInstance(this).getAllStats().get(uperId);
                    String nickname = data != null ? data.name : uperId;
                    
                    // 记录开始观看
                    // LogUtils.logEvent("WATCH", String.format("开始观看: %s(%s)", nickname, uperId));
                    //暂时注释
                    Intent intent = new Intent(this, LiveWatchService.class);
                    intent.putExtra("uperId", uperId);
                    intent.putExtra("liveId", entry.getValue());
                    intent.putExtra("nickname", nickname);
                    startService(intent);
                }
            });
        }
    }
    
    // 判断是否是同一天
    private boolean isSameDay(long time1, long time2) {
        java.util.Calendar cal1 = java.util.Calendar.getInstance();
        cal1.setTimeInMillis(time1);
        java.util.Calendar cal2 = java.util.Calendar.getInstance();
        cal2.setTimeInMillis(time2);
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
               cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR);
    }
    
    // 添加切换视图的方法
    private void toggleStatsView() {
        showingStats = !showingStats;
        if (showingStats) {
            StringBuilder stats = new StringBuilder();
            stats.append("今日经验统计：\n\n");
            
            // 获取统计数据
            WatchStatsManager statsManager = WatchStatsManager.getInstance(this);
            Map<String, WatchStatsManager.WatchData> allStats = statsManager.getAllStats();
            
            // 转换为列表并排序
            List<Map.Entry<String, WatchStatsManager.WatchData>> sortedStats = 
                new ArrayList<>(allStats.entrySet());
            Collections.sort(sortedStats, (a, b) -> 
                b.getValue().degree - a.getValue().degree);
            
            // 建显示文本
            for (Map.Entry<String, WatchStatsManager.WatchData> entry : sortedStats) {
                WatchStatsManager.WatchData data = entry.getValue();
                stats.append(String.format("【%s】今日验值: %d/360\n",
                    data.name, data.degree));
            }
            
            liveListText.setVisibility(View.GONE);
            statsText.setVisibility(View.VISIBLE);
            statsText.setText(stats.toString());
            toggleStatsText.setText("点击返回直播间列表");
        } else {
            statsText.setVisibility(View.GONE);
            liveListText.setVisibility(View.VISIBLE);
            toggleStatsText.setText("点击查看统计信息");
        }
    }
    
    private void checkServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        boolean isServiceRunning = false;
        
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LiveWatchService.class.getName().equals(service.service.getClassName())) {
                isServiceRunning = true;
                break;
            }
        }
        
        // 传入 this 作为 Context
        if (!isServiceRunning && !LiveWatchService.wasManuallyRemoved(this)) {
            Logger.d(TAG, "服务意外停止，正在重启...");
            Intent intent = new Intent(this, LiveWatchService.class);
            intent.putExtra("action", "restart");
            try {
                startForegroundService(intent);
                LogUtils.logEvent("SERVICE", "服务意外停止重启");
            } catch (Exception e) {
                LogUtils.logEvent("ERROR", "服务重启失败: " + e.getMessage());
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Logger.d(TAG, "Activity恢复，检查服务状态");
        checkServiceRunning();
    }
    
    private void checkBackgroundPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            
            // 检查是否有后台运行权限
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // 显示对话框提示用户
                new AlertDialog.Builder(this)
                    .setTitle("需要允许后台运行")
                    .setMessage("为了保证功能正常运行，请在接下来的设置中允许应用后台运行")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        try {
                            // 跳转到应用详情页面
                            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            settingsIntent.setData(android.net.Uri.parse("package:" + packageName));
                            startActivity(settingsIntent);
                        } catch (Exception e) {
                            // 如果跳转失败，尝试跳转到通用设置页面
                            Intent generalIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                            startActivity(generalIntent);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            }
        }
    }
    
    private void checkAutoStartPermission() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autostartChecked = prefs.getBoolean(KEY_AUTOSTART_CHECKED, false);

        if (!autostartChecked) {
            String manufacturer = android.os.Build.MANUFACTURER.toLowerCase();
            try {
                Intent intent = new Intent();
                boolean shouldShowDialog = false;

                if (manufacturer.contains("xiaomi")) {
                    intent.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                    shouldShowDialog = true;
                } else if (manufacturer.contains("oppo")) {
                    intent.setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                    shouldShowDialog = true;
                } else if (manufacturer.contains("vivo")) {
                    intent.setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                    shouldShowDialog = true;
                } else if (manufacturer.contains("huawei")) {
                    intent.setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                    shouldShowDialog = true;
                }

                if (shouldShowDialog) {
                    List<ResolveInfo> activities = getPackageManager().queryIntentActivities(intent,
                        PackageManager.MATCH_DEFAULT_ONLY);
                        
                    if (activities.size() > 0) {
                        new AlertDialog.Builder(this)
                            .setTitle("需要允许自启动权限")
                            .setMessage("请在接下来的界面中允许应用自启动，以保证功能正常运行")
                            .setPositiveButton("去设置", (dialog, which) -> {
                                try {
                                    startActivity(intent);
                                    LogUtils.logEvent("PERMISSION", "用户前往设置自启动权限");
                                } catch (Exception e) {
                                    LogUtils.logEvent("ERROR", "打开自启动设置失败: " + e.getMessage());
                                    Toast.makeText(this, "无法打开自启动设置界面", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("取消", (dialog, which) -> {
                                Toast.makeText(MainActivity.this,
                                    "未设置自启动可能会影响应用在后台运行", Toast.LENGTH_LONG).show();
                                LogUtils.logEvent("PERMISSION", "用户取消设置自启动权限");
                            })
                            .setOnDismissListener(dialog -> {
                                // 无论用户选择了什么，我们都标记为已检查
                                prefs.edit().putBoolean(KEY_AUTOSTART_CHECKED, true).apply();
                            })
                            .show();
                    } else {
                        // 如果没有找到对应的设置界面，也标记为已检查
                        prefs.edit().putBoolean(KEY_AUTOSTART_CHECKED, true).apply();
                    }
                } else {
                    // 如果不是需要特殊处理的品牌，也标记为已检查
                    prefs.edit().putBoolean(KEY_AUTOSTART_CHECKED, true).apply();
                }
            } catch (Exception e) {
                Logger.e(TAG, "检查自启动权限失败", e);
                LogUtils.logEvent("ERROR", "检查自启动权限失败: " + e.getMessage());
                // 发生错误时也标记为已检查，避免反复提示
                prefs.edit().putBoolean(KEY_AUTOSTART_CHECKED, true).apply();
            }
        }
    }
    
    // 在每天开始时清理已处理集合
    private void clearProcessedUpersIfNeeded() {
        long currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000);
        if (lastClearDay != currentDay) {
            processedUperIds.clear();
            lastClearDay = currentDay;
        }
    }
    
    
    private void handleNetworkError(Exception e) {
        LogUtils.logEvent("ERROR", "网络错误: " + e.getMessage());
    }
    
    // 在 MainActivity 中添加这个方法
    private void stopWatching(String uperId) {
        Intent intent = new Intent(this, LiveWatchService.class);
        intent.putExtra("action", "stop");
        intent.putExtra("uperId", uperId);
        startService(intent);
    }
    
    // 添加通知权限检查方法
    private void checkNotificationPermission() {
        // 仅在 Android 13 及以上版本需要动态请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("为了保证服务长时间运行，请允许应用通知权限")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            1001  // 请求码
                        );
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        Toast.makeText(MainActivity.this, 
                            "未授予通知权限可能影响服务后台运行", 
                            Toast.LENGTH_LONG).show();
                    })
                    .show();
            }
        }
    }
    
    // 添加权限请求结果处理
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {  // 通知权限的请求码
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户授予了权限
                LogUtils.logEvent("PERMISSION", "通知权限已授予");
            } else {
                // 用户拒绝了权限
                LogUtils.logEvent("PERMISSION", "通知权限被拒绝");
                Toast.makeText(this, "未授予通知权限可能影响服务后台运行", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }
    
  
    // 封装电池优化检查
    private void checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                new AlertDialog.Builder(this)
                    .setTitle("需要忽略电池优化")
                    .setMessage("请在接下来的界面中允许忽略电池优化，以保证应用正常运行")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        try {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + packageName));
                            startActivity(intent);
                            LogUtils.logEvent("PERMISSION", "用户前往设置电池优化");
                        } catch (Exception e) {
                            LogUtils.logEvent("ERROR", "打开电池优化设置失败: " + e.getMessage());
                            Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        Toast.makeText(this, "未关闭电池优化可能影响应用在后台运行", Toast.LENGTH_LONG).show();
                        LogUtils.logEvent("PERMISSION", "用户取消设置电池优化");
                    })
                    .show();
            }
        }
    }
}