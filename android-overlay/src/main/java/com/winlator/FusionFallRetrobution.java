// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;

import androidx.core.content.FileProvider;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * FusionFall Retrobution Android v0.5.1 Beta - Updater Validation.
 *
 * WebView2/Tauri are deliberately not part of the launch chain. Android performs
 * Retrobution API authentication, retrieves the current build manifest, and then
 * starts ffrunner.exe inside the embedded Winlator/Wine runtime.
 *
 * Session tokens and cookies are kept only in memory. When the user explicitly
 * enables automatic login, the password is encrypted with Android Keystore and
 * stored only inside this app's private preferences.
 */
public final class FusionFallRetrobution {
    private static final String TAG = "FusionFallRetrobution";
    private static final String CONTAINER_NAME = "FusionFall Retrobution";
    private static final String API_HOST = "api.ffretrobution.net";
    private static final String API_BASE = "https://" + API_HOST;
    private static final String CREDENTIAL_KEY_ALIAS = "fusionfall_retrobution_login_v1";
    private static final String PREF_USERNAME = "username";
    private static final String PREF_REMEMBER_LOGIN = "remember_login";
    private static final String PREF_PASSWORD_BLOB = "password_blob";
    private static final String PREF_LANGUAGE = "ui_language";
    private static final String PREF_UPDATE_CHANNEL = "update_channel";
    public static final String APP_VERSION = "0.5.1-beta";
    public static final int APP_VERSION_CODE = 501;
    private static final String RELEASES_API =
            "https://api.github.com/repos/rsigristc/OpenFusion_Android/releases";
    private static final String PROJECT_URL = "https://github.com/rsigristc/OpenFusion_Android";
    private static final String OPENFUSION_PORTABLE_URL =
            "https://github.com/OpenFusionProject/OpenFusionLauncher/releases/latest/download/OpenFusionLauncher-Windows-Portable.zip";

    private static final long ROOTFS_POLL_MS = 1500L;
    private static final long ROOTFS_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    // Explicit high-contrast launcher palette. Do not inherit Winlator/System theme
    // colors because the host can use a dark text appearance on a light AlertDialog.
    private static final int UI_BG = Color.rgb(255, 255, 255);
    private static final int UI_TEXT = Color.rgb(24, 31, 42);
    private static final int UI_SECONDARY = Color.rgb(71, 85, 105);
    private static final int UI_HINT = Color.rgb(100, 116, 139);
    private static final int UI_ACCENT = Color.rgb(0, 122, 204);
    private static final int UI_ERROR = Color.rgb(185, 28, 28);

    private FusionFallRetrobution() {}

