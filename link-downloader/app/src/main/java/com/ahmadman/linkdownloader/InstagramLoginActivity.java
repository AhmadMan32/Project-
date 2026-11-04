package com.ahmadman.linkdownloader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.linkdownloader.core.Cookies;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Log in on instagram.com inside the app; the session cookies are stored only on this phone
 * and passed to yt-dlp for Instagram links (many reels are only served to logged-in users).
 */
public class InstagramLoginActivity extends Activity {

    private static final String URL = "https://www.instagram.com/accounts/login/";
    private static final String COOKIE_URL = "https://www.instagram.com";

    private WebView web;
    private boolean saved;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        Ui.applyInsets(findViewById(R.id.root));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        TextView status = findViewById(R.id.status);
        web = findViewById(R.id.web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Some sites refuse logins from embedded browsers ("; wv" marker): look like Chrome.
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")"));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                checkLogin(status);
            }
        });
        web.loadUrl(URL);
    }

    private void checkLogin(TextView status) {
        if (saved) {
            return;
        }
        String header = CookieManager.getInstance().getCookie(COOKIE_URL);
        if (!Cookies.hasInstagramSession(header)) {
            status.setText(R.string.login_hint);
            return;
        }
        CookieManager.getInstance().flush();
        long expires = System.currentTimeMillis() / 1000 + 365L * 24 * 3600;
        File f = Engine.instagramCookies(this);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(Cookies.netscape("instagram.com", header, expires).getBytes(StandardCharsets.UTF_8));
            saved = true;
            Toast.makeText(this, R.string.login_saved, Toast.LENGTH_LONG).show();
            setResult(RESULT_OK);
            finish();
        } catch (IOException e) {
            status.setText(getString(R.string.login_save_failed, e.getMessage()));
            status.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /** Removes the saved session from the app and the embedded browser. */
    static void logout(Activity a) {
        //noinspection ResultOfMethodCallIgnored
        Engine.instagramCookies(a).delete();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    static boolean isLoggedIn(Activity a) {
        File f = Engine.instagramCookies(a);
        return f.isFile() && f.length() > 40;
    }
}
