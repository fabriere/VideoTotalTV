package ec.videototal.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Puerta de licencia de VideoTotal TV.
 *
 * Es el ÚNICO lanzador de la app (LAUNCHER + LEANBACK_LAUNCHER). Si ya hay una
 * licencia guardada y vigente, entra directo a SmartTube sin preguntar nada;
 * si no, pide el código. Todo está pensado para un control remoto: un campo,
 * un botón y el foco puesto en el campo.
 */
public class LicenseActivity extends Activity {

    /** Pantalla principal de SmartTube (el reproductor/explorador real). */
    private static final String DESTINO = "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";

    private EditText codeInput;
    private Button activateButton;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (LicenseStore.hasAccess(this)) {
            openApp();
            return;
        }

        setContentView(buildUi());
        if (LicenseStore.isExpired(this)) {
            status.setText("Tu licencia venció. Escribe un código nuevo.");
            status.setTextColor(0xFFFFB4A9);
        }
        Updater.checkAndPrompt(this, false);
    }

    private int dp(int valor) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, valor, getResources().getDisplayMetrics());
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0B1B2F"));
        int pad = dp(56);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("VideoTotal TV");
        title.setTextColor(Color.parseColor("#FEF7EA"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(dp(700), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = new TextView(this);
        intro.setText("Escribe el código que te proporcionaron para acceder y utilizar esta aplicación");
        intro.setTextColor(Color.parseColor("#CCFEF7EA"));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        intro.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(dp(700), ViewGroup.LayoutParams.WRAP_CONTENT);
        introParams.topMargin = dp(18);
        root.addView(intro, introParams);

        codeInput = new EditText(this);
        codeInput.setHint("CÓDIGO");
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        codeInput.setTextColor(Color.parseColor("#FEF7EA"));
        codeInput.setHintTextColor(0x66FEF7EA);
        codeInput.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(dp(520), dp(72));
        inputParams.topMargin = dp(32);
        root.addView(codeInput, inputParams);

        activateButton = new Button(this);
        activateButton.setText("Activar");
        activateButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        activateButton.setAllCaps(false);
        activateButton.setBackgroundColor(Color.parseColor("#CA9D33"));
        activateButton.setTextColor(Color.parseColor("#0B1B2F"));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(320), dp(68));
        buttonParams.topMargin = dp(20);
        activateButton.setOnClickListener(v -> activate());
        root.addView(activateButton, buttonParams);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#CA9D33"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(700), ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(18);
        root.addView(status, statusParams);

        Button updateButton = new Button(this);
        updateButton.setText("Buscar actualización  ·  v" + versionInstalada());
        updateButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        updateButton.setAllCaps(false);
        updateButton.setBackgroundColor(Color.TRANSPARENT);
        updateButton.setTextColor(0x99FEF7EA);
        updateButton.setOnClickListener(v -> Updater.checkAndPrompt(this, true));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(dp(360), dp(56));
        updateParams.topMargin = dp(8);
        root.addView(updateButton, updateParams);

        codeInput.requestFocus();
        return root;
    }

    private void activate() {
        String code = codeInput.getText().toString().trim();
        if (code.isEmpty()) {
            status.setTextColor(0xFFFFB4A9);
            status.setText("Escribe el código.");
            codeInput.requestFocus();
            return;
        }

        activateButton.setEnabled(false);
        status.setTextColor(Color.parseColor("#CA9D33"));
        status.setText("Comprobando…");

        new Thread(() -> {
            LicenseApi.Result result = LicenseApi.activate(getApplicationContext(), code);
            runOnUiThread(() -> {
                activateButton.setEnabled(true);
                if (result.ok && !"free".equalsIgnoreCase(result.plan)) {
                    LicenseStore.save(this, result.token, result.plan, result.expiresAt);
                    openApp();
                    return;
                }
                status.setTextColor(0xFFFFB4A9);
                status.setText("bad_code".equals(result.error)
                        ? "Ese código no es válido o ya se usó."
                        : "No se pudo conectar. Revisa la conexión a internet del televisor.");
                codeInput.requestFocus();
            });
        }).start();
    }

    private void openApp() {
        Intent intent = new Intent();
        intent.setClassName(this, DESTINO);
        startActivity(intent);
        finish();
    }

    private String versionInstalada() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
