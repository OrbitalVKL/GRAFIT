package fr.orbitalvkl.grafit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarme du journal continu : relevé par le service en cours, ou redémarrage du service s'il a été arrêté. */
public class SampleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!LoggerService.enabled(c)) return;
        LoggerService s = LoggerService.instance;
        if (s != null) s.sample(); else LoggerService.start(c);
    }
}
