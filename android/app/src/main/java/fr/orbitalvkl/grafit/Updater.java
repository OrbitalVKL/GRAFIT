package fr.orbitalvkl.grafit;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour depuis l'application : dernière release de OrbitalVKL/GRAFIT (API GitHub, HTTPS), téléchargement
 * de grafit.apk dans le cache de l'app, installation par PackageInstaller. Android refuse toute mise à jour
 * qui ne porte pas la même signature que l'application installée ; l'utilisateur confirme l'installation.
 * Envoie à la page : update {current, latest, available, state: idle|checking|downloading|installing|error, progress, msg}
 */
class Updater {
    static final String API = "https://api.github.com/repos/OrbitalVKL/GRAFIT/releases/latest";
    static final String ACTION = "fr.orbitalvkl.grafit.INSTALL_STATUS";
    final MainActivity act;
    String latest = null, url = null, state = "idle", msg = "";
    long size = 0;
    int progress = 0;
    boolean busy = false;

    Updater(MainActivity a) { act = a; }

    String current() { return act.version(); }

    static int[] parse(String v) {
        String[] p = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] r = new int[3];
        for (int i = 0; i < 3 && i < p.length; i++) try { r[i] = Integer.parseInt(p[i]); } catch (Exception e) { r[i] = 0; }
        return r;
    }

    static boolean newer(String a, String b) {            // a > b ?
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    boolean available() { return latest != null && url != null && newer(latest, current()); }

    void report() {
        try {
            JSONObject u = new JSONObject();
            u.put("current", current());
            if (latest != null) u.put("latest", latest);
            u.put("available", available());
            u.put("state", state);
            u.put("progress", progress);
            if (size > 0) u.put("size", size);
            if (!msg.isEmpty()) u.put("msg", msg);
            JSONObject d = new JSONObject();
            d.put("update", u);
            act.push(d);
        } catch (Exception e) { /* */ }
    }

    static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "GrafitAPK");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    void check() {
        if (busy) return;
        busy = true; state = "checking"; msg = ""; report();
        new Thread(() -> {
            try {
                HttpURLConnection c = open(API);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                }
                JSONObject r = new JSONObject(sb.toString());
                String tag = r.optString("tag_name", "");
                JSONArray as = r.optJSONArray("assets");
                String u = null; long sz = 0;
                if (as != null) for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if ("grafit.apk".equals(a.optString("name"))) { u = a.optString("browser_download_url"); sz = a.optLong("size"); }
                }
                // seule source admise : les releases du dépôt Grafit
                if (u != null && !u.startsWith("https://github.com/OrbitalVKL/GRAFIT/releases/download/")) u = null;
                latest = tag.startsWith("apk-v") ? tag.substring(5) : tag;
                url = u; size = sz; state = "idle";
                msg = available() ? "UPDATE " + latest + " AVAILABLE" : "UP TO DATE";
            } catch (Exception e) {
                state = "error"; msg = "CHECK FAILED · " + e.getMessage();
            }
            busy = false;
            report();
        }).start();
    }

    void install() {
        if (busy) return;
        if (!available()) { check(); return; }
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            state = "error"; msg = "ALLOW « INSTALL UNKNOWN APPS » FOR GRAFIT, THEN UPDATE AGAIN"; report();
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            return;
        }
        busy = true; state = "downloading"; progress = 0; msg = ""; report();
        new Thread(() -> {
            File f = new File(act.getCacheDir(), "update.apk");
            try {
                HttpURLConnection c = open(url);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : size, got = 0, lastT = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n); got += n;
                        if (total > 0 && System.currentTimeMillis() - lastT > 300) { progress = (int) (got * 100 / total); lastT = System.currentTimeMillis(); report(); }
                    }
                }
                if (size > 0 && f.length() != size) throw new Exception("SIZE MISMATCH");
                state = "installing"; progress = 100; report();
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(act.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("grafit.apk", 0, f.length())) {
                        byte[] b = new byte[65536]; int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        s.fsync(out);
                    }
                    Intent cb = new Intent(ACTION).setPackage(act.getPackageName());
                    PendingIntent pend = PendingIntent.getBroadcast(act, id, cb, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                state = "error"; msg = "UPDATE FAILED · " + e.getMessage(); busy = false; report();
                f.delete();
            }
        }).start();
    }

    /** Retour de PackageInstaller : confirmation à demander, échec, ou succès (l'app redémarre). */
    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(confirm); }
                return;
            }
            busy = false;
            if (st == PackageInstaller.STATUS_SUCCESS) { state = "idle"; msg = "INSTALLED"; }
            else { state = "error"; msg = "INSTALL " + (st == PackageInstaller.STATUS_FAILURE_ABORTED ? "CANCELLED" : "FAILED · " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)); }
            new File(act.getCacheDir(), "update.apk").delete();
            report();
        }
    };
}
