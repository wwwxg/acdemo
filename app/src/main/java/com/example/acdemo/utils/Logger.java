package com.example.acdemo.utils;

import android.util.Log;

public class Logger {
    private static final String TAG_PREFIX = "AcDemo_";
    private static boolean DEBUG = true;  // 可以通过BuildConfig.DEBUG控制

    public static void d(String tag, String msg) {
        if (DEBUG) {
            Log.d(TAG_PREFIX + tag, msg);
        }
    }

    public static void e(String tag, String msg) {
        Log.e(TAG_PREFIX + tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(TAG_PREFIX + tag, msg, tr);
    }
} 