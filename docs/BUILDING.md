# Building from source

The official workflow uses Ubuntu 24.04, Java 17, Android SDK 35, Android NDK 24.0.8215888 and
CMake 3.22.1. The commands below reproduce the source assembly performed by GitHub Actions.

```bash
git clone https://github.com/rsigristc/OpenFusion_Android.git
cd OpenFusion_Android

git init winlator-app
git -C winlator-app remote add origin https://github.com/brunodev85/winlator-app.git
git -C winlator-app fetch --depth=1 origin 4f55d117fff1542944e5b91f433470445160ce08
git -C winlator-app checkout --detach FETCH_HEAD

git -C winlator-app apply --check ../patches/winlator-4f55d11.patch
git -C winlator-app apply ../patches/winlator-4f55d11.patch
cp -a android-overlay/. winlator-app/app/

cd winlator-app
chmod +x gradlew
./gradlew --no-daemon :app:assembleDebug
```

The resulting debug APK is written below `winlator-app/app/build/outputs/apk/debug/`.

## Release signing

Local debug builds do not need the project's private release key. Official releases use an
RSA-4096 keystore held in encrypted GitHub Actions secrets. The Base64 operation visible in the
workflow decodes that secret keystore supplied by GitHub; it does not contain or reconstruct any
source code.

Never commit a keystore or its passwords. Builds signed with a different key cannot update the
official APK in place, which is normal Android behavior.

## Reviewing the complete assembled source

After applying the patch and copying the overlay, run:

```bash
git -C winlator-app status --short
git -C winlator-app diff
```

Added overlay files are available directly under `winlator-app/app/src/main/`. The same process is
performed in CI, followed by package, version, signature and SHA-256 checks.
