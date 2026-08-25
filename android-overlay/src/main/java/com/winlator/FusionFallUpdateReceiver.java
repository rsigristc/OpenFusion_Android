// Copyright 2026 OpenFusion Android contributors.
// SPDX-License-Identifier: LGPL-2.1-or-later
package com.winlator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives completion and user-confirmation events from Android's PackageInstaller session. */
public final class FusionFallUpdateReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_STATUS =
            "com.winlator.action.OPENFUSION_UPDATE_INSTALL_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            else show(context, "No se pudo abrir la confirmación de instalación.",
                    "Could not open the installation confirmation.");
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            show(context, "Actualización instalada.", "Update installed.");
            return;
        }
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        FusionFallDiagnostics.recordEvent("update install failed · status=" + status +
                (detail == null ? "" : " · " + detail));
        show(context, "Android rechazó la instalación del paquete.",
                "Android rejected the package installation.");
    }

    private static void show(Context context, String spanish, String english) {
        String language = context.getSharedPreferences("fusionfall_retrobution", Context.MODE_PRIVATE)
                .getString("ui_language", "en");
        Toast.makeText(context, "es".equals(language) ? spanish : english, Toast.LENGTH_LONG).show();
    }
}
