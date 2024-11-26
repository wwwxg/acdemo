package com.example.acdemo.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Calendar;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.Iterator;

public class WatchStatsManager {
    private static final String PREF_NAME = "watch_stats";
    private static final String KEY_DATE = "date";
    private static final String KEY_STATS = "stats";
    private static WatchStatsManager instance;
    private SharedPreferences prefs;
    private Map<String, WatchData> watchDataMap = new HashMap<>();

    public static class WatchData {
        public String name;
        public int degree;
        
        public WatchData(String name, int degree) {
            this.name = name;
            this.degree = degree;
        }
    }

    private WatchStatsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadData();
    }

    public static synchronized WatchStatsManager getInstance(Context context) {
        if (instance == null) {
            instance = new WatchStatsManager(context.getApplicationContext());
        }
        return instance;
    }

    private void loadData() {
        String savedDate = prefs.getString(KEY_DATE, "");
        String today = getCurrentDate();
        
        if (!today.equals(savedDate)) {
            watchDataMap.clear();
            prefs.edit()
                .putString(KEY_DATE, today)
                .putString(KEY_STATS, "{}")
                .apply();
            return;
        }

        try {
            String statsJson = prefs.getString(KEY_STATS, "{}");
            JSONObject stats = new JSONObject(statsJson);
            watchDataMap.clear();
            
            Iterator<String> keys = stats.keys();
            while(keys.hasNext()) {
                String uperId = keys.next();
                JSONObject data = stats.getJSONObject(uperId);
                watchDataMap.put(uperId, new WatchData(
                    data.getString("name"),
                    data.getInt("degree")
                ));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void updateWatchDegree(String uperId, String name, int degree) {
        watchDataMap.put(uperId, new WatchData(name, degree));
        saveData();
    }

    public Map<String, WatchData> getAllStats() {
        return new HashMap<>(watchDataMap);
    }

    private void saveData() {
        try {
            JSONObject stats = new JSONObject();
            for (Map.Entry<String, WatchData> entry : watchDataMap.entrySet()) {
                JSONObject data = new JSONObject();
                data.put("name", entry.getValue().name);
                data.put("degree", entry.getValue().degree);
                stats.put(entry.getKey(), data);
            }
            
            prefs.edit()
                .putString(KEY_STATS, stats.toString())
                .apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private String getCurrentDate() {
        Calendar cal = Calendar.getInstance();
        return String.format("%d-%02d-%02d", 
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        );
    }
} 