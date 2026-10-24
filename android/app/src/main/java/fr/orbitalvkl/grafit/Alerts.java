package fr.orbitalvkl.grafit;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Alertes vibrées d'arrière-plan (réglables, sans notification) : jugées à chaque relevé du journal continu, seulement
 * quand Grafit n'est pas au premier plan (la page a ses propres retours). Une alerte par épisode :
 *   BATT  ≤ 10 % hors charge (réarmée au-dessus de 15 % ou en charge)        motif long ×3
 *   HEAT  batterie ≥ 45 °C ou état thermique CRITICAL (réarmée sous 42 °C)    motif long ×3
 *   SHOCK pic ≥ 4 g (10 min entre deux)                                       motif long ×3
 *   PRESS chute ≥ 2,0 hPa à ≥ 1,3 hPa/h sur 3 h (règle ALERT de la tendance), sans saut brusque (changement
 *         d'altitude) — 6 h entre deux                                                                   motif court ×2
 *   RAIN  pluie annoncée dans les 30 min (Open-Meteo au quart d'heure, dernière position connue), sec maintenant
 *         — vérifiée toutes les 15 min, 3 h entre deux                                                    motif bref ×3
 * Chaque alerte est notée dans le journal (E,ms,VIB:<type>:<valeur>).
 */
final class Alerts {
    static final long[] CRIT = {0, 400, 150, 400, 150, 400}, CAUT = {0, 140, 110, 140}, RAIN = {0, 60, 90, 60, 90, 60};
    static final ArrayDeque<double[]> PRESS = new ArrayDeque<>();      // [ms, hPa]

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("vibe", true); }

    static void check(LoggerService s, long t, Double p, Integer batt, boolean chg, Double bt, int therm, Double amax) {
        SharedPreferences sp = prefs(s);
        SharedPreferences.Editor ed = sp.edit();
        boolean fg = MainActivity.resumed, on = enabled(s);
        // batterie basse
        if (batt != null) {
            boolean armed = sp.getBoolean("al_batt", true);
            if (armed && !chg && batt <= 10) { fire(s, fg, on, CRIT, "BATT:" + batt); ed.putBoolean("al_batt", false); }
            else if (!armed && (chg || batt > 15)) ed.putBoolean("al_batt", true);
        }
        // chaleur
        boolean hot = (bt != null && bt >= 45) || therm >= 4, armedH = sp.getBoolean("al_heat", true);
        if (armedH && hot) { fire(s, fg, on, CRIT, "HEAT:" + (bt != null ? String.format(Locale.ROOT, "%.1f", bt) : "T" + therm)); ed.putBoolean("al_heat", false); }
        else if (!armedH && (bt == null || bt < 42) && therm < 3) ed.putBoolean("al_heat", true);
        // choc
        if (amax != null && amax >= 4.0 && t - sp.getLong("al_shock", 0) > 600_000L) { fire(s, fg, on, CRIT, "SHOCK:" + String.format(Locale.ROOT, "%.1f", amax)); ed.putLong("al_shock", t); }
        // chute de pression
        if (p != null) {
            PRESS.addLast(new double[]{t, p});
            while (!PRESS.isEmpty() && t - PRESS.peekFirst()[0] > 3.5 * 3600_000L) PRESS.removeFirst();
            double[] old = null;
            for (double[] q : PRESS) if (t - q[0] >= 3 * 3600_000L) old = q;
            if (old != null && t - sp.getLong("al_press", 0) > 6 * 3600_000L) {
                double drop = old[1] - p, hrs = (t - old[0]) / 3600_000.0, jump = 0;
                double[] prev = null;
                for (double[] q : PRESS) { if (prev != null && q[0] - prev[0] <= 600_000L) jump = Math.max(jump, Math.abs(q[1] - prev[1])); prev = q; }
                if (drop >= 2.0 && drop / hrs >= 1.3 && jump < 0.8) { fire(s, fg, on, CAUT, "PRESS:" + String.format(Locale.ROOT, "%.1f", drop)); ed.putLong("al_press", t); }
            }
        }
        ed.apply();
        // pluie annoncée : toutes les 15 min
        if (t - sp.getLong("al_rainChk", 0) > 15 * 60_000L && sp.contains("lat")) {
            sp.edit().putLong("al_rainChk", t).apply();
            LoggerService.NET.execute(() -> rain(s, t));
        }
    }

    static void rain(LoggerService s, long t) {
        SharedPreferences sp = prefs(s);
        try {
            String u = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&minutely_15=precipitation&forecast_minutely_15=4&past_minutely_15=1&timeformat=unixtime",
                sp.getFloat("lat", 0), sp.getFloat("lon", 0));
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream is = c.getInputStream()) { byte[] b = new byte[4096]; int n; while ((n = is.read(b)) > 0) bo.write(b, 0, n); }
            JSONObject m = new JSONObject(bo.toString("UTF-8")).getJSONObject("minutely_15");
            JSONArray tm = m.getJSONArray("time"), pr = m.getJSONArray("precipitation");
            double now = 0, next = 0; long ts = t / 1000;
            for (int i = 0; i < tm.length(); i++) {
                long e = tm.getLong(i); double v = pr.isNull(i) ? 0 : pr.getDouble(i);
                if (e - 900 <= ts && ts < e) now = Math.max(now, v);
                else if (e > ts + 900 && e <= ts + 2700) next = Math.max(next, v);
            }
            if (now < 0.1 && next >= 0.2 && t - sp.getLong("al_rain", 0) > 3 * 3600_000L) {
                sp.edit().putLong("al_rain", t).apply();
                final double nx = next;
                s.h.post(() -> fire(s, MainActivity.resumed, enabled(s), RAIN, "RAIN:" + String.format(Locale.ROOT, "%.1f", nx * 4)));
            }
        } catch (Exception e) { /* réseau ou réponse illisible : prochaine vérification dans 15 min */ }
    }

    /** Vibration (sauf si Grafit est au premier plan ou si l'option est coupée) et trace dans le journal. */
    static void fire(LoggerService s, boolean fg, boolean on, long[] pat, String what) {
        s.event("VIB:" + what + (fg ? ":FG" : (on ? "" : ":OFF")));
        if (fg || !on) return;
        try {
            Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) { VibratorManager vm = s.getSystemService(VibratorManager.class); v = vm != null ? vm.getDefaultVibrator() : null; }
            else v = (Vibrator) s.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect fx = VibrationEffect.createWaveform(pat, -1);
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            else v.vibrate(fx, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        } catch (Exception e) { /* vibreur indisponible */ }
    }
}
