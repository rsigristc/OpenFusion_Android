// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.winlator.core.AppUtils;
import com.winlator.widget.TouchpadView;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.Locale;

/**
 * v0.5.2 Beta Android controls and lifecycle layer for OpenFusion Android.
 *
 * The Windows/Unity client is untouched. This class sits above XServerView and
 * converts Android touch/gamepad input to the keyboard/mouse events the legacy
 * client already understands.
 */
public final class FusionFallMobileControls {
    private static final String PREFS = "fusionfall_retrobution";
    private static final String PREF_LANGUAGE = "ui_language";
    private static final String OVERLAY_TAG = "fusionfall_mobile_overlay";
    private static final float DEFAULT_OPACITY = 0.58f;
    private static final float DEFAULT_SCALE = 1.0f;
    private static final float DEFAULT_CAMERA = 1.0f;
    private static final float DEFAULT_CAMERA_X = 1.0f;
    private static final float DEFAULT_CAMERA_Y = 1.0f;
    private static final float DEFAULT_JOYSTICK_DEADZONE = 0.24f;
    private static final float DEFAULT_FADED_ALPHA = 0.46f;
    private static final String CONTROL_TAG = OVERLAY_TAG + "_control";
    private static final float DEFAULT_CAMERA_DRAG_DEADZONE_DP = 3.5f;
    private static final int FRAME_SAMPLE_CAPACITY = 600;
    private static final long STATS_UPDATE_INTERVAL_MS = 500L;

    private static final int UI_BG = Color.rgb(255, 255, 255);
    private static final int UI_TEXT = Color.rgb(24, 31, 42);
    private static final int UI_SECONDARY = Color.rgb(71, 85, 105);
    private static final int UI_ACCENT = Color.rgb(0, 122, 204);

    private static WeakReference<XServerDisplayActivity> activityRef = new WeakReference<>(null);
    private static WeakReference<FrameLayout> rootRef = new WeakReference<>(null);
    private static WeakReference<TouchpadView> touchpadRef = new WeakReference<>(null);

    private static boolean autoRun;
    private static boolean padW;
    private static boolean padA;
    private static boolean padS;
    private static boolean padD;
    private static boolean padAttack;
    private static boolean touchAttack;
    private static boolean utilityExpanded;
    private static boolean uiMode;
    private static boolean topMenuExpanded;
    private static boolean editControlsMode;
    private static boolean gamepadConnected;

    private static boolean gamepadCameraActive;
    private static long gamepadCameraDownTime;
    private static float gamepadCameraX;
    private static float gamepadCameraY;
    private static Runnable renderPump;
    private static final WeakHashMap<View, Runnable> fadeTasks = new WeakHashMap<>();
    private static final WeakHashMap<View, DragState> dragStates = new WeakHashMap<>();
    private static InputManager inputManager;
    private static InputManager.InputDeviceListener inputDeviceListener;
    private static boolean sessionPaused;
    private static int lifecycleGeneration;
    private static long lastResumeUptime;
    private static int lastKnownRootWidth;
    private static int lastKnownRootHeight;
    private static long nextRenderDeadlineNanos;
    private static final Object frameStatsLock = new Object();
    private static final float[] frameTimeSamples = new float[FRAME_SAMPLE_CAPACITY];
    private static int frameSampleCount;
    private static int frameSampleIndex;
    private static long lastRenderedFrameNanos;
    private static long statsSessionStartNanos;
    private static long statsSessionFrames;
    private static long statsStutterCount;
    private static long lastStatsUiUpdateMs;
    private static WeakReference<TextView> performanceStatsRef = new WeakReference<>(null);

    private FusionFallMobileControls() {}

    private static final class DragState {
        float downRawX;
        float downRawY;
        float startTranslationX;
        float startTranslationY;
    }

