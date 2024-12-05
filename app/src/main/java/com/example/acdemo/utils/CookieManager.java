package com.example.acdemo.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.IOException;

public class CookieManager {
    private static final String TAG = "AcDemo_CookieManager";
    private static final String PREF_NAME = "acfun_cookies";
    private static final String KEY_COOKIES = "cookies";
    private static final String KEY_LAST_CHECK_TIME = "last_check_time";
    private static final long CHECK_INTERVAL = 7 * 24 * 60 * 60 * 1000L; // 一周检查一次
    private static SharedPreferences prefs;
    private static Context appContext;
    private static OkHttpClient client;
    
    public static final String ACTION_COOKIE_INVALID = "com.example.acdemo.COOKIE_INVALID";
    private static final String USER_INFO_API = "https://www.acfun.cn/rest/pc-direct/user/personalInfo";
    
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        client = new OkHttpClient.Builder().build();
    }
    
    public static void saveCookies(String cookies) {
        if (prefs != null) {
            prefs.edit()
                .putString(KEY_COOKIES, cookies)
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply();
            LogUtils.logEvent("COOKIE", "Cookie已更新");
        }
    }
    
    public static String getCookies() {
        if (shouldCheckCookie()) {
            validateCookie();
        }
        return prefs != null ? prefs.getString(KEY_COOKIES, null) : null;
    }
    
    public static boolean hasCookies() {
        String cookies = getCookies();
        return cookies != null && cookies.contains("acPasstoken") && cookies.contains("auth_key");
    }
    
    public static void clearCookies() {
        if (prefs != null) {
            prefs.edit()
                .remove(KEY_COOKIES)
                .remove(KEY_LAST_CHECK_TIME)
                .apply();
            LogUtils.logEvent("COOKIE", "Cookie已清除");
        }
    }
    
    private static boolean shouldCheckCookie() {
        long lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0);
        return System.currentTimeMillis() - lastCheckTime > CHECK_INTERVAL;
    }
    
    private static void validateCookie() {
        String cookies = prefs.getString(KEY_COOKIES, null);
        if (cookies == null) {
            notifyCookieInvalid();
            return;
        }

        Request request = new Request.Builder()
            .url(USER_INFO_API)
            .header("Cookie", cookies)
            .build();

        try {
            Response response = client.newCall(request).execute();
            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            
            if (response.code() != 200 || json.optInt("result") != 0) {
                LogUtils.logEvent("COOKIE", "Cookie验证失败: " + response.code());
                notifyCookieInvalid();
                return;
            }
            
            // 更新最后检查时间
            prefs.edit()
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply();
                
        } catch (Exception e) {
            Log.e(TAG, "验证Cookie失败", e);
            LogUtils.logEvent("ERROR", "验证Cookie时发生错误: " + e.getMessage());
        }
    }
    
    private static void notifyCookieInvalid() {
        clearCookies();
        Intent intent = new Intent(ACTION_COOKIE_INVALID);
        intent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
        LogUtils.logEvent("COOKIE", "Cookie已失效，发送广播");
    }
    
    public static boolean isResponseUnauthorized(Response response) {
        return response.code() == 401 || response.code() == 403;
    }
    
    public static boolean handleResponseUnauthorized(Response response) {
        if (isResponseUnauthorized(response)) {
            notifyCookieInvalid();
            return true;
        }
        return false;
    }
}