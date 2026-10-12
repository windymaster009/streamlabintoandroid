# WindyCast

**Free, self-hosted Android-to-Windows game mirroring** with QR pairing, a native Android sender (APK), a standalone Electron Windows receiver (EXE), and a single GitHub repository.

> **Current status: v0.2 experimental implementation.** The screen/video/audio pipeline and pairing UI are implemented, but real-device 1080p/60 FPS reliability is **not yet verified**. Expect to iterate after testing on a physical Android phone and Windows machine. This is not yet at LetsView feature parity.

## What it does

- Scan the Windows app's pairing QR with the Android app. No login, cloud server, or USB debugging.
- Capture Android screen via Android MediaProjection, H.264 surface encoder (MediaCodec), and a local-network WebSocket.
- Decode H.264 in the Windows Electron app with WebCodecs and display in a resizable window.
- Capture Android game playback audio where the game's Android audio-capture policy permits it (Android 10+), streaming PCM to the Windows output.
- Presets: **1080p / up to 60 FPS (5 Mbps)**, **720p / up to 30 FPS (2.5 Mbps)**, and **1080p / up to 60 FPS (8 Mbps)**.
- v0.2 has congestion-aware H.264 keyframe recovery instead of dropping individual dependent frames, plus a one-second I-frame interval.
- Built-in diagnostics: **Android encoded FPS**, **sent FPS**, **incoming FPS**, **displayed FPS**, **queue KB**, **dropped frames/s**, and **resync count**.
- Capturable from **OBS Studio** and **Streamlabs Desktop**, with no watermark or subscription.
- Both builds produced by GitHub Actions from this same repository.

## Download the builds

1. Open **[GitHub Actions](../../actions/workflows/build.yml)**.
2. Select a successful **Build Android APK and Windows EXE** workflow run.
3. Under **Artifacts**, download **WindyCast-Android-APK** and **WindyCast-Windows-EXE**.
4. Unzip each artifact. Install \`app-debug.apk\` on Android and run \`WindyCast-Windows-0.1.0.exe\` on Windows. The Windows build is a portable executable; it does not require a separate installer.

These are unsigned development builds. Windows SmartScreen may warn about a downloaded unsigned EXE; inspect and trust the source before proceeding. To generate a signed production app, configure signing separately.

## Connect

1. Put Android and Windows on the **same trusted Wi-Fi/LAN**. Prefer Ethernet for Windows and 5 GHz/6 GHz for Android. Allow the Windows app through Windows Firewall on **private networks**.
2. Run the Windows receiver; select your LAN IP if several network adapters are shown.
3. Launch WindyCast Android and tap **Scan PC QR code**.
4. Choose video quality and optionally enable game audio.
5. Tap **Start mirroring** and approve Android's system screen-capture prompt.
6. On Windows, use OBS/Streamlabs **Window Capture** to add WindyCast. Capture desktop/app audio too. If Window Capture is black, try Display Capture.
7. Stop in the Android app or from the foreground notification.

The Windows app listens on **TCP 47839** on the local network. Pairing tokens are random and regenerated with **Generate new pairing QR**.

## Repository

\`\`\`text
android/
  app/src/main/java/com/windycast/sender/MainActivity.kt  # QR scanner, permissions, presets, UI
  app/src/main/java/com/windycast/sender/CastService.kt    # MediaProjection, H.264, audio, WebSocket
desktop/
  main.cjs          # Electron window, pairing tokens, local WebSocket relay
  renderer.js       # WebCodecs H.264 decode, canvas rendering and audio
  audio-worklet.js  # Game audio playback
  index.html        # Windows interface
.github/workflows/build.yml
docs/protocol.md
\`\`\`

## Development

**Windows app**: Install Node.js 22, then:

\`\`\`bash
cd desktop
npm install
npm run dev
# Windows only: build a portable EXE
npm run dist:win
\`\`\`

**Android**: Open \`android/\` in Android Studio with JDK 17, install Android SDK 35, and select **Build APK(s)**. Or use Gradle 8.10.2:

\`\`\`bash
gradle -p android :app:assembleDebug
\`\`\`

Debug APK: \`android/app/build/outputs/apk/debug/app-debug.apk\`. Requires Android 10 / API 29 or later.

## Known limitations & next work

- This initial version supports **one sender**, one pairing session, and trusted-LAN Wi-Fi only. QR tokens are short-lived in memory but have no automatic expiry until regeneration/exit.
- **Traffic is not encrypted yet** (local ws://); never expose port 47839 to the Internet or use on untrusted/public Wi-Fi. A future version should add authenticated encryption and certificate pinning.
- **Audio capture depends on the game**. Android applications can disable playback capture, so sound may be silent.
- **Orientation changes during a stream aren't dynamically resized yet**; stop/start after rotating.
- Some devices may produce H.264 profiles or NAL representations requiring decoder compatibility fixes. Full cross-device testing is still needed.
- Not implemented yet: remote internet streaming, remote phone control, multi-phone streaming, recording, adaptive bitrate, loss recovery, signed releases, and end-to-end automated performance tests.
- Actual 1080p60 throughput/latency must be measured on your hardware, Wi-Fi, and game.

## License

MIT. Built independently, without LetsView binaries or proprietary APIs.

## Diagnosing low FPS (v0.2)

The live preview shows separate performance metrics:

- **Android FPS low:** Android capture, phone hardware encoder, screen content or heat/power limit is probably restricting performance. Try **Balanced 720p/30** and test with an animated game, not a static screen.
- **Android FPS high, Sent FPS low / Drop/s high / Queue KB high:** sender and network throughput can't keep up. Try 5 GHz Wi-Fi, move closer to router, and use Ethernet on the Windows PC.
- **Sent FPS high, Incoming FPS low:** receiver transport or desktop main process is backing up.
- **Incoming FPS high, Displayed FPS low:** Windows WebCodecs decode/render is the bottleneck. Test graphics drivers and compare fullscreen/windowed output.
- **Many Resyncs:** H.264 decoder or transport is losing synchronization. Save a screenshot of all metrics and open an issue.

**Use a fresh QR after showing screenshots publicly**: it contains the LAN pairing secret.
