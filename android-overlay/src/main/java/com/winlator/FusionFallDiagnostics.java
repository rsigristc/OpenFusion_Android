// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.core.AppUtils;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** v0.5.11 Beta bounded, privacy-conscious diagnostics for the Android compatibility layer. */
public final class FusionFallDiagnostics {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 160;
    private static final int MAX_LOG_FILES = 12;
    private static final int MAX_LOG_CHARS = 16 * 1024;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static volatile String glVendor = "pending";
    private static volatile String glRenderer = "pending";
    private static volatile String glVersion = "pending";
    private static volatile boolean glCaptured;

    private FusionFallDiagnostics() {}

    public static void attach(Activity activity) {
        recordEvent("session attached · " + displaySize(activity));
    }

    public static void recordEvent(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (LOCK) {
            while (EVENTS.size() >= MAX_EVENTS) EVENTS.removeFirst();
            EVENTS.addLast(time + "  " + sanitize(message));
        }
    }

    /** Called on the GL thread, where glGetString is valid. */
    public static void onRenderedFrame() {
        if (glCaptured) return;
        try {
            String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            if (renderer != null && !renderer.trim().isEmpty()) {
                glVendor = safeValue(vendor);
                glRenderer = safeValue(renderer);
                glVersion = safeValue(version);
                glCaptured = true;
                recordEvent("GL renderer captured · " + glRenderer);
            }
        }
        catch (Throwable ignored) {}
    }

