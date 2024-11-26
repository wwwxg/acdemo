package com.example.acdemo;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {
    private WebView webView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        
        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        
        // 设置PC版的User-Agent
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
        webView.getSettings().setUserAgentString(userAgent);
        
        webView.setWebViewClient(new WebViewClient() {
            private boolean hasLoggedIn = false;  // 添加标志位避免重复跳转
            
            @Override
            public void onLoadResource(WebView view, String url) {
                // 在资源加载过程中就检查登录状态
                if (!hasLoggedIn) {
                    String cookies = CookieManager.getInstance().getCookie("https://www.acfun.cn");
                    if (cookies != null && cookies.contains("auth_key=")) {
                        hasLoggedIn = true;
                        // 保存cookies
                        com.example.acdemo.utils.CookieManager.saveCookies(cookies);
                        
                        // 立即跳转到主页面
                        runOnUiThread(() -> {
                            startActivity(new Intent(LoginActivity.this, MainActivity.class));
                            finish();  // 关闭登录页面
                        });
                    }
                }
            }
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成时再次检查，以防万一
                if (!hasLoggedIn) {
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null && cookies.contains("auth_key=")) {
                        hasLoggedIn = true;
                        com.example.acdemo.utils.CookieManager.saveCookies(cookies);
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    }
                }
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        
        // 使用PC版登录页面
        webView.loadUrl("https://www.acfun.cn/login");
    }
    
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
} 