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
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                // 检测到登录成功的标志（cookie中包含auth_key）
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && cookies.contains("auth_key=")) {
                    // 保存cookies
                    com.example.acdemo.utils.CookieManager.saveCookies(cookies);
                    
                    // 直接跳转到主页面
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();  // 关闭登录页面
                }
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 不再需要处理URL重定向，直接返回false
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