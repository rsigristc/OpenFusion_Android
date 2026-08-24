# 📱 FusionFall Retrobution for Android

FusionFall Retrobution can now be played through a dedicated Android application with a mobile-focused interaction layer.

This project adapts the PC version of FusionFall Retrobution to Android using a customized Winlator-based runtime, while replacing much of the traditional desktop interaction with controls designed specifically for touchscreens and gamepads.

The goal is not simply to run FusionFall on Android, but to progressively make the experience feel like a proper mobile port.

## ✨ Current Features

🎮 **Mobile Controls**
- Virtual movement joystick
- Dedicated touchscreen camera control
- Attack, Jump and Target buttons
- Hold-to-attack support
- Drag-to-aim camera control while holding ATK
- Simultaneous movement + camera input
- Gamepad support

🧬 **Native Nano HUD Interaction**
- The original FusionFall Nano HUD can now be touched directly
- Nano slots automatically trigger the corresponding 1 / 2 / 3 game inputs
- Nano touch zones realign after fold/unfold and extra-wide display changes
- No additional Nano buttons are required for normal gameplay

🖱️ **Gameplay / UI Modes**
- Gameplay mode optimized for movement and camera control
- UI mode for menus and traditional mouse-style interaction
- Android keyboard access when text input is required

⚡ **Android Performance Layer**
- Controlled render loop to prevent the game from freezing while idle
- 30 / 45 / 60 FPS profiles
- Compatible and Unlocked profiles
- 960×540, 1280×720 and 1600×900 profiles
- Live frametime, average FPS, minimum FPS, 1% low and stutter metrics
- Optimized rendering behavior for mobile devices

🔐 **Android Login**
- Native Android launcher
- Remember username/password option
- Automatic login
- Passwords are encrypted using Android Keystore + AES-GCM
- Retrobution server configuration is integrated directly into the application

📦 **Signed Android Builds**
- APK releases now use a persistent RSA-4096 release signing certificate
- Future versions can be installed as updates without reinstalling the application
- Custom Retrobution Android launcher icon

## 🌐 Server

This Android build currently targets:

**FusionFall Retrobution**  
`api.ffretrobution.net`

## 🚧 Development Status

The Android client is still under active development.

The current POC series focuses on:

- touchscreen usability
- Android compatibility
- gamepad support
- performance and frame pacing
- native Android launcher integration
- reducing dependency on desktop-style mouse interaction
- progressively hiding the underlying compatibility layer from the user

Future versions will continue moving toward a more native Android experience.

## ❤️ Community Project

This is an unofficial community project created for preservation, experimentation and accessibility.

FusionFall and related intellectual property belong to their respective owners.  
This project is not affiliated with or endorsed by Cartoon Network.

Special thanks to the OpenFusion, Retrobution and Winlator communities whose work makes projects like this possible.

---

Feedback, device compatibility reports, gameplay videos and bug reports are very welcome.

