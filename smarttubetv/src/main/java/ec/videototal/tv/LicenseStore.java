package ec.videototal.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.UUID;

/**
 * Licencia guardada en el televisor.
 *
 * Regla que manda todo lo demás: si hay licencia guardada y no está vencida, la
 * app NO vuelve a pedir el código, ni siquiera sin internet.
 */
public final class LicenseStore {

    private static final String PREFS = "videototal_tv";

    private LicenseStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Identificador estable del equipo: ata la licencia a este televisor. */
    static String deviceId(Context context) {
        String guardado = prefs(context).getString("device_id", null);
        if (guardado != null && !guardado.isEmpty()) {
            return guardado;
        }
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String id = (androidId == null || androidId.isEmpty()) ? UUID.randomUUID().toString() : androidId;
        prefs(context).edit().putString("device_id", id).apply();
        return id;
    }

    static String deviceName() {
        String modelo = Build.MODEL == null ? "TV" : Build.MODEL;
        return "TV · " + modelo;
    }

    static void save(Context context, String token, String plan, long expiresAt) {
        prefs(context).edit()
                .putString("token", token)
                .putString("plan", plan == null ? "free" : plan)
                .putLong("expires_at", expiresAt)
                .putLong("last_ok", System.currentTimeMillis())
                .apply();
    }

    static String token(Context context) {
        return prefs(context).getString("token", "");
    }

    static String plan(Context context) {
        return prefs(context).getString("plan", "free");
    }

    static long expiresAt(Context context) {
        return prefs(context).getLong("expires_at", 0L);
    }

    static boolean isExpired(Context context) {
        long expira = expiresAt(context);
        return expira > 0 && System.currentTimeMillis() > expira;
    }

    static boolean hasAccess(Context context) {
        String token = token(context);
        if (token.isEmpty()) {
            return false;
        }
        String plan = plan(context);
        if (plan.isEmpty() || "free".equalsIgnoreCase(plan)) {
            return false;
        }
        return !isExpired(context);
    }

    static void clear(Context context) {
        String deviceId = deviceId(context);
        prefs(context).edit().clear().putString("device_id", deviceId).apply();
    }
}
