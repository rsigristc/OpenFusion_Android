# Compatibility identifiers

The public product name is **OpenFusion Android**, but several internal identifiers intentionally
retain historical names in v0.5.6 Beta:

- Android application ID `com.winlator` keeps official APK updates compatible.
- The Winlator container name `FusionFall Retrobution` preserves the installed Wine container and
  downloaded runtime.
- Shared preferences named `fusionfall_retrobution` preserve settings and the existing encrypted
  Retrobution login.
- Java classes such as `FusionFallRetrobution` remain referenced by the small Winlator patch.
- File-provider and resource identifiers retain their original names to avoid unnecessary Android
  manifest and migration risk.

These identifiers do not force the client to connect to Retrobution. New public UI, diagnostics,
artifacts and documentation use OpenFusion Android, while Retrobution remains the default server
profile. Internal names can be migrated later only with an explicit, tested data-migration path.
