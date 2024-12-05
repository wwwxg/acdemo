package com.example.acdemo.manager;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import android.util.Log;
import com.example.acdemo.model.WatchStats;
import com.example.acdemo.utils.LogUtils;
import java.util.Calendar;

public class StatsManager {
    private static final String TAG = "AcDemo_StatsManager";
    private static StatsManager instance;
    private Map<String, WatchStats> watchStatsMap = new HashMap<>();
    private long lastUpdateDay = 0;  // 用于判断是否需要重置统计
    
    // 添加重置回调
    public interface ResetCallback {
        void onStatsReset();
    }
    private ResetCallback resetCallback;

    public void setResetCallback(ResetCallback callback) {
        this.resetCallback = callback;
    }

    public synchronized void checkAndResetStats() {
        Calendar now = Calendar.getInstance();
        now.set(Calendar.HOUR_OF_DAY, 0);
        now.set(Calendar.MINUTE, 0);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        long todayStart = now.getTimeInMillis();
        
        if (lastUpdateDay < todayStart) {
            LogUtils.logEvent("STATS", "重置每日统计数据");
            watchStatsMap.clear();
            lastUpdateDay = todayStart;
            if (resetCallback != null) {
                resetCallback.onStatsReset();
            }
        }
    }
    
    private StatsManager() {}
    
    public static synchronized StatsManager getInstance() {
        if (instance == null) {
            instance = new StatsManager();
        }
        return instance;
    }
    
    public synchronized void updateWatchStats(String uperId, String uperName, int watchDegree) {
        checkAndResetStats();
        
        WatchStats stats = watchStatsMap.get(uperId);
        if (stats == null) {
            stats = new WatchStats(uperId, uperName, watchDegree);
            watchStatsMap.put(uperId, stats);
        } else {
            stats.updateWatchDegree(watchDegree);
        }
    }
    
    public synchronized List<WatchStats> getTodayStats() {
        List<WatchStats> sortedStats = new ArrayList<>(watchStatsMap.values());
        Collections.sort(sortedStats);  // 使用 WatchStats 的 compareTo 方法排序
        return sortedStats;
    }
} 