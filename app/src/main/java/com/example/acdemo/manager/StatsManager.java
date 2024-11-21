package com.example.acdemo.manager;

import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import android.util.Log;
import com.example.acdemo.model.WatchStats;

public class StatsManager {
    private static final String TAG = "AcDemo_StatsManager";
    private static StatsManager instance;
    private Map<String, WatchStats> watchStatsMap = new HashMap<>();
    private long lastUpdateDay = 0;  // 用于判断是否需要重置统计
    
    private StatsManager() {}
    
    public static synchronized StatsManager getInstance() {
        if (instance == null) {
            instance = new StatsManager();
        }
        return instance;
    }
    
    public synchronized void updateWatchStats(String uperId, String uperName, int watchDegree) {
        // 检查是否需要重置统计
        long currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000);
        if (lastUpdateDay != currentDay) {
            watchStatsMap.clear();
            lastUpdateDay = currentDay;
        }
        
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