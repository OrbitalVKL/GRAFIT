package fr.orbitalvkl.grafit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Widget d'écran d'accueil Grafit : date, prochaine pluie, prochain rendez-vous, batterie. La page fournit la pluie et
 * le rendez-vous (commande widget) ; le journal continu met à jour la batterie à chaque relevé. Toucher ouvre Grafit.
 */
public class GrafitWidget extends AppWidgetProvider {
    static final String P = "widget";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { render(c, m, ids); }

    static void set(Context c, String rain, String event, String extra, String evs) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("rain", rain).putString("event", event).putString("extra", extra)
            .putString("evs", evs).putLong("t", System.currentTimeMillis()).apply();
        refresh(c);
    }

    static void battery(Context c, int lvl, boolean chg) {
        SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        if (sp.getInt("batt", -1) == lvl && sp.getBoolean("chg", false) == chg && !minuteChanged(sp)) return;
        sp.edit().putInt("batt", lvl).putBoolean("chg", chg).putString("min", new SimpleDateFormat("HHmm", Locale.ROOT).format(new Date())).apply();
        refresh(c);
    }

    static boolean minuteChanged(SharedPreferences sp) {
        return !new SimpleDateFormat("HHmm", Locale.ROOT).format(new Date()).equals(sp.getString("min", ""));
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, GrafitWidget.class));
        if (ids != null && ids.length > 0) render(c, m, ids);
    }

    static void render(Context c, AppWidgetManager m, int[] ids) {
        SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        Date d = new Date();
        String date = new SimpleDateFormat("EEE dd/MM/yy · HH:mm", Locale.ENGLISH).format(d).toUpperCase(Locale.ROOT);
        int b = sp.getInt("batt", -1);
        String batt = b < 0 ? "BATT —" : "BATT " + b + " %" + (sp.getBoolean("chg", false) ? " ↑" : " ↓");
        String extra = sp.getString("extra", "");
        long age = System.currentTimeMillis() - sp.getLong("t", 0);
        String rain = sp.getString("rain", "OPEN GRAFIT FOR WEATHER"), event = sp.getString("event", "—");
        if (age > 6 * 3600_000L) rain = "WEATHER · OPEN GRAFIT TO REFRESH";
        else if (age > 90 * 60_000L) rain = rain + " · AT " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(sp.getLong("t", 0)));
        // prochain rendez-vous : le premier de la liste encore à venir, daté au moment de l'affichage
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp.getString("evs", "[]"));
            long now = d.getTime();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                long st = e.getLong(0);
                if (st <= now) continue;
                java.util.Calendar c0 = java.util.Calendar.getInstance(), c1 = java.util.Calendar.getInstance();
                c1.setTimeInMillis(st);
                int dd = c1.get(java.util.Calendar.DAY_OF_YEAR) - c0.get(java.util.Calendar.DAY_OF_YEAR) + 365 * (c1.get(java.util.Calendar.YEAR) - c0.get(java.util.Calendar.YEAR));
                String hm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(st));
                String when = dd == 0 ? hm : (dd == 1 ? "TMW " + hm : new SimpleDateFormat("EEE", Locale.ENGLISH).format(new Date(st)).toUpperCase(Locale.ROOT) + " " + hm);
                event = when + " · " + e.getString(1);
                break;
            }
        } catch (Exception ignored) { /* liste absente ou illisible : texte de la page */ }
        PendingIntent open = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_date, date);
            v.setTextViewText(R.id.w_rain, rain);
            v.setTextViewText(R.id.w_event, event);
            v.setTextViewText(R.id.w_batt, batt + (extra.isEmpty() ? "" : " · " + extra));
            v.setOnClickPendingIntent(R.id.w_root, open);
            m.updateAppWidget(id, v);
        }
    }
}
