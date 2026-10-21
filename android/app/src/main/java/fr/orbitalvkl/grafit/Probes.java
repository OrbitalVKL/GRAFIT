package fr.orbitalvkl.grafit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.CalendarContract;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Capteurs et sources du téléphone, lus seulement pendant que Grafit est au premier plan, et envoyés à la page :
 *   toutes les 2 s : baro (hPa), light (lx), ambient (°C), battTemp (°C), thermal {status, headroom},
 *                    steps (compteur depuis le démarrage), gnss [{c, svid, az, el, cn0, used}]
 *   toutes les 10 s : cell {rat, operator, pci, band, rsrp, ci, tac, mcc, mnc}
 *   au démarrage, au retour et à chaque changement : agenda [{start, stop (ms), title, loc, allDay, uid}]
 *   au démarrage et au retour : nextAlarm (ms), usage ; firstUnlock, lastLock (ms), glances si l'accès aux statistiques d'usage est accordé
 */
class Probes implements SensorEventListener {
    final MainActivity act;
    final Handler h = new Handler(Looper.getMainLooper());
    final SensorManager sm;
    final LocationManager lm;
    final TelephonyManager tm;
    final PowerManager pm;
    boolean running = false;
    Float baro, light, ambient;
    Long steps;
    JSONArray gnss;
    long gnssT = 0;
    JSONObject cell;
    ContentObserver calObs;

    Probes(MainActivity a) {
        act = a;
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        tm = (TelephonyManager) a.getSystemService(Context.TELEPHONY_SERVICE);
        pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
    }

