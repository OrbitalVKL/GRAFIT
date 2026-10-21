package fr.orbitalvkl.grafit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Démarrage du téléphone ou mise à jour de l'APK : relance le journal continu s'il est actif. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) && LoggerService.enabled(c)) LoggerService.start(c);
    }
}
