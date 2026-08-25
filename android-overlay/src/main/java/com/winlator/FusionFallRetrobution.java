// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
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
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.InputMethodManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.EnvVars;
import com.winlator.core.GPUHelper;
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
 * OpenFusion Android v0.5.9 Beta launcher and server-profile integration.
 *
 * WebView2/Tauri are deliberately not part of the launch chain. Android performs
 * server API authentication, retrieves the current build manifest, and then
 * starts ffrunner.exe inside the embedded Winlator/Wine runtime.
 *
 * Session tokens and cookies are kept only in memory. When the user explicitly
 * enables automatic login, the password is encrypted with Android Keystore and
 * stored only inside this app's private preferences.
 */
public final class FusionFallRetrobution {
    private static final String TAG = "FusionFallRetrobution";
    private static final String PREFS = "fusionfall_retrobution";
    // Kept for in-place upgrades: changing this name would create a new Winlator container.
    private static final String CONTAINER_NAME = "FusionFall Retrobution";
    private static final String DEFAULT_SERVER_ID = "retrobution";
    private static final String DEFAULT_SERVER_NAME = "FusionFall Retrobution";
    private static final String DEFAULT_API_BASE = "https://api.ffretrobution.net";
    private static final String PREF_SERVER_PROFILE = "server_profile";
    private static final String PREF_CUSTOM_SERVER_NAME = "custom_server_name";
    private static final String PREF_CUSTOM_API_BASE = "custom_api_base";
    // Kept so existing encrypted Retrobution credentials remain readable after updating.
    private static final String CREDENTIAL_KEY_ALIAS = "fusionfall_retrobution_login_v1";
    private static final String PREF_USERNAME = "username";
    private static final String PREF_REMEMBER_LOGIN = "remember_login";
    private static final String PREF_AUTO_LOGIN = "auto_login";
    private static final String PREF_PASSWORD_BLOB = "password_blob";
    private static final String PREF_PENDING_UPDATE_APK = "pending_update_apk";
    private static final String PREF_LANGUAGE = "ui_language";
    private static final String PREF_UPDATE_CHANNEL = "update_channel";
    public static final String APP_VERSION = "0.5.9-beta";
    public static final int APP_VERSION_CODE = 509;
    private static final String RELEASES_API =
            "https://api.github.com/repos/rsigristc/OpenFusion_Android/releases";
    private static final String PROJECT_URL = "https://github.com/rsigristc/OpenFusion_Android";
    private static final String OPENFUSION_PORTABLE_URL =
            "https://github.com/OpenFusionProject/OpenFusionLauncher/releases/latest/download/OpenFusionLauncher-Windows-Portable.zip";

    private static final long ROOTFS_POLL_MS = 1500L;
    private static final long ROOTFS_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService STATUS_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean UPDATE_INSTALLING = new AtomicBoolean(false);

    // Explicit high contrast launcher palette. Do not inherit Winlator/System theme
    // colors because the host can use a dark text appearance on a light AlertDialog.
    private static final int UI_BG = Color.rgb(255, 255, 255);
    private static final int UI_TEXT = Color.rgb(24, 31, 42);
    private static final int UI_SECONDARY = Color.rgb(71, 85, 105);
    private static final int UI_HINT = Color.rgb(100, 116, 139);
    private static final int UI_ACCENT = Color.rgb(0, 122, 204);
    private static final int UI_ERROR = Color.rgb(185, 28, 28);

    private FusionFallRetrobution() {}

    private static final class ServerProfile {
        final String id;
        final String name;
        final String apiBase;
        final String apiEndpoint;

        ServerProfile(String id, String name, String apiBase) throws IOException {
            this.id = id;
            this.name = name;
            this.apiBase = normalizeApiBase(apiBase);
            this.apiEndpoint = new URL(this.apiBase).getAuthority();
        }

