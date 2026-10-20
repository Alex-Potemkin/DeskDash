package com.deskdash;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Sign-in page of Home Assistant or Yandex in a WebView. The page redirects to an address we
 * never load: we catch it and take the code (HA) or the token (Yandex) out of it.
 */
public class LoginActivity extends Activity {
    static final String EXTRA_MODE = "mode";

    private Prefs prefs;
    private boolean ha, done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        ha = "ha".equals(getIntent().getStringExtra(EXTRA_MODE));
        setTitle(ha ? "Вход в Home Assistant" : "Вход в Яндекс");

        WebView web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return check(r.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (check(url)) v.stopLoading();
            }
        });
        setContentView(web);
        web.loadUrl(ha ? HomeAssistant.authorizeUrl(prefs.haUrl()) : YandexHome.authorizeUrl(prefs.yaClientId()));
    }

    private boolean check(String url) {
        if (done) return true;
        if (ha && url.startsWith(HomeAssistant.REDIRECT)) {
            done = true;
            String code = Uri.parse(url).getQueryParameter("code");
            if (code == null) {
                finishWith("Home Assistant не пустил: " + url);
                return true;
            }
            new Thread(() -> {
                String msg;
                try {
                    HomeAssistant.exchangeCode(prefs, code);
                    msg = "Home Assistant подключён";
                } catch (Exception e) {
                    msg = "Не вышло: " + SmartHome.msg(e);
                }
                String m = msg;
                runOnUiThread(() -> finishWith(m));
            }).start();
            return true;
        }
        if (!ha && url.contains("access_token=")) {
            String frag = url.substring(url.indexOf('#') + 1);
            String token = Uri.parse("x://x?" + frag).getQueryParameter("access_token");
            if (token != null && !token.isEmpty()) {
                done = true;
                prefs.setYaToken(token);
                finishWith("Яндекс подключён");
                return true;
            }
        }
        if (!ha && url.startsWith("https://oauth.yandex.ru/verification_code") && url.contains("error=")) {
            String err = Uri.parse(url.replace('#', '&')).getQueryParameter("error_description");
            if (err != null) {
                done = true;
                finishWith("Яндекс: " + err);
                return true;
            }
        }
        return false;
    }

    private void finishWith(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }
}