    // ------------------------------------------------------------------ cycle de vie
    void start() {
        if (running) return;
        running = true;
        reg(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL);
        reg(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL);
        reg(Sensor.TYPE_AMBIENT_TEMPERATURE, SensorManager.SENSOR_DELAY_NORMAL);
        if (act.has(Manifest.permission.ACTIVITY_RECOGNITION)) reg(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL);
        if (act.has(Manifest.permission.ACCESS_FINE_LOCATION) && lm != null) {
            try {
                lm.registerGnssStatusCallback(gnssCb, h);
                // le moteur GNSS doit tourner pour décrire les satellites
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locL, Looper.getMainLooper());
            } catch (SecurityException e) { /* permission retirée */ }
        }
        if (act.has(Manifest.permission.READ_CALENDAR)) {
            calObs = new ContentObserver(h) {
                @Override public void onChange(boolean self) { h.removeCallbacks(agendaR); h.postDelayed(agendaR, 2000); }
            };
            try { act.getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calObs); } catch (Exception e) { calObs = null; }
        }
        h.post(fastR);
        h.post(cellR);
        h.postDelayed(agendaR, 500);
    }

    void stop() {
        if (!running) return;
        running = false;
        h.removeCallbacksAndMessages(null);
        sm.unregisterListener(this);
        if (lm != null) {
            try { lm.unregisterGnssStatusCallback(gnssCb); lm.removeUpdates(locL); } catch (Exception e) { /* */ }
        }
        if (calObs != null) { act.getContentResolver().unregisterContentObserver(calObs); calObs = null; }
    }

    void pushAll() {
        if (!running) return;
        h.removeCallbacks(agendaR);
        h.post(agendaR);
        h.post(this::pushUnlock);
    }

    void reg(int type, int delay) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, delay);
    }

    // ------------------------------------------------------------------ capteurs
    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_PRESSURE: baro = e.values[0]; break;
            case Sensor.TYPE_LIGHT: light = e.values[0]; break;
            case Sensor.TYPE_AMBIENT_TEMPERATURE: ambient = e.values[0]; break;
            case Sensor.TYPE_STEP_COUNTER: steps = (long) e.values[0]; break;
            default: break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    final Runnable fastR = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                JSONObject d = new JSONObject();
                if (baro != null) d.put("baro", round(baro, 2));
                if (light != null) d.put("light", round(light, 1));
                if (ambient != null) d.put("ambient", round(ambient, 1));
                if (steps != null) d.put("steps", steps);
                Intent b = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                    if (t != Integer.MIN_VALUE) d.put("battTemp", t / 10.0);
                }
                if (pm != null) {
                    JSONObject th = new JSONObject();
                    th.put("status", pm.getCurrentThermalStatus());
                    if (Build.VERSION.SDK_INT >= 30) {
                        float hr = pm.getThermalHeadroom(10);
                        if (!Float.isNaN(hr)) th.put("headroom", round(hr, 2));
                    }
                    d.put("thermal", th);
                }
                if (gnss != null && System.currentTimeMillis() - gnssT < 10000) d.put("gnss", gnss);
                act.push(d);
            } catch (Exception e) { /* une valeur illisible n'arrête pas le flux */ }
            h.postDelayed(this, 2000);
        }
    };

    static double round(double v, int n) { double k = Math.pow(10, n); return Math.round(v * k) / k; }

    // ------------------------------------------------------------------ GNSS brut
    final LocationListener locL = loc -> { };

    final GnssStatus.Callback gnssCb = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus st) {
            try {
                JSONArray a = new JSONArray();
                for (int i = 0; i < st.getSatelliteCount(); i++) {
                    float el = st.getElevationDegrees(i), cn0 = st.getCn0DbHz(i);
                    if (el <= 0 && cn0 <= 0) continue;
                    JSONObject s = new JSONObject();
                    s.put("c", letter(st.getConstellationType(i)));
                    s.put("svid", st.getSvid(i));
                    s.put("az", round(st.getAzimuthDegrees(i), 1));
                    s.put("el", round(el, 1));
                    s.put("cn0", round(cn0, 1));
                    s.put("used", st.usedInFix(i));
                    a.put(s);
                }
                gnss = a;
                gnssT = System.currentTimeMillis();
            } catch (Exception e) { /* */ }
        }
    };

    static String letter(int c) {
        switch (c) {
            case GnssStatus.CONSTELLATION_GPS: return "G";
            case GnssStatus.CONSTELLATION_GALILEO: return "E";
            case GnssStatus.CONSTELLATION_GLONASS: return "R";
            case GnssStatus.CONSTELLATION_BEIDOU: return "C";
            case GnssStatus.CONSTELLATION_QZSS: return "J";
            case GnssStatus.CONSTELLATION_SBAS: return "S";
            case GnssStatus.CONSTELLATION_IRNSS: return "I";
            default: return "U";
        }
    }

    // ------------------------------------------------------------------ cellule servante
    final Runnable cellR = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (tm != null && act.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                try {
                    tm.requestCellInfoUpdate(act.getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                        @Override public void onCellInfo(List<CellInfo> list) { onCells(list); }
                    });
                } catch (SecurityException e) { /* */ }
                catch (Exception e) {
                    try { onCells(tm.getAllCellInfo()); } catch (SecurityException se) { /* */ }
                }
            }
            h.postDelayed(this, 10000);
        }
    };

    void onCells(List<CellInfo> list) {
        if (list == null) return;
        try {
            for (CellInfo ci : list) {
                if (!ci.isRegistered()) continue;
                JSONObject c = new JSONObject();
                String op = null;
                if (ci instanceof CellInfoNr) {
                    CellIdentityNr id = (CellIdentityNr) ci.getCellIdentity();
                    CellSignalStrengthNr ss = (CellSignalStrengthNr) ci.getCellSignalStrength();
                    c.put("rat", "5G NR");
                    put(c, "pci", id.getPci());
                    put(c, "tac", id.getTac());
                    if (id.getNci() != Long.MAX_VALUE) c.put("ci", id.getNci());
                    put(c, "rsrp", ss.getSsRsrp());
                    if (Build.VERSION.SDK_INT >= 30 && id.getBands().length > 0) c.put("band", "n" + id.getBands()[0]);
                    c.put("mcc", id.getMccString()); c.put("mnc", id.getMncString());
                    op = str(id.getOperatorAlphaShort());
                } else if (ci instanceof CellInfoLte) {
                    CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                    c.put("rat", "4G LTE");
                    put(c, "pci", id.getPci());
                    put(c, "tac", id.getTac());
                    put(c, "ci", id.getCi());
                    put(c, "rsrp", ((CellInfoLte) ci).getCellSignalStrength().getRsrp());
                    put(c, "rsrq", ((CellInfoLte) ci).getCellSignalStrength().getRsrq());
                    if (Build.VERSION.SDK_INT >= 30 && id.getBands().length > 0) c.put("band", id.getBands()[0]);
                    c.put("mcc", id.getMccString()); c.put("mnc", id.getMncString());
                    op = str(id.getOperatorAlphaShort());
                } else if (ci instanceof CellInfoWcdma) {
                    CellIdentityWcdma id = ((CellInfoWcdma) ci).getCellIdentity();
                    c.put("rat", "3G UMTS");
                    put(c, "pci", id.getPsc());
                    put(c, "ci", id.getCid());
                    put(c, "tac", id.getLac());
                    put(c, "rsrp", ((CellInfoWcdma) ci).getCellSignalStrength().getDbm());
                    c.put("mcc", id.getMccString()); c.put("mnc", id.getMncString());
                    op = str(id.getOperatorAlphaShort());
                } else if (ci instanceof CellInfoGsm) {
                    CellIdentityGsm id = ((CellInfoGsm) ci).getCellIdentity();
                    c.put("rat", "2G GSM");
                    put(c, "ci", id.getCid());
                    put(c, "tac", id.getLac());
                    put(c, "rsrp", ((CellInfoGsm) ci).getCellSignalStrength().getDbm());
                    c.put("mcc", id.getMccString()); c.put("mnc", id.getMncString());
                    op = str(id.getOperatorAlphaShort());
                } else continue;
                if (op == null && tm != null) op = str(tm.getNetworkOperatorName());
                if (op != null) c.put("operator", op.toUpperCase());
                cell = c;
                JSONObject d = new JSONObject();
                d.put("cell", c);
                act.push(d);
                return;
            }
        } catch (Exception e) { /* */ }
    }

    static void put(JSONObject o, String k, int v) throws Exception {
        if (v != CellInfo.UNAVAILABLE && v != Integer.MAX_VALUE) o.put(k, v);
    }

    static String str(CharSequence s) { return s == null || s.length() == 0 ? null : s.toString(); }

    // ------------------------------------------------------------------ agenda Android
    final Runnable agendaR = new Runnable() {
        @Override public void run() {
            if (!running) return;
            pushAgenda();
            h.removeCallbacks(this);
            h.postDelayed(this, 5 * 60 * 1000);
        }
    };

    void pushAgenda() {
        if (!act.has(Manifest.permission.READ_CALENDAR)) return;
        long now = System.currentTimeMillis(), from = now - 7L * 86400000L, to = now + 120L * 86400000L;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.STATUS,
        };
        JSONArray a = new JSONArray();
        ContentResolver cr = act.getContentResolver();
        try (Cursor c = cr.query(b.build(), proj, CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return;
            while (c.moveToNext() && a.length() < 600) {
                if (!c.isNull(6) && c.getInt(6) == CalendarContract.Instances.STATUS_CANCELED) continue;
                boolean allDay = c.getInt(4) != 0;
                long s = c.getLong(0), e = c.getLong(1);
                if (allDay) { s = utcDayToLocal(s); e = utcDayToLocal(e); }
                JSONObject o = new JSONObject();
                o.put("start", s);
                o.put("stop", e);
                o.put("title", c.isNull(2) ? "" : c.getString(2));
                o.put("loc", c.isNull(3) ? "" : c.getString(3));
                o.put("allDay", allDay);
                o.put("uid", c.getLong(5) + "@" + c.getLong(0));
                a.put(o);
            }
        } catch (Exception e) { return; }
        try { JSONObject d = new JSONObject(); d.put("agenda", a); act.push(d); } catch (Exception e) { /* */ }
    }

    /** Journée entière : minuit UTC dans l'agenda -> minuit local du même jour. */
    static long utcDayToLocal(long utc) {
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.setTimeInMillis(utc);
        Calendar l = Calendar.getInstance();
        l.clear();
        l.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        return l.getTimeInMillis();
    }

    // ------------------------------------------------------------------ réveil : alarme et premier déverrouillage durable
    // nextAlarm : prochaine alarme du réveil Android (ms, 0 = aucune), sans permission ; la page la retient la
    // veille pour dater le réveil du lendemain. usage : accès aux statistiques d'usage accordé ou non.
    // firstUnlock : premier déverrouillage « durable » après 03:00 — un coup d'œil nocturne (session < 3 min suivie
    // de 45 min sans déverrouillage) est ignoré ; glances = nombre de coups d'œil écartés.
    static final long GLANCE_MS = 3 * 60000L, GLANCE_GAP_MS = 45 * 60000L;

    void pushUnlock() {
        JSONObject d = new JSONObject();
        try {
            AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo ai = am != null ? am.getNextAlarmClock() : null;
            d.put("nextAlarm", ai != null ? ai.getTriggerTime() : 0);
        } catch (Exception e) { /* */ }
        try {
            AppOpsManager ops = (AppOpsManager) act.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), act.getPackageName());
            boolean ok = mode == AppOpsManager.MODE_ALLOWED;
            d.put("usage", ok);
            if (ok) unlockInto(d);
        } catch (Exception e) { /* accès refusé */ }
        act.push(d);
    }

    void unlockInto(JSONObject d) throws Exception {
        UsageStatsManager usm = (UsageStatsManager) act.getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 3); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long day3 = c.getTimeInMillis(), now = System.currentTimeMillis();
        if (now < day3) return;
        UsageEvents ev = usm.queryEvents(day3 - 9L * 3600000L, now);      // depuis 18 h la veille
        UsageEvents.Event e = new UsageEvents.Event();
        long lastLock = 0;
        List<long[]> ses = new ArrayList<>();                              // [début, fin] après 03:00
        long open = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            long ts = e.getTimeStamp();
            boolean lock = t == UsageEvents.Event.KEYGUARD_SHOWN || t == UsageEvents.Event.SCREEN_NON_INTERACTIVE;
            if (ts < day3) { if (lock) lastLock = ts; continue; }
            if (t == UsageEvents.Event.KEYGUARD_HIDDEN && open == 0) open = ts;
            else if (lock && open != 0) { ses.add(new long[]{open, ts}); open = 0; }
        }
        if (open != 0) ses.add(new long[]{open, now});
        long first = 0;
        int glances = 0;
        for (int i = 0; i < ses.size(); i++) {
            long[] s = ses.get(i);
            boolean last = i == ses.size() - 1;
            long nextStart = last ? Long.MAX_VALUE : ses.get(i + 1)[0];
            boolean glance = !last && s[1] - s[0] < GLANCE_MS && nextStart - s[1] >= GLANCE_GAP_MS;
            if (glance) { glances++; continue; }
            first = s[0];
            break;
        }
        if (first == 0) return;
        d.put("firstUnlock", first);
        d.put("glances", glances);
        if (lastLock > 0) d.put("lastLock", lastLock);
    }
}
