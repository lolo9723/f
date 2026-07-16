package tr.edu.balikesir.anketrapor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final int FILE_REQUEST = 41;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), FILE_REQUEST); return true; }
                catch (Exception e) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                    try { startActivityForResult(i, FILE_REQUEST); return true; }
                    catch (Exception ignored) { fileCallback = null; return false; }
                }
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface public void printPage() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                pm.print("Anket Raporu", webView.createPrintDocumentAdapter("Anket Raporu"), new PrintAttributes.Builder().build());
            });
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
