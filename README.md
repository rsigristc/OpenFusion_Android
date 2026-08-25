# OpenFusion Android

> v0.5.3 Beta · Default server profile: **FusionFall Retrobution**

[![License: LGPL v2.1 or later](https://img.shields.io/badge/License-LGPL_v2.1_or_later-blue.svg)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/rsigristc/OpenFusion_Android?include_prereleases)](https://github.com/rsigristc/OpenFusion_Android/releases)

OpenFusion Android is an open-source Android adaptation of FusionFall built on a pinned
[Winlator](https://github.com/brunodev85/winlator-app) revision. It adds a native launcher,
touch and gamepad controls, Fold-aware HUD interaction, lifecycle recovery, diagnostics and
verified in app updates.

The source used to build v0.5.3 Beta and future APKs is visible directly in this repository.

## Server profiles

v0.5.3 Beta keeps **FusionFall Retrobution** as the ready-to-use default profile and adds an
HTTPS custom-server profile under **Settings → Server**. Custom servers must implement the
documented launcher API contract; selecting an arbitrary OpenFusion server does not guarantee
protocol compatibility.

Credentials are encrypted and scoped to the selected API endpoint. Switching profiles cannot
automatically reuse or send a saved password belonging to another server. See
[Server profiles](docs/SERVER_PROFILES.md) for configuration and compatibility details.

Some Java classes, preference names and Android authorities retain historical Retrobution or
FusionFall names to preserve installed data and update compatibility. These are implementation
identifiers rather than a server restriction; see [Compatibility identifiers](docs/COMPATIBILITY_IDENTIFIERS.md).

## Source layout

- [`android-overlay/`](android-overlay/) contains the Android Java sources and resources added
  by this project. These files are directly browsable and reviewable.
- [`patches/winlator-4f55d11.patch`](patches/winlator-4f55d11.patch) is a standard Git patch for
  the small set of existing Winlator files that must be modified.
- [`.github/workflows/main.yml`](.github/workflows/main.yml) checks out this repository, fetches
  the pinned upstream Winlator commit, applies the public patch and copies the public overlay.
- [`docs/BUILDING.md`](docs/BUILDING.md) documents how to reproduce a local build.
- [`scripts/build.py`](scripts/build.py) is the single build entry point used locally and by CI.
- [`NOTICE`](NOTICE) records upstream provenance, modifications and trademark disclaimers.

The pinned upstream revision is
[`4f55d117fff1542944e5b91f433470445160ce08`](https://github.com/brunodev85/winlator-app/commit/4f55d117fff1542944e5b91f433470445160ce08).
Pinning the exact revision makes the patch deterministic and the resulting source auditable.

## Current features

- Native bilingual Android launcher and encrypted credentials using Android Keystore + AES-GCM.
- Retrobution default profile plus configurable HTTPS server profiles with credential isolation.
- Live server status, game version and online-player count from the OpenFusion endpoint API.
- Separate remember-password and automatic-sign-in controls, plus registration and password recovery.
- Touch movement, camera, attack, jump, target and native Nano HUD interaction.
- Drag to aim while holding ATK.
- Gamepad support and automatic touch-HUD behavior.
- 30 / 45 / 60 FPS and unlocked performance profiles.
- Live frametime, average FPS, minimum FPS, 1% low and stutter metrics.
- Exportable diagnostics with credential and token redaction for bug tracking and compatibility issues.
- Stable/Beta update channels with HTTPS, SHA-256 verification, visible progress and automatic installer resume.
- Persistent Android signing identity for upgrade compatible releases.

## Building

See [Building from source](docs/BUILDING.md). A local debug build does not require the private
release signing key. Official releases are signed by GitHub Actions using encrypted repository
secrets; the keystore itself is never committed.

## Releases and verification

Official APKs are published through [GitHub Releases](https://github.com/rsigristc/OpenFusion_Android/releases).
Each release includes the APK, its SHA-256 checksum, Android package/version metadata and the
public signing certificate digest.

## License and attribution

This derivative is distributed under the **GNU Lesser General Public License, version 2.1 or
later**. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

The downloaded OpenFusion game launcher/runtime is not stored in this repository and remains
subject to its own project and dependency licenses.

FusionFall and related intellectual property belong to their respective owners. This unofficial
community preservation project is not affiliated with or endorsed by Cartoon Network.

Contributions, device reports and security reviews are welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## Support development

If you would like to help fund development, device testing and release infrastructure, you can
support the project through Ko-fi. Support is optional; source code, downloads and project
features remain publicly available.

[![Support the project on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/rodrigosigrist)