    private static boolean isGamepadDevice(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static boolean scanForGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            if (isGamepadDevice(InputDevice.getDevice(id))) return true;
        }
        return false;
    }

    private static void startGamepadMonitor(XServerDisplayActivity activity) {
        stopGamepadMonitor();
        if (activity == null) return;
        inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
        gamepadConnected = scanForGamepad();
        FusionFallDiagnostics.recordEvent("gamepad scan · " + (gamepadConnected ? "connected" : "not connected"));
        if (inputManager == null) return;
        inputDeviceListener = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId) { refreshGamepadState(true); }
            @Override public void onInputDeviceRemoved(int deviceId) { refreshGamepadState(true); }
            @Override public void onInputDeviceChanged(int deviceId) { refreshGamepadState(false); }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener, null);
    }

    private static void stopGamepadMonitor() {
        if (inputManager != null && inputDeviceListener != null) {
            try { inputManager.unregisterInputDeviceListener(inputDeviceListener); }
            catch (Throwable ignored) {}
        }
        inputManager = null;
        inputDeviceListener = null;
        gamepadConnected = false;
    }

    private static void refreshGamepadState(boolean notify) {
        XServerDisplayActivity activity = activityRef.get();
        boolean connected = scanForGamepad();
        if (connected == gamepadConnected) return;
        gamepadConnected = connected;
        FusionFallDiagnostics.recordEvent("gamepad " + (connected ? "connected" : "disconnected"));
        if (activity != null && !activity.isFinishing()) {
            if (notify && prefs(activity).getBoolean("auto_hide_touch_on_gamepad", true)) {
                AppUtils.showToast(activity, connected ?
                        tr(activity, "Gamepad conectado · controles táctiles ocultos", "Gamepad connected · touch controls hidden") :
                        tr(activity, "Gamepad desconectado · controles táctiles restaurados", "Gamepad disconnected · touch controls restored"));
            }
            FrameLayout root = rootRef.get();
            if (root != null) root.post(FusionFallMobileControls::rebuild);
        }
    }

    private static void markGamepadConnectedFromEvent(XServerDisplayActivity activity) {
        if (gamepadConnected) return;
        gamepadConnected = true;
        FusionFallDiagnostics.recordEvent("gamepad detected from input event");
        if (activity != null && prefs(activity).getBoolean("auto_hide_touch_on_gamepad", true)) {
            AppUtils.showToast(activity, tr(activity, "Gamepad detectado · controles táctiles ocultos", "Gamepad detected · touch controls hidden"));
            FrameLayout root = rootRef.get();
            if (root != null) root.post(FusionFallMobileControls::rebuild);
        }
    }

    private static String offsetKey(String controlId, String axis) {
        return "control_offset_" + controlId + "_" + axis + "_dp";
    }

    private static String visibleKey(String controlId) {
        return "control_visible_" + controlId;
    }

    private static String scaleKey(String controlId) {
        return "control_scale_" + controlId;
    }

    private static String opacityKey(String controlId) {
        return "control_opacity_" + controlId;
    }

    private static boolean defaultControlVisible(String controlId) {
        return "attack".equals(controlId) || "jump".equals(controlId) ||
                "target".equals(controlId) || "weapon".equals(controlId) ||
                "nano_power".equals(controlId);
    }

    private static boolean controlVisible(Context context, String controlId) {
        if (controlId == null) return true;
        return prefs(context).getBoolean(visibleKey(controlId), defaultControlVisible(controlId));
    }

    private static float controlScale(Context context, String controlId) {
        if (controlId == null) return 1.0f;
        return Math.max(0.70f, Math.min(1.35f, prefs(context).getFloat(scaleKey(controlId), 1.0f)));
    }

    private static float controlOpacityMultiplier(Context context, String controlId) {
        if (controlId == null) return 1.0f;
        return Math.max(0.55f, Math.min(1.35f, prefs(context).getFloat(opacityKey(controlId), 1.0f)));
    }

    private static boolean isActionControl(String controlId) {
        if (controlId == null) return false;
        switch (controlId) {
            case "attack": case "jump": case "target": case "weapon": case "nano_power":
            case "inventory": case "journal": case "email": case "map":
                return true;
            default:
                return false;
        }
    }

    private static void applySavedOffset(Context context, View view, String controlId) {
        if (context == null || view == null || controlId == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        SharedPreferences p = prefs(context);
        view.setTranslationX(p.getFloat(offsetKey(controlId, "x"), 0f) * density);
        view.setTranslationY(p.getFloat(offsetKey(controlId, "y"), 0f) * density);
    }

    private static void saveControlOffset(Context context, View view, String controlId) {
        if (context == null || view == null || controlId == null) return;
        float density = Math.max(0.01f, context.getResources().getDisplayMetrics().density);
        prefs(context).edit()
                .putFloat(offsetKey(controlId, "x"), view.getTranslationX() / density)
                .putFloat(offsetKey(controlId, "y"), view.getTranslationY() / density)
                .apply();
    }

    private static void resetControlOffsets(Context context) {
        if (context == null) return;
        SharedPreferences.Editor e = prefs(context).edit();
        String[] ids = {"joystick", "attack", "jump", "target", "weapon", "nano_power", "inventory", "journal", "email", "map"};
        for (String id : ids) {
            e.remove(offsetKey(id, "x"));
            e.remove(offsetKey(id, "y"));
        }
        e.apply();
    }

    private static boolean handleControlEditDrag(View view, String controlId, MotionEvent event) {
        if (!editControlsMode || view == null || event == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            DragState state = new DragState();
            state.downRawX = event.getRawX();
            state.downRawY = event.getRawY();
            state.startTranslationX = view.getTranslationX();
            state.startTranslationY = view.getTranslationY();
            dragStates.put(view, state);
            haptic(view);
            view.animate().alpha(0.92f).scaleX(1.04f).scaleY(1.04f).setDuration(80L).start();
            return true;
        }
        DragState state = dragStates.get(view);
        if (state == null) return true;
        if (action == MotionEvent.ACTION_MOVE) {
            float tx = state.startTranslationX + event.getRawX() - state.downRawX;
            float ty = state.startTranslationY + event.getRawY() - state.downRawY;
            ViewParentClamp clamp = clampTranslation(view, controlId, tx, ty);
            view.setTranslationX(clamp.x);
            view.setTranslationY(clamp.y);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            saveControlOffset(view.getContext(), view, controlId);
            dragStates.remove(view);
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(90L).start();
            return true;
        }
        return true;
    }

    private static final class ViewParentClamp {
        final float x;
        final float y;
        ViewParentClamp(float x, float y) { this.x = x; this.y = y; }
    }

    private static ViewParentClamp clampTranslation(View view, String controlId, float tx, float ty) {
        if (!(view.getParent() instanceof ViewGroup)) return new ViewParentClamp(tx, ty);
        ViewGroup parent = (ViewGroup)view.getParent();
        float minX = -view.getLeft() + dp(view.getContext(), 4f);
        float maxX = parent.getWidth() - view.getRight() - dp(view.getContext(), 4f);
        float minY = -view.getTop() + dp(view.getContext(), 4f);
        float maxY = parent.getHeight() - view.getBottom() - dp(view.getContext(), 4f);
        if (parent.getWidth() <= 0 || parent.getHeight() <= 0) return new ViewParentClamp(tx, ty);
        float clampedX = Math.max(minX, Math.min(maxX, tx));
        float clampedY = Math.max(minY, Math.min(maxY, ty));

        // POC4.9.1 safe-zone editor: action buttons cannot be left on top of
        // the native minimap, Nano HUD, or chat area. The joystick is exempt.
        if (isActionControl(controlId)) {
            float left = view.getLeft() + clampedX;
            float top = view.getTop() + clampedY;
            float right = left + view.getWidth();
            float bottom = top + view.getHeight();
            float pw = parent.getWidth();
            float ph = parent.getHeight();
            float gap = dp(view.getContext(), 8f);

            // Minimap / upper-right native HUD.
            float mapLeft = pw * 0.74f;
            float mapBottom = ph * 0.29f;
            if (right > mapLeft && top < mapBottom) {
                clampedY += (mapBottom - top) + gap;
            }

            // Equipped Nano slots / lower-right native HUD.
            left = view.getLeft() + clampedX;
            top = view.getTop() + clampedY;
            right = left + view.getWidth();
            bottom = top + view.getHeight();
            float nanoLeft = pw * 0.70f;
            float nanoTop = ph * 0.77f;
            if (right > nanoLeft && bottom > nanoTop) {
                clampedY -= (bottom - nanoTop) + gap;
            }

            // Chat / lower-left native HUD.
            left = view.getLeft() + clampedX;
            top = view.getTop() + clampedY;
            right = left + view.getWidth();
            bottom = top + view.getHeight();
            float chatRight = pw * 0.36f;
            float chatTop = ph * 0.77f;
            if (left < chatRight && bottom > chatTop) {
                clampedY -= (bottom - chatTop) + gap;
            }
            clampedY = Math.max(minY, Math.min(maxY, clampedY));
        }
        return new ViewParentClamp(clampedX, clampedY);
    }

    public static final class LaunchConfig {
        public final int width;
        public final int height;
        public final int fpsCap;
        public final String profile;
        public final boolean nativeAspect;

        LaunchConfig(int width, int height, int fpsCap, String profile, boolean nativeAspect) {
            this.width = width;
            this.height = height;
            this.fpsCap = fpsCap;
            this.profile = profile;
            this.nativeAspect = nativeAspect;
        }

        public String screenSize() {
            return width + "x" + height;
        }
    }

    public static LaunchConfig getLaunchConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        String profile = prefs.getString("performance_profile", "compatible");
        boolean nativeAspect = prefs.getBoolean("native_aspect", false);

        int width = 1280;
        int height = 720;
        int fps = 0; // 0 means preserve OpenFusion's current/default behavior.

        if ("battery".equals(profile)) {
            width = 960;
            height = 540;
            fps = 30;
        }
        else if ("balanced".equals(profile)) {
            width = 1280;
            height = 720;
            fps = 45;
        }
        else if ("performance".equals(profile)) {
            width = 1280;
            height = 720;
            fps = 60;
        }
        else if ("unlocked".equals(profile)) {
            width = 1600;
            height = 900;
            fps = 0;
        }

        if (nativeAspect) {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int longSide = Math.max(dm.widthPixels, dm.heightPixels);
            int shortSide = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            float aspect = Math.max(16f / 9f, Math.min(22f / 9f, (float)longSide / (float)shortSide));
            width = Math.round(height * aspect / 8f) * 8;
        }

        return new LaunchConfig(width, height, fps, profile, nativeAspect);
    }

    public static void install(XServerDisplayActivity activity, FrameLayout root, TouchpadView touchpad) {
        if (activity == null || root == null || touchpad == null) return;
        activityRef = new WeakReference<>(activity);
        rootRef = new WeakReference<>(root);
        touchpadRef = new WeakReference<>(touchpad);

        SharedPreferences prefs = prefs(activity);
        uiMode = false;
        sessionPaused = false;
        resetFrameStats();
        lifecycleGeneration++;
        lastResumeUptime = SystemClock.uptimeMillis();
        lastKnownRootWidth = root.getWidth();
        lastKnownRootHeight = root.getHeight();
        FusionFallDiagnostics.attach(activity);
        // Camera V2 applies horizontal/vertical scaling itself. Keep TouchpadView at
        // neutral sensitivity so X/Y can be tuned independently and UI mode remains predictable.
        touchpad.setSensitivity(1.0f);
        applyInteractionMode(activity, touchpad);
        startGamepadMonitor(activity);

        rebuild();
        scheduleResumeRecovery(activity, 0L);
        startRenderPump();
    }

    /**
     * Android lifecycle bridge for the FusionFall session. Winlator already pauses
     * the XEnvironment/GLSurfaceView; this layer additionally releases synthetic
     * inputs and stops Android-side polling so returning from another app cannot
     * leave W/mouse/camera stuck.
     */
    public static void onSessionPause(XServerDisplayActivity activity) {
        XServerDisplayActivity attached = activityRef.get();
        if (attached != activity) return;
        sessionPaused = true;
        lifecycleGeneration++;
        FusionFallDiagnostics.recordEvent("session pause");

        releaseMovementKeys(activity);
        if (autoRun) {
            sendKey(activity, KeyEvent.KEYCODE_W, false);
            autoRun = false;
        }
        endGamepadCamera(touchpadRef.get());
        if (padAttack || touchAttack) activity.fusionFallSendMouseButton(false);
        padAttack = false;
        touchAttack = false;
        stopRenderPump();
        stopGamepadMonitor();
        cancelPendingUiCallbacks();
    }

    /** Resume controls only after Winlator has resumed its renderer/environment. */
    public static void onSessionResume(XServerDisplayActivity activity) {
        XServerDisplayActivity attached = activityRef.get();
        if (attached != activity || activity == null || activity.isFinishing()) return;
        sessionPaused = false;
        lifecycleGeneration++;
        FusionFallDiagnostics.recordEvent("session resume");
        lastResumeUptime = SystemClock.uptimeMillis();
        startGamepadMonitor(activity);
        applyInteractionMode(activity, touchpadRef.get());
        startRenderPump();

        // A fold/unfold, lock screen or task switch may report the final root size
        // a few frames after onResume. Three bounded passes are enough to restore
        // safe zones, joystick/buttons and the WHEN_DIRTY presentation pulse.
        scheduleResumeRecovery(activity, 0L);
        scheduleResumeRecovery(activity, 140L);
        scheduleResumeRecovery(activity, 520L);
    }

    /** Rebuild only the Android overlay when display metrics/configuration change. */
    public static void onSessionConfigurationChanged(XServerDisplayActivity activity) {
        XServerDisplayActivity attached = activityRef.get();
        if (attached != activity || activity == null || activity.isFinishing()) return;
        lifecycleGeneration++;
        FrameLayout root = rootRef.get();
        FusionFallDiagnostics.recordEvent("configuration changed · " +
                (root == null ? "root unavailable" : root.getWidth() + "x" + root.getHeight()));
        scheduleResumeRecovery(activity, 0L);
        scheduleResumeRecovery(activity, 180L);
        scheduleResumeRecovery(activity, 650L);
    }

    /** Window focus can return after notifications/permission UI without onResume. */
    public static void onSessionWindowFocusChanged(XServerDisplayActivity activity, boolean hasFocus) {
        XServerDisplayActivity attached = activityRef.get();
        if (attached != activity || activity == null || activity.isFinishing() || !hasFocus || sessionPaused) return;
        FusionFallDiagnostics.recordEvent("window focus restored");
        scheduleResumeRecovery(activity, 0L);
        scheduleResumeRecovery(activity, 120L);
    }

    private static void scheduleResumeRecovery(XServerDisplayActivity activity, long delayMs) {
        FrameLayout root = rootRef.get();
        if (activity == null || root == null) return;
        final int generation = lifecycleGeneration;
        root.postDelayed(() -> {
            XServerDisplayActivity current = activityRef.get();
            FrameLayout currentRoot = rootRef.get();
            if (generation != lifecycleGeneration || sessionPaused || current != activity ||
                    current == null || currentRoot == null || current.isFinishing()) return;

            int width = currentRoot.getWidth();
            int height = currentRoot.getHeight();
            boolean geometryChanged = width > 0 && height > 0 &&
                    (width != lastKnownRootWidth || height != lastKnownRootHeight);
            if (width > 0) lastKnownRootWidth = width;
            if (height > 0) lastKnownRootHeight = height;

            // Rebuilding is cheap compared with restarting Wine and keeps all HUD
            // hitboxes aligned after fold/multi-window/display-size changes.
            if (geometryChanged || delayMs == 0L || SystemClock.uptimeMillis() - lastResumeUptime < 1200L) rebuild();
            current.fusionFallRequestRender();
        }, Math.max(0L, delayMs));
    }

    private static void cancelPendingUiCallbacks() {
        FrameLayout root = rootRef.get();
        if (root != null) {
            for (java.util.Map.Entry<View, Runnable> entry : fadeTasks.entrySet()) {
                View view = entry.getKey();
                Runnable task = entry.getValue();
                if (view != null && task != null) view.removeCallbacks(task);
            }
        }
        fadeTasks.clear();
        dragStates.clear();
    }

    public static void detach(XServerDisplayActivity activity) {
        XServerDisplayActivity attached = activityRef.get();
        if (attached == activity) {
            FusionFallDiagnostics.recordEvent("session detached");
            releaseMovementKeys(activity);
            if (autoRun) {
                sendKey(activity, KeyEvent.KEYCODE_W, false);
                autoRun = false;
            }
            endGamepadCamera(touchpadRef.get());
            if (padAttack || touchAttack) activity.fusionFallSendMouseButton(false);
            padAttack = false;
            touchAttack = false;
            sessionPaused = true;
            lifecycleGeneration++;
            stopRenderPump();
            cancelPendingUiCallbacks();
            utilityExpanded = false;
            topMenuExpanded = false;
            editControlsMode = false;
            uiMode = false;
            stopGamepadMonitor();
            activityRef.clear();
            rootRef.clear();
            touchpadRef.clear();
        }
    }

    private static void startRenderPump() {
        stopRenderPump();
        XServerDisplayActivity activity = activityRef.get();
        FrameLayout root = rootRef.get();
        if (activity == null || root == null) return;

        nextRenderDeadlineNanos = System.nanoTime();
        renderPump = new Runnable() {
            @Override public void run() {
                XServerDisplayActivity current = activityRef.get();
                FrameLayout currentRoot = rootRef.get();
                if (current == null || currentRoot == null || current.isFinishing() || sessionPaused) return;

                // Winlator's XServerView renders WHEN_DIRTY. FusionFall can update the
                // shared surface without always emitting an Android-side invalidation,
                // which made the last frame appear frozen until the cursor moved.
                // Keep a bounded presentation pulse only for the FusionFall session.
                LaunchConfig config = getLaunchConfig(current);
                int targetFps = config.fpsCap > 0 ? config.fpsCap : resolvePresentationFps(current);
                long intervalNanos = 1_000_000_000L / Math.max(1, targetFps);
                long now = System.nanoTime();
                if (now >= nextRenderDeadlineNanos) {
                    if (current.hasWindowFocus()) current.fusionFallRequestRender();
                    do nextRenderDeadlineNanos += intervalNanos;
                    while (nextRenderDeadlineNanos <= now);
                }
                long remainingNanos = Math.max(1_000_000L, nextRenderDeadlineNanos - System.nanoTime());
                long delayMs = Math.max(1L, remainingNanos / 1_000_000L);
                currentRoot.postDelayed(this, delayMs);
            }
        };
        root.post(renderPump);
    }

    private static void stopRenderPump() {
        FrameLayout root = rootRef.get();
        if (root != null && renderPump != null) root.removeCallbacks(renderPump);
        renderPump = null;
        nextRenderDeadlineNanos = 0L;
    }

    private static int resolvePresentationFps(Activity activity) {
        try {
            android.view.Display display = activity.getDisplay();
            if (display != null) return Math.max(30, Math.min(120, Math.round(display.getRefreshRate())));
        }
        catch (Throwable ignored) {}
        return 60;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, 0);
    }

    private static boolean isEnglish(Context context) {
        return "en".equals(prefs(context).getString(PREF_LANGUAGE, "es"));
    }

    private static String tr(Context context, String es, String en) {
        return isEnglish(context) ? en : es;
    }

    private static String controlLabel(Context context, String id) {
        if ("weapon".equals(id)) return tr(context, "ARMA", "WEAPON");
        if ("inventory".equals(id)) return tr(context, "INVENT.", "INV.");
        if ("map".equals(id)) return tr(context, "MAPA", "MAP");
        if ("attack".equals(id)) return "ATK";
        if ("jump".equals(id)) return "JUMP";
        if ("target".equals(id)) return "TARGET";
        if ("nano_power".equals(id)) return "NANO";
        if ("journal".equals(id)) return "JOURNAL";
        if ("email".equals(id)) return "E-MAIL";
        return id == null ? "" : id.toUpperCase(Locale.US);
    }

    private static int dp(Context context, float dp) {
        return Math.max(1, Math.round(dp * context.getResources().getDisplayMetrics().density));
    }

    private static void removeOverlay(FrameLayout root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && ((String)tag).startsWith(OVERLAY_TAG)) root.removeViewAt(i);
        }
    }

    private static final class Viewport {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Viewport(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int topBand() { return Math.max(0, top); }
        int bottomBand(int rootHeight) { return Math.max(0, rootHeight - bottom); }
        int leftBand() { return Math.max(0, left); }
        int rightBand(int rootWidth) { return Math.max(0, rootWidth - right); }
    }

    private static Viewport calculateViewport(FrameLayout root, LaunchConfig config) {
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw <= 0 || rh <= 0) {
            android.util.DisplayMetrics dm = root.getResources().getDisplayMetrics();
            rw = Math.max(dm.widthPixels, dm.heightPixels);
            rh = Math.min(dm.widthPixels, dm.heightPixels);
        }

        float surfaceAspect = (float)rw / (float)Math.max(1, rh);
        float gameAspect = (float)config.width / (float)Math.max(1, config.height);

        if (surfaceAspect > gameAspect) {
            int width = Math.round(rh * gameAspect);
            int left = Math.max(0, (rw - width) / 2);
            return new Viewport(left, 0, left + width, rh);
        }

        int height = Math.round(rw / gameAspect);
        int top = Math.max(0, (rh - height) / 2);
        return new Viewport(0, top, rw, top + height);
    }

    private static void markControl(View view) {
        view.setTag(CONTROL_TAG);
    }

    private static void pokeControl(View view) {
        if (view == null) return;
        SharedPreferences prefs = prefs(view.getContext());
        boolean autoFade = prefs.getBoolean("controls_auto_fade", true);
        Runnable previous = fadeTasks.remove(view);
        if (previous != null) view.removeCallbacks(previous);
        view.animate().cancel();
        view.setAlpha(1f);
        if (autoFade) {
            Runnable fade = () -> {
                fadeTasks.remove(view);
                if (view.isAttachedToWindow()) {
                    view.animate().alpha(DEFAULT_FADED_ALPHA).setDuration(320L).start();
                }
            };
            fadeTasks.put(view, fade);
            view.postDelayed(fade, 1800L);
        }
    }

    private static void applyIdleFade(FrameLayout root) {
        boolean autoFade = prefs(root.getContext()).getBoolean("controls_auto_fade", true);
        float alpha = autoFade ? DEFAULT_FADED_ALPHA : 1f;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (CONTROL_TAG.equals(child.getTag())) child.setAlpha(alpha);
        }
    }

    private static void setUiMode(XServerDisplayActivity activity, boolean enabled) {
        if (activity == null || activity.isFinishing() || uiMode == enabled) return;

        if (enabled) {
            releaseMovementKeys(activity);
            if (autoRun) setAutoRun(activity, false);
            if (padAttack || touchAttack) {
                activity.fusionFallSendMouseButton(false);
                padAttack = false;
                touchAttack = false;
            }
            endGamepadCamera(touchpadRef.get());
        }

        uiMode = enabled;
        applyInteractionMode(activity, touchpadRef.get());
        rebuild();
    }

    private static void applyInteractionMode(XServerDisplayActivity activity, TouchpadView touchpad) {
        if (activity == null || touchpad == null) return;
        touchpad.setMoveCursorToTouchpoint(uiMode);
        touchpad.setSensitivity(1.0f);
        setCursorVisible(activity, uiMode);
    }

    /**
     * Keep the POC resilient to small Winlator snapshot differences. The audited
     * source is patched with this helper, but reflection prevents a hard javac
     * dependency if a user applies only part of the mobile patch by mistake.
     */
    private static void setCursorVisible(Activity activity, boolean visible) {
        if (activity == null || activity.isFinishing()) return;
        try {
            java.lang.reflect.Method method = activity.getClass().getMethod(
                    "fusionFallSetCursorVisible", boolean.class);
            method.invoke(activity, visible);
        }
        catch (Throwable ignored) {
            // The Winlator cursor remains in its current state on older snapshots.
        }
    }

    /** Direct relative camera bridge. Avoids the synthetic virtual absolute pointer
     * used by POC4.8.0-4.8.2, which could clamp the vertical axis in fullscreen. */
    private static void sendRelativeCamera(Activity activity, int dx, int dy) {
        if (activity == null || activity.isFinishing() || (dx == 0 && dy == 0)) return;
        try {
            java.lang.reflect.Method method = activity.getClass().getMethod(
                    "fusionFallSendRelativeMouse", int.class, int.class);
            method.invoke(activity, dx, dy);
        }
        catch (Throwable ignored) {}
    }

    /** ATK aim must bypass X11 pointer movement while the left button is held. */
    private static void sendAttackAim(Activity activity, int dx, int dy) {
        if (activity == null || activity.isFinishing() || (dx == 0 && dy == 0)) return;
        try {
            java.lang.reflect.Method method = activity.getClass().getMethod(
                    "fusionFallSendAttackAim", int.class, int.class);
            method.invoke(activity, dx, dy);
        }
        catch (Throwable ignored) {}
    }

    private static void rebuild() {
        XServerDisplayActivity activity = activityRef.get();
        FrameLayout root = rootRef.get();
        TouchpadView touchpad = touchpadRef.get();
        if (activity == null || root == null || touchpad == null || activity.isFinishing() || sessionPaused) return;

        removeOverlay(root);
        SharedPreferences prefs = prefs(activity);
        float scale = prefs.getFloat("mobile_controls_scale", DEFAULT_SCALE);
        float opacity = prefs.getFloat("mobile_controls_opacity", DEFAULT_OPACITY);
        boolean leftHanded = prefs.getBoolean("left_handed", false);
        boolean hiddenByGamepad = prefs.getBoolean("auto_hide_touch_on_gamepad", true) && gamepadConnected;
        boolean visible = prefs.getBoolean("mobile_controls_visible", true) && !hiddenByGamepad;
        if (editControlsMode) visible = true;

        applyInteractionMode(activity, touchpad);

        LaunchConfig config = getLaunchConfig(activity);
        Viewport viewport = calculateViewport(root, config);
        int rootWidth = root.getWidth();
        int rootHeight = root.getHeight();
        if (rootWidth <= 0 || rootHeight <= 0) {
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            rootWidth = Math.max(dm.widthPixels, dm.heightPixels);
            rootHeight = Math.min(dm.widthPixels, dm.heightPixels);
        }

        int topBand = viewport.topBand();
        int bottomBand = viewport.bottomBand(rootHeight);
        int sideBand = leftHanded ? viewport.leftBand() : viewport.rightBand(rootWidth);

        addTopToolbar(activity, root, opacity, topBand);
        if (prefs.getBoolean("show_performance_hud", false)) {
            addPerformanceStats(activity, root, viewport);
        }

        // UI mode deliberately removes the gameplay interception layer. The stock
        // Winlator TouchpadView then behaves like a direct Android mouse: tap on a
        // control to click it, drag to move the cursor, and use the keyboard button
        // in the toolbar for chat/text entry.
        if (uiMode && !editControlsMode) return;

        int moveGravity = Gravity.BOTTOM | (leftHanded ? Gravity.END : Gravity.START);
        int actionGravity = Gravity.BOTTOM | (leftHanded ? Gravity.START : Gravity.END);

        int baseBottom = bottomBand > dp(activity, 24f)
                ? Math.max(dp(activity, 10f), bottomBand - dp(activity, 22f))
                : dp(activity, 18f);
        int sideBase = sideBand > dp(activity, 30f)
                ? Math.max(dp(activity, 12f), sideBand / 3)
                : dp(activity, 18f);

        // Mobile Controls V2: a dedicated camera surface uses TouchpadView.mouseMove
        // (relative mouse semantics) and never emits a click. The opposite lower
        // corner remains reserved for the movement joystick.
        int nanoReserve = Math.max(dp(activity, 154f * scale), Math.round(rootHeight * 0.21f));
        if (!editControlsMode && prefs.getBoolean("touch_camera_enabled", true)) {
            CameraTouchView camera = new CameraTouchView(activity, touchpad);
            camera.setTag(OVERLAY_TAG + "_camera");
            int cameraWidth = Math.min(rootWidth, Math.max(dp(activity, 260f), Math.round(rootWidth * 0.56f)));
            float toolbarDp = topMenuExpanded ? expandedToolbarHeightDp(activity) : (editControlsMode ? 72f : 52f);
            int toolbarClearance = Math.max(dp(activity, toolbarDp), topBand + dp(activity, 6f));
            int cameraHeight = Math.min(rootHeight, Math.max(dp(activity, 160f), rootHeight - toolbarClearance - nanoReserve));
            FrameLayout.LayoutParams cameraLp = new FrameLayout.LayoutParams(cameraWidth, cameraHeight);
            cameraLp.gravity = Gravity.TOP | (leftHanded ? Gravity.START : Gravity.END);
            cameraLp.topMargin = toolbarClearance;
            root.addView(camera, cameraLp);
        }

        // The original FusionFall Nano HUD remains usable. This transparent zone
        // converts a tap to an absolute cursor move + click instead of treating it
        // as camera input, so players can still touch the native game interface.
        if (!editControlsMode && prefs.getBoolean("native_hud_touch_enabled", true)) {
            NativeHudTouchView nativeHud = new NativeHudTouchView(activity, touchpad);
            nativeHud.setTag(OVERLAY_TAG + "_native_hud");
            int nativeHudWidth = Math.min(rootWidth, Math.max(dp(activity, 210f), Math.round(rootWidth * 0.27f)));
            int nativeHudHeight = Math.min(rootHeight, Math.max(dp(activity, 132f), nanoReserve));
            FrameLayout.LayoutParams nativeHudLp = new FrameLayout.LayoutParams(nativeHudWidth, nativeHudHeight);
            nativeHudLp.gravity = Gravity.BOTTOM | Gravity.END;
            nativeHudLp.bottomMargin = Math.max(0, bottomBand);
            root.addView(nativeHud, nativeHudLp);

            // POC4.6.3: the three equipped-Nano caps in FusionFall's field HUD are
            // keyboard indicators, not reliable mouse buttons in this client build.
            // Put transparent hit targets exactly over those native slots and map
            // them to the same 1/2/3 key events that the PC client expects. This
            // preserves the native artwork while making it genuinely touchable.
            addNativeNanoHotspots(activity, root, viewport);
        }

        // Hiding visible controls keeps camera + native HUD touch active. This is
        // useful with external controllers while preserving touchscreen fallback.
        if (!visible) return;

        if (editControlsMode) addProtectedZoneGuides(activity, root);

        boolean floatingJoystick = "floating".equals(prefs.getString("joystick_mode", "fixed"));
        JoystickView joystick = new JoystickView(activity, opacity, floatingJoystick);
        markControl(joystick);
        int joyBottom = leftHanded ? Math.max(baseBottom, nanoReserve + dp(activity, 10f)) : baseBottom;
        if (floatingJoystick && !editControlsMode) {
            int joyWidth = Math.min(dp(activity, 360f), Math.max(dp(activity, 220f), Math.round(rootWidth * 0.38f)));
            int joyHeight = Math.min(dp(activity, 300f), Math.max(dp(activity, 190f), Math.round(rootHeight * 0.40f)));
            FrameLayout.LayoutParams joyLp = new FrameLayout.LayoutParams(joyWidth, joyHeight);
            joyLp.gravity = moveGravity;
            joyLp.setMargins(sideBase, 0, sideBase, joyBottom);
            root.addView(joystick, joyLp);
            applySavedOffset(activity, joystick, "joystick");
        }
        else {
            int joySize = dp(activity, 132f * scale);
            FrameLayout.LayoutParams joyLp = new FrameLayout.LayoutParams(joySize, joySize);
            joyLp.gravity = moveGravity;
            joyLp.setMargins(sideBase, 0, sideBase, joyBottom);
            root.addView(joystick, joyLp);
            applySavedOffset(activity, joystick, "joystick");
        }

        // POC4.9.1 HUD personalization: every mobile action can live either
        // as a permanent draggable button or inside the compact ⋯ menu. During
        // edit mode all configurable buttons are temporarily shown.
        int hudClearance = baseBottom + dp(activity, 94f * scale);
        if (editControlsMode || controlVisible(activity, "attack"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "attack"), "attack", actionGravity,
                    sideBase, hudClearance, dp(activity, 66f * scale), opacity, Action.MOUSE_CLICK);
        if (editControlsMode || controlVisible(activity, "jump"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "jump"), "jump", actionGravity,
                    sideBase + dp(activity, 74f * scale), hudClearance + dp(activity, 12f * scale),
                    dp(activity, 54f * scale), opacity, Action.KEY_SPACE);
        if (editControlsMode || controlVisible(activity, "target"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "target"), "target", actionGravity,
                    sideBase + dp(activity, 5f * scale), hudClearance + dp(activity, 84f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_Z);
        if (editControlsMode || controlVisible(activity, "weapon"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "weapon"), "weapon", actionGravity,
                    sideBase + dp(activity, 76f * scale), hudClearance + dp(activity, 84f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_TAB);
        if (editControlsMode || controlVisible(activity, "nano_power"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "nano_power"), "nano_power", actionGravity,
                    sideBase + dp(activity, 5f * scale), hudClearance + dp(activity, 145f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_X);

        // Optional permanent utility buttons. Their default is hidden because the
        // compact menu remains the primary home for these non-combat actions.
        if (editControlsMode || controlVisible(activity, "inventory"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "inventory"), "inventory", actionGravity,
                    sideBase + dp(activity, 76f * scale), hudClearance + dp(activity, 145f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_I);
        if (editControlsMode || controlVisible(activity, "journal"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "journal"), "journal", actionGravity,
                    sideBase + dp(activity, 5f * scale), hudClearance + dp(activity, 206f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_J);
        if (editControlsMode || controlVisible(activity, "email"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "email"), "email", actionGravity,
                    sideBase + dp(activity, 76f * scale), hudClearance + dp(activity, 206f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_P);
        if (editControlsMode || controlVisible(activity, "map"))
            addActionButton(activity, root, touchpad, controlLabel(activity, "map"), "map", actionGravity,
                    sideBase + dp(activity, 5f * scale), hudClearance + dp(activity, 267f * scale),
                    dp(activity, 50f * scale), opacity, Action.KEY_M);

        // The native Nano HUD is now the primary mobile control. Legacy 1/2/3
        // shortcuts remain available as an opt-in fallback only.
        if (prefs.getBoolean("show_nano_shortcuts", false)) {
            int nanoBottom = hudClearance + dp(activity, 91f * scale);
            int nanoSide = sideBase + dp(activity, 66f * scale);
            addActionButton(activity, root, touchpad, "1", null, actionGravity,
                    nanoSide, nanoBottom, dp(activity, 40f * scale), opacity, Action.KEY_1);
            addActionButton(activity, root, touchpad, "2", null, actionGravity,
                    nanoSide + dp(activity, 47f * scale), nanoBottom,
                    dp(activity, 40f * scale), opacity, Action.KEY_2);
            addActionButton(activity, root, touchpad, "3", null, actionGravity,
                    nanoSide + dp(activity, 94f * scale), nanoBottom,
                    dp(activity, 40f * scale), opacity, Action.KEY_3);
        }

        if (!editControlsMode) applyIdleFade(root);
    }

    private static void addTopToolbar(XServerDisplayActivity activity, FrameLayout root, float opacity, int topBand) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setTag(OVERLAY_TAG + "_top_menu");
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);

        if (editControlsMode) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);

            TextView status = makeButton(activity, tr(activity, "EDITANDO", "EDITING"), 88f, 38f, Math.max(opacity, 0.80f));
            status.setTextSize(11f);
            status.setContentDescription(tr(activity, "Editor de controles activo", "Controls editor active"));
            row.addView(status);

            TextView reset = makeButton(activity, "RESET", 68f, 38f, Math.max(opacity, 0.76f));
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(dp(activity, 68f), dp(activity, 38f));
            resetLp.leftMargin = dp(activity, 8f);
            row.addView(reset, resetLp);
            reset.setOnClickListener(v -> {
                haptic(v);
                resetControlOffsets(activity);
                rebuild();
            });

            TextView done = makeButton(activity, tr(activity, "LISTO", "DONE"), 68f, 38f, Math.max(opacity, 0.86f));
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(dp(activity, 68f), dp(activity, 38f));
            doneLp.leftMargin = dp(activity, 8f);
            row.addView(done, doneLp);
            done.setOnClickListener(v -> {
                haptic(v);
                editControlsMode = false;
                topMenuExpanded = false;
                rebuild();
            });

            panel.addView(row);
        }
        else {
            TextView toggle = makeButton(activity, topMenuExpanded ? "×" : "⋯",
                    54f, 40f, Math.max(opacity, 0.72f));
            toggle.setTextSize(22f);
            toggle.setContentDescription(topMenuExpanded ? tr(activity, "Cerrar menú móvil", "Close mobile menu") : tr(activity, "Abrir menú móvil", "Open mobile menu"));
            panel.addView(toggle, new LinearLayout.LayoutParams(dp(activity, 54f), dp(activity, 40f)));
            toggle.setOnClickListener(v -> {
                haptic(v);
                topMenuExpanded = !topMenuExpanded;
                rebuild();
            });

            if (topMenuExpanded) {
                LinearLayout row1 = new LinearLayout(activity);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                row1.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40f));
                rowLp.topMargin = dp(activity, 6f);
                panel.addView(row1, rowLp);

                TextView mode = makeButton(activity, uiMode ? tr(activity, "JUEGO", "GAME") : "UI", 62f, 36f, Math.max(opacity, 0.78f));
                mode.setTextSize(11f);
                mode.setContentDescription(uiMode ? tr(activity, "Volver al modo juego", "Return to game mode") : tr(activity, "Cambiar al modo interfaz", "Switch to UI mode"));
                row1.addView(mode);
                mode.setOnClickListener(v -> {
                    haptic(v);
                    topMenuExpanded = false;
                    setUiMode(activity, !uiMode);
                });

                TextView keyboard = makeButton(activity, tr(activity, "TECLADO", "KEYBOARD"), 72f, 36f, Math.max(opacity, 0.72f));
                keyboard.setTextSize(10f);
                keyboard.setContentDescription(tr(activity, "Mostrar teclado Android", "Show Android keyboard"));
                LinearLayout.LayoutParams keyboardLp = new LinearLayout.LayoutParams(dp(activity, 72f), dp(activity, 36f));
                keyboardLp.leftMargin = dp(activity, 6f);
                row1.addView(keyboard, keyboardLp);
                keyboard.setOnClickListener(v -> {
                    haptic(v);
                    AppUtils.showKeyboard(activity);
                });

                TextView settings = makeButton(activity, tr(activity, "AJUSTES", "SETTINGS"), 72f, 36f, Math.max(opacity, 0.72f));
                settings.setTextSize(10f);
                settings.setContentDescription(tr(activity, "Ajustes móviles de FusionFall", "FusionFall mobile settings"));
                LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(activity, 72f), dp(activity, 36f));
                settingsLp.leftMargin = dp(activity, 6f);
                row1.addView(settings, settingsLp);
                settings.setOnClickListener(v -> {
                    haptic(v);
                    showSettings(activity, FusionFallMobileControls::rebuild);
                });

                if (!uiMode) {
                    TextView edit = makeButton(activity, tr(activity, "EDITAR", "EDIT"), 66f, 36f, Math.max(opacity, 0.72f));
                    edit.setTextSize(10f);
                    edit.setContentDescription(tr(activity, "Editar posición de los controles", "Edit control positions"));
                    LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(activity, 66f), dp(activity, 36f));
                    editLp.leftMargin = dp(activity, 6f);
                    row1.addView(edit, editLp);
                    edit.setOnClickListener(v -> {
                        haptic(v);
                        topMenuExpanded = false;
                        editControlsMode = true;
                        rebuild();
                    });
                }

                LinearLayout row2 = new LinearLayout(activity);
                row2.setOrientation(LinearLayout.HORIZONTAL);
                row2.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40f));
                row2Lp.topMargin = dp(activity, 4f);
                panel.addView(row2, row2Lp);

                boolean requestedVisible = prefs(activity).getBoolean("mobile_controls_visible", true);
                TextView hide = makeButton(activity, requestedVisible ? tr(activity, "OCULTAR", "HIDE") : tr(activity, "MOSTRAR", "SHOW"),
                        72f, 36f, Math.max(opacity, 0.70f));
                hide.setTextSize(10f);
                hide.setContentDescription(requestedVisible ? tr(activity, "Ocultar controles táctiles", "Hide touch controls") : tr(activity, "Mostrar controles táctiles", "Show touch controls"));
                row2.addView(hide);
                hide.setOnClickListener(v -> {
                    haptic(v);
                    prefs(activity).edit().putBoolean("mobile_controls_visible", !requestedVisible).apply();
                    rebuild();
                });

                TextView chat = makeButton(activity, "CHAT", 58f, 36f, Math.max(opacity, 0.70f));
                LinearLayout.LayoutParams chatLp = new LinearLayout.LayoutParams(dp(activity, 58f), dp(activity, 36f));
                chatLp.leftMargin = dp(activity, 6f);
                row2.addView(chat, chatLp);
                chat.setOnClickListener(v -> {
                    haptic(v);
                    sendKeyTap(activity, KeyEvent.KEYCODE_ENTER);
                    v.postDelayed(() -> AppUtils.showKeyboard(activity), 140L);
                });

                TextView auto = makeButton(activity, autoRun ? "AUTO✓" : "AUTO", 60f, 36f, Math.max(opacity, 0.70f));
                LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(dp(activity, 60f), dp(activity, 36f));
                autoLp.leftMargin = dp(activity, 6f);
                row2.addView(auto, autoLp);
                auto.setOnClickListener(v -> {
                    haptic(v);
                    setAutoRun(activity, !autoRun);
                    auto.setText(autoRun ? "AUTO✓" : "AUTO");
                });

                boolean floatingJoy = "floating".equals(prefs(activity).getString("joystick_mode", "fixed"));
                TextView joyMode = makeButton(activity, floatingJoy ? tr(activity, "JOY FLOT.", "JOY FLOAT") : tr(activity, "JOY FIJO", "JOY FIXED"),
                        78f, 36f, Math.max(opacity, 0.70f));
                joyMode.setTextSize(9.5f);
                joyMode.setContentDescription(floatingJoy ? tr(activity, "Cambiar joystick a fijo", "Switch joystick to fixed") : tr(activity, "Cambiar joystick a flotante", "Switch joystick to floating"));
                LinearLayout.LayoutParams joyModeLp = new LinearLayout.LayoutParams(dp(activity, 78f), dp(activity, 36f));
                joyModeLp.leftMargin = dp(activity, 6f);
                row2.addView(joyMode, joyModeLp);
                joyMode.setOnClickListener(v -> {
                    haptic(v);
                    prefs(activity).edit().putString("joystick_mode", floatingJoy ? "fixed" : "floating").apply();
                    rebuild();
                });

                // Every action hidden from the permanent HUD is automatically available here.
                addHiddenActionsMenu(activity, panel, opacity);
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int barHeight = dp(activity, 40f);
        lp.topMargin = topBand >= barHeight + dp(activity, 8f)
                ? Math.max(dp(activity, 4f), (topBand - barHeight) / 2)
                : dp(activity, 8f);
        root.addView(panel, lp);
    }

    private static float expandedToolbarHeightDp(Context context) {
        int hidden = 0;
        String[] ids = {"attack", "jump", "target", "weapon", "nano_power", "inventory", "journal", "email", "map"};
        for (String id : ids) if (!controlVisible(context, id)) hidden++;
        int rows = (hidden + 3) / 4;
        return 136f + rows * 44f;
    }

    private static void addHiddenActionsMenu(XServerDisplayActivity activity, LinearLayout panel, float opacity) {
        String[] ids = {"attack", "jump", "target", "weapon", "nano_power", "inventory", "journal", "email", "map"};
        String[] labels = {controlLabel(activity, "attack"), controlLabel(activity, "jump"),
                controlLabel(activity, "target"), controlLabel(activity, "weapon"), controlLabel(activity, "nano_power"),
                controlLabel(activity, "inventory"), controlLabel(activity, "journal"), controlLabel(activity, "email"),
                controlLabel(activity, "map")};
        Action[] actions = {Action.MOUSE_CLICK, Action.KEY_SPACE, Action.KEY_Z, Action.KEY_TAB, Action.KEY_X,
                Action.KEY_I, Action.KEY_J, Action.KEY_P, Action.KEY_M};
        String[] descriptions = {tr(activity, "Ataque / usar objetivo", "Attack / Use Target"),
                tr(activity, "Salto", "Jump"), tr(activity, "Seleccionar objetivo", "Target"),
                tr(activity, "Cambiar arma", "Switch Weapon"), tr(activity, "Poder de Nano", "Nano Power"),
                tr(activity, "Inventario / My Stuff", "My Stuff / Inventory"), "Journal", "E-Mail",
                tr(activity, "Mapa", "Map")};

        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < ids.length; i++) {
            if (controlVisible(activity, ids[i])) continue;
            if (row == null || inRow == 4) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40f));
                rowLp.topMargin = dp(activity, 4f);
                panel.addView(row, rowLp);
                inRow = 0;
            }
            TextView button = quickActionButton(activity, labels[i], descriptions[i], 68f, opacity, actions[i]);
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(dp(activity, 68f), dp(activity, 36f));
            if (inRow > 0) buttonLp.leftMargin = dp(activity, 6f);
            row.addView(button, buttonLp);
            inRow++;
        }
    }

    private static TextView quickActionButton(XServerDisplayActivity activity, String label,
                                               String description, float widthDp, float opacity, Action action) {
        TextView button = makeButton(activity, label, widthDp, 36f, Math.max(opacity, 0.70f));
        button.setTextSize(label.length() > 7 ? 8.5f : 9.5f);
        button.setContentDescription(description);
        button.setOnClickListener(v -> {
            haptic(v);
            if (action == Action.MOUSE_CLICK) {
                activity.fusionFallSendMouseButton(true);
                v.postDelayed(() -> activity.fusionFallSendMouseButton(false), 55L);
            }
            else {
                int keyCode = keyCodeForAction(action);
                if (keyCode != 0) sendKeyTap(activity, keyCode);
            }
        });
        return button;
    }

    private static void addProtectedZoneGuides(Context context, FrameLayout root) {
        addProtectedGuide(context, root, tr(context, "MINIMAPA", "MINIMAP"), Gravity.TOP | Gravity.END, 0, dp(context, 6f),
                Math.max(dp(context, 190f), Math.round(root.getWidth() * 0.25f)),
                Math.max(dp(context, 120f), Math.round(root.getHeight() * 0.27f)));
        addProtectedGuide(context, root, "NANOS", Gravity.BOTTOM | Gravity.END, 0, dp(context, 4f),
                Math.max(dp(context, 210f), Math.round(root.getWidth() * 0.28f)),
                Math.max(dp(context, 130f), Math.round(root.getHeight() * 0.22f)));
        addProtectedGuide(context, root, "CHAT", Gravity.BOTTOM | Gravity.START, 0, dp(context, 4f),
                Math.max(dp(context, 250f), Math.round(root.getWidth() * 0.34f)),
                Math.max(dp(context, 115f), Math.round(root.getHeight() * 0.20f)));
    }

    private static void addProtectedGuide(Context context, FrameLayout root, String label, int gravity,
                                          int sideMargin, int verticalMargin, int width, int height) {
        TextView guide = new TextView(context);
        guide.setTag(OVERLAY_TAG + "_protected_" + label);
        guide.setText(label + tr(context, " · HUD PROTEGIDO", " · PROTECTED HUD"));
        guide.setTextColor(Color.argb(210, 255, 120, 120));
        guide.setTextSize(10f);
        guide.setGravity(Gravity.CENTER);
        guide.setClickable(false);
        guide.setFocusable(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(24, 255, 60, 60));
        bg.setStroke(dp(context, 1f), Color.argb(170, 255, 90, 90));
        bg.setCornerRadius(dp(context, 8f));
        guide.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(root.getWidth(), width), Math.min(root.getHeight(), height));
        lp.gravity = gravity;
        lp.setMargins(sideMargin, verticalMargin, sideMargin, verticalMargin);
        root.addView(guide, lp);
    }

    private static TextView quickKeyButton(XServerDisplayActivity activity, String label,
                                               String description, float widthDp, float opacity, int keyCode) {
        TextView button = makeButton(activity, label, widthDp, 36f, Math.max(opacity, 0.70f));
        button.setTextSize(9.5f);
        button.setContentDescription(description);
        button.setOnClickListener(v -> {
            haptic(v);
            sendKeyTap(activity, keyCode);
        });
        return button;
    }

    private static void addUtilityMenu(XServerDisplayActivity activity, FrameLayout root,
                                       float opacity, float scale, int bottomBand) {
        int buttonHeight = dp(activity, 40f * scale);
        int bottomMargin = bottomBand >= buttonHeight + dp(activity, 8f)
                ? Math.max(dp(activity, 4f), (bottomBand - buttonHeight) / 2)
                : dp(activity, 10f);

        TextView toggle = makeButton(activity, utilityExpanded ? "×" : "⋯",
                50f * scale, 40f * scale, Math.max(opacity, 0.64f));
        markControl(toggle);
        toggle.setTextSize(22f);
        toggle.setContentDescription(utilityExpanded ? tr(activity, "Cerrar accesos rápidos", "Close quick actions") : tr(activity, "Abrir accesos rápidos", "Open quick actions"));
        FrameLayout.LayoutParams toggleLp = new FrameLayout.LayoutParams(
                dp(activity, 50f * scale), dp(activity, 40f * scale));
        toggleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        toggleLp.bottomMargin = bottomMargin;
        root.addView(toggle, toggleLp);
        toggle.setOnClickListener(v -> {
            haptic(v);
            utilityExpanded = !utilityExpanded;
            rebuild();
        });

        if (!utilityExpanded) return;

        LinearLayout utility = new LinearLayout(activity);
        markControl(utility);
        utility.setOrientation(LinearLayout.HORIZONTAL);
        utility.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams utilLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        utilLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        utilLp.bottomMargin = bottomMargin + dp(activity, 48f * scale);
        root.addView(utility, utilLp);

        TextView chat = makeButton(activity, "CHAT", 66f * scale, 42f * scale, opacity);
        chat.setContentDescription(tr(activity, "Abrir chat y teclado Android", "Open chat and Android keyboard"));
        chat.setOnClickListener(v -> {
            pokeControl(utility);
            haptic(v);
            sendKeyTap(activity, KeyEvent.KEYCODE_ENTER);
            v.postDelayed(() -> AppUtils.showKeyboard(activity), 140L);
        });
        utility.addView(chat);

        TextView auto = makeButton(activity, autoRun ? "AUTO✓" : "AUTO", 66f * scale, 42f * scale, opacity);
        auto.setContentDescription(tr(activity, "Alternar carrera automática", "Toggle auto-run"));
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                dp(activity, 66f * scale), dp(activity, 42f * scale));
        autoLp.leftMargin = dp(activity, 8f);
        utility.addView(auto, autoLp);
        auto.setOnClickListener(v -> {
            pokeControl(utility);
            haptic(v);
            setAutoRun(activity, !autoRun);
            auto.setText(autoRun ? "AUTO✓" : "AUTO");
        });

        TextView menu = makeButton(activity, "MENU", 66f * scale, 42f * scale, opacity);
        menu.setContentDescription(tr(activity, "Abrir menú de FusionFall", "Open FusionFall menu"));
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                dp(activity, 66f * scale), dp(activity, 42f * scale));
        menuLp.leftMargin = dp(activity, 8f);
        utility.addView(menu, menuLp);
        menu.setOnClickListener(v -> {
            pokeControl(utility);
            haptic(v);
            sendKeyTap(activity, KeyEvent.KEYCODE_ESCAPE);
        });
    }

    private static void addNativeNanoHotspots(XServerDisplayActivity activity, FrameLayout root, Viewport viewport) {
        int rootWidth = Math.max(1, root.getWidth());
        int rootHeight = Math.max(1, root.getHeight());
        int viewportWidth = Math.max(1, viewport.right - viewport.left);
        int viewportHeight = Math.max(1, viewport.bottom - viewport.top);
        float surfaceAspect = (float)rootWidth / rootHeight;
        float viewportAspect = (float)viewportWidth / viewportHeight;

        // Winlator's fullscreen renderer stretches the game surface on extra-wide
        // displays such as an unfolded Galaxy Z Fold. The previous code continued
        // anchoring to the theoretical 16:9 letterbox, moving all three invisible
        // hitboxes inward while the native Nano artwork stayed at the physical edge.
        boolean stretchedWideSurface = surfaceAspect > viewportAspect + 0.08f;
        int hudLeft = stretchedWideSurface ? 0 : viewport.left;
        int hudTop = stretchedWideSurface ? 0 : viewport.top;
        int hudRight = stretchedWideSurface ? rootWidth : viewport.right;
        int hudBottom = stretchedWideSurface ? rootHeight : viewport.bottom;
        int gameWidth = Math.max(1, hudRight - hudLeft);
        int gameHeight = Math.max(1, hudBottom - hudTop);

        // The three equipped Nano caps sit at the extreme lower right
        // of the 16:9 game viewport. Ratios keep the hotspots aligned across the
        // 960x540 / 1280x720 / 1600x900 profiles and letterboxed Android screens.
        int slotWidth = Math.max(54, Math.round(gameWidth * 0.060f));
        // On the unfolded extra-wide surface the slots are visually closer to
        // the bottom edge. A slightly shorter bottom aligned target moves the
        // active area down without affecting phones using the normal viewport.
        float slotHeightRatio = stretchedWideSurface ? 0.090f : 0.105f;
        int slotHeight = Math.max(54, Math.round(gameHeight * slotHeightRatio));
        float[] centerFromRight = {0.150f, 0.095f, 0.040f};
        int[] keys = {KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3};

        int centerY = hudTop + Math.round(gameHeight * 0.952f);
        int top = Math.max(hudTop, Math.min(hudBottom - slotHeight, centerY - slotHeight / 2));

        for (int i = 0; i < 3; i++) {
            final int slot = i + 1;
            final int keyCode = keys[i];
            int centerX = hudRight - Math.round(gameWidth * centerFromRight[i]);
            int left = Math.max(hudLeft, Math.min(hudRight - slotWidth, centerX - slotWidth / 2));

            View hotspot = new View(activity);
            hotspot.setTag(OVERLAY_TAG + "_native_nano_" + slot);
            hotspot.setBackgroundColor(Color.TRANSPARENT);
            hotspot.setClickable(true);
            hotspot.setFocusable(false);
            hotspot.setContentDescription(tr(activity, "Activar Nano ", "Activate Nano ") + slot);
            hotspot.setOnTouchListener((v, event) -> {
                int a = event.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    haptic(v);
                    sendKey(activity, keyCode, true);
                    return true;
                }
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    sendKey(activity, keyCode, false);
                    return true;
                }
                return true;
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(slotWidth, slotHeight);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = left;
            lp.topMargin = top;
            root.addView(hotspot, lp);
        }
    }

    private enum Action {
        MOUSE_CLICK, KEY_SPACE, KEY_1, KEY_2, KEY_3, KEY_TAB, KEY_X, KEY_Z, KEY_I, KEY_J, KEY_P, KEY_M
    }

    private static int keyCodeForAction(Action action) {
        switch (action) {
            case KEY_SPACE: return KeyEvent.KEYCODE_SPACE;
            case KEY_1: return KeyEvent.KEYCODE_1;
            case KEY_2: return KeyEvent.KEYCODE_2;
            case KEY_3: return KeyEvent.KEYCODE_3;
            case KEY_TAB: return KeyEvent.KEYCODE_TAB;
            case KEY_X: return KeyEvent.KEYCODE_X;
            case KEY_Z: return KeyEvent.KEYCODE_Z;
            case KEY_I: return KeyEvent.KEYCODE_I;
            case KEY_J: return KeyEvent.KEYCODE_J;
            case KEY_P: return KeyEvent.KEYCODE_P;
            case KEY_M: return KeyEvent.KEYCODE_M;
            default: return 0;
        }
    }

    private static final class AttackCameraDrag {
        float downRawX, downRawY, lastRawX, lastRawY;
        float remainderX, remainderY;
        boolean engaged;

        void begin(MotionEvent event) {
            downRawX = lastRawX = event.getRawX();
            downRawY = lastRawY = event.getRawY();
            remainderX = remainderY = 0f;
            engaged = false;
        }

        void move(Context context, MotionEvent event) {
            float rawX = event.getRawX();
            float rawY = event.getRawY();
            SharedPreferences p = prefs(context);
            float dead = dp(context, p.getFloat("camera_drag_deadzone_dp", DEFAULT_CAMERA_DRAG_DEADZONE_DP));
            if (!engaged) {
                float dx0 = rawX - downRawX;
                float dy0 = rawY - downRawY;
                if (dx0 * dx0 + dy0 * dy0 < dead * dead) return;
                engaged = true;
                lastRawX = rawX;
                lastRawY = rawY;
                return;
            }

            float sx = p.getFloat("camera_sensitivity_x", DEFAULT_CAMERA_X);
            float sy = p.getFloat("camera_sensitivity_y", DEFAULT_CAMERA_Y);
            if (p.getBoolean("camera_invert_y", false)) sy = -sy;
            float maxDelta = dp(context, 42f);
            float dx = Math.max(-maxDelta, Math.min(maxDelta, rawX - lastRawX));
            float dy = Math.max(-maxDelta, Math.min(maxDelta, rawY - lastRawY));
            lastRawX = rawX;
            lastRawY = rawY;

            float scaledX = dx * sx + remainderX;
            float scaledY = dy * sy + remainderY;
            int outX = Math.round(scaledX);
            int outY = Math.round(scaledY);
            remainderX = scaledX - outX;
            remainderY = scaledY - outY;
            sendAttackAim(activityRef.get(), outX, outY);
        }

        void end() {
            engaged = false;
            remainderX = remainderY = 0f;
        }
    }

    private static void addActionButton(XServerDisplayActivity activity, FrameLayout root,
                                        TouchpadView touchpad, String text, String controlId, int gravity,
                                        int sideMarginPx, int bottomMarginPx, int sizePx,
                                        float opacity, Action action) {
        if (controlId != null) {
            sizePx = Math.max(dp(activity, 34f), Math.round(sizePx * controlScale(activity, controlId)));
            opacity = Math.max(0.18f, Math.min(0.95f, opacity * controlOpacityMultiplier(activity, controlId)));
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int extraHit = dp(activity, action == Action.MOUSE_CLICK ? 16f : 10f);
        int hitSize = sizePx + extraHit * 2;

        FrameLayout hitbox = new FrameLayout(activity);
        markControl(hitbox);
        hitbox.setContentDescription("FusionFall " + text);
        hitbox.setClickable(true);
        hitbox.setFocusable(false);

        TextView button = makeButton(activity, text, sizePx / density, sizePx / density, opacity);
        FrameLayout.LayoutParams visualLp = new FrameLayout.LayoutParams(sizePx, sizePx);
        visualLp.gravity = Gravity.CENTER;
        hitbox.addView(button, visualLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(hitSize, hitSize);
        lp.gravity = gravity;
        int adjustedSide = Math.max(0, sideMarginPx - extraHit);
        int adjustedBottom = Math.max(0, bottomMarginPx - extraHit);
        lp.setMargins(adjustedSide, 0, adjustedSide, adjustedBottom);
        root.addView(hitbox, lp);
        if (controlId != null) applySavedOffset(activity, hitbox, controlId);

        if (editControlsMode && controlId != null) {
            button.setText(text + "\n↕");
            button.setTextSize(10f);
            hitbox.setOnTouchListener((v, event) -> handleControlEditDrag(v, controlId, event));
            return;
        }

        if (action == Action.MOUSE_CLICK) {
            AttackCameraDrag attackCamera = new AttackCameraDrag();
            hitbox.setOnTouchListener((v, event) -> {
                int actionMasked = event.getActionMasked();
                if (actionMasked == MotionEvent.ACTION_DOWN) {
                    pokeControl(v);
                    haptic(v);
                    button.animate().scaleX(0.92f).scaleY(0.92f).setDuration(55L).start();
                    touchAttack = true;
                    activity.fusionFallSendMouseButton(true);
                    attackCamera.begin(event);
                    return true;
                }
                if (actionMasked == MotionEvent.ACTION_MOVE) {
                    // Android keeps dispatching this gesture to the original ATK
                    // hitbox even after the finger leaves it. Keep attack held and
                    // turn the drag into relative camera motion for comfortable aim.
                    attackCamera.move(activity, event);
                    return true;
                }
                if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                    if (touchAttack) activity.fusionFallSendMouseButton(false);
                    touchAttack = false;
                    attackCamera.end();
                    button.animate().scaleX(1f).scaleY(1f).setDuration(70L).start();
                    return true;
                }
                return true;
            });
            return;
        }

        final int keyCode = keyCodeForAction(action);
        if (keyCode == 0) return;

        hitbox.setOnTouchListener((v, event) -> {
            int actionMasked = event.getActionMasked();
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                pokeControl(v);
                haptic(v);
                button.animate().scaleX(0.94f).scaleY(0.94f).setDuration(55L).start();
                sendKey(activity, keyCode, true);
                return true;
            }
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                sendKey(activity, keyCode, false);
                button.animate().scaleX(1f).scaleY(1f).setDuration(70L).start();
                return true;
            }
            return true;
        });
    }

    private static TextView makeButton(Context context, String text, float widthDp, float heightDp, float opacity) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(12f);
        view.setGravity(Gravity.CENTER);
        view.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(context, Math.min(widthDp, heightDp) * 0.34f));
        int alpha = Math.max(40, Math.min(230, Math.round(opacity * 255f)));
        bg.setColor(Color.argb(alpha, 12, 22, 28));
        bg.setStroke(dp(context, 1.25f), Color.argb(Math.min(255, alpha + 55), 45, 220, 255));
        view.setBackground(bg);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp)));
        return view;
    }

    private static void haptic(View view) {
        if (view == null) return;
        if (prefs(view.getContext()).getBoolean("haptic_feedback", true)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private static void sendKeyTap(XServerDisplayActivity activity, int keyCode) {
        sendKey(activity, keyCode, true);
        activity.getWindow().getDecorView().postDelayed(() -> sendKey(activity, keyCode, false), 34L);
    }

    private static void sendKey(XServerDisplayActivity activity, int keyCode, boolean down) {
        if (activity == null || activity.isFinishing()) return;
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                0, InputDevice.SOURCE_KEYBOARD);
        activity.fusionFallSendKeyEvent(event);
    }

    private static void setAutoRun(XServerDisplayActivity activity, boolean enabled) {
        if (autoRun == enabled) return;
        autoRun = enabled;
        sendKey(activity, KeyEvent.KEYCODE_W, enabled);
    }

    private static void releaseMovementKeys(XServerDisplayActivity activity) {
        if (padW) sendKey(activity, KeyEvent.KEYCODE_W, false);
        if (padA) sendKey(activity, KeyEvent.KEYCODE_A, false);
        if (padS) sendKey(activity, KeyEvent.KEYCODE_S, false);
        if (padD) sendKey(activity, KeyEvent.KEYCODE_D, false);
        padW = padA = padS = padD = false;
    }

    private static void updatePadKey(XServerDisplayActivity activity, int keyCode, boolean desired) {
        boolean current;
        switch (keyCode) {
            case KeyEvent.KEYCODE_W: current = padW; break;
            case KeyEvent.KEYCODE_A: current = padA; break;
            case KeyEvent.KEYCODE_S: current = padS; break;
            case KeyEvent.KEYCODE_D: current = padD; break;
            default: return;
        }
        if (current == desired) return;
        sendKey(activity, keyCode, desired);
        if (keyCode == KeyEvent.KEYCODE_W) padW = desired;
        if (keyCode == KeyEvent.KEYCODE_A) padA = desired;
        if (keyCode == KeyEvent.KEYCODE_S) padS = desired;
        if (keyCode == KeyEvent.KEYCODE_D) padD = desired;
    }

    /** Physical controller shortcut mapping. Left stick is handled in handleGamepadMotion. */
    public static boolean handleGamepadKey(XServerDisplayActivity activity, KeyEvent event) {
        if (activity == null || event == null || event.getDevice() == null) return false;
        if (uiMode) return false;
        int sources = event.getSource();
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!gamepad) return false;
        markGamepadConnectedFromEvent(activity);

        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (!padAttack) {
                    padAttack = true;
                    activity.fusionFallSendMouseButton(true);
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP && padAttack) {
                padAttack = false;
                activity.fusionFallSendMouseButton(false);
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) {
            sendKey(activity, KeyEvent.KEYCODE_TAB, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }

        int mapped = 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: mapped = KeyEvent.KEYCODE_SPACE; break;
            case KeyEvent.KEYCODE_BUTTON_B: mapped = KeyEvent.KEYCODE_ESCAPE; break;
            case KeyEvent.KEYCODE_BUTTON_X: mapped = KeyEvent.KEYCODE_1; break;
            case KeyEvent.KEYCODE_BUTTON_Y: mapped = KeyEvent.KEYCODE_2; break;
            case KeyEvent.KEYCODE_BUTTON_R1: mapped = KeyEvent.KEYCODE_3; break;
            case KeyEvent.KEYCODE_BUTTON_L1: mapped = KeyEvent.KEYCODE_TAB; break;
            case KeyEvent.KEYCODE_BUTTON_START: mapped = KeyEvent.KEYCODE_ESCAPE; break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: mapped = KeyEvent.KEYCODE_ENTER; break;
            case KeyEvent.KEYCODE_DPAD_UP: mapped = KeyEvent.KEYCODE_1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: mapped = KeyEvent.KEYCODE_2; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: mapped = KeyEvent.KEYCODE_3; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: mapped = KeyEvent.KEYCODE_TAB; break;
            default: return false;
        }

        sendKey(activity, mapped, event.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }

    /**
     * Maps the physical controller left stick to WASD. We intentionally return
     * false so Winlator's normal controller pipeline can still process other axes.
     */
    public static boolean handleGamepadMotion(XServerDisplayActivity activity, MotionEvent event) {
        if (activity == null || event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        if (uiMode) return false;
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        markGamepadConnectedFromEvent(activity);

        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);
        float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > Math.abs(x)) x = hx;
        if (Math.abs(hy) > Math.abs(y)) y = hy;

        final float moveDead = 0.32f;
        updatePadKey(activity, KeyEvent.KEYCODE_A, x < -moveDead);
        updatePadKey(activity, KeyEvent.KEYCODE_D, x > moveDead);
        updatePadKey(activity, KeyEvent.KEYCODE_W, y < -moveDead);
        updatePadKey(activity, KeyEvent.KEYCODE_S, y > moveDead);

        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        float altRx = event.getAxisValue(MotionEvent.AXIS_RX);
        float altRy = event.getAxisValue(MotionEvent.AXIS_RY);
        if (Math.abs(altRx) > Math.abs(rx)) rx = altRx;
        if (Math.abs(altRy) > Math.abs(ry)) ry = altRy;

        SharedPreferences prefs = prefs(activity);
        if (prefs.getBoolean("gamepad_right_stick_camera", true)) {
            float sx = prefs.getFloat("camera_sensitivity_x", DEFAULT_CAMERA_X);
            float sy = prefs.getFloat("camera_sensitivity_y", DEFAULT_CAMERA_Y);
            if (prefs.getBoolean("camera_invert_y", false)) sy = -sy;
            updateGamepadCamera(touchpadRef.get(), rx, ry, sx, sy);
        }

        float trigger = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        boolean attackNow = trigger > 0.62f;
        if (attackNow != padAttack) {
            activity.fusionFallSendMouseButton(attackNow);
            padAttack = attackNow;
        }

        return false;
    }

    private static void updateGamepadCamera(TouchpadView touchpad, float x, float y, float sensitivityX, float sensitivityY) {
        if (touchpad == null || touchpad.getWidth() <= 0 || touchpad.getHeight() <= 0) return;
        final float dead = 0.16f;
        if (Math.abs(x) < dead) x = 0f;
        if (Math.abs(y) < dead) y = 0f;

        if (x == 0f && y == 0f) {
            endGamepadCamera(touchpad);
            return;
        }

        long now = SystemClock.uptimeMillis();
        float centerX = touchpad.getWidth() * 0.5f;
        float centerY = touchpad.getHeight() * 0.5f;

        if (!gamepadCameraActive) {
            gamepadCameraActive = true;
            gamepadCameraDownTime = now;
            gamepadCameraX = centerX;
            gamepadCameraY = centerY;
            dispatchSyntheticTouch(touchpad, MotionEvent.ACTION_DOWN, gamepadCameraDownTime,
                    now, gamepadCameraX, gamepadCameraY);
        }

        float step = dp(touchpad.getContext(), 10f);
        gamepadCameraX += x * step * Math.max(0.35f, Math.abs(sensitivityX)) * Math.signum(sensitivityX == 0f ? 1f : sensitivityX);
        gamepadCameraY += y * step * Math.max(0.35f, Math.abs(sensitivityY)) * Math.signum(sensitivityY == 0f ? 1f : sensitivityY);

        float edge = dp(touchpad.getContext(), 28f);
        if (gamepadCameraX < edge || gamepadCameraX > touchpad.getWidth() - edge ||
                gamepadCameraY < edge || gamepadCameraY > touchpad.getHeight() - edge) {
            dispatchSyntheticTouch(touchpad, MotionEvent.ACTION_UP, gamepadCameraDownTime,
                    now, gamepadCameraX, gamepadCameraY);
            gamepadCameraDownTime = now;
            gamepadCameraX = centerX;
            gamepadCameraY = centerY;
            dispatchSyntheticTouch(touchpad, MotionEvent.ACTION_DOWN, gamepadCameraDownTime,
                    now, gamepadCameraX, gamepadCameraY);
        }

        dispatchSyntheticTouch(touchpad, MotionEvent.ACTION_MOVE, gamepadCameraDownTime,
                now, gamepadCameraX, gamepadCameraY);
    }

    private static void endGamepadCamera(TouchpadView touchpad) {
        if (!gamepadCameraActive || touchpad == null) {
            gamepadCameraActive = false;
            return;
        }
        long now = SystemClock.uptimeMillis();
        dispatchSyntheticTouch(touchpad, MotionEvent.ACTION_UP, gamepadCameraDownTime,
                now, gamepadCameraX, gamepadCameraY);
        gamepadCameraActive = false;
    }

    private static void dispatchSyntheticTouch(TouchpadView touchpad, int action, long downTime,
                                               long eventTime, float x, float y) {
        MotionEvent synthetic = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        synthetic.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            touchpad.onTouchEvent(synthetic);
        }
        finally {
            synthetic.recycle();
        }
    }
    
    private static void setPerformanceHudEnabled(Activity activity, boolean enabled) {
        if (activity == null || activity.isFinishing()) return;
        try {
            java.lang.reflect.Method method = activity.getClass().getMethod(
                    "fusionFallSetPerformanceHudEnabled", boolean.class);
            method.invoke(activity, enabled);
        }
        catch (Throwable ignored) {
            // Preference is still persisted by the settings dialog; launch-time
            // setup remains a safe fallback if a vendor/runtime changes reflection.
        }
    }

    public static void onPerformanceHudPreferenceChanged(boolean enabled) {
        resetFrameStats();
        FrameLayout root = rootRef.get();
        if (root != null) root.post(FusionFallMobileControls::rebuild);
    }

    public static void onRenderedFrame() {
        FusionFallDiagnostics.onRenderedFrame();
        XServerDisplayActivity activity = activityRef.get();
        if (activity == null || sessionPaused) return;

        long now = System.nanoTime();
        LaunchConfig config = getLaunchConfig(activity);
        float targetMs = 1000f / Math.max(1, config.fpsCap > 0 ? config.fpsCap : resolvePresentationFps(activity));
        boolean updateUi = false;
        synchronized (frameStatsLock) {
            if (statsSessionStartNanos == 0L) statsSessionStartNanos = now;
            statsSessionFrames++;
            if (lastRenderedFrameNanos != 0L) {
                float frameMs = (now - lastRenderedFrameNanos) / 1_000_000f;
                if (frameMs >= 0.5f && frameMs <= 1000f) {
                    frameTimeSamples[frameSampleIndex] = frameMs;
                    frameSampleIndex = (frameSampleIndex + 1) % FRAME_SAMPLE_CAPACITY;
                    frameSampleCount = Math.min(FRAME_SAMPLE_CAPACITY, frameSampleCount + 1);
                    if (frameMs > Math.max(25f, targetMs * 1.5f)) statsStutterCount++;
                }
            }
            lastRenderedFrameNanos = now;
            long uptime = SystemClock.uptimeMillis();
            if (uptime - lastStatsUiUpdateMs >= STATS_UPDATE_INTERVAL_MS) {
                lastStatsUiUpdateMs = uptime;
                updateUi = true;
            }
        }
        if (updateUi && prefs(activity).getBoolean("show_performance_hud", false)) {
            TextView stats = performanceStatsRef.get();
            if (stats != null) stats.post(FusionFallMobileControls::updatePerformanceStatsText);
        }
    }

    private static void addPerformanceStats(XServerDisplayActivity activity, FrameLayout root, Viewport viewport) {
        TextView stats = new TextView(activity);
        stats.setTag(OVERLAY_TAG + "_performance_stats");
        stats.setTextColor(Color.WHITE);
        stats.setTextSize(11f);
        stats.setPadding(dp(activity, 8f), dp(activity, 5f), dp(activity, 8f), dp(activity, 5f));
        stats.setBackgroundColor(Color.argb(178, 8, 15, 24));
        stats.setClickable(false);
        stats.setFocusable(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = viewport.left + dp(activity, 8f);
        lp.topMargin = viewport.top + dp(activity, 8f);
        root.addView(stats, lp);
        performanceStatsRef = new WeakReference<>(stats);
        updatePerformanceStatsText();
    }

    private static void updatePerformanceStatsText() {
        TextView stats = performanceStatsRef.get();
        XServerDisplayActivity activity = activityRef.get();
        if (stats == null || activity == null) return;

        float[] samples;
        long start;
        long frames;
        long stutters;
        synchronized (frameStatsLock) {
            samples = Arrays.copyOf(frameTimeSamples, frameSampleCount);
            start = statsSessionStartNanos;
            frames = statsSessionFrames;
            stutters = statsStutterCount;
        }
        if (samples.length == 0 || start == 0L) {
            stats.setText(tr(activity, "Midiendo rendimiento…", "Measuring performance…"));
            return;
        }

        Arrays.sort(samples);
        float totalMs = 0f;
        for (float sample : samples) totalMs += sample;
        float frameMs = totalMs / samples.length;
        float currentFps = frameMs > 0f ? 1000f / frameMs : 0f;
        float minFps = 1000f / Math.max(0.001f, samples[samples.length - 1]);
        int lowCount = Math.max(1, (int)Math.ceil(samples.length * 0.01f));
        float lowFpsTotal = 0f;
        for (int i = samples.length - lowCount; i < samples.length; i++) {
            lowFpsTotal += 1000f / Math.max(0.001f, samples[i]);
        }
        float onePercentLow = lowFpsTotal / lowCount;
        float elapsedSeconds = Math.max(0.001f, (System.nanoTime() - start) / 1_000_000_000f);
        float averageFps = frames / elapsedSeconds;
        String format = tr(activity,
                "FPS %.1f  ·  %.1f ms\nProm %.1f  ·  1%% low %.1f  ·  Mín %.1f  ·  Stutter %d",
                "FPS %.1f  ·  %.1f ms\nAvg %.1f  ·  1%% low %.1f  ·  Min %.1f  ·  Stutter %d");
        stats.setText(String.format(Locale.US, format, currentFps, frameMs, averageFps,
                onePercentLow, minFps, stutters));
    }

    private static void resetFrameStats() {
        synchronized (frameStatsLock) {
            Arrays.fill(frameTimeSamples, 0f);
            frameSampleCount = 0;
            frameSampleIndex = 0;
            lastRenderedFrameNanos = 0L;
            statsSessionStartNanos = 0L;
            statsSessionFrames = 0L;
            statsStutterCount = 0L;
            lastStatsUiUpdateMs = 0L;
        }
    }

    public static String buildRuntimeDiagnostics(Context context) {
        LaunchConfig config = getLaunchConfig(context);
        FrameLayout root = rootRef.get();
        float[] samples;
        long start;
        long frames;
        long stutters;
        synchronized (frameStatsLock) {
            samples = Arrays.copyOf(frameTimeSamples, frameSampleCount);
            start = statsSessionStartNanos;
            frames = statsSessionFrames;
            stutters = statsStutterCount;
        }
        Arrays.sort(samples);
        float frameMs = 0f;
        float minFps = 0f;
        float onePercentLow = 0f;
        if (samples.length > 0) {
            for (float sample : samples) frameMs += sample;
            frameMs /= samples.length;
            minFps = 1000f / Math.max(0.001f, samples[samples.length - 1]);
            int lowCount = Math.max(1, (int)Math.ceil(samples.length * 0.01f));
            for (int i = samples.length - lowCount; i < samples.length; i++)
                onePercentLow += 1000f / Math.max(0.001f, samples[i]);
            onePercentLow /= lowCount;
        }
        float elapsed = start == 0L ? 0f : Math.max(0.001f,
                (System.nanoTime() - start) / 1_000_000_000f);
        float averageFps = elapsed == 0f ? 0f : frames / elapsed;
        String rootSize = root == null ? "unavailable" : root.getWidth() + "x" + root.getHeight();
        return String.format(Locale.US,
                "Profile: %s\nLaunch resolution: %s%s\nFPS cap: %s\nOverlay root: %s\n" +
                "Session paused: %s\nGamepad: %s\nTouch UI mode: %s\n" +
                "Measured avg/frame/1%% low/min: %.1f FPS / %.2f ms / %.1f / %.1f\n" +
                "Frames/samples/stutters: %d / %d / %d\n",
                config.profile, config.screenSize(), config.nativeAspect ? " (native aspect)" : "",
                config.fpsCap > 0 ? Integer.toString(config.fpsCap) : "display/default",
                rootSize, sessionPaused, gamepadConnected ? "connected" : "not connected",
                uiMode, averageFps, frameMs, onePercentLow, minFps,
                frames, samples.length, stutters);
    }

    private static final class HudControlSetting {
        final String id;
        final CheckBox visible;
        final Spinner size;
        final Spinner opacity;

        HudControlSetting(String id, CheckBox visible, Spinner size, Spinner opacity) {
            this.id = id;
            this.visible = visible;
            this.size = size;
            this.opacity = opacity;
        }

        void set(boolean show, int sizeIndex, int opacityIndex) {
            visible.setChecked(show);
            size.setSelection(sizeIndex);
            opacity.setSelection(opacityIndex);
        }
    }

    private static int nearestIndex(float value, float[] values) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float distance = Math.abs(value - values[i]);
            if (distance < bestDistance) { best = i; bestDistance = distance; }
        }
        return best;
    }

    private static HudControlSetting addHudControlSetting(LinearLayout layout, Activity activity,
                                                          SharedPreferences preferences, String id, String label) {
        CheckBox show = checkBox(activity, label, controlVisible(activity, id));
        layout.addView(show);

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        options.setPadding(dp(activity, 30f), 0, 0, dp(activity, 4f));

        TextView sizeLabel = new TextView(activity);
        sizeLabel.setText(tr(activity, "Tamaño", "Size"));
        sizeLabel.setTextColor(UI_SECONDARY);
        sizeLabel.setTextSize(12f);
        options.addView(sizeLabel);

        Spinner size = new Spinner(activity);
        String[] sizeValues = {"80%", "100%", "120%"};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, sizeValues);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        size.setAdapter(sizeAdapter);
        float[] scales = {0.80f, 1.00f, 1.20f};
        size.setSelection(nearestIndex(preferences.getFloat(scaleKey(id), 1.0f), scales));
        LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(dp(activity, 90f), ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeLp.leftMargin = dp(activity, 4f);
        options.addView(size, sizeLp);

        TextView opacityLabel = new TextView(activity);
        opacityLabel.setText(tr(activity, "Opacidad", "Opacity"));
        opacityLabel.setTextColor(UI_SECONDARY);
        opacityLabel.setTextSize(12f);
        LinearLayout.LayoutParams opacityLabelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        opacityLabelLp.leftMargin = dp(activity, 10f);
        options.addView(opacityLabel, opacityLabelLp);

        Spinner opacity = new Spinner(activity);
        String[] opacityValues = {tr(activity, "Suave", "Soft"), tr(activity, "Normal", "Normal"), tr(activity, "Alta", "High")};
        ArrayAdapter<String> opacityAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, opacityValues);
        opacityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        opacity.setAdapter(opacityAdapter);
        float[] opacityMultipliers = {0.70f, 1.00f, 1.25f};
        opacity.setSelection(nearestIndex(preferences.getFloat(opacityKey(id), 1.0f), opacityMultipliers));
        LinearLayout.LayoutParams opacityLp = new LinearLayout.LayoutParams(dp(activity, 105f), ViewGroup.LayoutParams.WRAP_CONTENT);
        opacityLp.leftMargin = dp(activity, 4f);
        options.addView(opacity, opacityLp);

        layout.addView(options);
        return new HudControlSetting(id, show, size, opacity);
    }

    private static void setHudPreset(HudControlSetting[] controls, String preset, CheckBox controlsVisible) {
        for (HudControlSetting setting : controls) {
            boolean show;
            if ("full".equals(preset)) show = true;
            else if ("gamepad".equals(preset)) show = false;
            else show = "attack".equals(setting.id) || "jump".equals(setting.id) ||
                    "target".equals(setting.id) || "nano_power".equals(setting.id);
            setting.set(show, 1, 1);
        }
        controlsVisible.setChecked(!"gamepad".equals(preset));
    }

    private static void saveHudSettings(SharedPreferences.Editor editor, HudControlSetting[] controls) {
        float[] scales = {0.80f, 1.00f, 1.20f};
        float[] opacityMultipliers = {0.70f, 1.00f, 1.25f};
        for (HudControlSetting setting : controls) {
            editor.putBoolean(visibleKey(setting.id), setting.visible.isChecked());
            editor.putFloat(scaleKey(setting.id), scales[Math.max(0, Math.min(2, setting.size.getSelectedItemPosition()))]);
            editor.putFloat(opacityKey(setting.id), opacityMultipliers[Math.max(0, Math.min(2, setting.opacity.getSelectedItemPosition()))]);
        }
    }

    public static void showSettings(Activity activity, Runnable onSaved) {
        if (activity == null || activity.isFinishing()) return;
        SharedPreferences prefs = prefs(activity);
        int pad = dp(activity, 20f);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, dp(activity, 14f), pad, dp(activity, 10f));
        layout.setBackgroundColor(UI_BG);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UI_BG);
        scroll.addView(layout, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(activity);
        title.setText("OpenFusion Android · v0.5.2 Beta");
        title.setTextSize(22f);
        title.setTextColor(UI_TEXT);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(activity, 8f));
        layout.addView(title);

        TextView note = new TextView(activity);
        note.setText(tr(activity,
                "Perfiles de servidor, actualizaciones verificadas, diagnóstico exportable y controles móviles estables.",
                "Server profiles, verified updates, exportable diagnostics and stable mobile controls."));
        note.setTextSize(14f);
        note.setTextColor(UI_SECONDARY);
        note.setPadding(0, 0, 0, dp(activity, 16f));
        layout.addView(note);

        layout.addView(sectionLabel(activity, tr(activity, "Servidor", "Server")));
        TextView serverSummary = new TextView(activity);
        serverSummary.setText(FusionFallRetrobution.serverSummary(activity));
        serverSummary.setTextSize(14f);
        serverSummary.setTextColor(UI_SECONDARY);
        serverSummary.setPadding(0, 0, 0, dp(activity, 6f));
        layout.addView(serverSummary);
        TextView configureServer = makeButton(activity,
                tr(activity, "CONFIGURAR SERVIDOR", "CONFIGURE SERVER"), 220f, 42f, 0.90f);
        configureServer.setOnClickListener(v -> FusionFallRetrobution.showServerSettings(activity,
                () -> serverSummary.setText(FusionFallRetrobution.serverSummary(activity))));
        layout.addView(configureServer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42f)));

        layout.addView(sectionLabel(activity, tr(activity, "Idioma / Language", "Language / Idioma")));
        TextView languageHelp = new TextView(activity);
        languageHelp.setText(tr(activity,
                "Selecciona el idioma de la interfaz de OpenFusion Android. FusionFall conserva su idioma original.",
                "Select the OpenFusion Android interface language. FusionFall itself keeps its original game language."));
        languageHelp.setTextSize(13f);
        languageHelp.setTextColor(UI_SECONDARY);
        languageHelp.setPadding(0, 0, 0, dp(activity, 4f));
        layout.addView(languageHelp);
        Spinner languageMode = new Spinner(activity);
        String[] languageValues = {"Español", "English"};
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, languageValues);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageMode.setAdapter(languageAdapter);
        languageMode.setSelection(isEnglish(activity) ? 1 : 0);
        layout.addView(languageMode);

        layout.addView(sectionLabel(activity, tr(activity, "Actualizaciones", "Updates")));
        TextView updateHelp = new TextView(activity);
        updateHelp.setText(tr(activity,
                "Beta recibe versiones de prueba. Stable solo ofrece publicaciones finales. Las APK se verifican con SHA-256 antes de abrir el instalador de Android.",
                "Beta receives preview builds. Stable only receives final releases. APKs are verified with SHA-256 before Android's installer opens."));
        updateHelp.setTextSize(13f);
        updateHelp.setTextColor(UI_SECONDARY);
        layout.addView(updateHelp);
        Spinner updateChannel = new Spinner(activity);
        String[] channelValues = {"Beta", "Stable"};
        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, channelValues);
        channelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        updateChannel.setAdapter(channelAdapter);
        updateChannel.setSelection("stable".equals(prefs.getString("update_channel", "beta")) ? 1 : 0);
        layout.addView(updateChannel);

        LinearLayout releaseButtons = new LinearLayout(activity);
        releaseButtons.setOrientation(LinearLayout.HORIZONTAL);
        releaseButtons.setPadding(0, dp(activity, 6f), 0, dp(activity, 4f));
        TextView checkUpdates = makeButton(activity,
                tr(activity, "COMPROBAR ACTUALIZACIÓN", "CHECK FOR UPDATE"), 220f, 42f, 0.90f);
        checkUpdates.setOnClickListener(v -> {
            prefs.edit().putString("update_channel",
                    updateChannel.getSelectedItemPosition() == 1 ? "stable" : "beta").apply();
            FusionFallRetrobution.checkForUpdates(activity, true);
        });
        releaseButtons.addView(checkUpdates, new LinearLayout.LayoutParams(0, dp(activity, 42f), 1f));
        TextView about = makeButton(activity,
                tr(activity, "ACERCA DE", "ABOUT"), 120f, 42f, 0.90f);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(0, dp(activity, 42f), 0.55f);
        aboutLp.leftMargin = dp(activity, 6f);
        releaseButtons.addView(about, aboutLp);
        about.setOnClickListener(v -> FusionFallRetrobution.showAbout(activity));
        layout.addView(releaseButtons);

        TextView perfLabel = sectionLabel(activity, tr(activity, "Perfil de rendimiento", "Performance profile"));
        layout.addView(perfLabel);
        Spinner performance = new Spinner(activity);
        String[] perfValues = {
                tr(activity, "Compatible · 1280×720 / FPS actual", "Compatible · 1280×720 / current FPS"),
                tr(activity, "Batería · 960×540 / 30 FPS", "Battery · 960×540 / 30 FPS"),
                tr(activity, "Equilibrado · 1280×720 / 45 FPS", "Balanced · 1280×720 / 45 FPS"),
                tr(activity, "Rendimiento · 1280×720 / 60 FPS", "Performance · 1280×720 / 60 FPS"),
                tr(activity, "Sin límite · 1600×900", "Unlocked · 1600×900")
        };
        ArrayAdapter<String> perfAdapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, perfValues) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView)super.getView(position, convertView, parent);
                view.setTextColor(UI_TEXT);
                view.setTextSize(15f);
                view.setPadding(dp(activity, 8f), dp(activity, 10f), dp(activity, 8f), dp(activity, 10f));
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView)super.getDropDownView(position, convertView, parent);
                view.setTextColor(UI_TEXT);
                view.setBackgroundColor(UI_BG);
                view.setTextSize(15f);
                view.setPadding(dp(activity, 12f), dp(activity, 12f), dp(activity, 12f), dp(activity, 12f));
                return view;
            }
        };
        perfAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        performance.setAdapter(perfAdapter);
        String perf = prefs.getString("performance_profile", "compatible");
        int perfIndex = "battery".equals(perf) ? 1 :
                ("balanced".equals(perf) ? 2 : ("performance".equals(perf) ? 3 :
                        ("unlocked".equals(perf) ? 4 : 0)));
        performance.setSelection(perfIndex);
        layout.addView(performance);

        CheckBox nativeAspect = checkBox(activity, tr(activity, "Usar relación de aspecto nativa (experimental)", "Use native aspect ratio (experimental)"),
                prefs.getBoolean("native_aspect", false));
        layout.addView(nativeAspect);

        CheckBox performanceHud = checkBox(activity, tr(activity, "Mostrar FPS, frametime, 1% low y stutter", "Show FPS, frametime, 1% low and stutter"),
                prefs.getBoolean("show_performance_hud", false));
        layout.addView(performanceHud);
        performanceHud.setOnCheckedChangeListener((buttonView, checked) ->
                setPerformanceHudEnabled(activity, checked));

        layout.addView(sectionLabel(activity, tr(activity, "Diagnóstico", "Diagnostics")));
        TextView diagnosticsHelp = new TextView(activity);
        diagnosticsHelp.setText(tr(activity,
                "Consulta el estado actual o exporta un archivo de texto. Las credenciales y tokens detectados se ocultan automáticamente.",
                "Review the current state or export a text file. Detected credentials and tokens are automatically redacted."));
        diagnosticsHelp.setTextSize(13f);
        diagnosticsHelp.setTextColor(UI_SECONDARY);
        diagnosticsHelp.setPadding(0, 0, 0, dp(activity, 6f));
        layout.addView(diagnosticsHelp);
        TextView diagnosticsButton = makeButton(activity,
                tr(activity, "VER / EXPORTAR DIAGNÓSTICO", "VIEW / EXPORT DIAGNOSTICS"),
                250f, 42f, 0.90f);
        diagnosticsButton.setContentDescription(tr(activity, "Abrir diagnóstico de FusionFall", "Open FusionFall diagnostics"));
        diagnosticsButton.setOnClickListener(v -> FusionFallDiagnostics.showDialog(activity));
        layout.addView(diagnosticsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42f)));

        layout.addView(sectionLabel(activity, tr(activity, "Controles táctiles", "Touch controls")));

        TextView opacityLabel = valueLabel(activity);
        layout.addView(opacityLabel);
        SeekBar opacity = new SeekBar(activity);
        opacity.setMax(75);
        opacity.setProgress(Math.max(0, Math.min(75,
                Math.round((prefs.getFloat("mobile_controls_opacity", DEFAULT_OPACITY) - 0.25f) * 100f))));
        layout.addView(opacity);

        TextView scaleLabel = valueLabel(activity);
        layout.addView(scaleLabel);
        SeekBar scale = new SeekBar(activity);
        scale.setMax(60);
        scale.setProgress(Math.max(0, Math.min(60,
                Math.round((prefs.getFloat("mobile_controls_scale", DEFAULT_SCALE) - 0.70f) * 100f))));
        layout.addView(scale);

        layout.addView(sectionLabel(activity, tr(activity, "Modo del joystick", "Joystick mode")));
        TextView joystickHelp = new TextView(activity);
        joystickHelp.setText(tr(activity, "Fijo: permanece en su posición. Flotante: aparece bajo el pulgar. También puedes alternarlo rápidamente desde ⋯ → JOY FIJO/FLOT.", "Fixed: stays in place. Floating: appears under your thumb. You can also toggle it quickly from ⋯ → JOY FIXED/FLOAT."));
        joystickHelp.setTextSize(13f);
        joystickHelp.setTextColor(UI_SECONDARY);
        joystickHelp.setPadding(0, 0, 0, dp(activity, 4f));
        layout.addView(joystickHelp);
        Spinner joystickMode = new Spinner(activity);
        String[] joyModes = {tr(activity, "Joystick fijo", "Fixed joystick"), tr(activity, "Joystick flotante", "Floating joystick")};
        ArrayAdapter<String> joyAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, joyModes);
        joyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        joystickMode.setAdapter(joyAdapter);
        joystickMode.setSelection("floating".equals(prefs.getString("joystick_mode", "fixed")) ? 1 : 0);
        layout.addView(joystickMode);

        TextView deadzoneLabel = valueLabel(activity);
        layout.addView(deadzoneLabel);
        SeekBar deadzone = new SeekBar(activity);
        deadzone.setMax(40);
        deadzone.setProgress(Math.max(0, Math.min(40,
                Math.round((prefs.getFloat("joystick_deadzone", DEFAULT_JOYSTICK_DEADZONE) - 0.10f) * 100f))));
        layout.addView(deadzone);

        CheckBox left = checkBox(activity, tr(activity, "Modo zurdo", "Left-handed mode"), prefs.getBoolean("left_handed", false));
        layout.addView(left);
        CheckBox haptic = checkBox(activity, tr(activity, "Vibración al tocar controles", "Vibrate on control touch"), prefs.getBoolean("haptic_feedback", true));
        layout.addView(haptic);
        CheckBox visible = checkBox(activity, tr(activity, "Mostrar controles táctiles", "Show touch controls"), prefs.getBoolean("mobile_controls_visible", true));
        layout.addView(visible);
        CheckBox autoFade = checkBox(activity, tr(activity, "Desvanecer controles cuando no se usan", "Fade controls when idle"), prefs.getBoolean("controls_auto_fade", true));
        layout.addView(autoFade);
        CheckBox autoHideGamepad = checkBox(activity, tr(activity, "Ocultar controles automáticamente con gamepad", "Automatically hide controls with gamepad"),
                prefs.getBoolean("auto_hide_touch_on_gamepad", true));
        layout.addView(autoHideGamepad);
        CheckBox showNanoShortcuts = checkBox(activity, tr(activity, "Mostrar accesos rápidos 1 / 2 / 3 (respaldo)", "Show 1 / 2 / 3 quick shortcuts (fallback)"),
                prefs.getBoolean("show_nano_shortcuts", false));
        layout.addView(showNanoShortcuts);

        layout.addView(sectionLabel(activity, tr(activity, "HUD personalizable", "Customizable HUD")));
        TextView hudHelp = new TextView(activity);
        hudHelp.setText(tr(activity, "Elige qué acciones permanecen en pantalla. Las que ocultes aparecen automáticamente dentro de ⋯. Cada botón puede usar tamaño y opacidad independientes.", "Choose which actions stay on screen. Hidden actions automatically appear inside ⋯. Each button can use its own size and opacity."));
        hudHelp.setTextSize(13f);
        hudHelp.setTextColor(UI_SECONDARY);
        hudHelp.setPadding(0, 0, 0, dp(activity, 6f));
        layout.addView(hudHelp);

        LinearLayout presets = new LinearLayout(activity);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        TextView compactPreset = makeButton(activity, tr(activity, "COMPACTO", "COMPACT"), 86f, 36f, 0.80f);
        TextView fullPreset = makeButton(activity, tr(activity, "COMPLETO", "FULL"), 86f, 36f, 0.80f);
        TextView gamepadPreset = makeButton(activity, "GAMEPAD", 86f, 36f, 0.80f);
        presets.addView(compactPreset);
        LinearLayout.LayoutParams fullPresetLp = new LinearLayout.LayoutParams(dp(activity, 86f), dp(activity, 36f));
        fullPresetLp.leftMargin = dp(activity, 6f);
        presets.addView(fullPreset, fullPresetLp);
        LinearLayout.LayoutParams gamepadPresetLp = new LinearLayout.LayoutParams(dp(activity, 86f), dp(activity, 36f));
        gamepadPresetLp.leftMargin = dp(activity, 6f);
        presets.addView(gamepadPreset, gamepadPresetLp);
        layout.addView(presets);

        HudControlSetting[] hudControls = {
                addHudControlSetting(layout, activity, prefs, "attack", tr(activity, "ATK · ataque", "ATK · attack")),
                addHudControlSetting(layout, activity, prefs, "jump", tr(activity, "JUMP · salto", "JUMP")),
                addHudControlSetting(layout, activity, prefs, "target", tr(activity, "TARGET · seleccionar/usar objetivo", "TARGET · select/use target")),
                addHudControlSetting(layout, activity, prefs, "weapon", tr(activity, "ARMA · cambiar arma", "WEAPON · switch weapon")),
                addHudControlSetting(layout, activity, prefs, "nano_power", tr(activity, "NANO · poder de Nano", "NANO · Nano Power")),
                addHudControlSetting(layout, activity, prefs, "inventory", tr(activity, "INVENT. · My Stuff", "INV. · My Stuff")),
                addHudControlSetting(layout, activity, prefs, "journal", "JOURNAL"),
                addHudControlSetting(layout, activity, prefs, "email", "E-MAIL"),
                addHudControlSetting(layout, activity, prefs, "map", tr(activity, "MAPA", "MAP"))
        };
        compactPreset.setOnClickListener(v -> { haptic(v); setHudPreset(hudControls, "compact", visible); });
        fullPreset.setOnClickListener(v -> { haptic(v); setHudPreset(hudControls, "full", visible); });
        gamepadPreset.setOnClickListener(v -> { haptic(v); setHudPreset(hudControls, "gamepad", visible); });

        TextView editButton = makeButton(activity, tr(activity, "EDITAR POSICIÓN DE CONTROLES", "EDIT CONTROL POSITIONS"), 250f, 40f, 0.86f);
        editButton.setTextSize(11f);
        LinearLayout.LayoutParams editButtonLp = new LinearLayout.LayoutParams(dp(activity, 250f), dp(activity, 40f));
        editButtonLp.gravity = Gravity.CENTER_HORIZONTAL;
        editButtonLp.topMargin = dp(activity, 8f);
        editButtonLp.bottomMargin = dp(activity, 12f);
        layout.addView(editButton, editButtonLp);

        layout.addView(sectionLabel(activity, tr(activity, "Cámara", "Camera")));
        float legacyCamera = prefs.getFloat("camera_sensitivity", DEFAULT_CAMERA);
        float initialCameraX = prefs.contains("camera_sensitivity_x") ?
                prefs.getFloat("camera_sensitivity_x", DEFAULT_CAMERA_X) : legacyCamera;
        float initialCameraY = prefs.contains("camera_sensitivity_y") ?
                prefs.getFloat("camera_sensitivity_y", DEFAULT_CAMERA_Y) : legacyCamera;

        TextView cameraXLabel = valueLabel(activity);
        layout.addView(cameraXLabel);
        SeekBar cameraX = new SeekBar(activity);
        cameraX.setMax(210);
        cameraX.setProgress(Math.max(0, Math.min(210, Math.round((initialCameraX - 0.40f) * 100f))));
        layout.addView(cameraX);

        TextView cameraYLabel = valueLabel(activity);
        layout.addView(cameraYLabel);
        SeekBar cameraY = new SeekBar(activity);
        cameraY.setMax(210);
        cameraY.setProgress(Math.max(0, Math.min(210, Math.round((initialCameraY - 0.40f) * 100f))));
        layout.addView(cameraY);

        TextView cameraDeadLabel = valueLabel(activity);
        layout.addView(cameraDeadLabel);
        SeekBar cameraDead = new SeekBar(activity);
        cameraDead.setMax(80);
        cameraDead.setProgress(Math.max(0, Math.min(80,
                Math.round((prefs.getFloat("camera_drag_deadzone_dp", DEFAULT_CAMERA_DRAG_DEADZONE_DP) - 2.0f) * 10f))));
        layout.addView(cameraDead);

        CheckBox invertY = checkBox(activity, tr(activity, "Invertir eje vertical de cámara", "Invert camera vertical axis"), prefs.getBoolean("camera_invert_y", false));
        layout.addView(invertY);
        CheckBox touchCamera = checkBox(activity, tr(activity, "Cámara táctil dedicada en modo juego", "Dedicated touch camera in game mode"), prefs.getBoolean("touch_camera_enabled", true));
        layout.addView(touchCamera);
        CheckBox rightStick = checkBox(activity, tr(activity, "Stick derecho del gamepad controla la cámara", "Gamepad right stick controls camera"), prefs.getBoolean("gamepad_right_stick_camera", true));
        layout.addView(rightStick);

        layout.addView(sectionLabel(activity, tr(activity, "HUD nativo", "Native HUD")));
        CheckBox nativeHudTouch = checkBox(activity, tr(activity, "Activar Nanos tocando directamente sus ranuras", "Activate Nanos by touching their slots directly"),
                prefs.getBoolean("native_hud_touch_enabled", true));
        layout.addView(nativeHudTouch);

        Runnable updateLabels = () -> {
            float op = 0.25f + opacity.getProgress() / 100f;
            float sc = 0.70f + scale.getProgress() / 100f;
            float dz = 0.10f + deadzone.getProgress() / 100f;
            float sx = 0.40f + cameraX.getProgress() / 100f;
            float sy = 0.40f + cameraY.getProgress() / 100f;
            float cameraDz = 2.0f + cameraDead.getProgress() / 10f;
            opacityLabel.setText(String.format(Locale.US, tr(activity, "Opacidad: %.0f%%", "Opacity: %.0f%%"), op * 100f));
            scaleLabel.setText(String.format(Locale.US, tr(activity, "Tamaño de controles: %.0f%%", "Control size: %.0f%%"), sc * 100f));
            deadzoneLabel.setText(String.format(Locale.US, tr(activity, "Zona muerta del joystick: %.0f%%", "Joystick deadzone: %.0f%%"), dz * 100f));
            cameraXLabel.setText(String.format(Locale.US, tr(activity, "Sensibilidad horizontal: %.2fx", "Horizontal sensitivity: %.2fx"), sx));
            cameraYLabel.setText(String.format(Locale.US, tr(activity, "Sensibilidad vertical: %.2fx", "Vertical sensitivity: %.2fx"), sy));
            cameraDeadLabel.setText(String.format(Locale.US, tr(activity, "Umbral inicial de cámara: %.1f dp", "Initial camera threshold: %.1f dp"), cameraDz));
        };
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateLabels.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        opacity.setOnSeekBarChangeListener(listener);
        scale.setOnSeekBarChangeListener(listener);
        deadzone.setOnSeekBarChangeListener(listener);
        cameraX.setOnSeekBarChangeListener(listener);
        cameraY.setOnSeekBarChangeListener(listener);
        cameraDead.setOnSeekBarChangeListener(listener);
        updateLabels.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setNeutralButton(tr(activity, "Restaurar", "Restore"), null)
                .setNegativeButton(tr(activity, "Cancelar", "Cancel"), null)
                .setPositiveButton(tr(activity, "Guardar", "Save"), null)
                .create();

        editButton.setOnClickListener(v -> {
            haptic(v);
            dialog.dismiss();
            uiMode = false;
            topMenuExpanded = false;
            editControlsMode = true;
            if (onSaved != null) onSaved.run(); else rebuild();
        });

        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(UI_BG));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int desired = Math.min(dp(activity, 620f), (int)(screenWidth * 0.92f));
                window.setLayout(desired, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(UI_ACCENT);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UI_ACCENT);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UI_ACCENT);

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                resetControlOffsets(activity);
                prefs.edit()
                        .putString("performance_profile", "compatible")
                        .putString("update_channel", "beta")
                        .putBoolean("native_aspect", false)
                        .putBoolean("show_performance_hud", false)
                        .putFloat("mobile_controls_opacity", DEFAULT_OPACITY)
                        .putFloat("mobile_controls_scale", DEFAULT_SCALE)
                        .putFloat("camera_sensitivity", DEFAULT_CAMERA)
                        .putFloat("camera_sensitivity_x", DEFAULT_CAMERA_X)
                        .putFloat("camera_sensitivity_y", DEFAULT_CAMERA_Y)
                        .putFloat("camera_drag_deadzone_dp", DEFAULT_CAMERA_DRAG_DEADZONE_DP)
                        .putBoolean("camera_invert_y", false)
                        .putFloat("joystick_deadzone", DEFAULT_JOYSTICK_DEADZONE)
                        .putString("joystick_mode", "fixed")
                        .putBoolean("left_handed", false)
                        .putBoolean("haptic_feedback", true)
                        .putBoolean("mobile_controls_visible", true)
                        .putBoolean("controls_auto_fade", true)
                        .putBoolean("auto_hide_touch_on_gamepad", true)
                        .putBoolean("show_nano_shortcuts", false)
                        .putBoolean("gamepad_right_stick_camera", true)
                        .putBoolean("touch_camera_enabled", true)
                        .putBoolean("native_hud_touch_enabled", true)
                        .apply();
                SharedPreferences.Editor hudReset = prefs.edit();
                String[] hudIds = {"attack", "jump", "target", "weapon", "nano_power", "inventory", "journal", "email", "map"};
                for (String id : hudIds) {
                    hudReset.remove(visibleKey(id)).remove(scaleKey(id)).remove(opacityKey(id));
                }
                hudReset.apply();
                for (HudControlSetting setting : hudControls) {
                    setting.set(defaultControlVisible(setting.id), 1, 1);
                }
                setPerformanceHudEnabled(activity, false);
                performance.setSelection(0);
                updateChannel.setSelection(0);
                nativeAspect.setChecked(false);
                performanceHud.setChecked(false);
                opacity.setProgress(Math.round((DEFAULT_OPACITY - 0.25f) * 100f));
                scale.setProgress(Math.round((DEFAULT_SCALE - 0.70f) * 100f));
                cameraX.setProgress(Math.round((DEFAULT_CAMERA_X - 0.40f) * 100f));
                cameraY.setProgress(Math.round((DEFAULT_CAMERA_Y - 0.40f) * 100f));
                cameraDead.setProgress(Math.round((DEFAULT_CAMERA_DRAG_DEADZONE_DP - 2.0f) * 10f));
                deadzone.setProgress(Math.round((DEFAULT_JOYSTICK_DEADZONE - 0.10f) * 100f));
                joystickMode.setSelection(0);
                invertY.setChecked(false);
                left.setChecked(false);
                haptic.setChecked(true);
                visible.setChecked(true);
                autoFade.setChecked(true);
                autoHideGamepad.setChecked(true);
                showNanoShortcuts.setChecked(false);
                rightStick.setChecked(true);
                touchCamera.setChecked(true);
                nativeHudTouch.setChecked(true);
                updateLabels.run();
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int selected = performance.getSelectedItemPosition();
                String selectedPerf = selected == 1 ? "battery" :
                        (selected == 2 ? "balanced" : (selected == 3 ? "performance" :
                                (selected == 4 ? "unlocked" : "compatible")));
                SharedPreferences.Editor editor = prefs.edit()
                        .putString(PREF_LANGUAGE, languageMode.getSelectedItemPosition() == 1 ? "en" : "es")
                        .putString("update_channel", updateChannel.getSelectedItemPosition() == 1 ? "stable" : "beta")
                        .putString("performance_profile", selectedPerf)
                        .putBoolean("native_aspect", nativeAspect.isChecked())
                        .putBoolean("show_performance_hud", performanceHud.isChecked())
                        .putFloat("mobile_controls_opacity", 0.25f + opacity.getProgress() / 100f)
                        .putFloat("mobile_controls_scale", 0.70f + scale.getProgress() / 100f)
                        .putFloat("camera_sensitivity_x", 0.40f + cameraX.getProgress() / 100f)
                        .putFloat("camera_sensitivity_y", 0.40f + cameraY.getProgress() / 100f)
                        .putFloat("camera_drag_deadzone_dp", 2.0f + cameraDead.getProgress() / 10f)
                        .putBoolean("camera_invert_y", invertY.isChecked())
                        .putFloat("joystick_deadzone", 0.10f + deadzone.getProgress() / 100f)
                        .putString("joystick_mode", joystickMode.getSelectedItemPosition() == 1 ? "floating" : "fixed")
                        .putBoolean("left_handed", left.isChecked())
                        .putBoolean("haptic_feedback", haptic.isChecked())
                        .putBoolean("mobile_controls_visible", visible.isChecked())
                        .putBoolean("controls_auto_fade", autoFade.isChecked())
                        .putBoolean("auto_hide_touch_on_gamepad", autoHideGamepad.isChecked())
                        .putBoolean("show_nano_shortcuts", showNanoShortcuts.isChecked())
                        .putBoolean("gamepad_right_stick_camera", rightStick.isChecked())
                        .putBoolean("touch_camera_enabled", touchCamera.isChecked())
                        .putBoolean("native_hud_touch_enabled", nativeHudTouch.isChecked());
                saveHudSettings(editor, hudControls);
                editor.apply();
                FusionFallDiagnostics.recordEvent("settings saved · profile " + selectedPerf);
                setPerformanceHudEnabled(activity, performanceHud.isChecked());
                TouchpadView tp = touchpadRef.get();
                if (tp != null) tp.setSensitivity(1.0f);
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            });
        });
        dialog.show();
    }

    private static TextView sectionLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(15f);
        label.setTextColor(UI_TEXT);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(context, 8f), 0, dp(context, 4f));
        return label;
    }

    private static TextView valueLabel(Context context) {
        TextView label = new TextView(context);
        label.setTextSize(15f);
        label.setTextColor(UI_TEXT);
        return label;
    }

    private static CheckBox checkBox(Context context, String text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setTextColor(UI_TEXT);
        box.setTextSize(15f);
        box.setChecked(checked);
        return box;
    }

    /**
     * Transparent gameplay camera zone. It intentionally uses TouchpadView.mouseMove
     * rather than TouchpadView.onTouchEvent: camera drags therefore generate only
     * relative mouse motion and can never become a click when the finger is lifted.
     */
    private static final class CameraTouchView extends View {
        private float downRawX, downRawY, lastRawX, lastRawY;
        private float remainderX, remainderY;
        private boolean engaged;

        CameraTouchView(Context context, TouchpadView touchpad) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
            setFocusable(false);
            setContentDescription(tr(context, "Zona táctil de cámara", "Touch camera zone"));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (uiMode || editControlsMode) return false;
            int action = event.getActionMasked();
            float rawX = event.getRawX();
            float rawY = event.getRawY();

            if (action == MotionEvent.ACTION_DOWN) {
                downRawX = lastRawX = rawX;
                downRawY = lastRawY = rawY;
                remainderX = remainderY = 0f;
                engaged = false;
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                SharedPreferences p = prefs(getContext());
                float dead = dp(getContext(), p.getFloat("camera_drag_deadzone_dp", DEFAULT_CAMERA_DRAG_DEADZONE_DP));
                if (!engaged) {
                    float dx0 = rawX - downRawX;
                    float dy0 = rawY - downRawY;
                    if (dx0 * dx0 + dy0 * dy0 < dead * dead) return true;
                    engaged = true;
                    // Start from the current sample. Do not replay the dead-zone
                    // displacement, which was causing an initial pitch jump.
                    lastRawX = rawX;
                    lastRawY = rawY;
                    return true;
                }

                float sx = p.getFloat("camera_sensitivity_x", DEFAULT_CAMERA_X);
                float sy = p.getFloat("camera_sensitivity_y", DEFAULT_CAMERA_Y);
                if (p.getBoolean("camera_invert_y", false)) sy = -sy;

                float maxDelta = dp(getContext(), 42f);
                float dx = Math.max(-maxDelta, Math.min(maxDelta, rawX - lastRawX));
                float dy = Math.max(-maxDelta, Math.min(maxDelta, rawY - lastRawY));
                lastRawX = rawX;
                lastRawY = rawY;

                float scaledX = dx * sx + remainderX;
                float scaledY = dy * sy + remainderY;
                int outX = Math.round(scaledX);
                int outY = Math.round(scaledY);
                remainderX = scaledX - outX;
                remainderY = scaledY - outY;
                sendRelativeCamera(activityRef.get(), outX, outY);
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                engaged = false;
                remainderX = remainderY = 0f;
                return true;
            }
            return true;
        }
    }

    /**
     * Transparent pass through placed over FusionFall's native lower-right HUD.
     * Gameplay camera input is excluded from this area. Taps are forwarded to the
     * stock TouchpadView with absolute cursor-to-touchpoint enabled for the gesture,
     * allowing Nano/interface controls to remain directly touchable.
     */
    private static final class NativeHudTouchView extends View {
        private final TouchpadView touchpad;
        private long gestureDownTime;

        NativeHudTouchView(Context context, TouchpadView touchpad) {
            super(context);
            this.touchpad = touchpad;
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
            setFocusable(false);
            setContentDescription(tr(context, "Zona táctil del HUD nativo", "Native HUD touch zone"));
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (uiMode || touchpad == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) gestureDownTime = SystemClock.uptimeMillis();

            float[] point = toLocal(touchpad, event.getRawX(), event.getRawY());
            MotionEvent forwarded = MotionEvent.obtain(
                    gestureDownTime > 0 ? gestureDownTime : event.getDownTime(),
                    SystemClock.uptimeMillis(), action, point[0], point[1], 0);
            forwarded.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            touchpad.setMoveCursorToTouchpoint(true);
            try {
                touchpad.onTouchEvent(forwarded);
            }
            finally {
                forwarded.recycle();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchpad.setMoveCursorToTouchpoint(false);
                    gestureDownTime = 0L;
                }
            }
            return true;
        }
    }

    private static float[] toLocal(View target, float rawX, float rawY) {
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        return new float[]{rawX - location[0], rawY - location[1]};
    }

    private static final class JoystickView extends View {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean floating;
        private float centerX;
        private float centerY;
        private float knobX;
        private float knobY;
        private boolean active;
        private boolean w;
        private boolean a;
        private boolean s;
        private boolean d;

        JoystickView(Context context, float opacity, boolean floating) {
            super(context);
            this.floating = floating;
            setContentDescription(floating ? tr(context, "Joystick flotante de movimiento", "Floating movement joystick") : tr(context, "Joystick virtual de movimiento", "Virtual movement joystick"));
            setFocusable(false);
            setClickable(true);
            int alpha = Math.max(45, Math.min(220, Math.round(opacity * 255f)));
            basePaint.setColor(Color.argb(Math.max(35, alpha - 45), 8, 20, 25));
            basePaint.setStyle(Paint.Style.FILL);
            basePaint.setStrokeWidth(dp(context, 2f));
            knobPaint.setColor(Color.argb(Math.min(240, alpha + 45), 35, 220, 255));
            knobPaint.setStyle(Paint.Style.FILL);
        }

        @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
            centerX = width * 0.5f;
            centerY = height * 0.5f;
            knobX = centerX;
            knobY = centerY;
        }

        private float baseRadius() {
            if (floating) return Math.min(dp(getContext(), 58f), Math.min(getWidth(), getHeight()) * 0.24f);
            return Math.min(getWidth(), getHeight()) * 0.45f;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (floating && !active && !editControlsMode) return;
            float radius = baseRadius();
            canvas.drawCircle(centerX, centerY, radius, basePaint);
            canvas.drawCircle(knobX, knobY, radius * 0.36f, knobPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (editControlsMode) return handleControlEditDrag(this, "joystick", event);
            XServerDisplayActivity activity = activityRef.get();
            if (activity == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (autoRun) setAutoRun(activity, false);
                    pokeControl(this);
                    haptic(this);
                    active = true;
                    if (floating) {
                        centerX = event.getX();
                        centerY = event.getY();
                        knobX = centerX;
                        knobY = centerY;
                    }
                    update(event.getX(), event.getY(), activity);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    pokeControl(this);
                    update(event.getX(), event.getY(), activity);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setDirections(activity, false, false, false, false);
                    active = false;
                    if (!floating) {
                        centerX = getWidth() * 0.5f;
                        centerY = getHeight() * 0.5f;
                        knobX = centerX;
                        knobY = centerY;
                    }
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void update(float x, float y, XServerDisplayActivity activity) {
            float max = Math.max(dp(getContext(), 22f), baseRadius() * 0.82f);
            float dx = x - centerX;
            float dy = y - centerY;
            float dist = (float)Math.sqrt(dx * dx + dy * dy);
            if (dist > max && dist > 0.001f) {
                dx = dx / dist * max;
                dy = dy / dist * max;
            }
            knobX = centerX + dx;
            knobY = centerY + dy;
            float nx = dx / max;
            float ny = dy / max;
            float dead = prefs(activity).getFloat("joystick_deadzone", DEFAULT_JOYSTICK_DEADZONE);
            setDirections(activity, ny < -dead, nx < -dead, ny > dead, nx > dead);
            invalidate();
        }

        private void setDirections(XServerDisplayActivity activity, boolean nw, boolean na, boolean ns, boolean nd) {
            if (w != nw) { sendKey(activity, KeyEvent.KEYCODE_W, nw); w = nw; }
            if (a != na) { sendKey(activity, KeyEvent.KEYCODE_A, na); a = na; }
            if (s != ns) { sendKey(activity, KeyEvent.KEYCODE_S, ns); s = ns; }
            if (d != nd) { sendKey(activity, KeyEvent.KEYCODE_D, nd); d = nd; }
        }
    }

}
