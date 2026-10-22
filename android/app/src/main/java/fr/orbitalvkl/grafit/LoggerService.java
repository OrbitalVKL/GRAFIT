package fr.orbitalvkl.grafit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Journal continu de Grafit (réglable, actif par défaut) : un relevé par minute, écran éteint compris, écrit dans
 * un fichier privé de l'application (files/bglog.csv, une semaine environ) que la page lit au retour au premier plan.
 *   S,ms,pression station hPa,batterie %,en charge 0/1,batterie °C,santé,état thermique,pas,dBm,RTT ms (−1 perdu),
 *     réseau W|C|E|O|N,mouvement g rms,pic g      (mouvement : accéléromètre 3 s ; RTT : 2 requêtes 204, la meilleure)
 *   E,ms,UNLOCK|OFF|ON          (déverrouillage, extinction et allumage de l'écran)
 * Service de premier plan (exigé par Android pour lire le baromètre en arrière-plan). L'application ne demande pas
 * la permission des notifications : la notification obligatoire du service n'apparaît donc pas dans le volet (elle
 * reste visible dans le gestionnaire des applications actives). Réveil : alarme exacte chaque minute (Android peut
 * espacer en veille profonde) ; verrou de veille de quelques secondes le temps du relevé. Rien n'est envoyé ailleurs.
 */
public class LoggerService extends Service implements SensorEventListener {
    static final String ACTION_SAMPLE = "fr.orbitalvkl.grafit.SAMPLE";
    static final String CHANNEL = "grafit-log", FILE = "bglog.csv", PREFS = "grafit", KEY_ON = "bglog";
    static final long PERIOD_MS = 60_000L, MAX_BYTES = 1_200_000L, KEEP_BYTES = 700_000L;
    static final int NOTIF_ID = 16;
    static final Object LOCK = new Object();
    static LoggerService instance;
    static final ExecutorService NET = Executors.newSingleThreadExecutor();
    static final String PROBE = "https://www.gstatic.com/generate_204";

    final Handler h = new Handler(Looper.getMainLooper());
    SensorManager sm;
    PowerManager pm;
    TelephonyManager tm;
    AlarmManager am;
    PowerManager.WakeLock wl;
    BroadcastReceiver screenRx;
    boolean sampling = false;
    double pSum = 0;
    int pN = 0;
    Long steps = null;
    double aSum2 = 0, aMax = 0;
    int aN = 0;
    boolean winDone = false, netDone = false;
    long rtt = -2;                                    // −2 : pas de mesure (pas de réseau), −1 : perdu
    String tr = "N";
    final Runnable winR = () -> { winDone = true; sm.unregisterListener(this); if (netDone) finish(); };
    final Runnable finishR = this::finish;

