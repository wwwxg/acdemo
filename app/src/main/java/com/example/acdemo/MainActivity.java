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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AcDemo_MainActivity";
    private TextView userInfoText;
    private TextView liveListText;
    private TextView runtimeText;
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
    
    private static String startTime = null;  // 启动时间
    private Handler runtimeHandler = new Handler();
    private static long startTimeMillis; // 记录启动的时间戳
    
    private Map<String, Long> experienceFullUperIds = new HashMap<>(); // 记录经验值已满的主播ID和时间
    
    private TextView statsText;
    private TextView toggleStatsText;
    private boolean showingStats = false;
    
    public static Map<String, String> uperNames = new HashMap<>();  // 新增: 存储主播ID和名字的映射
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 请求忽略电池优化
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }
        
        setContentView(R.layout.activity_main);
        
        userInfoText = findViewById(R.id.userInfoText);
        liveListText = findViewById(R.id.liveListText);
        runtimeText = findViewById(R.id.runtimeText);
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
        
        // 如果是第一次启动，记录启动时间
        if (startTime == null) {
            startTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            startTimeMillis = System.currentTimeMillis();
        }
        
        // 每秒更新运行时间
        runtimeHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateRuntime();
                runtimeHandler.postDelayed(this, 1000);
            }
        }, 1000);
        
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
        refreshHandler.removeCallbacksAndMessages(null);
        runtimeHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
                Log.d(TAG, "用户信息响应: " + json);
                
                try {
                    JSONObject result = new JSONObject(json);
                    if (result.getInt("result") == 0) {
                        JSONObject info = result.getJSONObject("info");
                        String userName = info.getString("userName");
                        String userId = info.getString("userId");
                        
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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Referer", "https://www.acfun.cn")
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
                Log.d(TAG, "粉丝牌列表响应: " + json);
                
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
        String cookies = CookieManager.getCookies();
        Log.d(TAG, "Using cookies for live list: " + cookies);
        
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
                Log.d(TAG, "直播列表响应: " + json);
                
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
                            // 同一天内已满经验，跳过处理
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
                
                // 如果没有需要处理的主播，直接更新UI
                if (pendingRequests == 0) {
                    runOnUiThread(() -> {
                        if (!showingStats) {
                            finalStringBuilder.append("当前没有正在直播的已关注主播");
                            liveListText.setText(finalStringBuilder.toString());
                        }
                    });
                }
                
                // 对于不在线的主播，保留最后一次的统计数据
                List<WatchStats> allStats = StatsManager.getInstance().getTodayStats();
                for (WatchStats stats : allStats) {
                    // 只有经验未满且不在直播列表的主播才显示"已下播"
                    if (!currentLiveUpers.contains(stats.getUperId()) 
                        && stats.getWatchDegree() < 360) {
                        Log.d(TAG, String.format("主播 %s 已下播，保留最后统计数据: %d", 
                            stats.getUperName(), stats.getWatchDegree()));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析直播列表失败", e);
            runOnUiThread(() -> {
                if (!showingStats) {
                    liveListText.setText("获取直播列表失败，请检查网络连接\n" + e.getMessage());
                }
            });
        }
    }
    
    private void handleMedalInfo(String uperId, String nickname, String title, String liveId, int index) {
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
                Log.d(TAG, "粉丝牌信息响应: " + json + " for uperId: " + uperId);
                
                try {
                    JSONObject jsonObject = new JSONObject(json);
                    if (jsonObject.getInt("result") == 0) {
                        JSONObject medalInfo = jsonObject.getJSONObject("medalDegreeLimit");
                        int watchDegree = medalInfo.getInt("liveWatchDegree");
                        int watchLimit = medalInfo.getInt("liveWatchDegreeLimit");
                        
                        // 更新统计信息
                        StatsManager.getInstance().updateWatchStats(uperId, nickname, watchDegree);
                        
                        if (watchDegree >= watchLimit) {
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
                        
                        if (watchDegree < watchLimit) {
                            // 获取直播间状态
                            boolean isInRoom = LiveWatchService.roomStatuses.containsKey(uperId);
                            String status = isInRoom ? " (已进入直播间)" : "";
                            
                            String info = String.format("☊ %s (%d/%d)%s %s\n",
                                nickname, watchDegree, watchLimit, status, title);
                            liveIdMap.put(uperId, liveId);
                            checkAndUpdateUI(index, info);
                        }
                    } else {
                        checkAndUpdateUI(index, "");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析粉丝牌信息失败: " + uperId + ", json: " + json, e);
                    checkAndUpdateUI(index, "");
                }
            }
        });
        
        uperNames.put(uperId, nickname);  // 保存主播名字
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
                    Intent intent = new Intent(this, LiveWatchService.class);
                    intent.putExtra("uperId", entry.getKey());
                    intent.putExtra("liveId", entry.getValue());
                    startService(intent);
                }
            });
        }
    }
    
    private void updateRuntime() {
        long currentTime = System.currentTimeMillis();
        long diffTime = currentTime - startTimeMillis;
        
        // 转换为时分秒格式
        long seconds = diffTime / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds = seconds % 60;
        minutes = minutes % 60;
        
        String runtime = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        
        // 更新UI，把两个时间放在同一行
        runOnUiThread(() -> {
            runtimeText.setText(String.format("开始时间: %s    已运行: %s", 
                              startTime, runtime));
        });
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
            // 显示统计信息
            StringBuilder stats = new StringBuilder();
            stats.append("今日经验统计：\n\n");
            for (WatchStats watchStats : StatsManager.getInstance().getTodayStats()) {
                stats.append(watchStats.getFormattedStats()).append("\n");
            }
            liveListText.setVisibility(View.GONE);
            statsText.setVisibility(View.VISIBLE);
            statsText.setText(stats.toString());
            toggleStatsText.setText("点击返回直播间列表");
        } else {
            // 显示直播间列表
            liveListText.setVisibility(View.VISIBLE);
            statsText.setVisibility(View.GONE);
            toggleStatsText.setText("点击查看今日小计");
        }
    }
}