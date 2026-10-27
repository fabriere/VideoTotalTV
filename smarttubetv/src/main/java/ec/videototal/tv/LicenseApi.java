package ec.videototal.tv;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Mismo backend de licencias que la versión de celular, así los códigos y las
 * activaciones del televisor aparecen en el mismo panel.
 *
 * POST /license/activate  {code, deviceId, name} → {access_token, user:{plan, planExpiresAt}}
 * GET  /me                (Bearer)               → {plan, planExpiresAt}
 */
final class LicenseApi {

    private static final String TAG = "VT-Licencia";
    private static final String BASE = "https://api-production-0cd81.up.railway.app";
    private static final int TIMEOUT_MS = 20000;

    static final class Result {
        boolean ok;
        String error;
        String token;
        String plan;
        long expiresAt;
    }

    private LicenseApi() {
    }

    static Result activate(Context context, String code) {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code);
            body.put("deviceId", LicenseStore.deviceId(context));
            body.put("name", LicenseStore.deviceName());

            HttpURLConnection connection = open("/license/activate", "POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            connection.disconnect();

            Result result = new Result();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "activate HTTP " + status + " " + text);
                if (text.contains("code_exhausted")) {
                    // El código ya se usó en otro equipo (p. ej. el celular).
                    result.error = "ya_usado";
                } else if (status == 400 || status == 404) {
                    result.error = "bad_code";
                } else {
                    result.error = "network";
                }
                return result;
            }

            JSONObject json = new JSONObject(text);
            JSONObject user = json.optJSONObject("user");
            result.ok = true;
            result.token = json.optString("access_token", "");
            result.plan = user == null ? "free" : user.optString("plan", "free");
            result.expiresAt = user == null ? 0L : parseDate(user.opt("planExpiresAt"));
            return result;
        } catch (Exception error) {
            Log.w(TAG, "activate: " + error);
            Result result = new Result();
            result.error = "network";
            return result;
        }
    }

    /**
     * Revalida en segundo plano. Si el servidor dice que ya no hay plan, se
     * borra la sesión. Si no hay red, no se toca nada: se sigue con lo guardado.
     */
    static void revalidate(Context context) {
        String token = LicenseStore.token(context);
        if (token.isEmpty()) {
            return;
        }
        try {
            HttpURLConnection connection = open("/me", "GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            connection.disconnect();

            if (status == 401) {
                Log.w(TAG, "sesión rechazada: se pedirá código de nuevo");
                LicenseStore.clear(context);
                return;
            }
            if (status < 200 || status >= 300) {
                return;
            }
            JSONObject user = new JSONObject(text);
            String plan = user.optString("plan", "free");
            long expiresAt = parseDate(user.opt("planExpiresAt"));
            if (plan.isEmpty() || "free".equalsIgnoreCase(plan)) {
                LicenseStore.clear(context);
            } else {
                LicenseStore.save(context, token, plan, expiresAt);
            }
        } catch (Exception error) {
            Log.i(TAG, "sin revalidar (offline): " + error);
        }
    }

    private static HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** planExpiresAt llega como ISO-8601 desde Postgres; 0 = sin caducidad. */
    static long parseDate(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return 0L;
        }
        if (text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        String limpio = text.replace("Z", "").replaceAll("[+-]\\d{2}:?\\d{2}$", "");
        String[] patrones = {"yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
        for (String patron : patrones) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(patron, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(limpio);
                if (date != null) {
                    return date.getTime();
                }
            } catch (Exception ignored) {
                // siguiente patrón
            }
        }
        return 0L;
    }
}
