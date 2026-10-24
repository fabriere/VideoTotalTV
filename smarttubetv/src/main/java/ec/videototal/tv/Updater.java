package ec.videototal.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Actualizaciones desde los releases de GitHub del repositorio de VideoTotal TV.
 *
 * Se compara la versión, se descarga el APK y se abre el instalador de Android:
 * el usuario solo confirma. Android no permite instalar sin ese toque.
 */
final class Updater {

    private static final String TAG = "VT-Actualizacion";
    private static final String RELEASES_API =
            "https://api.github.com/repos/fabriere/VideoTotalTV/releases/latest";
    private static final int TIMEOUT_MS = 20000;

    private static boolean checkedThisLaunch = false;

    private Updater() {
    }

    private static final class Info {
        String version;
        String url;
        long size;
    }

    static void checkAndPrompt(Activity activity, boolean manual) {
        if (checkedThisLaunch && !manual) {
            return;
        }
        checkedThisLaunch = true;

        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Info info = null;
            boolean fallo = false;
            try {
                info = consultar(activity);
            } catch (Exception error) {
                Log.w(TAG, "no se pudo comprobar: " + error);
                fallo = true;
            }

            final Info encontrada = info;
            final boolean huboFallo = fallo;
            ui.post(() -> {
                if (huboFallo) {
                    if (manual) {
                        aviso(activity, "No se pudo comprobar la actualización. Revisa la conexión.");
                    }
                    return;
                }
                if (encontrada == null) {
                    if (manual) {
                        aviso(activity, "Ya tienes la última versión (" + versionInstalada(activity) + ").");
                    }
                    return;
                }
                preguntar(activity, encontrada);
            });
        }).start();
    }

    private static Info consultar(Activity activity) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }
        String text = leer(connection.getInputStream());
        connection.disconnect();

        JSONObject release = new JSONObject(text);
        String version = release.optString("tag_name", "").trim();
        JSONArray assets = release.optJSONArray("assets");
        if (version.isEmpty() || assets == null) {
            return null;
        }
        JSONObject apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                apk = asset;
                break;
            }
        }
        if (apk == null || comparar(version, versionInstalada(activity)) <= 0) {
            return null;
        }
        Info info = new Info();
        info.version = version;
        info.url = apk.optString("browser_download_url", "");
        info.size = apk.optLong("size", 0L);
        return info.url.isEmpty() ? null : info;
    }

    private static void preguntar(Activity activity, Info info) {
        int mb = (int) Math.max(1, info.size / (1024 * 1024));
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("VideoTotal TV")
                .setMessage("Hay una versión nueva: " + info.version + " (" + mb
                        + " MB). ¿Descargar e instalar?")
                .setPositiveButton("Descargar e instalar", (dialog, which) -> descargar(activity, info))
                .setNegativeButton("Ahora no", null)
                .show();
    }

    private static void descargar(Activity activity, Info info) {
        Handler ui = new Handler(Looper.getMainLooper());
        AlertDialog progreso = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("VideoTotal TV")
                .setMessage("Descargando… 0%")
                .setCancelable(false)
                .create();
        progreso.show();

        new Thread(() -> {
            try {
                File carpeta = new File(activity.getExternalFilesDir(null), "updates");
                if (!carpeta.exists() && !carpeta.mkdirs()) {
                    throw new IllegalStateException("no se pudo crear la carpeta");
                }
                File destino = new File(carpeta, "videototal-tv.apk");
                boolean completo = destino.exists() && info.size > 0 && destino.length() == info.size;

                if (!completo) {
                    HttpURLConnection connection = (HttpURLConnection) new URL(info.url).openConnection();
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);
                    connection.setInstanceFollowRedirects(true);
                    long total = connection.getContentLengthLong() > 0
                            ? connection.getContentLengthLong() : info.size;
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(destino)) {
                        byte[] buffer = new byte[64 * 1024];
                        long descargado = 0;
                        int leido;
                        int ultimo = -1;
                        while ((leido = in.read(buffer)) > 0) {
                            out.write(buffer, 0, leido);
                            descargado += leido;
                            if (total > 0) {
                                int pct = (int) (descargado * 100 / total);
                                if (pct >= ultimo + 10) {
                                    ultimo = pct;
                                    final int mostrar = pct;
                                    ui.post(() -> progreso.setMessage("Descargando… " + mostrar + "%"));
                                }
                            }
                        }
                    } finally {
                        connection.disconnect();
                    }
                }

                ui.post(() -> {
                    progreso.setMessage("Abriendo el instalador…");
                    instalar(activity, destino);
                    new Handler(Looper.getMainLooper()).postDelayed(progreso::dismiss, 1500);
                });
            } catch (Exception error) {
                Log.w(TAG, "descarga: " + error);
                ui.post(() -> {
                    progreso.dismiss();
                    aviso(activity, "No se pudo descargar la actualización.");
                });
            }
        }).start();
    }

    private static void instalar(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception error) {
            Log.w(TAG, "instalador: " + error);
            try {
                Intent ajustes = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                ajustes.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(ajustes);
            } catch (Exception sinAjustes) {
                aviso(activity, "No se pudo abrir el instalador de Android.");
            }
        }
    }

    private static void aviso(Activity activity, String texto) {
        new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("VideoTotal TV")
                .setMessage(texto)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String versionInstalada(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "0.0.0" : info.versionName;
        } catch (Exception error) {
            return "0.0.0";
        }
    }

    /** 1.0.10 > 1.0.9 (comparación numérica, no de texto). */
    static int comparar(String a, String b) {
        String[] partesA = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] partesB = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(partesA.length, partesB.length); i++) {
            int va = i < partesA.length && !partesA[i].isEmpty() ? Integer.parseInt(partesA[i]) : 0;
            int vb = i < partesB.length && !partesB[i].isEmpty() ? Integer.parseInt(partesB[i]) : 0;
            if (va != vb) {
                return va - vb;
            }
        }
        return 0;
    }

    private static String leer(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