    // ------------------------------------------------------------------ commandes (page, démarrage, alarme)
    static boolean enabled(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON, true); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        if (on) start(c); else stop(c);
    }

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, LoggerService.class)); } catch (Exception e) { /* démarrage refusé */ }
    }

    static void stop(Context c) {
        AlarmManager a = c.getSystemService(AlarmManager.class);
        if (a != null) a.cancel(alarmIntent(c));
        c.stopService(new Intent(c, LoggerService.class));
    }

    static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, SampleReceiver.class).setAction(ACTION_SAMPLE);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Lignes postérieures à since (ms), au plus max (les plus récentes), pour la page. */
    static JSONArray readSince(Context c, long since, int max) {
        ArrayDeque<JSONArray> q = new ArrayDeque<>();
        synchronized (LOCK) {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        JSONArray a = parse(line);
                        if (a == null || a.optLong(0) <= since) continue;
                        q.addLast(a);
                        if (q.size() > max) q.removeFirst();
                    }
                } catch (Exception e) { /* fichier illisible : on rend ce qu'on a */ }
            }
        }
        JSONArray out = new JSONArray();
        for (JSONArray a : q) out.put(a);
        return out;
    }

    static JSONArray parse(String line) {
        try {
            String[] p = line.split(",", -1);
            JSONArray a = new JSONArray();
            if (p.length >= 3 && "E".equals(p[0])) { a.put(Long.parseLong(p[1])); a.put("E"); a.put(p[2]); return a; }
            if (p.length < 10 || !"S".equals(p[0])) return null;
            a.put(Long.parseLong(p[1])); a.put("S");
            for (int i = 2; i < 14; i++) {
                if (i >= p.length || p[i].isEmpty()) a.put(org.json.JSONObject.NULL);
                else if (i == 11) a.put(p[i]);
                else if (i == 2 || i == 5 || i == 12 || i == 13) a.put(Double.parseDouble(p[i]));
                else a.put(Long.parseLong(p[i]));
            }
            return a;
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ cycle de vie
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        am = getSystemService(AlarmManager.class);
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(a)) event("UNLOCK");
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) event("OFF");
                else if (Intent.ACTION_SCREEN_ON.equals(a)) event("ON");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_USER_PRESENT); f.addAction(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screenRx, f);
    }

    void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Grafit · journal continu", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Grafit")
            .setContentText("Journal continu · un relevé par minute")
            .setContentIntent(open)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        sample();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenRx); } catch (Exception e) { /* */ }
        try { sm.unregisterListener(this); } catch (Exception e) { /* */ }
        release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // ------------------------------------------------------------------ relevé
    void schedule() {
        if (am == null) return;
        long at = System.currentTimeMillis() + PERIOD_MS;
        PendingIntent pi = alarmIntent(this);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    void sample() {
        schedule();
        if (sampling) return;
        sampling = true;
        if (wl == null) { wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grafit:log"); wl.setReferenceCounted(false); }
        wl.acquire(10_000L);
        pSum = 0; pN = 0; aSum2 = 0; aMax = 0; aN = 0; winDone = false; netDone = false; rtt = -2; tr = "N";
        Sensor ps = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (ps != null) sm.registerListener(this, ps, SensorManager.SENSOR_DELAY_NORMAL, h);
        Sensor ac = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (ac != null) sm.registerListener(this, ac, SensorManager.SENSOR_DELAY_GAME, h);
        NET.execute(this::probe);
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            Sensor sc = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (sc != null) sm.registerListener(this, sc, SensorManager.SENSOR_DELAY_NORMAL, h);
        }
        h.postDelayed(winR, 3000);
        h.postDelayed(finishR, 9000);                  // garde-fou : la ligne s'écrit quoi qu'il arrive
    }

    /** Latence : deux petites requêtes sur le réseau actif ; la seconde réutilise la connexion. Type de réseau. */
    void probe() {
        long best = -2; String t = "N";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            if (nc != null) {
                t = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "W" : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "C"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "E" : "O";
                best = -1;
                for (int k = 0; k < 2; k++) {
                    long t0 = SystemClock.elapsedRealtime();
                    HttpURLConnection c = (HttpURLConnection) n.openConnection(new URL(PROBE));
                    c.setConnectTimeout(4000); c.setReadTimeout(4000); c.setUseCaches(false);
                    try {
                        int code = c.getResponseCode();
                        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) { if (is != null) while (is.read() >= 0) { /* vide */ } }
                        long dt = SystemClock.elapsedRealtime() - t0;
                        if (code == 204 && (best < 0 || dt < best)) best = dt;
                    } catch (Exception e) { /* tentative perdue */ }
                }
            }
        } catch (Exception e) { /* */ }
        final long r = best; final String tt = t;
        h.post(() -> { rtt = r; tr = tt; netDone = true; if (winDone) finish(); });
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_PRESSURE) { pSum += e.values[0]; pN++; }
        else if (t == Sensor.TYPE_STEP_COUNTER) steps = (long) e.values[0];
        else if (t == Sensor.TYPE_ACCELEROMETER) {
            double m = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]), dv = m - SensorManager.GRAVITY_EARTH;
            aSum2 += dv * dv; aN++; if (m > aMax) aMax = m;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    void finish() {
        if (!sampling) return;
        sampling = false;
        h.removeCallbacks(winR); h.removeCallbacks(finishR);
        sm.unregisterListener(this);
        StringBuilder b = new StringBuilder("S,").append(System.currentTimeMillis()).append(',');
        if (pN > 0) b.append(String.format(Locale.ROOT, "%.2f", pSum / pN));
        b.append(',');
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bi != null) {
            int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1), tp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (lvl >= 0 && sc > 0) b.append(Math.round(lvl * 100f / sc));
            b.append(',').append(st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL ? 1 : 0).append(',');
            if (tp != Integer.MIN_VALUE) b.append(String.format(Locale.ROOT, "%.1f", tp / 10.0));
            b.append(',').append(bi.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)).append(',');
        } else b.append(",,,,");
        b.append(pm.getCurrentThermalStatus()).append(',');
        if (steps != null) b.append(steps);
        b.append(',');
        Integer dbm = dbm();
        if (dbm != null) b.append(dbm);
        b.append(',');
        if (rtt >= -1) b.append(rtt);
        b.append(',').append(tr).append(',');
        if (aN > 0) b.append(String.format(Locale.ROOT, "%.3f,%.2f", Math.sqrt(aSum2 / aN) / SensorManager.GRAVITY_EARTH, aMax / SensorManager.GRAVITY_EARTH));
        else b.append(',');
        append(b.toString());
        release();
    }

    Integer dbm() {
        try {
            SignalStrength ss = tm != null ? tm.getSignalStrength() : null;
            if (ss == null) return null;
            List<CellSignalStrength> l = ss.getCellSignalStrengths();
            if (l == null || l.isEmpty()) return null;
            int v = l.get(0).getDbm();
            return v == Integer.MAX_VALUE || v >= 0 ? null : v;
        } catch (Exception e) { return null; }
    }

    void event(String k) { append("E," + System.currentTimeMillis() + "," + k); }

    void release() { try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { /* */ } }

    /** Ajout d'une ligne ; au-delà de MAX_BYTES, le fichier est ramené à ses KEEP_BYTES derniers octets. */
    void append(String line) {
        synchronized (LOCK) {
            File f = new File(getFilesDir(), FILE);
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { return; }
            if (f.length() <= MAX_BYTES) return;
            try {
                byte[] tail = new byte[(int) KEEP_BYTES];
                try (RandomAccessFile r = new RandomAccessFile(f, "r")) { r.seek(f.length() - KEEP_BYTES); r.readFully(tail); }
                int k = 0; while (k < tail.length && tail[k] != '\n') k++;
                try (FileOutputStream os = new FileOutputStream(f, false)) { os.write(tail, k + 1, tail.length - k - 1); }
            } catch (Exception e) { /* au pire le fichier reste plus gros */ }
        }
    }
}
