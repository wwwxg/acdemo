package com.example.acdemo.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class CookieManager {
    private static final String PREF_NAME = "acfun_cookies";
    private static final String KEY_COOKIES = "cookies";
    private static SharedPreferences prefs;
    
    public static void init(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public static void saveCookies(String cookies) {
        if (prefs != null) {
            prefs.edit().putString(KEY_COOKIES, cookies).apply();
        }
    }
    
    public static String getCookies() {
        return prefs != null ? prefs.getString(KEY_COOKIES, null) : null;
    }
    
    public static boolean hasCookies() {
        String cookies = getCookies();
        return cookies != null && cookies.contains("acPasstoken");
    }
    
    public static void clearCookies() {
        if (prefs != null) {
            prefs.edit().remove(KEY_COOKIES).apply();
        }
    }
} 