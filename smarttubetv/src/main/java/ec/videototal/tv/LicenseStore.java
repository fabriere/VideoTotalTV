package ec.videototal.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Licencia guardada en el televisor.
 *
 * POR QUÉ SE GUARDA DOS VECES
 * SmartTube sale del proceso con `System.exit(0)` (common/.../Utils.java). Con
 * `SharedPreferences.apply()` la escritura es asíncrona y el proceso moría antes
 * de llegar al disco: el cliente activaba, cerraba la app y al reabrir le volvía
 * a pedir el código. Por eso:
 *
 *   1. SharedPreferences con `commit()` (síncrono, escribe antes de devolver).
 *   2. Copia en un archivo propio con fsync + rename atómico, que sobrevive
 *      incluso si el televisor corta la luz.
 *
 * Al leer, si las preferencias están vacías se recupera del archivo.
 */
public final class LicenseStore {

    private static final String TAG = "VT-Licencia";
    private static final String PREFS = "videototal_tv";
    private static final String ARCHIVO = "videototal-sesion.json";

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
        prefs(context).edit().putString("device_id", id).commit();
        return id;
    }

    static String deviceName() {
        String modelo = Build.MODEL == null ? "TV" : Build.MODEL;
        return "TV · " + modelo;
    }

    static void save(Context context, String token, String plan, long expiresAt) {
        boolean guardado = prefs(context).edit()
                .putString("token", token)
                .putString("plan", plan == null ? "free" : plan)
                .putLong("expires_at", expiresAt)
                .putLong("last_ok", System.currentTimeMillis())
                .commit(); // síncrono: no se pierde si el proceso muere enseguida
        escribirArchivo(context, token, plan, expiresAt);
        Log.i(TAG, "sesión guardada plan=" + plan + " expira=" + expiresAt + " prefs=" + guardado);
    }

    static String token(Context context) {
        hidratar(context);
        return prefs(context).getString("token", "");
    }

    static String plan(Context context) {
        hidratar(context);
        return prefs(context).getString("plan", "free");
    }

    static long expiresAt(Context context) {
        hidratar(context);
        return prefs(context).getLong("expires_at", 0L);
    }

    static boolean isExpired(Context context) {
        long expira = expiresAt(context);
        return expira > 0 && System.currentTimeMillis() > expira;
    }

    /** ¿Se puede entrar ahora mismo sin pedir código? */
    static boolean hasAccess(Context context) {
        String token = token(context);
        String plan = plan(context);
        boolean vigente = !token.isEmpty() && plan != null && !plan.isEmpty()
                && !"free".equalsIgnoreCase(plan) && !isExpired(context);
        Log.i(TAG, "acceso=" + vigente + " (token=" + (token.isEmpty() ? "no" : "sí")
                + ", plan=" + plan + ", expira=" + expiresAt(context) + ")");
        return vigente;
    }

    static void clear(Context context) {
        String deviceId = deviceId(context);
        prefs(context).edit().clear().putString("device_id", deviceId).commit();
        File archivo = new File(context.getFilesDir(), ARCHIVO);
        if (archivo.exists() && !archivo.delete()) {
            Log.w(TAG, "no se pudo borrar el respaldo de la sesión");
        }
        Log.i(TAG, "sesión borrada");
    }

    // --- respaldo en archivo -------------------------------------------------

    private static void escribirArchivo(Context context, String token, String plan, long expiresAt) {
        File destino = new File(context.getFilesDir(), ARCHIVO);
        File temporal = new File(context.getFilesDir(), ARCHIVO + ".tmp");
        try {
            JSONObject json = new JSONObject();
            json.put("token", token);
            json.put("plan", plan == null ? "free" : plan);
            json.put("expires_at", expiresAt);
            byte[] datos = json.toString().getBytes(StandardCharsets.UTF_8);

            try (FileOutputStream out = new FileOutputStream(temporal)) {
                out.write(datos);
                out.flush();
                out.getFD().sync(); // al disco, no a la caché del sistema
            }
            if (!temporal.renameTo(destino)) {
                Log.w(TAG, "no se pudo renombrar el respaldo de la sesión");
            }
        } catch (Exception error) {
            Log.w(TAG, "no se pudo escribir el respaldo: " + error);
        }
    }

    /** Si las preferencias se perdieron, se recupera la sesión del archivo. */
    private static void hidratar(Context context) {
        String token = prefs(context).getString("token", "");
        if (!token.isEmpty()) {
            return;
        }
        File archivo = new File(context.getFilesDir(), ARCHIVO);
        if (!archivo.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(archivo)) {
            byte[] buffer = new byte[(int) archivo.length()];
            int leido = in.read(buffer);
            if (leido <= 0) {
                return;
            }
            JSONObject json = new JSONObject(new String(buffer, 0, leido, StandardCharsets.UTF_8));
            String tokenGuardado = json.optString("token", "");
            String planGuardado = json.optString("plan", "free");
            long expira = json.optLong("expires_at", 0L);
            if (!tokenGuardado.isEmpty()) {
                prefs(context).edit()
                        .putString("token", tokenGuardado)
                        .putString("plan", planGuardado)
                        .putLong("expires_at", expira)
                        .commit();
                Log.i(TAG, "sesión recuperada del respaldo (plan=" + planGuardado + ")");
            }
        } catch (Exception error) {
            Log.w(TAG, "respaldo ilegible: " + error);
        }
    }
}
