package org.mitvdebloat.vitrina;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Приём настроек с ноутбука:
 *   adb shell am broadcast -n org.mitvdebloat.vitrina/.ConfigReceiver \
 *       -a org.mitvdebloat.vitrina.CONFIG --es b64 <JSON в base64>
 * В манифесте приёмник закрыт правом DUMP — его есть только у adb shell
 * и системы, обычные приложения прислать настройки не могут.
 * Ключи JSON: dock (список пакетов), city, lat, lon.
 * Отсутствующий ключ не трогается.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String b64 = intent.getStringExtra("b64");
        if (b64 == null) { setResultData("ERROR: нет --es b64"); return; }
        try {
            JSONObject o = new JSONObject(new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8));
            SharedPreferences.Editor e = Config.prefs(c).edit();
            if (o.has("dock")) {
                JSONArray a = o.getJSONArray("dock");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(','); sb.append(a.getString(i)); }
                e.putString(Config.DOCK, sb.toString());
            }
            if (o.has("city")) e.putString(Config.CITY, o.getString("city"));
            if (o.has("lat")) e.putLong(Config.LAT, Double.doubleToLongBits(o.getDouble("lat")));
            if (o.has("lon")) e.putLong(Config.LON, Double.doubleToLongBits(o.getDouble("lon")));
            e.apply();
            // Новые настройки — свежие данные: старый кэш погоды не нужен.
            Net.dropCache(c);
            setResultData("OK");
        } catch (Exception ex) {
            Log.w("Vitrina", "config", ex);
            setResultData("ERROR: " + ex.getMessage());
        }
    }
}