    public static void showDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16f);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.WHITE);

        TextView report = new TextView(activity);
        report.setText(buildReport(activity, false));
        report.setTextColor(Color.rgb(24, 31, 42));
        report.setTextSize(12f);
        report.setTextIsSelectable(true);
        layout.addView(report, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(layout);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(isEnglish(activity) ? "FusionFall diagnostics" : "Diagnóstico de FusionFall")
                .setView(scroll)
                .setNegativeButton(isEnglish(activity) ? "Close" : "Cerrar", null)
                .setPositiveButton(isEnglish(activity) ? "Export" : "Exportar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> export(activity)));
        dialog.show();
    }

    public static void export(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        AppUtils.showToast(activity, isEnglish(activity) ? "Preparing diagnostic…" : "Preparando diagnóstico…");
        new Thread(() -> {
            try {
                String report = buildReport(activity, true);
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String filename = "OpenFusion-Android-v0.5.11-beta-" + stamp + ".txt";
                String location = saveToDownloads(activity, filename, report.getBytes(StandardCharsets.UTF_8));
                recordEvent("diagnostic saved · " + location);
                activity.runOnUiThread(() -> AppUtils.showToast(activity,
                        (isEnglish(activity) ? "Diagnostic saved in " : "Diagnóstico guardado en ") + location));
            }
            catch (Exception error) {
                recordEvent("diagnostic export failed · " + error.getClass().getSimpleName());
                activity.runOnUiThread(() -> AppUtils.showToast(activity,
                        isEnglish(activity) ? "Could not export diagnostic" : "No se pudo exportar el diagnóstico"));
            }
        }, "FusionFallDiagnostics").start();
    }

    private static String saveToDownloads(Activity activity, String filename, byte[] content) throws Exception {
        String relative = Environment.DIRECTORY_DOWNLOADS + "/OpenFusion Android";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = activity.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create Downloads entry");
            try {
                try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Could not open Downloads entry");
                    output.write(content);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                activity.getContentResolver().update(uri, values, null, null);
            }
            catch (Exception error) {
                activity.getContentResolver().delete(uri, null, null);
                throw error;
            }
        }
        else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "OpenFusion Android");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create Downloads directory");
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
                output.write(content);
            }
        }
        return relative + "/" + filename;
    }

    private static String buildReport(Activity activity, boolean includeLogs) {
        StringBuilder out = new StringBuilder(32 * 1024);
        out.append("OpenFusion Android Diagnostics\n");
        out.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append("\n\n");

        section(out, "Application");
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            out.append("Package: ").append(activity.getPackageName()).append('\n');
            out.append("Version: ").append(info.versionName).append(" (").append(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode).append(")\n");
        }
        catch (Throwable error) { out.append("Version: unavailable\n"); }
        out.append("Server profile: ").append(FusionFallRetrobution.serverSummary(activity)).append('\n');
        out.append(FusionFallMobileControls.buildRuntimeDiagnostics(activity));

        section(out, "Android device");
        out.append("Manufacturer: ").append(safeValue(Build.MANUFACTURER)).append('\n');
        out.append("Model: ").append(safeValue(Build.MODEL)).append('\n');
        out.append("Device/Product: ").append(safeValue(Build.DEVICE)).append(" / ").append(safeValue(Build.PRODUCT)).append('\n');
        out.append("Board/Hardware: ").append(safeValue(Build.BOARD)).append(" / ").append(safeValue(Build.HARDWARE)).append('\n');
        if (Build.VERSION.SDK_INT >= 31) {
            out.append("SoC: ").append(safeValue(Build.SOC_MANUFACTURER)).append(' ').append(safeValue(Build.SOC_MODEL)).append('\n');
        }
        out.append("ABI: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown").append('\n');
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Security patch: ").append(safeValue(Build.VERSION.SECURITY_PATCH)).append('\n');
        out.append("Display: ").append(displaySize(activity)).append('\n');

        section(out, "Graphics");
        out.append("GL vendor: ").append(glVendor).append('\n');
        out.append("GL renderer: ").append(glRenderer).append('\n');
        out.append("GL version: ").append(glVersion).append('\n');
        appendContainerGraphics(activity, out);

        section(out, "Memory");
        appendMemory(activity, out);

        if (includeLogs) {
            section(out, "Network endpoints");
            out.append(FusionFallRetrobution.buildNetworkDiagnostics(activity));
        }

        section(out, "Session events");
        synchronized (LOCK) {
            if (EVENTS.isEmpty()) out.append("No events recorded\n");
            else for (String event : EVENTS) out.append(event).append('\n');
        }

        if (includeLogs) {
            section(out, "Application logcat (own process)");
            appendLogcat(out);
            section(out, "Wine / Box64 / XServer log files");
            appendKnownLogs(activity, out);
        }
        return sanitize(out.toString());
    }

    private static void appendMemory(Activity activity, StringBuilder out) {
        Runtime runtime = Runtime.getRuntime();
        out.append("Java heap used/max: ").append(mb(runtime.totalMemory() - runtime.freeMemory()))
                .append(" / ").append(mb(runtime.maxMemory())).append(" MB\n");
        try {
            ActivityManager manager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            out.append("System available/total: ").append(mb(info.availMem)).append(" / ")
                    .append(mb(info.totalMem)).append(" MB\n");
            out.append("Low memory: ").append(info.lowMemory).append('\n');
        }
        catch (Throwable error) { out.append("System memory: unavailable\n"); }
    }

    private static void appendLogcat(StringBuilder out) {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "240", "--pid=" + Process.myPid()});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 240) out.append(sanitize(line)).append('\n');
            }
        }
        catch (Throwable error) { out.append("Unavailable: ").append(error.getClass().getSimpleName()).append('\n'); }
        finally { if (process != null) process.destroy(); }
    }

    private static void appendKnownLogs(Activity activity, StringBuilder out) {
        List<File> candidates = new ArrayList<>();
        try {
            for (Container container : new ContainerManager(activity).getContainers()) {
                if ("FusionFall Retrobution".equals(container.getName()) ||
                        "FusionFall".equals(container.getName())) {
                    File runtimeDirectory = new File(container.getRootDir(),
                            ".wine/drive_c/OpenFusionRuntime");
                    File runnerLog = new File(runtimeDirectory, "ffrunner.log");
                    if (runnerLog.isFile()) candidates.add(runnerLog);
                    collectLogs(runtimeDirectory, candidates, 0);
                }
            }
        }
        catch (Throwable error) {
            out.append("Could not inspect FusionFall runtime logs: ")
                    .append(error.getClass().getSimpleName()).append('\n');
        }
        collectLogs(activity.getFilesDir(), candidates, 0);
        collectLogs(activity.getCacheDir(), candidates, 0);
        if (candidates.isEmpty()) {
            out.append("No matching app-private logs found\n");
            return;
        }
        int emitted = 0;
        for (File file : candidates) {
            if (emitted++ >= MAX_LOG_FILES) break;
            out.append("\n--- ").append(file.getName()).append(" (last ").append(MAX_LOG_CHARS).append(" bytes) ---\n");
            appendTail(file, out);
        }
    }

    private static void appendContainerGraphics(Activity activity, StringBuilder out) {
        try {
            for (Container container : new ContainerManager(activity).getContainers()) {
                if ("FusionFall Retrobution".equals(container.getName()) ||
                        "FusionFall".equals(container.getName())) {
                    out.append("Container graphics driver: ").append(container.getGraphicsDriver()).append('\n');
                    out.append("Container DX wrapper: ").append(container.getDXWrapper()).append('\n');
                    out.append("Container Box64 preset: ").append(container.getBox64Preset()).append('\n');
                    File runtimeLog = new File(container.getRootDir(),
                            ".wine/drive_c/OpenFusionRuntime/ffrunner.log");
                    out.append("ffrunner.log: ").append(runtimeLog.isFile() ?
                            runtimeLog.length() + " bytes" : "missing").append('\n');
                    return;
                }
            }
            out.append("Container graphics: unavailable\n");
        }
        catch (Throwable error) {
            out.append("Container graphics: unavailable (")
                    .append(error.getClass().getSimpleName()).append(")\n");
        }
    }

    private static void collectLogs(File directory, List<File> output, int depth) {
        if (directory == null || depth > 4 || output.size() >= MAX_LOG_FILES * 2) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectLogs(file, output, depth + 1);
            else {
                String name = file.getName().toLowerCase(Locale.US);
                if ((name.endsWith(".log") || name.endsWith(".trace")) &&
                        !output.contains(file)) output.add(file);
            }
        }
    }

    private static void appendTail(File file, StringBuilder out) {
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0L, file.length() - MAX_LOG_CHARS);
            while (skip > 0L) {
                long skipped = input.skip(skip);
                if (skipped <= 0L) break;
                skip -= skipped;
            }
            byte[] buffer = new byte[MAX_LOG_CHARS];
            int read = input.read(buffer);
            if (read > 0) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == 0) {
                        out.append("Skipped binary file\n");
                        return;
                    }
                }
                out.append(sanitize(new String(buffer, 0, read, StandardCharsets.UTF_8))).append('\n');
            }
        }
        catch (Throwable error) { out.append("Unreadable: ").append(error.getClass().getSimpleName()).append('\n'); }
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value
                .replaceAll("(?i)(password|passwd|token|secret|authorization)(\\s*[:=]\\s*)[^\\s\\r\\n]+", "$1$2[REDACTED]")
                .replaceAll("(?i)bearer\\s+[a-z0-9._~+\\-/]+=*", "Bearer [REDACTED]");
    }

    private static String displaySize(Activity activity) {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            float refreshRate = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
            return metrics.widthPixels + "x" + metrics.heightPixels + " @ " + metrics.densityDpi + " dpi / " +
                    String.format(Locale.US, "%.1f", refreshRate) + " Hz";
        }
        catch (Throwable error) { return "unavailable"; }
    }

    private static void section(StringBuilder out, String title) { out.append("\n[").append(title).append("]\n"); }
    private static long mb(long bytes) { return Math.max(0L, bytes / (1024L * 1024L)); }
    private static String safeValue(String value) { return value == null || value.trim().isEmpty() ? "unknown" : value.trim(); }
    private static int dp(Context context, float value) { return Math.max(1, Math.round(value * context.getResources().getDisplayMetrics().density)); }
    private static boolean isEnglish(Context context) {
        return "en".equals(context.getSharedPreferences("fusionfall_retrobution", 0).getString("ui_language", "es"));
    }
}
