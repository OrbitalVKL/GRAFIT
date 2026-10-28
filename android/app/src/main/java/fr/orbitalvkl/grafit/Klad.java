package fr.orbitalvkl.grafit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * 7.1 FINANCE : résumé de l'app de finances Klad, lu sur le téléphone par son ContentProvider (lecture seule).
 * Rien ne passe par le réseau. Klad n'est lu que si son certificat de release correspond à l'empreinte épinglée
 * (un autre APK installé sous le même nom de package est ignoré) ; Klad, de son côté, ne répond qu'à Grafit.
 * Envoi à la page : {klad: {state: ok|missing|untrusted|denied|error|empty, v, updated, json, msg}}.
 * Lecture au démarrage, au retour au premier plan, à chaque notifyChange de Klad, et à la demande de la page.
 */
class Klad {
    static final String PKG = "io.github.orbitalvkl.klad";
    static final Uri URI = Uri.parse("content://io.github.orbitalvkl.klad.grafit/summary");
    static final byte[] CERT = hex("48B6158D079C390DCE5F0C66F0F25CC711EBD556547153D2D5F44DC8EBD59F1C");

    final MainActivity act;
    final Handler h = new Handler(Looper.getMainLooper());
    ContentObserver obs;
    boolean busy = false;

    Klad(MainActivity a) { act = a; }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** installé et signé par la clé de release de Klad */
    String trust() {
        PackageManager pm = act.getPackageManager();
        try { pm.getPackageInfo(PKG, 0); } catch (PackageManager.NameNotFoundException e) { return "missing"; }
        try { return pm.hasSigningCertificate(PKG, CERT, PackageManager.CERT_INPUT_SHA256) ? "ok" : "untrusted"; }
        catch (Exception e) { return "untrusted"; }
    }

    void read() {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            JSONObject k = new JSONObject();
            try {
                String t = trust();
                k.put("state", t);
                if ("ok".equals(t)) {
                    try (Cursor c = act.getContentResolver().query(URI, null, null, null, null)) {
                        if (c == null || !c.moveToFirst()) k.put("state", "empty");
                        else {
                            int iv = c.getColumnIndex("v"), iu = c.getColumnIndex("updated_at"), ij = c.getColumnIndex("json");
                            if (iv >= 0) k.put("v", c.getInt(iv));
                            if (iu >= 0) k.put("updated", c.getLong(iu));
                            String j = ij >= 0 ? c.getString(ij) : null;
                            if (j == null || j.length() > 64 * 1024) k.put("state", "empty");
                            else k.put("json", j);
                        }
                    } catch (SecurityException e) { k.put("state", "denied"); k.put("msg", String.valueOf(e.getMessage())); }
                    catch (Exception e) { k.put("state", "error"); k.put("msg", String.valueOf(e.getMessage())); }
                }
                JSONObject d = new JSONObject(); d.put("klad", k); act.push(d);
            } catch (Exception e) { /* */ }
            busy = false;
        }).start();
    }

    /** observation des changements, seulement si Klad est le bon (sinon rien n'est enregistré) */
    void watch() {
        if (obs != null || !"ok".equals(trust())) return;
        obs = new ContentObserver(h) { @Override public void onChange(boolean self) { read(); } };
        try { act.getContentResolver().registerContentObserver(URI, false, obs); } catch (Exception e) { obs = null; }
    }

    void unwatch() {
        if (obs == null) return;
        try { act.getContentResolver().unregisterContentObserver(obs); } catch (Exception e) { /* */ }
        obs = null;
    }
}
