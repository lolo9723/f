package tr.edu.balikesir.anketrapor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }

        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setBackgroundColor(0xFFF7D998);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 17);
        }
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface public void saveVideo(String dataUrl, String fileName) {
            runOnUiThread(() -> {
                try {
                    int comma = dataUrl.indexOf(',');
                    String payload = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
                    byte[] bytes = Base64.decode(payload, Base64.DEFAULT);
                    String safeName = (fileName == null || fileName.isEmpty()) ? "basma-yanarsin.webm" : fileName;
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "video/webm");
                    if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("Dosya konumu oluşturulamadı");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IllegalStateException("Dosya açılamadı");
                        out.write(bytes);
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                    Toast.makeText(MainActivity.this, "Video İndirilenler klasörüne kaydedildi", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Video kaydedilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void showMessage(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }
}