        boolean isDefault() {
            return DEFAULT_SERVER_ID.equals(id);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    private static ServerProfile serverProfile(Context context) {
        SharedPreferences preferences = prefs(context);
        if ("custom".equals(preferences.getString(PREF_SERVER_PROFILE, DEFAULT_SERVER_ID))) {
            String name = preferences.getString(PREF_CUSTOM_SERVER_NAME, "Custom OpenFusion server").trim();
            String apiBase = preferences.getString(PREF_CUSTOM_API_BASE, DEFAULT_API_BASE);
            try {
                return new ServerProfile("custom", name.isEmpty() ? "Custom OpenFusion server" : name, apiBase);
            }
            catch (IOException error) {
                Log.w(TAG, "Invalid saved custom server; using Retrobution", error);
            }
        }
        try {
            return new ServerProfile(DEFAULT_SERVER_ID, DEFAULT_SERVER_NAME, DEFAULT_API_BASE);
        }
        catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalizeApiBase(String raw) throws IOException {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        URL url;
        try {
            url = new URL(value);
        }
        catch (Exception error) {
            throw new IOException("Invalid server API URL", error);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Server API must use HTTPS");
        if (url.getHost() == null || url.getHost().trim().isEmpty()) throw new IOException("Server API host is missing");
        if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Server API URL cannot contain credentials, query parameters or fragments");
        }
        return value;
    }

    private static String credentialPreferenceKey(Context context, String baseKey) {
        ServerProfile profile = serverProfile(context);
        if (profile.isDefault()) return baseKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(profile.apiBase.getBytes(StandardCharsets.UTF_8));
            StringBuilder scope = new StringBuilder();
            for (int i = 0; i < 8; i++) scope.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
            return "server_" + scope + "_" + baseKey;
        }
        catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static String serverSummary(Context context) {
        ServerProfile profile = serverProfile(context);
        return profile.name + " · " + profile.apiBase;
    }

    public static void showServerSettings(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;
        SharedPreferences preferences = prefs(activity);
        ServerProfile current = serverProfile(activity);
        int pad = (int)(20 * activity.getResources().getDisplayMetrics().density);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, pad / 4);
        layout.setBackgroundColor(UI_BG);

        TextView help = new TextView(activity);
        help.setText(tr(activity,
                "Retrobution permanece como perfil predeterminado. Un servidor personalizado debe implementar el contrato API documentado y usar HTTPS. Solo introduce credenciales en servidores de confianza.",
                "Retrobution remains the default profile. A custom server must implement the documented API contract and use HTTPS. Only enter credentials for servers you trust."));
        help.setTextColor(UI_SECONDARY);
        help.setTextSize(13f);
        help.setPadding(0, 0, 0, pad / 2);
        layout.addView(help);

        Spinner profile = new Spinner(activity);
        String[] profiles = {"FusionFall Retrobution", tr(activity, "Servidor personalizado", "Custom server")};
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, profiles);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profile.setAdapter(profileAdapter);
        profile.setSelection(current.isDefault() ? 0 : 1);
        layout.addView(profile);

        EditText name = new EditText(activity);
        name.setHint(tr(activity, "Nombre del servidor", "Server name"));
        name.setSingleLine(true);
        name.setText(preferences.getString(PREF_CUSTOM_SERVER_NAME, ""));
        name.setTextColor(UI_TEXT);
        name.setHintTextColor(UI_HINT);
        layout.addView(name);

        EditText api = new EditText(activity);
        api.setHint("https://server.example/api");
        api.setSingleLine(true);
        api.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        api.setText(preferences.getString(PREF_CUSTOM_API_BASE, ""));
        api.setTextColor(UI_TEXT);
        api.setHintTextColor(UI_HINT);
        layout.addView(api);

        TextView error = new TextView(activity);
        error.setTextColor(UI_ERROR);
        error.setTextSize(13f);
        error.setPadding(0, pad / 4, 0, 0);
        layout.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(tr(activity, "Perfil del servidor", "Server profile"))
                .setView(layout)
                .setNeutralButton(tr(activity, "Usar Retrobution", "Use Retrobution"), null)
                .setNegativeButton(tr(activity, "Cancelar", "Cancel"), null)
                .setPositiveButton(tr(activity, "Guardar", "Save"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                preferences.edit().putString(PREF_SERVER_PROFILE, DEFAULT_SERVER_ID).apply();
                FusionFallDiagnostics.recordEvent("server profile changed · Retrobution");
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (profile.getSelectedItemPosition() == 0) {
                    preferences.edit().putString(PREF_SERVER_PROFILE, DEFAULT_SERVER_ID).apply();
                    FusionFallDiagnostics.recordEvent("server profile changed · Retrobution");
                    dialog.dismiss();
                    if (onSaved != null) onSaved.run();
                    return;
                }
                String customName = name.getText().toString().trim();
                String customApi;
                try {
                    if (customName.isEmpty()) throw new IOException(tr(activity,
                            "Escribe un nombre para el servidor", "Enter a server name"));
                    customApi = normalizeApiBase(api.getText().toString());
                }
                catch (IOException problem) {
                    error.setText(problem.getMessage());
                    return;
                }
                preferences.edit()
                        .putString(PREF_SERVER_PROFILE, "custom")
                        .putString(PREF_CUSTOM_SERVER_NAME, customName)
                        .putString(PREF_CUSTOM_API_BASE, customApi)
                        .apply();
                FusionFallDiagnostics.recordEvent("server profile changed · " + customName);
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            });
        });
        dialog.show();
    }

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

            show(activity, "Preparando OpenFusion Android…");
            JSONObject data = new JSONObject();
            FusionFallMobileControls.LaunchConfig launchConfig = FusionFallMobileControls.getLaunchConfig(activity);
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", launchConfig.screenSize());
            data.put("envVars", Container.DEFAULT_ENV_VARS);
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("extraData", new JSONObject());

            manager.createContainerAsync(data, container -> {
                if (container == null) {
                    fail(activity, "No se pudo crear el contenedor de OpenFusion Android.", null);
                    STARTED.set(false);
                    return;
                }
                applyLaunchSettings(activity, container);
                prepareRuntimeAsync(activity, container);
            });
        }
        catch (Exception e) {
            fail(activity, "Error creando el contenedor de OpenFusion Android.", e);
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

            // Route Direct3D through WineD3D and Mesa Zink on Mali and other
            // non-Adreno GPUs. Gladio renders Unity's alpha-only font atlas as
            // opaque rectangles, while Zink exposes the texture format fixups
            // WineD3D needs. Keep the established Vulkan/DXVK path on Adreno.
            if (GPUHelper.getAdrenoModelId(activity) <= 0) {
                String compatibleDrivers = GraphicsDrivers.VORTEK + "," + GraphicsDrivers.ZINK;
                if (!compatibleDrivers.equals(container.getGraphicsDriver())) {
                    container.setGraphicsDriver(compatibleDrivers);
                    changed = true;
                }
                if (!DXWrappers.WINED3D.equals(container.getDXWrapper())) {
                    container.setDXWrapper(DXWrappers.WINED3D);
                    container.setDXWrapperConfig("");
                    changed = true;
                }

                EnvVars compatibilityEnv = new EnvVars(container.getEnvVars());
                if (compatibilityEnv.has("MESA_EXTENSION_MAX_YEAR")) {
                    compatibilityEnv.remove("MESA_EXTENSION_MAX_YEAR");
                    container.setEnvVars(compatibilityEnv.toString());
                    changed = true;
                }
            }

            boolean showHud = prefs(activity)
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
        return "en".equals(prefs(context)
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
        layout.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        TextView title = new TextView(activity);
        title.setText("OpenFusion Android");
        title.setTextSize(24f);
        title.setTextColor(UI_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView server = new TextView(activity);
        server.setText(serverSummary(activity) + "\n" +
                tr(activity, "Consultando estado del servidor…", "Checking server status…") +
                "\nOpenFusion Android v" + APP_VERSION);
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
        username.setContentDescription(tr(activity, "Usuario del servidor", "Server username"));
        SharedPreferences prefs = prefs(activity);
        username.setText(prefs.getString(credentialPreferenceKey(activity, PREF_USERNAME), ""));
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
        password.setContentDescription(tr(activity, "Contraseña del servidor", "Server password"));
        layout.addView(password, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox rememberLogin = new CheckBox(activity);
        rememberLogin.setText(tr(activity, "Recordar contraseña", "Remember password"));
        rememberLogin.setTextColor(UI_TEXT);
        rememberLogin.setTextSize(14f);
        String savedPassword = loadSavedPassword(activity);
        boolean hasSavedLogin = prefs.getBoolean(credentialPreferenceKey(activity, PREF_REMEMBER_LOGIN), false) && savedPassword != null;
        rememberLogin.setChecked(hasSavedLogin);
        if (hasSavedLogin) password.setText(savedPassword);
        rememberLogin.setContentDescription(tr(activity, "Guardar la contraseña de forma protegida", "Store the password securely"));
        layout.addView(rememberLogin);

        CheckBox autoLogin = new CheckBox(activity);
        autoLogin.setText(tr(activity, "Iniciar sesión automáticamente", "Sign in automatically"));
        autoLogin.setTextColor(UI_TEXT);
        autoLogin.setTextSize(14f);
        autoLogin.setEnabled(hasSavedLogin);
        autoLogin.setChecked(hasSavedLogin && autoLoginEnabled(activity));
        autoLogin.setContentDescription(tr(activity, "Entrar automáticamente al abrir la aplicación", "Sign in automatically when the app opens"));
        layout.addView(autoLogin);
        rememberLogin.setOnCheckedChangeListener((buttonView, checked) -> {
            autoLogin.setEnabled(checked);
            if (!checked) {
                autoLogin.setChecked(false);
                clearSavedCredentials(activity, true);
            }
        });
        autoLogin.setOnCheckedChangeListener((buttonView, checked) -> prefs.edit()
                .putBoolean(credentialPreferenceKey(activity, PREF_AUTO_LOGIN), checked)
                .apply());

        LinearLayout accountActions = new LinearLayout(activity);
        accountActions.setOrientation(LinearLayout.HORIZONTAL);
        Button register = new Button(activity);
        register.setText(tr(activity, "REGISTRARSE", "REGISTER"));
        register.setTextColor(UI_ACCENT);
        register.setTextSize(12f);
        Button forgot = new Button(activity);
        forgot.setText(tr(activity, "RECUPERAR CONTRASEÑA", "FORGOT PASSWORD"));
        forgot.setTextColor(UI_ACCENT);
        forgot.setTextSize(12f);
        accountActions.addView(register, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        accountActions.addView(forgot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layout.addView(accountActions);

        TextView status = new TextView(activity);
        status.setText(tr(activity, "Listo para conectar con el servidor.", "Ready to connect to the server."));
        status.setTextColor(UI_SECONDARY);
        status.setTextSize(14f);
        status.setPadding(0, pad / 2, 0, pad / 2);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        layout.addView(status);

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
        autoLogin.setOnTouchListener(manualInteraction);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(layout);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
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
            register.setOnClickListener(v -> {
                suppressAutoLogin[0] = true;
                showRegistrationDialog(activity);
            });
            forgot.setOnClickListener(v -> {
                suppressAutoLogin[0] = true;
                showPasswordRecoveryDialog(activity);
            });
            refreshServerStatus(activity, server);

            settings.setOnClickListener(v -> {
                suppressAutoLogin[0] = true;
                hideKeyboard(activity, password);
                FusionFallMobileControls.showSettings(activity, () -> {
                    FusionFallMobileControls.LaunchConfig config = FusionFallMobileControls.getLaunchConfig(activity);
                    username.setHint(tr(activity, "Usuario", "Username"));
                    password.setHint(tr(activity, "Contraseña", "Password"));
                    rememberLogin.setText(tr(activity, "Recordar contraseña", "Remember password"));
                    autoLogin.setText(tr(activity, "Iniciar sesión automáticamente", "Sign in automatically"));
                    register.setText(tr(activity, "REGISTRARSE", "REGISTER"));
                    forgot.setText(tr(activity, "RECUPERAR CONTRASEÑA", "FORGOT PASSWORD"));
                    username.setText(prefs.getString(credentialPreferenceKey(activity, PREF_USERNAME), ""));
                    String profilePassword = loadSavedPassword(activity);
                    boolean profileRemembered = prefs.getBoolean(
                            credentialPreferenceKey(activity, PREF_REMEMBER_LOGIN), false) && profilePassword != null;
                    rememberLogin.setChecked(profileRemembered);
                    autoLogin.setEnabled(profileRemembered);
                    autoLogin.setChecked(profileRemembered && autoLoginEnabled(activity));
                    password.setText(profileRemembered ? profilePassword : "");
                    settings.setText(tr(activity, "AJUSTES", "SETTINGS"));
                    exit.setText(tr(activity, "SALIR", "EXIT"));
                    if (!loginInFlight[0]) play.setText(tr(activity, "JUGAR", "PLAY"));
                    status.setTextColor(UI_SECONDARY);
                    status.setText(tr(activity, "Perfil: ", "Profile: ") + config.profile + " · " + config.screenSize() +
                            (config.fpsCap > 0 ? " · " + config.fpsCap + " FPS" : tr(activity, " · FPS actual", " · current FPS")));
                    applyLaunchSettings(activity, container);
                    refreshServerStatus(activity, server);
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
                final boolean automatic = remember && autoLogin.isChecked();
                if (user.isEmpty() || pass.isEmpty()) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "Ingresa usuario y contraseña.", "Enter username and password."));
                    return;
                }
                hideKeyboard(activity, password);
                loginInFlight[0] = true;
                play.setText(tr(activity, "CONECTANDO…", "CONNECTING…"));
                username.setEnabled(true);
                password.setEnabled(true);
                status.setTextColor(UI_ACCENT);
                status.setText(tr(activity, "Autenticando con el servidor…", "Authenticating with the server…"));
                prefs.edit().putString(credentialPreferenceKey(activity, PREF_USERNAME), user).apply();

                EXECUTOR.execute(() -> {
                    try {
                        LaunchData data = authenticateAndPrepare(activity, user, pass, container);
                        if (remember) saveCredentials(activity, user, pass, automatic);
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
                        Log.e(TAG, "Server login/launch preparation failed", e);
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

            if (hasSavedLogin && autoLogin.isChecked() && !username.getText().toString().trim().isEmpty() &&
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

    private static void refreshServerStatus(Activity activity, TextView serverView) {
        ServerProfile profile = serverProfile(activity);
        serverView.setText(profile.name + "\n" +
                tr(activity, "Consultando estado del servidor…", "Checking server status…") +
                "\nOpenFusion Android v" + APP_VERSION);
        STATUS_EXECUTOR.execute(() -> {
            try {
                JSONObject info = getJson(profile.apiBase + "/");
                JSONObject live = getJson(profile.apiBase + "/status");
                String versionId = info.optString("game_version", "");
                JSONArray versions = info.optJSONArray("game_versions");
                if (versions != null && versions.length() > 0) versionId = versions.optString(0, versionId);
                String versionName = versionId;
                if (!versionId.isEmpty()) {
                    try {
                        versionName = getJson(profile.apiBase + "/versions/" + versionId).optString("name", versionId);
                    }
                    catch (IOException first) {
                        versionName = getJson(profile.apiBase + "/versions/" + versionId + ".json").optString("name", versionId);
                    }
                }
                final String displayName = info.optString("server_name", profile.name);
                final String displayVersion = versionName.isEmpty() ? tr(activity, "No informada", "Not reported") : versionName;
                final int players = live.optInt("player_count", 0);
                MAIN.post(() -> {
                    if (activity.isFinishing() || !profile.apiBase.equals(serverProfile(activity).apiBase)) return;
                    serverView.setText(displayName + "\n" +
                            tr(activity, "Versión del juego: ", "Game version: ") + displayVersion + "\n" +
                            tr(activity, "Estado: EN LÍNEA · Jugadores: ", "Status: ONLINE · Players: ") + players +
                            "\nOpenFusion Android v" + APP_VERSION);
                    serverView.setTextColor(UI_SECONDARY);
                });
            }
            catch (Exception error) {
                Log.w(TAG, "Could not refresh server status", error);
                MAIN.post(() -> {
                    if (activity.isFinishing() || !profile.apiBase.equals(serverProfile(activity).apiBase)) return;
                    serverView.setText(profile.name + "\n" +
                            tr(activity, "Estado no disponible", "Status unavailable") +
                            "\nOpenFusion Android v" + APP_VERSION);
                    serverView.setTextColor(UI_ERROR);
                });
            }
        });
    }

    private static EditText accountInput(Activity activity, String esHint, String enHint, boolean secret) {
        EditText input = new EditText(activity);
        input.setHint(tr(activity, esHint, enHint));
        input.setTextColor(UI_TEXT);
        input.setHintTextColor(UI_HINT);
        input.setSingleLine(true);
        input.setTextSize(16f);
        input.setInputType(secret ?
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD :
                InputType.TYPE_CLASS_TEXT);
        return input;
    }

    private static TextView accountTitle(Activity activity, String es, String en) {
        TextView title = new TextView(activity);
        title.setText(tr(activity, es, en));
        title.setTextColor(UI_TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    private static void showRegistrationDialog(Activity activity) {
        int pad = Math.max(16, Math.round(20f * activity.getResources().getDisplayMetrics().density));
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, pad / 2);
        form.setBackgroundColor(UI_BG);
        form.addView(accountTitle(activity, "Crear cuenta", "Create account"));

        TextView server = new TextView(activity);
        server.setText(serverProfile(activity).name);
        server.setTextColor(UI_SECONDARY);
        server.setPadding(0, pad / 4, 0, pad / 4);
        form.addView(server);

        EditText username = accountInput(activity, "Usuario (4–32: letras, números, - o _)",
                "Username (4–32: letters, numbers, - or _)", false);
        EditText password = accountInput(activity, "Contraseña (8–32 caracteres)",
                "Password (8–32 characters)", true);
        EditText confirm = accountInput(activity, "Confirmar contraseña", "Confirm password", true);
        EditText email = accountInput(activity, "Correo electrónico (opcional)", "Email (optional)", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(username);
        form.addView(password);
        form.addView(confirm);
        form.addView(email);

        TextView note = new TextView(activity);
        note.setText(tr(activity,
                "El correo permite verificar la cuenta y recuperar la contraseña. Algunos servidores pueden exigirlo.",
                "Email enables account verification and password recovery. Some servers may require it."));
        note.setTextColor(UI_SECONDARY);
        note.setTextSize(13f);
        note.setPadding(0, pad / 4, 0, pad / 4);
        form.addView(note);

        TextView status = new TextView(activity);
        status.setTextColor(UI_SECONDARY);
        status.setTextSize(13f);
        form.addView(status);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setNegativeButton(tr(activity, "Cancelar", "Cancel"), null)
                .setPositiveButton(tr(activity, "Registrar", "Register"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(UI_BG));
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            cancel.setTextColor(UI_ACCENT);
            submit.setTextColor(UI_ACCENT);
            submit.setOnClickListener(v -> {
                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                String repeated = confirm.getText().toString();
                String address = email.getText().toString().trim();
                if (!user.matches("[A-Za-z0-9_-]{4,32}")) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "El usuario no cumple el formato requerido.", "The username does not match the required format."));
                    return;
                }
                if (pass.length() < 8 || pass.length() > 32) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "La contraseña debe tener entre 8 y 32 caracteres.", "The password must be 8–32 characters long."));
                    return;
                }
                if (!pass.equals(repeated)) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "Las contraseñas no coinciden.", "Passwords do not match."));
                    return;
                }
                if (!address.isEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "Ingresa un correo válido.", "Enter a valid email address."));
                    return;
                }
                submit.setEnabled(false);
                status.setTextColor(UI_ACCENT);
                status.setText(tr(activity, "Enviando registro…", "Submitting registration…"));
                ServerProfile profile = serverProfile(activity);
                EXECUTOR.execute(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("username", user);
                        body.put("password", pass);
                        if (!address.isEmpty()) body.put("email", address);
                        request("POST", profile.apiBase + "/account/register", body.toString(), null, "application/json");
                        MAIN.post(() -> {
                            if (activity.isFinishing()) return;
                            dialog.dismiss();
                            Toast.makeText(activity, tr(activity,
                                    address.isEmpty() ? "Cuenta registrada. Ya puedes iniciar sesión." : "Registro enviado. Revisa tu correo si el servidor solicita verificación.",
                                    address.isEmpty() ? "Account registered. You can now sign in." : "Registration submitted. Check your email if the server requests verification."),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                    catch (Exception error) {
                        Log.w(TAG, "Account registration failed", error);
                        MAIN.post(() -> {
                            submit.setEnabled(true);
                            status.setTextColor(UI_ERROR);
                            status.setText(humanAccountError(activity, error));
                        });
                    }
                });
            });
        });
        dialog.show();
    }

    private static void showPasswordRecoveryDialog(Activity activity) {
        int pad = Math.max(16, Math.round(20f * activity.getResources().getDisplayMetrics().density));
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, pad / 2);
        form.setBackgroundColor(UI_BG);
        form.addView(accountTitle(activity, "Recuperar contraseña", "Forgot password"));

        TextView note = new TextView(activity);
        note.setText(tr(activity,
                "Ingresa el correo asociado a tu cuenta. El servidor enviará una contraseña temporal de un solo uso.",
                "Enter the email associated with your account. The server will send a one-time temporary password."));
        note.setTextColor(UI_SECONDARY);
        note.setPadding(0, pad / 3, 0, pad / 4);
        form.addView(note);
        EditText email = accountInput(activity, "Correo electrónico", "Email", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(email);
        TextView status = new TextView(activity);
        status.setTextColor(UI_SECONDARY);
        status.setTextSize(13f);
        form.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(form)
                .setNegativeButton(tr(activity, "Cancelar", "Cancel"), null)
                .setPositiveButton(tr(activity, "Enviar contraseña temporal", "Send temporary password"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(UI_BG));
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            cancel.setTextColor(UI_ACCENT);
            submit.setTextColor(UI_ACCENT);
            submit.setOnClickListener(v -> {
                String address = email.getText().toString().trim();
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                    status.setTextColor(UI_ERROR);
                    status.setText(tr(activity, "Ingresa un correo válido.", "Enter a valid email address."));
                    return;
                }
                submit.setEnabled(false);
                status.setTextColor(UI_ACCENT);
                status.setText(tr(activity, "Solicitando contraseña temporal…", "Requesting temporary password…"));
                ServerProfile profile = serverProfile(activity);
                EXECUTOR.execute(() -> {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("email", address);
                        request("POST", profile.apiBase + "/account/otp", body.toString(), null, "application/json");
                        MAIN.post(() -> {
                            if (activity.isFinishing()) return;
                            dialog.dismiss();
                            Toast.makeText(activity, tr(activity,
                                    "Solicitud enviada. Revisa tu correo para obtener la contraseña temporal.",
                                    "Request sent. Check your email for the temporary password."), Toast.LENGTH_LONG).show();
                        });
                    }
                    catch (Exception error) {
                        Log.w(TAG, "Password recovery failed", error);
                        MAIN.post(() -> {
                            submit.setEnabled(true);
                            status.setTextColor(UI_ERROR);
                            status.setText(humanAccountError(activity, error));
                        });
                    }
                });
            });
        });
        dialog.show();
    }

    private static String humanAccountError(Context context, Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return tr(context, "No se pudo completar la solicitud: ", "Could not complete the request: ") + message;
    }

    private static boolean autoLoginEnabled(Context context) {
        SharedPreferences preferences = prefs(context);
        String key = credentialPreferenceKey(context, PREF_AUTO_LOGIN);
        if (preferences.contains(key)) return preferences.getBoolean(key, false);
        // v0.5.2 and older combined both settings; preserve that behavior once.
        return preferences.getBoolean(credentialPreferenceKey(context, PREF_REMEMBER_LOGIN), false);
    }

    private static void saveCredentials(Context context, String username, String password, boolean automatic) {
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
            prefs(context).edit()
                    .putString(credentialPreferenceKey(context, PREF_USERNAME), username)
                    .putString(credentialPreferenceKey(context, PREF_PASSWORD_BLOB), blob)
                    .putBoolean(credentialPreferenceKey(context, PREF_REMEMBER_LOGIN), true)
                    .putBoolean(credentialPreferenceKey(context, PREF_AUTO_LOGIN), automatic)
                    .apply();
        }
        catch (Exception e) {
            Log.w(TAG, "Could not securely store server credentials", e);
        }
    }

    private static String loadSavedPassword(Context context) {
        try {
            SharedPreferences prefs = prefs(context);
            if (!prefs.getBoolean(credentialPreferenceKey(context, PREF_REMEMBER_LOGIN), false)) return null;
            String blob = prefs.getString(credentialPreferenceKey(context, PREF_PASSWORD_BLOB), null);
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
            Log.w(TAG, "Could not load saved server credentials", e);
            clearSavedCredentials(context, true);
            return null;
        }
    }

    private static void clearSavedCredentials(Context context, boolean keepUsername) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit()
                .remove(credentialPreferenceKey(context, PREF_PASSWORD_BLOB))
                .putBoolean(credentialPreferenceKey(context, PREF_REMEMBER_LOGIN), false)
                .putBoolean(credentialPreferenceKey(context, PREF_AUTO_LOGIN), false);
        if (!keepUsername) editor.remove(credentialPreferenceKey(context, PREF_USERNAME));
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
        ServerProfile profile = serverProfile(activity);
        String apiBase = profile.apiBase;
        JSONObject info = getJson(apiBase + "/");
        String serverName = info.optString("server_name", profile.name);
        String loginAddress = info.getString("login_address");
        boolean customLoadingScreen = info.optBoolean("custom_loading_screen", false);

        String versionUuid = null;
        JSONArray versions = info.optJSONArray("game_versions");
        if (versions != null && versions.length() > 0) versionUuid = versions.getString(0);
        if (versionUuid == null || versionUuid.isEmpty()) versionUuid = info.optString("game_version", "");
        if (versionUuid.isEmpty()) throw new IOException("El servidor no informó una versión de juego activa");

        JSONObject version;
        try {
            version = getJson(apiBase + "/versions/" + versionUuid);
        }
        catch (IOException first) {
            version = getJson(apiBase + "/versions/" + versionUuid + ".json");
        }

        String assetUrl = version.getString("asset_url");
        while (assetUrl.endsWith("/")) assetUrl = assetUrl.substring(0, assetUrl.length() - 1);
        String mainUrl = version.optString("main_file_url", "");
        if (mainUrl.isEmpty()) mainUrl = assetUrl + "/main.unity3d";

        JSONObject auth = new JSONObject();
        auth.put("username", username);
        auth.put("password", password);
        String refreshToken = request("POST", apiBase + "/auth", auth.toString(), null, "application/json").trim();
        if (refreshToken.isEmpty()) throw new IOException("El servidor devolvió un token de autenticación vacío");

        JSONObject session = new JSONObject(request("POST", apiBase + "/auth/session", null, refreshToken, null));
        String sessionToken = session.getString("session_token");

        JSONObject cookieJson = new JSONObject(request("POST", apiBase + "/cookie", null, sessionToken, null));
        String cookie = cookieJson.getString("cookie");
        String cookieUser = cookieJson.optString("username", session.optString("username", username));
        long expires = cookieJson.optLong("expires", Long.MAX_VALUE);
        if (expires < System.currentTimeMillis() / 1000L) {
            throw new IOException("La cookie del servidor expiró; revisa la hora del dispositivo");
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
        appendArg(args, "-e", profile.apiEndpoint);
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
        final String expectedSha256;

        UpdateInfo(String tag, String name, String notes, String pageUrl,
                   String apkUrl, String checksumUrl, String expectedSha256) {
            this.tag = tag;
            this.name = name;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
            this.expectedSha256 = expectedSha256;
        }
    }

    public static void checkForUpdates(Activity activity, boolean userInitiated) {
        if (activity == null || activity.isFinishing()) return;
        if (userInitiated) Toast.makeText(activity,
                tr(activity, "Buscando actualizaciones…", "Checking for updates…"),
                Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                String channel = prefs(activity)
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
            String expectedSha256 = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String assetName = asset.optString("name", "").toLowerCase(Locale.US);
                String assetUrl = asset.optString("browser_download_url", "");
                if (assetName.endsWith(".apk")) {
                    apkUrl = assetUrl;
                    String digest = asset.optString("digest", "").toLowerCase(Locale.US);
                    if (digest.startsWith("sha256:")) expectedSha256 = digest.substring("sha256:".length());
                }
                else if (assetName.endsWith(".apk.sha256")) checksumUrl = assetUrl;
            }
            if (apkUrl == null || (checksumUrl == null && expectedSha256 == null)) continue;
            return new UpdateInfo(
                    release.optString("tag_name", ""),
                    release.optString("name", release.optString("tag_name", "Update")),
                    release.optString("body", ""),
                    release.optString("html_url", PROJECT_URL + "/releases"),
                    apkUrl, checksumUrl, expectedSha256);
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
                String expected = update.expectedSha256;
                if (expected == null || expected.isEmpty()) {
                    enforceHttps(update.checksumUrl);
                    String checksumText = publicGetFollowingHttpsRedirects(update.checksumUrl, 4096).trim();
                    expected = checksumText.split("\\s+", 2)[0].toLowerCase(Locale.US);
                }
                if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 manifest");
                File directory = new File(activity.getCacheDir(), "fusionfall-updates");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create update cache");
                File apk = new File(directory, "OpenFusion-Android-" + update.tag + ".apk");
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
        layout.setBackgroundColor(UI_BG);
        TextView title = new TextView(activity);
        title.setText(tr(activity, "Descargando ", "Downloading ") + update.tag);
        title.setTextColor(UI_TEXT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, pad / 2);
        layout.addView(title);
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
                .setView(layout)
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(UI_BG));
        return new UpdateProgress(activity, dialog, bar, status);
    }

    private static void enforceHttps(String value) throws IOException {
        try {
            if (value == null || !"https".equalsIgnoreCase(new URL(value).getProtocol())) {
                throw new IOException("HTTPS required");
            }
        }
        catch (IOException error) {
            throw error;
        }
        catch (Exception error) {
            throw new IOException("Invalid HTTPS URL", error);
        }
    }

    private static void installVerifiedApk(Activity activity, File apk) {
        try {
            prefs(activity).edit().putString(PREF_PENDING_UPDATE_APK, apk.getAbsolutePath()).apply();
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, tr(activity,
                        "Autoriza instalar actualizaciones y vuelve a la aplicación; el instalador se abrirá automáticamente.",
                        "Allow app updates and return to the app; the installer will open automatically."), Toast.LENGTH_LONG).show();
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }
            stagePackageInstallerSession(activity, apk);
        }
        catch (Exception error) {
            Log.e(TAG, "Could not open Android package installer", error);
            Toast.makeText(activity, tr(activity,
                    "No se pudo abrir el instalador de Android.",
                    "Could not open the Android package installer."), Toast.LENGTH_LONG).show();
        }
    }

    public static void onMainActivityResume(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String path = prefs(activity).getString(PREF_PENDING_UPDATE_APK, null);
        if (path == null || path.trim().isEmpty()) return;
        File apk = new File(path);
        try {
            File updates = new File(activity.getCacheDir(), "fusionfall-updates");
            String allowed = updates.getCanonicalPath() + File.separator;
            if (!apk.getCanonicalPath().startsWith(allowed) || !apk.isFile()) {
                prefs(activity).edit().remove(PREF_PENDING_UPDATE_APK).apply();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return;
            stagePackageInstallerSession(activity, apk);
        }
        catch (Exception error) {
            Log.e(TAG, "Could not resume pending APK installation", error);
            prefs(activity).edit().remove(PREF_PENDING_UPDATE_APK).apply();
            Toast.makeText(activity, tr(activity,
                    "No se pudo reanudar el instalador de Android.",
                    "Could not resume the Android package installer."), Toast.LENGTH_LONG).show();
        }
    }

    private static void stagePackageInstallerSession(Activity activity, File apk) {
        if (!UPDATE_INSTALLING.compareAndSet(false, true)) return;
        Toast.makeText(activity, tr(activity,
                "Preparando el instalador de Android…",
                "Preparing the Android installer…"), Toast.LENGTH_LONG).show();
        EXECUTOR.execute(() -> {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            try {
                validateDownloadedPackage(activity, apk);
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());
                params.setSize(apk.length());
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (FileInputStream input = new FileInputStream(apk);
                         OutputStream output = session.openWrite("OpenFusion-Android.apk", 0L, apk.length())) {
                        byte[] buffer = new byte[128 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                        session.fsync(output);
                    }
                    Intent result = new Intent(activity, FusionFallUpdateReceiver.class)
                            .setAction(FusionFallUpdateReceiver.ACTION_INSTALL_STATUS);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, result, flags);
                    prefs(activity).edit().remove(PREF_PENDING_UPDATE_APK).apply();
                    session.commit(pending.getIntentSender());
                }
                apk.delete();
            }
            catch (Exception error) {
                Log.e(TAG, "Could not stage verified APK", error);
                if (sessionId >= 0) {
                    try { installer.abandonSession(sessionId); }
                    catch (Exception ignored) {}
                }
                MAIN.post(() -> Toast.makeText(activity, tr(activity,
                        "Android no pudo preparar el paquete verificado.",
                        "Android could not prepare the verified package."), Toast.LENGTH_LONG).show());
            }
            finally {
                UPDATE_INSTALLING.set(false);
            }
        });
    }

    private static void validateDownloadedPackage(Activity activity, File apk) throws Exception {
        if (!apk.isFile() || apk.length() <= 0L) throw new IOException("Downloaded APK is missing or empty");
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
            throw new IOException("Downloaded file is not an OpenFusion Android APK");
        }
        PackageInfo installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        long archiveVersion = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        long installedVersion = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (archiveVersion <= installedVersion) {
            throw new IOException("Downloaded APK version is not newer than the installed version");
        }
    }

    public static void showAbout(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String message = "OpenFusion Android\nv" + APP_VERSION +
                " (" + APP_VERSION_CODE + ")\n\n" + tr(activity,
                "Cliente Android comunitario no oficial con perfiles de servidor configurables. Retrobution es el perfil predeterminado.\n\n" +
                        "Créditos: OpenFusion, Retrobution y Winlator.\n\n" +
                        "FusionFall y sus propiedades pertenecen a sus respectivos propietarios. " +
                        "Este proyecto no está afiliado ni respaldado por Cartoon Network.\n\n" +
                        "Las licencias y avisos de los proyectos base se conservan en sus repositorios oficiales.",
                "Unofficial community Android client with configurable server profiles. Retrobution is the default profile.\n\n" +
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
        enforceHttps(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "OpenFusion-Android/" + APP_VERSION);
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

    private static String publicGetFollowingHttpsRedirects(String url, int maxBytes) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects < 10; redirects++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection conn = (HttpURLConnection)current.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "OpenFusion-Android/" + APP_VERSION);
            conn.setRequestProperty("Accept", "text/plain, application/octet-stream");
            try {
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Redirect without Location header");
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                long length = conn.getContentLengthLong();
                if (length > maxBytes) throw new IOException("Response exceeds safe size limit");
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int total = 0;
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        total += count;
                        if (total > maxBytes) throw new IOException("Response exceeds safe size limit");
                        out.write(buffer, 0, count);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            finally {
                conn.disconnect();
            }
        }
        throw new IOException("Too many HTTPS redirects");
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
            conn.setRequestProperty("User-Agent", "OpenFusion-Android/" + APP_VERSION);
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
                    "  \"launcher\": \"android-native-openfusion\"\n" +
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
