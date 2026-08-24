# Building from source

The official workflow uses Ubuntu 24.04, Python 3, Java 17, Android SDK 35, Android NDK
24.0.8215888 and CMake 3.22.1. Once those Android dependencies are installed, developers and
GitHub Actions use the same command:

```bash
git clone https://github.com/rsigristc/OpenFusion_Android.git
cd OpenFusion_Android
python3 scripts/build.py
```

On Windows, use `py scripts/build.py`. The script fetches the pinned Winlator commit, applies the
public Git patch, copies the public overlay and invokes Gradle. The resulting debug APK is written
below `.build/winlator-app/app/build/outputs/apk/debug/`.

To assemble the complete source tree without invoking Gradle:

```bash
python3 scripts/build.py --prepare-only
```

Run `python3 scripts/build.py --help` for the managed build-directory and Gradle-task options.

## Release signing

Local debug builds do not need the project's private release key. Official releases use an
RSA-4096 keystore held in encrypted GitHub Actions secrets. The Base64 operation visible in the
workflow decodes that secret keystore supplied by GitHub; it does not contain or reconstruct any
source code.

Never commit a keystore or its passwords. Builds signed with a different key cannot update the
official APK in place, which is normal Android behavior.

## Reviewing the complete assembled source

After running the script with `--prepare-only`, inspect the assembled source with:

```bash
git -C .build/winlator-app status --short
git -C .build/winlator-app diff
```

Added overlay files are available directly under `.build/winlator-app/app/src/main/`. Pull requests
use an ephemeral debug signing key, so external contributors do not need access to release secrets.
Official release builds additionally verify the persistent certificate and APK SHA-256.
