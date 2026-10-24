package fr.orbitalvkl.grafit;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    static final int REQ_PERMS = 7, REQ_RESTORE = 8;
    static volatile boolean resumed = false;
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
        // bords de l'écran (Android 15+, affichage bord à bord imposé) : la WebView ignore son propre
        // remplissage, elle est donc posée dans un cadre qui, lui, laisse la place de la barre d'état, de la
        // découpe de caméra et de la barre de navigation.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C0E);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
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
                    boolean ok = HOST.equals(req.getOrigin().getHost());
                    for (String r : req.getResources()) if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) ok = false;
                    if (ok && !has(Manifest.permission.RECORD_AUDIO)) {          // micro pas encore permis par Android : on le demande
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
                        ok = false;
                    }
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
        if (LoggerService.enabled(this)) LoggerService.start(this);       // journal continu (arrière-plan)
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (!loaded) load(); else { probes.stop(); probes.start(); probes.pushAll(); }
    }

    /** Commandes de la page : {cmd: perms | usage | keepOn | immersive | settings | saveLog | bglog | bgRead | netReset, on, name, text, since} */
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
                case "saveLog":
                    saveLog(o.optString("name"), o.optString("text"));
                    break;
                case "bglog":
                    LoggerService.setEnabled(this, on);
                    bgPush(-1);
                    break;
                case "bgCfg": {                                   // alertes vibrées d'arrière-plan + dernière position (pluie)
                    android.content.SharedPreferences.Editor ed = getSharedPreferences(LoggerService.PREFS, MODE_PRIVATE).edit();
                    if (o.has("vibe")) ed.putBoolean("vibe", o.optBoolean("vibe", true));
                    if (o.has("lat")) ed.putFloat("lat", (float) o.optDouble("lat")).putFloat("lon", (float) o.optDouble("lon"));
                    ed.apply();
                    bgPush(-1);
                    break;
                }
                case "widget":
                    GrafitWidget.set(this, o.optString("rain"), o.optString("event"), o.optString("extra"), o.optString("evs", "[]"));
                    break;
                case "saveFile":
                    saveFile(o.optString("name"), o.optString("text"));
                    break;
                case "exportCsv":
                    exportCsv(o.optString("name"));
                    break;
                case "restore": {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                    startActivityForResult(pick, REQ_RESTORE);
                    break;
                }
                case "netReset":
                    // le bouton « Réinitialiser Internet » est en haut de la page Internet (Android 12+ : action Wi-Fi)
                    try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
                    break;
                case "bgRead":
                    bgPush(o.optLong("since", 0));
                    break;
                case "bright": {                                  // luminosité de la fenêtre Grafit : 0,01…1, ou -1 = réglage d'Android
                    double v = o.optDouble("v", -1);
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : (float) Math.max(0.01, Math.min(1.0, v));
                    getWindow().setAttributes(lp);
                    break;
                }
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

    /** Journal exporté par la page : Téléchargements/Grafit/grafit-log-AAAAMMJJ-HHMM.txt (MediaStore, sans permission). */
    void saveLog(String name, String text) {
        JSONObject d = new JSONObject();
        try {
            if (!name.matches("grafit-log-[0-9]{8}-[0-9]{4}\\.txt")) throw new IllegalArgumentException("BAD NAME");
            write(name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
            d.put("logSaved", "DOWNLOAD/GRAFIT/" + name);
        } catch (Exception e) {
            try { d.put("logError", String.valueOf(e.getMessage())); } catch (Exception x) { /* */ }
        }
        push(d);
    }

    /** Sauvegarde / export de la page : grafit-(backup|health|samples)-AAAAMMJJ-HHMM.(json|csv) dans Téléchargements/Grafit. */
    void saveFile(String name, String text) {
        JSONObject d = new JSONObject();
        try {
            if (!name.matches("grafit-(backup|health|samples)-[0-9]{8}-[0-9]{4}\\.(json|csv)")) throw new IllegalArgumentException("BAD NAME");
            write(name, name.endsWith(".json") ? "application/json" : "text/csv", text.getBytes(StandardCharsets.UTF_8));
            d.put("fileSaved", "DOWNLOAD/GRAFIT/" + name);
        } catch (Exception e) {
            try { d.put("fileError", String.valueOf(e.getMessage())); } catch (Exception x) { /* */ }
        }
        push(d);
    }

    /** Relevés du journal continu (une semaine) en CSV lisible : heure locale + colonnes nommées. */
    void exportCsv(String name) {
        new Thread(() -> {
            StringBuilder b = new StringBuilder("time,epoch_ms,pressure_station_hpa,battery_pct,charging,battery_c,battery_health,thermal_status,steps_counter,cell_dbm,rtt_ms,network,movement_g_rms,movement_peak_g\n");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
            org.json.JSONArray rows = LoggerService.readSince(this, 0, 20000);
            for (int i = 0; i < rows.length(); i++) {
                org.json.JSONArray r = rows.optJSONArray(i);
                if (r == null || !"S".equals(r.optString(1))) continue;
                b.append(f.format(new java.util.Date(r.optLong(0)))).append(',').append(r.optLong(0));
                for (int k = 2; k < 14; k++) b.append(',').append(r.isNull(k) ? "" : r.opt(k));
                b.append('\n');
            }
            saveFile(name, b.toString());
        }).start();
    }

    void write(String name, String mime, byte[] data) throws Exception {
        {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grafit");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IllegalStateException("MEDIASTORE REFUSED");
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                if (os == null) throw new IllegalStateException("NO STREAM");
                os.write(data);
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_RESTORE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        new Thread(() -> {
            JSONObject d = new JSONObject();
            try (java.io.InputStream is = getContentResolver().openInputStream(u)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
                while (is != null && (n = is.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > 16_000_000) throw new IllegalStateException("FILE TOO LARGE"); }
                d.put("restore", bo.toString("UTF-8"));
            } catch (Exception e) { try { d.put("fileError", String.valueOf(e.getMessage())); } catch (Exception x) { /* */ } }
            push(d);
        }).start();
    }

    /** Journal continu vers la page : état, et lignes postérieures à since (since < 0 : état seul). Lecture hors du fil principal. */
    void bgPush(long since) {
        new Thread(() -> {
            try {
                JSONObject b = new JSONObject();
                b.put("on", LoggerService.enabled(this));
                b.put("stat", LoggerService.stats(this));
                if (since >= 0) b.put("rows", LoggerService.readSince(this, since, 15000));
                JSONObject d = new JSONObject(); d.put("bglog", b);
                push(d);
            } catch (Exception e) { /* */ }
        }).start();
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
        resumed = true;
        web.onResume();
        if (loaded) {
            probes.start(); probes.pushAll();
            try { JSONObject d = new JSONObject(); d.put("resumed", true); push(d); } catch (Exception e) { /* */ }
            if (pageReady) autoCheck();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
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
