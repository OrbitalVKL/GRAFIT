package fr.orbitalvkl.grafit;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Grafit Mobile : la page Grafit (embarquée dans l'APK, servie en https://appassets.androidplatform.net)
 * dans une WebView, et le pont « grafit-native » vers les capteurs qu'un navigateur ne donne pas.
 * Sécurité : seules les pages embarquées sont chargées (tout autre lien s'ouvre dans le navigateur) et
 * le canal de commandes n'accepte que l'origine locale. Rien ne tourne quand l'application est fermée.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    static final String START = ORIGIN + "/assets/www/index.html";
    static final int REQ_PERMS = 7;
    static final String[] PERMS = {
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CALENDAR, Manifest.permission.ACTIVITY_RECOGNITION,
    };

    WebView web;
    Probes probes;
    Updater updater;
    long updT = 0;
    boolean pageReady = false, loaded = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0C0E);
        setContentView(web);
        // bords de l'écran (Android 15+) : la page reste dans la zone sûre, barres système sur fond Grafit
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            Insets b = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsets.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " GrafitAPK/" + version());

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* aucun navigateur */ }
                return true;
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (probes != null) probes.pushAll();
                updater.report();
                autoCheck();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, ORIGIN.equals(trim(origin)) && has(Manifest.permission.ACCESS_FINE_LOCATION), false);
            }
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean ok = HOST.equals(req.getOrigin().getHost()) && has(Manifest.permission.RECORD_AUDIO);
                    for (String r : req.getResources()) if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) ok = false;
                    if (ok) req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); else req.deny();
                });
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "GrafitAndroid", Collections.singleton(ORIGIN),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && HOST.equals(sourceOrigin.getHost())) onCommand(message.getData());
                });
        }
        probes = new Probes(this);
        updater = new Updater(this);
        IntentFilter f = new IntentFilter(Updater.ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updater.receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updater.receiver, f);
        if (missing().isEmpty()) load(); else requestPermissions(missing().toArray(new String[0]), REQ_PERMS);
    }

    static String trim(String o) { return o != null && o.endsWith("/") ? o.substring(0, o.length() - 1) : o; }

    String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    List<String> missing() {
        List<String> m = new ArrayList<>();
        for (String p : PERMS) if (!has(p)) m.add(p);
        return m;
    }

    void load() {
        if (loaded) return;
        loaded = true;
        web.loadUrl(START);
        probes.start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (!loaded) load(); else { probes.stop(); probes.start(); probes.pushAll(); }
    }

    /** Commandes de la page : {cmd: perms | usage | keepOn | immersive | settings, on} */
    void onCommand(String data) {
        try {
            JSONObject o = new JSONObject(data);
            String cmd = o.optString("cmd");
            boolean on = o.optBoolean("on");
            switch (cmd) {
                case "perms":
                    if (!missing().isEmpty()) requestPermissions(missing().toArray(new String[0]), REQ_PERMS);
                    else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    break;
                case "usage":
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    break;
                case "settings":
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    break;
                case "checkUpdate":
                    updT = System.currentTimeMillis(); updater.check();
                    break;
                case "update":
                    updater.install();
                    break;
                case "keepOn":
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                case "immersive": {
                    WindowInsetsController c = getWindow().getInsetsController();
                    if (c != null) {
                        if (on) {
                            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            c.hide(WindowInsets.Type.systemBars());
                        } else c.show(WindowInsets.Type.systemBars());
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Exception e) { /* commande illisible : ignorée */ }
    }

    /** Envoi à la page : window.dispatchEvent(new CustomEvent('grafit-native', {detail})) */
    void push(JSONObject detail) {
        if (!pageReady || detail.length() == 0) return;
        String js = "window.dispatchEvent(new CustomEvent('grafit-native',{detail:" + detail + "}))";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (loaded) { probes.start(); probes.pushAll(); if (pageReady) autoCheck(); }
    }

    @Override
    protected void onPause() {
        probes.stop();
        web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    /** Recherche de mise à jour : au lancement, puis au retour au premier plan toutes les 6 h au plus. */
    void autoCheck() {
        if (System.currentTimeMillis() - updT < 6 * 3600 * 1000L) return;
        updT = System.currentTimeMillis();
        updater.check();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(updater.receiver); } catch (Exception e) { /* */ }
        probes.stop();
        web.destroy();
        super.onDestroy();
    }
}
