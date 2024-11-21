package com.example.acdemo.model;

public class LiveRoom {
    private String uperId;
    private String name;
    private String medalInfo;
    private int watchDegree;
    private int watchLimit;
    private boolean isLiving;
    
    // getters and setters
    public String getUperId() {
        return uperId;
    }

    public void setUperId(String uperId) {
        this.uperId = uperId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMedalInfo() {
        return medalInfo;
    }

    public void setMedalInfo(String medalInfo) {
        this.medalInfo = medalInfo;
    }

    public int getWatchDegree() {
        return watchDegree;
    }

    public void setWatchDegree(int watchDegree) {
        this.watchDegree = watchDegree;
    }

    public int getWatchLimit() {
        return watchLimit;
    }

    public void setWatchLimit(int watchLimit) {
        this.watchLimit = watchLimit;
    }

    public boolean isLiving() {
        return isLiving;
    }

    public void setLiving(boolean living) {
        isLiving = living;
    }
} 