    public static void startWhenReady(MainActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!STARTED.compareAndSet(false, true)) return;
        waitForRootFS(activity, System.currentTimeMillis());
    }

    private static void waitForRootFS(MainActivity activity, long startedAt) {
        if (activity.isFinishing()) return;
        RootFS rootFS = RootFS.find(activity);
        if (rootFS.isValid() && rootFS.getVersion() >= RootFSInstaller.LATEST_VERSION) {
            bootstrap(activity);
            return;
        }

        if (System.currentTimeMillis() - startedAt > ROOTFS_TIMEOUT_MS) {
            fail(activity, "No se pudo preparar el entorno Wine.", null);
            STARTED.set(false);
            return;
        }
        MAIN.postDelayed(() -> waitForRootFS(activity, startedAt), ROOTFS_POLL_MS);
    }

    private static void bootstrap(MainActivity activity) {
        try {
            ContainerManager manager = new ContainerManager(activity);
            Container existing = findContainer(manager.getContainers());
            if (existing != null) {
                applyLaunchSettings(activity, existing);
                prepareRuntimeAsync(activity, existing);
                return;
            }

            show(activity, "Preparando el contenedor de Retrobution…");
            JSONObject data = new JSONObject();
            FusionFallMobileControls.LaunchConfig launchConfig = FusionFallMobileControls.getLaunchConfig(activity);
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", launchConfig.screenSize());
            data.put("envVars", Container.DEFAULT_ENV_VARS + " MESA_EXTENSION_MAX_YEAR=2003");
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("extraData", new JSONObject());

            manager.createContainerAsync(data, container -> {
                if (container == null) {
                    fail(activity, "No se pudo crear el contenedor de Retrobution.", null);
                    STARTED.set(false);
                    return;
                }
                applyLaunchSettings(activity, container);
                prepareRuntimeAsync(activity, container);
            });
        }
        catch (Exception e) {
            fail(activity, "Error creando el contenedor de Retrobution.", e);
            STARTED.set(false);
        }
    }

    private static void applyLaunchSettings(MainActivity activity, Container container) {
        try {
            FusionFallMobileControls.LaunchConfig config = FusionFallMobileControls.getLaunchConfig(activity);
            boolean changed = false;
            if (!config.screenSize().equals(container.getScreenSize())) {
                container.setScreenSize(config.screenSize());
                changed = true;
            }

            // Winlator 11.x exposes HUD Mode, but keep this patch resilient to
            // minor source changes by setting it reflectively. Ordinal 0 is
            // DISABLED and ordinal 1 is SIMPLE in the audited snapshot.
            boolean showHud = activity.getSharedPreferences("fusionfall_retrobution", 0)
                    .getBoolean("show_performance_hud", false);
            try {
                java.lang.reflect.Method setter;
                try {
                    setter = container.getClass().getMethod("setHUDMode", byte.class);
                    setter.invoke(container, (byte)(showHud ? 1 : 0));
                }
                catch (NoSuchMethodException e) {
                    setter = container.getClass().getMethod("setHUDMode", int.class);
                    setter.invoke(container, showHud ? 1 : 0);
                }
                changed = true;
            }
            catch (Exception hudError) {
                Log.w(TAG, "Winlator HUD mode is not available in this snapshot", hudError);
            }

            if (changed) container.saveData();
        }
        catch (Exception e) {
            Log.w(TAG, "Could not apply FusionFall mobile launch settings", e);
        }
    }

    private static Container findContainer(ArrayList<Container> containers) {
        for (Container c : containers) {
            if (CONTAINER_NAME.equals(c.getName()) || "FusionFall".equals(c.getName())) return c;
        }
        return null;
    }

    private static void prepareRuntimeAsync(MainActivity activity, Container container) {
        EXECUTOR.execute(() -> {
            try {
                File installDir = new File(container.getRootDir(), ".wine/drive_c/OpenFusionRuntime");
                File ffrunner = findNamedFile(installDir, "ffrunner.exe");

                if (ffrunner == null) {
                    show(activity, "Descargando runtime de OpenFusion…");
                    File tempDir = new File(activity.getCacheDir(), "openfusion-runtime-poc3");
                    deleteRecursive(tempDir);
                    if (!tempDir.mkdirs() && !tempDir.isDirectory()) {
                        throw new IOException("Could not create temporary directory");
                    }

                    File zip = new File(tempDir, "OpenFusionLauncher-Windows-Portable.zip");
                    download(OPENFUSION_PORTABLE_URL, zip);
                    File extracted = new File(tempDir, "extracted");
                    if (!extracted.mkdirs() && !extracted.isDirectory()) {
                        throw new IOException("Could not create extraction directory");
                    }
                    unzip(zip, extracted);

                    File sourceRunner = findNamedFile(extracted, "ffrunner.exe");
                    if (sourceRunner == null) {
                        throw new IOException("ffrunner.exe was not found in the official OpenFusion portable archive");
                    }

                    File payloadRoot = sourceRunner.getParentFile();
                    deleteRecursive(installDir);
                    if (!copyRecursive(payloadRoot, installDir)) {
                        throw new IOException("Could not copy the OpenFusion runtime into Wine C: drive");
                    }
                    ffrunner = findNamedFile(installDir, "ffrunner.exe");
                    if (ffrunner == null) throw new IOException("ffrunner.exe missing after runtime installation");

                    writeRuntimeMetadata(installDir, zip);
                    deleteRecursive(tempDir);
                }

                final File launchRunner = ffrunner;
                MAIN.post(() -> showLogin(activity, container, launchRunner));
            }
            catch (Exception e) {
                fail(activity, "No se pudo preparar el runtime de FusionFall.", e);
                STARTED.set(false);
            }
        });
    }

    private static boolean isEnglish(Context context) {
        return "en".equals(context.getSharedPreferences("fusionfall_retrobution", 0)
                .getString(PREF_LANGUAGE, "es"));
    }

    private static String tr(Context context, String es, String en) {
        return isEnglish(context) ? en : es;
    }

    private static void showLogin(MainActivity activity, Container container, File ffrunner) {
        if (activity.isFinishing()) return;

        try {
            if (activity.getSupportActionBar() != null) activity.getSupportActionBar().hide();
        }
        catch (Exception ignored) {}

        int pad = (int)(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, pad / 4);
        layout.setBackgroundColor(UI_BG);
        // POC4.6.3: do not make the parent panel steal touch focus from EditTexts.
        // The dialog window itself keeps the IME hidden until a field is touched.
        layout.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        TextView title = new TextView(activity);
        title.setText("FusionFall · Retrobution");
        title.setTextSize(24f);
        title.setTextColor(UI_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView server = new TextView(activity);
        server.setText(API_HOST + "\n" + tr(activity, "FusionFall Retrobution Android v0.5.1 Beta", "FusionFall Retrobution Android v0.5.1 Beta"));
        server.setTextSize(14f);
        server.setTextColor(UI_SECONDARY);
        server.setPadding(0, pad / 4, 0, pad / 2);
        layout.addView(server);

        EditText username = new EditText(activity);
        username.setHint(tr(activity, "Usuario", "Username"));
        username.setTextColor(UI_TEXT);
        username.setHintTextColor(UI_HINT);
        username.setTextSize(17f);
        username.setSingleLine(true);
        username.setSelectAllOnFocus(false);
        username.setEnabled(true);
        username.setClickable(true);
        username.setFocusable(true);
        username.setFocusableInTouchMode(true);
        username.setContentDescription(tr(activity, "Usuario de Retrobution", "Retrobution username"));
        SharedPreferences prefs = activity.getSharedPreferences("fusionfall_retrobution", 0);
        username.setText(prefs.getString(PREF_USERNAME, ""));
        layout.addView(username, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText password = new EditText(activity);
        password.setHint(tr(activity, "Contraseña", "Password"));
        password.setTextColor(UI_TEXT);
        password.setHintTextColor(UI_HINT);
        password.setTextSize(17f);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setEnabled(true);
        password.setClickable(true);
        password.setFocusable(true);
        password.setFocusableInTouchMode(true);
        password.setContentDescription(tr(activity, "Contraseña de Retrobution", "Retrobution password"));
        layout.addView(password, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox rememberLogin = new CheckBox(activity);
        rememberLogin.setText(tr(activity, "Recordar contraseña e iniciar sesión automáticamente", "Remember password and sign in automatically"));
        rememberLogin.setTextColor(UI_TEXT);
        rememberLogin.setTextSize(14f);
        String savedPassword = loadSavedPassword(activity);
        boolean hasSavedLogin = prefs.getBoolean(PREF_REMEMBER_LOGIN, false) && savedPassword != null;
        rememberLogin.setChecked(hasSavedLogin);
        if (hasSavedLogin) password.setText(savedPassword);
        rememberLogin.setContentDescription(tr(activity, "Recordar contraseña y activar inicio de sesión automático", "Remember password and enable automatic sign-in"));
        layout.addView(rememberLogin);
        rememberLogin.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!checked) clearSavedCredentials(activity, true);
        });

        TextView status = new TextView(activity);
        status.setText(tr(activity, "Listo para conectar con Retrobution.", "Ready to connect to Retrobution."));
        status.setTextColor(UI_SECONDARY);
        status.setTextSize(14f);
        status.setPadding(0, pad / 2, 0, pad / 2);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        layout.addView(status);

        // These flags live on the UI thread. Auto-login must never make the form
        // appear frozen: fields remain editable and every tap gets visible feedback.
        final boolean[] loginInFlight = {false};
        final boolean[] suppressAutoLogin = {false};
        View.OnTouchListener manualInteraction = (view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                suppressAutoLogin[0] = true;
            }
            return false;
        };
        username.setOnTouchListener(manualInteraction);
        password.setOnTouchListener(manualInteraction);
        rememberLogin.setOnTouchListener(manualInteraction);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(layout)
                .setPositiveButton(tr(activity, "Jugar", "Play"), null)
                .setNeutralButton(tr(activity, "Ajustes", "Settings"), null)
                .setNegativeButton(tr(activity, "Salir", "Exit"), (d, which) -> activity.finish())
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(UI_BG));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }

            Button settings = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button exit = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button play = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            settings.setTextColor(UI_ACCENT);
            exit.setTextColor(UI_ACCENT);
            play.setTextColor(UI_ACCENT);

            settings.setOnClickListener(v -> {
                suppressAutoLogin[0] = true;
                hideKeyboard(activity, password);
                FusionFallMobileControls.showSettings(activity, () -> {
                    FusionFallMobileControls.LaunchConfig config = FusionFallMobileControls.getLaunchConfig(activity);
                    username.setHint(tr(activity, "Usuario", "Username"));
                    password.setHint(tr(activity, "Contraseña", "Password"));
                    rememberLogin.setText(tr(activity, "Recordar contraseña e iniciar sesión automáticamente", "Remember password and sign in automatically"));
                    server.setText(API_HOST + "\nFusionFall Retrobution Android v0.5.1 Beta");
                    settings.setText(tr(activity, "AJUSTES", "SETTINGS"));
                    exit.setText(tr(activity, "SALIR", "EXIT"));
                    if (!loginInFlight[0]) play.setText(tr(activity, "JUGAR", "PLAY"));
                    status.setTextColor(UI_SECONDARY);
                    status.setText(tr(activity, "Perfil: ", "Profile: ") + config.profile + " · " + config.screenSize() +
                            (config.fpsCap > 0 ? " · " + config.fpsCap + " FPS" : tr(activity, " · FPS actual", " · current FPS")));
                    applyLaunchSettings(activity, container);
                });
            });

            play.setEnabled(true);
            play.setClickable(true);
            play.setOnClickListener(v -> {
                suppressAutoLogin[0] = true;
                if (loginInFlight[0]) {
                    status.setTextColor(UI_ACCENT);
                    status.setText(tr(activity, "Inicio de sesión en curso… espera unos segundos.", "Sign-in already in progress… please wait a few seconds."));
                    return;
                }

                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                final boolean remember = rememberLogin.isChecked();
                if (user.isEmpty() || pass.isEmpty()) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "Ingresa usuario y contraseña.", "Enter username and password."));
                    return;
                }
                hideKeyboard(activity, password);
                loginInFlight[0] = true;
                play.setText(tr(activity, "CONECTANDO…", "CONNECTING…"));
                // Do not disable the EditTexts. On some Samsung/Android builds the
                // old auto-login path looked like a dead touch layer for up to 60 s.
                username.setEnabled(true);
                password.setEnabled(true);
                status.setTextColor(UI_ACCENT);
                status.setText(tr(activity, "Autenticando con Retrobution…", "Authenticating with Retrobution…"));
                prefs.edit().putString(PREF_USERNAME, user).apply();

                EXECUTOR.execute(() -> {
                    try {
                        LaunchData data = authenticateAndPrepare(activity, user, pass, container);
                        if (remember) saveCredentials(activity, user, pass);
                        else clearSavedCredentials(activity, true);
                        MAIN.post(() -> {
                            if (activity.isFinishing()) return;
                            status.setTextColor(UI_ACCENT);
                            status.setText(tr(activity, "Iniciando FusionFall…", "Starting FusionFall…"));
                            dialog.dismiss();
                            launch(activity, container, ffrunner, data);
                        });
                    }
                    catch (Exception e) {
                        Log.e(TAG, "Retrobution login/launch preparation failed", e);
                        MAIN.post(() -> {
                            loginInFlight[0] = false;
                            play.setEnabled(true);
                            play.setClickable(true);
                            play.setText(tr(activity, "JUGAR", "PLAY"));
                            username.setEnabled(true);
                            password.setEnabled(true);
                            status.setTextColor(UI_ERROR);
                            status.setText(humanError(activity, e));
                        });
                    }
                });
            });

            if (hasSavedLogin && !username.getText().toString().trim().isEmpty() &&
                    !password.getText().toString().isEmpty()) {
                status.setTextColor(UI_ACCENT);
                status.setText(tr(activity, "Credenciales protegidas cargadas · inicio automático en 1 s…", "Protected credentials loaded · automatic sign-in in 1 s…"));
                MAIN.postDelayed(() -> {
                    if (dialog.isShowing() && !suppressAutoLogin[0] && !loginInFlight[0]) {
                        play.performClick();
                    }
                    else if (dialog.isShowing() && suppressAutoLogin[0] && !loginInFlight[0]) {
                        status.setTextColor(UI_SECONDARY);
                        status.setText(tr(activity, "Inicio automático cancelado por interacción manual.", "Automatic sign-in cancelled by manual interaction."));
                    }
                }, 1000L);
            }
        });
        dialog.show();
        MAIN.postDelayed(() -> checkForUpdates(activity, false), 1800L);
    }

    private static void saveCredentials(Context context, String username, String password) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            SecretKey key;
            if (keyStore.containsAlias(CREDENTIAL_KEY_ALIAS)) {
                key = ((KeyStore.SecretKeyEntry)keyStore.getEntry(CREDENTIAL_KEY_ALIAS, null)).getSecretKey();
            }
            else {
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(
                        CREDENTIAL_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                key = generator.generateKey();
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            String blob = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP);
            context.getSharedPreferences("fusionfall_retrobution", 0).edit()
                    .putString(PREF_USERNAME, username)
                    .putString(PREF_PASSWORD_BLOB, blob)
                    .putBoolean(PREF_REMEMBER_LOGIN, true)
                    .apply();
        }
        catch (Exception e) {
            Log.w(TAG, "Could not securely store Retrobution credentials", e);
        }
    }

    private static String loadSavedPassword(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("fusionfall_retrobution", 0);
            if (!prefs.getBoolean(PREF_REMEMBER_LOGIN, false)) return null;
            String blob = prefs.getString(PREF_PASSWORD_BLOB, null);
            if (blob == null || !blob.contains(".")) return null;
            String[] parts = blob.split("\\.", 2);
            if (parts.length != 2) return null;

            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(CREDENTIAL_KEY_ALIAS)) return null;
            SecretKey key = ((KeyStore.SecretKeyEntry)keyStore.getEntry(CREDENTIAL_KEY_ALIAS, null)).getSecretKey();

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            Log.w(TAG, "Could not load saved Retrobution credentials", e);
            clearSavedCredentials(context, true);
            return null;
        }
    }

    private static void clearSavedCredentials(Context context, boolean keepUsername) {
        SharedPreferences prefs = context.getSharedPreferences("fusionfall_retrobution", 0);
        SharedPreferences.Editor editor = prefs.edit()
                .remove(PREF_PASSWORD_BLOB)
                .putBoolean(PREF_REMEMBER_LOGIN, false);
        if (!keepUsername) editor.remove(PREF_USERNAME);
        editor.apply();
    }

    private static void hideKeyboard(MainActivity activity, View view) {
        try {
            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && view != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            if (view != null) view.clearFocus();
        }
        catch (Exception ignored) {}
    }

    private static LaunchData authenticateAndPrepare(MainActivity activity, String username, String password, Container container) throws Exception {
        JSONObject info = getJson(API_BASE + "/");
        String serverName = info.optString("server_name", "Retrobution");
        String loginAddress = info.getString("login_address");
        boolean customLoadingScreen = info.optBoolean("custom_loading_screen", false);

        String versionUuid = null;
        JSONArray versions = info.optJSONArray("game_versions");
        if (versions != null && versions.length() > 0) versionUuid = versions.getString(0);
        if (versionUuid == null || versionUuid.isEmpty()) versionUuid = info.optString("game_version", "");
        if (versionUuid.isEmpty()) throw new IOException("Retrobution no informó una versión de juego activa");

        JSONObject version;
        try {
            version = getJson(API_BASE + "/versions/" + versionUuid);
        }
        catch (IOException first) {
            version = getJson(API_BASE + "/versions/" + versionUuid + ".json");
        }

        String assetUrl = version.getString("asset_url");
        while (assetUrl.endsWith("/")) assetUrl = assetUrl.substring(0, assetUrl.length() - 1);
        String mainUrl = version.optString("main_file_url", "");
        if (mainUrl.isEmpty()) mainUrl = assetUrl + "/main.unity3d";

        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        String refreshToken = request("POST", API_BASE + "/auth", auth.toString(), null, "application/json").trim();
        if (refreshToken.isEmpty()) throw new IOException("Retrobution devolvió un token de autenticación vacío");

        JSONObject session = new JSONObject(request("POST", API_BASE + "/auth/session", null, refreshToken, null));
        String sessionToken = session.getString("session_token");

        JSONObject cookieJson = new JSONObject(request("POST", API_BASE + "/cookie", null, sessionToken, null));
        String cookie = cookieJson.getString("cookie");
        String cookieUser = cookieJson.optString("username", session.optString("username", username));
        long expires = cookieJson.optLong("expires", Long.MAX_VALUE);
        if (expires < System.currentTimeMillis() / 1000L) {
            throw new IOException("La cookie de Retrobution expiró; revisa la hora del dispositivo");
        }

        String resolvedLogin = resolveAddress(loginAddress);
        File cache = new File(container.getRootDir(), ".wine/drive_c/OpenFusionCache/" + versionUuid);
        if (!cache.mkdirs() && !cache.isDirectory()) {
            throw new IOException("No se pudo crear el cache local de FusionFall");
        }

        FusionFallMobileControls.LaunchConfig launchConfig = FusionFallMobileControls.getLaunchConfig(activity);
        if (!launchConfig.screenSize().equals(container.getScreenSize())) {
            container.setScreenSize(launchConfig.screenSize());
            container.saveData();
        }

        StringBuilder args = new StringBuilder();
        appendArg(args, "-m", mainUrl);
        appendArg(args, "-a", resolvedLogin);
        appendArg(args, "--asseturl", assetUrl + "/");
        appendArg(args, "-l", "C:\\OpenFusionRuntime\\ffrunner.log");
        appendArg(args, "-n", serverName);
        appendArg(args, "-u", cookieUser);
        appendArg(args, "-t", cookie);
        appendArg(args, "-e", API_HOST);
        appendArg(args, "--width", Integer.toString(launchConfig.width));
        appendArg(args, "--height", Integer.toString(launchConfig.height));
        if (customLoadingScreen) args.append(" --loader-images");

        String envVars = "UNITY_FF_CACHE_DIR=C:\\OpenFusionCache\\" + versionUuid;
        if (launchConfig.fpsCap > 0) envVars += " UNITY_FF_FPS_CAP=" + launchConfig.fpsCap;
        return new LaunchData(args.toString().trim(), envVars, versionUuid);
    }

    private static String resolveAddress(String address) throws Exception {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) return address;
        String host = address.substring(0, colon);
        String port = address.substring(colon + 1);
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return address;
        return InetAddress.getByName(host).getHostAddress() + ":" + port;
    }

    private static void appendArg(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(key).append(' ').append(quote(value));
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static void launch(MainActivity activity, Container container, File ffrunner, LaunchData data) {
        try {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("exec_path", ffrunner.getAbsolutePath());
            intent.putExtra("exec_args", data.execArgs);
            intent.putExtra("env_vars", data.envVars);
            intent.putExtra("fusionfall_mobile_controls", true);
            intent.putExtra("fusionfall_force_fullscreen", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(intent);
            STARTED.set(false);
        }
        catch (Exception e) {
            fail(activity, "No se pudo abrir ffrunner.", e);
            STARTED.set(false);
        }
    }

    private static final class UpdateInfo {
        final String tag;
        final String name;
        final String notes;
        final String pageUrl;
        final String apkUrl;
        final String checksumUrl;

        UpdateInfo(String tag, String name, String notes, String pageUrl,
                   String apkUrl, String checksumUrl) {
            this.tag = tag;
            this.name = name;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
        }
    }

    public static void checkForUpdates(Activity activity, boolean userInitiated) {
        if (activity == null || activity.isFinishing()) return;
        if (userInitiated) Toast.makeText(activity,
                tr(activity, "Buscando actualizaciones…", "Checking for updates…"),
                Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                String channel = activity.getSharedPreferences("fusionfall_retrobution", 0)
                        .getString(PREF_UPDATE_CHANNEL, "beta");
                UpdateInfo update = fetchLatestRelease(channel);
                MAIN.post(() -> {
                    if (activity.isFinishing()) return;
                    if (update == null || sameVersion(update.tag, APP_VERSION)) {
                        if (userInitiated) new AlertDialog.Builder(activity)
                                .setTitle(tr(activity, "Aplicación actualizada", "App is up to date"))
                                .setMessage("v" + APP_VERSION + " · " + channel.toUpperCase(Locale.US))
                                .setPositiveButton(tr(activity, "Aceptar", "OK"), null)
                                .show();
                        return;
                    }
                    showUpdateDialog(activity, update);
                });
            }
            catch (Exception error) {
                Log.w(TAG, "Update check failed", error);
                if (userInitiated) MAIN.post(() -> Toast.makeText(activity,
                        tr(activity, "No se pudo consultar GitHub Releases.",
                                "Could not check GitHub Releases."), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static UpdateInfo fetchLatestRelease(String channel) throws Exception {
        JSONArray releases = new JSONArray(request("GET", RELEASES_API, null, null, null));
        boolean beta = !"stable".equals(channel);
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false)) continue;
            if (!beta && release.optBoolean("prerelease", false)) continue;
            String apkUrl = null;
            String checksumUrl = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String assetName = asset.optString("name", "").toLowerCase(Locale.US);
                String assetUrl = asset.optString("browser_download_url", "");
                if (assetName.endsWith(".apk")) apkUrl = assetUrl;
                else if (assetName.endsWith(".apk.sha256")) checksumUrl = assetUrl;
            }
            if (apkUrl == null || checksumUrl == null) continue;
            return new UpdateInfo(
                    release.optString("tag_name", ""),
                    release.optString("name", release.optString("tag_name", "Update")),
                    release.optString("body", ""),
                    release.optString("html_url", PROJECT_URL + "/releases"),
                    apkUrl, checksumUrl);
        }
        return null;
    }

    private static boolean sameVersion(String tag, String version) {
        if (tag == null) return false;
        String clean = tag.trim().toLowerCase(Locale.US);
        if (clean.startsWith("v")) clean = clean.substring(1);
        return clean.equals(version.toLowerCase(Locale.US));
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo update) {
        String notes = update.notes == null || update.notes.trim().isEmpty() ?
                tr(activity, "Nueva versión disponible.", "A new version is available.") : update.notes.trim();
        new AlertDialog.Builder(activity)
                .setTitle(update.name)
                .setMessage(tr(activity, "Instalada: v", "Installed: v") + APP_VERSION +
                        "\n" + tr(activity, "Disponible: ", "Available: ") + update.tag + "\n\n" + notes)
                .setNegativeButton(tr(activity, "Después", "Later"), null)
                .setNeutralButton(tr(activity, "Ver en GitHub", "View on GitHub"),
                        (dialog, which) -> openUrl(activity, update.pageUrl))
                .setPositiveButton(tr(activity, "Descargar y verificar", "Download and verify"),
                        (dialog, which) -> downloadAndInstall(activity, update))
                .show();
    }

    private static void downloadAndInstall(Activity activity, UpdateInfo update) {
        UpdateProgress progress = showUpdateProgress(activity, update);
        EXECUTOR.execute(() -> {
            try {
                enforceHttps(update.apkUrl);
                enforceHttps(update.checksumUrl);
                String checksumText = request("GET", update.checksumUrl, null, null, "text/plain").trim();
                String expected = checksumText.split("\\s+", 2)[0].toLowerCase(Locale.US);
                if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 manifest");
                File directory = new File(activity.getCacheDir(), "fusionfall-updates");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create update cache");
                File apk = new File(directory, "FusionFall-Retrobution-" + update.tag + ".apk");
                download(update.apkUrl, apk, (downloaded, total) ->
                        MAIN.post(() -> progress.setDownload(downloaded, total)));
                MAIN.post(progress::setVerifying);
                String actual = sha256(apk).toLowerCase(Locale.US);
                if (!expected.equals(actual)) {
                    apk.delete();
                    throw new IOException("SHA-256 mismatch");
                }
                MAIN.post(() -> {
                    progress.dismiss();
                    installVerifiedApk(activity, apk);
                });
            }
            catch (Exception error) {
                Log.e(TAG, "Verified update download failed", error);
                MAIN.post(() -> {
                    progress.dismiss();
                    Toast.makeText(activity,
                            tr(activity, "La actualización no superó la descarga/verificación.",
                                    "The update could not be downloaded or verified."), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static final class UpdateProgress {
        final Activity activity;
        final AlertDialog dialog;
        final ProgressBar bar;
        final TextView status;

        UpdateProgress(Activity activity, AlertDialog dialog, ProgressBar bar, TextView status) {
            this.activity = activity;
            this.dialog = dialog;
            this.bar = bar;
            this.status = status;
        }

        void setDownload(long downloaded, long total) {
            if (activity.isFinishing() || !dialog.isShowing()) return;
            float downloadedMb = downloaded / (1024f * 1024f);
            if (total > 0L) {
                int percent = (int)Math.min(100L, downloaded * 100L / total);
                bar.setIndeterminate(false);
                bar.setProgress(percent);
                status.setText(String.format(Locale.US,
                        tr(activity, "%d%% · %.1f de %.1f MB", "%d%% · %.1f of %.1f MB"),
                        percent, downloadedMb, total / (1024f * 1024f)));
            }
            else {
                bar.setIndeterminate(true);
                status.setText(String.format(Locale.US,
                        tr(activity, "%.1f MB descargados", "%.1f MB downloaded"), downloadedMb));
            }
        }

        void setVerifying() {
            if (activity.isFinishing() || !dialog.isShowing()) return;
            bar.setIndeterminate(true);
            status.setText(tr(activity, "Verificando SHA-256…", "Verifying SHA-256…"));
        }

        void dismiss() {
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    private static UpdateProgress showUpdateProgress(Activity activity, UpdateInfo update) {
        int pad = Math.max(16, Math.round(20f * activity.getResources().getDisplayMetrics().density));
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        TextView status = new TextView(activity);
        status.setText(tr(activity, "Conectando con GitHub Releases…", "Connecting to GitHub Releases…"));
        status.setTextColor(UI_TEXT);
        status.setTextSize(15f);
        layout.addView(status);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = pad / 2;
        layout.addView(bar, barLp);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(tr(activity, "Descargando ", "Downloading ") + update.tag)
                .setView(layout)
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return new UpdateProgress(activity, dialog, bar, status);
    }

    private static void enforceHttps(String value) throws IOException {
        if (value == null || !value.startsWith("https://")) throw new IOException("HTTPS required");
    }

    private static void installVerifiedApk(Activity activity, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, tr(activity,
                        "Autoriza instalar actualizaciones y vuelve a pulsar Comprobar actualización.",
                        "Allow app updates, then tap Check for updates again."), Toast.LENGTH_LONG).show();
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fusionfall.files", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        }
        catch (Exception error) {
            Log.e(TAG, "Could not open Android package installer", error);
            Toast.makeText(activity, tr(activity,
                    "No se pudo abrir el instalador de Android.",
                    "Could not open the Android package installer."), Toast.LENGTH_LONG).show();
        }
    }

    public static void showAbout(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String message = "FusionFall Retrobution Android\nv" + APP_VERSION +
                " (" + APP_VERSION_CODE + ")\n\n" + tr(activity,
                "Proyecto comunitario no oficial para preservación y accesibilidad.\n\n" +
                        "Créditos: OpenFusion, Retrobution y Winlator.\n\n" +
                        "FusionFall y sus propiedades pertenecen a sus respectivos propietarios. " +
                        "Este proyecto no está afiliado ni respaldado por Cartoon Network.\n\n" +
                        "Las licencias y avisos de los proyectos base se conservan en sus repositorios oficiales.",
                "Unofficial community project for preservation and accessibility.\n\n" +
                        "Credits: OpenFusion, Retrobution and Winlator.\n\n" +
                        "FusionFall and related properties belong to their respective owners. " +
                        "This project is not affiliated with or endorsed by Cartoon Network.\n\n" +
                        "Licenses and notices for the upstream projects remain available in their official repositories.");
        new AlertDialog.Builder(activity)
                .setTitle(tr(activity, "Acerca de · Créditos · Licencias", "About · Credits · Licenses"))
                .setMessage(message)
                .setNegativeButton(tr(activity, "Cerrar", "Close"), null)
                .setPositiveButton("GitHub", (dialog, which) -> openUrl(activity, PROJECT_URL))
                .show();
    }

    private static void openUrl(Activity activity, String url) {
        try {
            enforceHttps(url);
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
        catch (Exception error) {
            Toast.makeText(activity, tr(activity, "No se pudo abrir el enlace.",
                    "Could not open the link."), Toast.LENGTH_SHORT).show();
        }
    }

    private static JSONObject getJson(String url) throws IOException {
        try {
            return new JSONObject(request("GET", url, null, null, null));
        }
        catch (org.json.JSONException e) {
            throw new IOException("Respuesta JSON inválida desde " + url, e);
        }
    }

    private static String request(String method, String url, String body, String bearer, String contentType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "FusionFall-Retrobution-Android/0.5.1-beta");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (bearer != null && !bearer.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + bearer);

        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType != null ? contentType : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = stream != null ? readAll(stream) : "";
        conn.disconnect();
        if (code < 200 || code >= 300) {
            String msg = response.trim();
            if (code == 401) msg = "Usuario o contraseña incorrectos";
            throw new IOException(msg.isEmpty() ? "HTTP " + code : msg);
        }
        return response;
    }

    private static String readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String humanError(Context context, Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        if (isEnglish(context)) {
            if ("Usuario o contraseña incorrectos".equals(m)) m = "Incorrect username or password";
            return "Could not connect: " + m;
        }
        return "No se pudo conectar: " + m;
    }

    private interface DownloadProgressListener {
        void onProgress(long downloaded, long total);
    }

    private static void download(String url, File destination) throws IOException {
        download(url, destination, null);
    }

    private static void download(String url, File destination, DownloadProgressListener listener) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects < 10; redirects++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "FusionFall-Retrobution-Android/0.5.1-beta");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("Redirect without Location header");
                current = new URL(current, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " descargando runtime de OpenFusion");
            }
            File parent = destination.getParentFile();
            if (parent != null) parent.mkdirs();
            long total = conn.getContentLengthLong();
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[128 * 1024];
                int n;
                long downloaded = 0L;
                long lastReport = 0L;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    downloaded += n;
                    if (listener != null && (downloaded - lastReport >= 512L * 1024L || downloaded == total)) {
                        lastReport = downloaded;
                        listener.onProgress(downloaded, total);
                    }
                }
            }
            finally {
                conn.disconnect();
            }
            return;
        }
        throw new IOException("Too many HTTP redirects");
    }

    private static void unzip(File zip, File destination) throws IOException {
        String canonicalBase = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(canonicalBase)) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                }
                else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buffer)) >= 0) bos.write(buffer, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static File findNamedFile(File root, String name) {
        if (root == null || !root.exists()) return null;
        if (root.isFile()) return root.getName().equalsIgnoreCase(name) ? root : null;
        File[] children = root.listFiles();
        if (children == null) return null;
        for (File child : children) {
            File result = findNamedFile(child, name);
            if (result != null) return result;
        }
        return null;
    }

    private static boolean copyRecursive(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children == null) return true;
            for (File child : children) {
                // Explicitly skip the Tauri launcher: POC3 never executes it.
                if (child.getName().equalsIgnoreCase("OpenFusionLauncher.exe")) continue;
                if (!copyRecursive(child, new File(destination, child.getName()))) return false;
            }
            return true;
        }
        File parent = destination.getParentFile();
        if (parent != null) parent.mkdirs();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        return true;
    }

    private static void writeRuntimeMetadata(File installDir, File zip) {
        try {
            String json = "{\n" +
                    "  \"source\": \"" + OPENFUSION_PORTABLE_URL + "\",\n" +
                    "  \"sha256\": \"" + sha256(zip) + "\",\n" +
                    "  \"launcher\": \"android-native-retrobution\"\n" +
                    "}\n";
            try (FileOutputStream fos = new FileOutputStream(new File(installDir, "fusionfall-android-runtime.json"))) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not write runtime metadata", e);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void show(MainActivity activity, String message) {
        MAIN.post(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }

    private static void fail(MainActivity activity, String message, Exception e) {
        if (e != null) Log.e(TAG, message, e);
        String detail = e != null && e.getMessage() != null ? "\n" + e.getMessage() : "";
        show(activity, message + detail);
    }

    private static final class LaunchData {
        final String execArgs;
        final String envVars;
        final String versionUuid;

        LaunchData(String execArgs, String envVars, String versionUuid) {
            this.execArgs = execArgs;
            this.envVars = envVars;
            this.versionUuid = versionUuid;
        }
    }
}
