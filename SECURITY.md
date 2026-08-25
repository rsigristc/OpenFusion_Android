# Security and release integrity

## What the build downloads

GitHub Actions checks out the exact Winlator commit documented in `README.md`, applies the public
Git patch and copies the public Android overlay. The workflow does not decode or generate source
code from an embedded archive.

The application downloads the official OpenFusion portable launcher when preparing its private
runtime. It does not bundle game account credentials or game files in this repository.

## APK authenticity

Official APKs are signed with a persistent Android release key stored as encrypted GitHub Actions
secrets. The current public signing-certificate SHA-256 digest is:

```text
8cbcd76a1e1139ca1c763ee98f528b8e4a7790ed4806c2f441d110818694da0f
```

Android verifies that signature when installing an update over an existing official build. Each
GitHub Release also contains an APK SHA-256 file to detect corruption or an incomplete download.
Because the APK and checksum are hosted in the same release, the Android signing certificate—not
the checksum alone—is the principal authenticity boundary.

## Updater behavior

- Release metadata and APKs are requested over HTTPS from GitHub.
- Downloads remain in the app-private cache.
- The APK SHA-256 is verified before the Android package installer is opened.
- Installation is performed by Android; the application does not silently install packages.
- Saved passwords use Android Keystore and AES-GCM and are isolated by server API endpoint.
- Exported diagnostics redact detected credentials, tokens and authorization values.

## Custom server profiles

Custom profiles require HTTPS and reject URLs containing embedded credentials, query parameters
or fragments. The selected server still receives the username and password entered for that
profile, so users must only configure servers they trust. OpenFusion Android does not certify or
endorse custom server operators. Changing endpoints changes the credential-storage scope and
prevents automatic reuse of a password saved for a different server.

## Reporting a vulnerability

Do not publish passwords, tokens, keystores or personal diagnostic data in an issue. Open a
minimal GitHub issue requesting a private contact channel and include only the affected version
and a short non-sensitive summary.
