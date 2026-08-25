// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.winlator.core.AppUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** v0.5.3 Beta bounded, privacy-conscious diagnostics for the Android compatibility layer. */
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
                File directory = new File(activity.getCacheDir(), "fusionfall-diagnostics");
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create diagnostics cache");
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File file = new File(directory, "OpenFusion-Android-v0.5.3-beta-" + stamp + ".txt");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                }
                activity.runOnUiThread(() -> shareFile(activity, file));
            }
            catch (Throwable error) {
                recordEvent("diagnostic export failed · " + error.getClass().getSimpleName());
                activity.runOnUiThread(() -> AppUtils.showToast(activity,
                        isEnglish(activity) ? "Could not export diagnostic" : "No se pudo exportar el diagnóstico"));
            }
        }, "FusionFallDiagnostics").start();
    }

    private static void shareFile(Activity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fusionfall.files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "OpenFusion Android v0.5.3 Beta diagnostics");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            recordEvent("diagnostic exported · " + file.getName());
            activity.startActivity(Intent.createChooser(send,
                    isEnglish(activity) ? "Share diagnostic" : "Compartir diagnóstico"));
        }
        catch (Throwable error) {
            recordEvent("diagnostic share failed · " + error.getClass().getSimpleName());
            AppUtils.showToast(activity, isEnglish(activity) ?
                    "Could not share diagnostic" : "No se pudo compartir el diagnóstico");
        }
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

        section(out, "Memory");
        appendMemory(activity, out);

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

    private static void collectLogs(File directory, List<File> output, int depth) {
        if (directory == null || depth > 4 || output.size() >= MAX_LOG_FILES * 2) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) collectLogs(file, output, depth + 1);
            else {
                String name = file.getName().toLowerCase(Locale.US);
                if (name.endsWith(".log") || name.contains("wine") || name.contains("box64") || name.contains("xserver")) output.add(file);
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
            if (read > 0) out.append(sanitize(new String(buffer, 0, read, StandardCharsets.UTF_8))).append('\n');
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
