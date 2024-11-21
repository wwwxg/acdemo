package com.example.acdemo.model;

public class WatchStats implements Comparable<WatchStats> {
    private String uperId;
    private String uperName;
    private int watchDegree;
    private long lastUpdateTime;

    public WatchStats(String uperId, String uperName, int watchDegree) {
        this.uperId = uperId;
        this.uperName = uperName;
        this.watchDegree = watchDegree;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public void updateWatchDegree(int newWatchDegree) {
        this.watchDegree = newWatchDegree;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public String getFormattedStats() {
        return String.format("【%s】经验值: %d/360", uperName, watchDegree);
    }

    public String getUperId() {
        return uperId;
    }

    public String getUperName() {
        return uperName;
    }

    public int getWatchDegree() {
        return watchDegree;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    @Override
    public int compareTo(WatchStats other) {
        return Integer.compare(other.watchDegree, this.watchDegree);
    }
} 