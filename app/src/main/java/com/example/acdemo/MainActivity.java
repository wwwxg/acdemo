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

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AcDemo_MainActivity";
    private TextView userInfoText;
    private TextView liveListText;
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
                
                try {
                    JSONObject jsonObject = new JSONObject(json);
                    JSONObject channelListData = jsonObject.getJSONObject("channelListData");
                    if (channelListData.getInt("result") == 0) {
                        JSONArray liveList = channelListData.getJSONArray("liveList");
                        
                        finalStringBuilder = new StringBuilder();
                        currentTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                .format(new java.util.Date());
                        finalStringBuilder.append("更新时间: ").append(currentTime).append("\n\n");
                        finalStringBuilder.append("当前正在直播且粉丝牌未满的主播：\n\n");
                        
                        pendingRequests = 0;
                        liveIdMap.clear(); // 清空之前的映射
                        
                        for (int i = 0; i < liveList.length(); i++) {
                            JSONObject live = liveList.getJSONObject(i);
                            JSONObject user = live.getJSONObject("user");
                            String userId = user.getString("id");
                            
                            if (medalUperIds.contains(userId)) {
                                pendingRequests++;
                                String nickname = user.getString("name");
                                String title = live.getString("title");
                                String liveId = live.getString("liveId");
                                checkMedalAndUpdateUI(userId, nickname, title, liveId, pendingRequests);
                            }
                        }
                        
                        if (pendingRequests == 0) {
                            finalStringBuilder.append("没有需要挂机的直播间\n");
                            runOnUiThread(() -> liveListText.setText(finalStringBuilder.toString()));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析直播列表失败", e);
                }
            }
        });
    }
    
    private void checkMedalAndUpdateUI(String uperId, String nickname, String title, String liveId, int index) {
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
                        
                        if (watchDegree < watchLimit) {
                            // 获取直播间状态
                            boolean isInRoom = LiveWatchService.roomStatuses.containsKey(uperId);
                            String status = isInRoom ? " (已进入直播间)" : "";
                            
                            String info = String.format("%d. %s (%d/%d)%s %s\n",
                                index, nickname, watchDegree, watchLimit, status, title);
                            liveIdMap.put(uperId, liveId);
                            checkAndUpdateUI(index, info);
                        } else {
                            // 经验已满，停止挂机
                            Intent intent = new Intent(MainActivity.this, LiveWatchService.class);
                            intent.putExtra("uperId", uperId);
                            intent.putExtra("action", "stop");
                            startService(intent);
                            checkAndUpdateUI(index, "");
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
    }
    
    private synchronized void checkAndUpdateUI(int index, String info) {
        finalStringBuilder.append(info);
        pendingRequests--;
        
        if (pendingRequests == 0) {
            runOnUiThread(() -> {
                liveListText.setText(finalStringBuilder.toString());
                
                // 在UI线程中启动服务，移除检查
                for (Map.Entry<String, String> entry : liveIdMap.entrySet()) {
                    Intent intent = new Intent(this, LiveWatchService.class);
                    intent.putExtra("uperId", entry.getKey());
                    intent.putExtra("liveId", entry.getValue());
                    startService(intent);
                }
            });
        }
    }
}