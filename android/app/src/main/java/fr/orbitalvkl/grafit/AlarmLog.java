package fr.orbitalvkl.grafit;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;

/**
 * Relevés de la prochaine alarme du réveil Android, pris même quand la page est fermée (1.21.2) : à chaque
 * modification du réveil (signal système NEXT_ALARM_CLOCK_CHANGED), à chaque relevé du journal continu et à chaque
 * lecture par la page. Une ligne [ms du relevé, ms de l'alarme (0 = aucune)] seulement quand la valeur change ;
 * les 40 dernières dans les préférences privées. La page les rejoue dans l'ordre pour retrouver l'heure du réveil
 * réellement prévue chaque matin, même modifiée après la dernière ouverture de Grafit.
 */
public class AlarmLog extends BroadcastReceiver {
    static final String KEY = "alarmLog";
    static final int MAX = 40;
    static final Object LOCK = new Object();

    @Override
    public void onReceive(Context c, Intent i) {
        if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(i.getAction())) record(c);
    }

    /** Lit la prochaine alarme et la note si elle a changé ; rend sa valeur (ms, 0 = aucune, −1 = illisible). */
    static long record(Context c) {
        long next;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo ai = am != null ? am.getNextAlarmClock() : null;
            next = ai != null ? ai.getTriggerTime() : 0;
        } catch (Exception e) { return -1; }
        synchronized (LOCK) {
            try {
                SharedPreferences sp = c.getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE);
                JSONArray a = new JSONArray(sp.getString(KEY, "[]"));
                int n = a.length();
                if (n > 0 && a.getJSONArray(n - 1).optLong(1, -1) == next) return next;
                a.put(new JSONArray().put(System.currentTimeMillis()).put(next));
                JSONArray out = new JSONArray();
                for (int k = Math.max(0, a.length() - MAX); k < a.length(); k++) out.put(a.get(k));
                sp.edit().putString(KEY, out.toString()).apply();
            } catch (Exception e) { /* préférences illisibles : relevé perdu */ }
        }
        return next;
    }

    static JSONArray read(Context c) {
        synchronized (LOCK) {
            try { return new JSONArray(c.getSharedPreferences(LoggerService.PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")); }
            catch (Exception e) { return new JSONArray(); }
        }
    }
}
