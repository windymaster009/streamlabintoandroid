# WindyCast

**Free, self-hosted Android-to-Windows game mirroring** with QR pairing, a native Android sender (APK), a standalone Electron Windows receiver (EXE), and a single GitHub repository.

> **Current status: v0.5 experimental implementation.** The screen/video/audio pipeline and pairing UI are implemented, but real-device 1080p/60 FPS reliability is **not yet verified**. Expect to iterate after testing on a physical Android phone and Windows machine. This is not yet at LetsView feature parity.

## What it does

- Scan the Windows app's pairing QR with the Android app. No login, cloud server, or USB debugging.
- Capture Android screen via Android MediaProjection, H.264 surface encoder (MediaCodec), and a local-network WebSocket.
- Decode H.264 in the Windows Electron app with WebCodecs and display in a resizable window.
- Capture Android game playback audio where the game's Android audio-capture policy permits it (Android 10+), streaming PCM to the Windows output.
- Presets: **1080p / up to 60 FPS (5 Mbps)**, **720p / up to 30 FPS (2.5 Mbps)**, **1080p / up to 60 FPS (8 Mbps)**, and **Ultra native up to 4K / up to 30 FPS (12 Mbps)**, and a **Low Latency 720p/60 FPS (3 Mbps)** mode (default). Ultra is limited by the real phone display resolution and falls back if the H.264 encoder rejects the requested mode.
- v0.2 has congestion-aware H.264 keyframe recovery instead of dropping individual dependent frames, plus a one-second I-frame interval.
- Built-in diagnostics: **Android encoded FPS**, **sent FPS**, **incoming FPS**, **displayed FPS**, **queue KB**, **dropped frames/s**, and **resync count**.
- **Automatic portrait ↔ landscape changes** resize Android's existing MediaProjection virtual display and recreate the H.264 surface encoder without requiring a new QR connection. Game View adjusts its own window aspect ratio when the stream switches orientation.
- **Open Game View** creates a dedicated, resizable, opaque Windows capture window with only the phone video and no QR/settings. Capture the **WindyCast Game View - Streamlabs Capture** window in OBS Studio or Streamlabs Desktop; no official integration is required.
- The dashboard decodes no video while Game View is open, helping prevent double video-decode load; the dashboard continues to play the phone's captured game audio.
- Both builds produced by GitHub Actions from this same repository.

## Download the builds

1. Open **[GitHub Actions](../../actions/workflows/build.yml)**.
2. Select a successful **Build Android APK and Windows EXE** workflow run.
3. Under **Artifacts**, download **WindyCast-Android-APK** and **WindyCast-Windows-EXE**.
4. Unzip each artifact. Install `app-debug.apk` on Android and run `WindyCast-Windows-0.5.0.exe` on Windows. The Windows build is a portable executable; it does not require a separate installer.

These are unsigned development builds. Windows SmartScreen may warn about a downloaded unsigned EXE; inspect and trust the source before proceeding. To generate a signed production app, configure signing separately.

## Connect

1. Put Android and Windows on the **same trusted Wi-Fi/LAN**. Prefer Ethernet for Windows and 5 GHz/6 GHz for Android. Allow the Windows app through Windows Firewall on **private networks**.
2. Run the Windows receiver; select your LAN IP if several network adapters are shown.
3. Launch WindyCast Android and tap **Scan PC QR code**.
4. Choose video quality and optionally enable game audio.
5. Tap **Start mirroring** and approve Android's system screen-capture prompt.
6. On Windows, click **Open Game View**, then in OBS/Streamlabs add a **Window Capture** source and select **WindyCast Game View - Streamlabs Capture**. Keep the Game View window open and not minimized.
7. Add your PC's **Desktop Audio** (or supported application audio capture) in Streamlabs to capture sound played by the WindyCast dashboard. Mute duplicates if monitoring/desktop audio doubles playback.
8. If WindyCast is missing from Streamlabs' Window list, confirm the Game View window appears under Alt+Tab, unminimize it, reopen Window Capture settings and try **Windows 10 (1903 and up)**, then **BitBlt**. Try running both applications at the same Windows privilege level. If Window Capture remains unavailable, use **Display Capture** as a fallback.
9. Stop in the Android app or from the foreground notification.

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
  index.html        # Windows dashboard and separate capture-only view
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

## v0.3 Streamlabs / OBS capture window

**Open Game View** on the main Windows dashboard creates a separate, normal Windows application window entitled `WindyCast Game View - Streamlabs Capture`. It contains only a black background and the Android video, ideal for Window Capture and resizing. The window is **not** a proprietary Streamlabs plugin, and cannot force Streamlabs to enumerate it if Windows or Streamlabs filters it. Capture Method changes and Display Capture are supported fallback options. Keep this window visible (not minimized) during capture.

When the dedicated Game View is open, the dashboard pauses H.264 decoding, while continuing to receive statistics and play phone audio; the Game View itself plays **no audio** to prevent duplicate sounds. If you use Streamlabs, ensure Desktop Audio is configured correctly. Since this is still an early build, capture behavior must be verified on a real Windows system.

## v0.4 auto-rotate + Ultra capture mode

- **Rotate the phone or launch a landscape game after connecting:** Android's virtual display is resized in place and its H.264 encoder restarted in the same MediaProjection session. The Windows decoder resets automatically and Game View changes between a wide landscape and tall portrait capture window. **No QR rescan needed.** During orientation changes there may be a short black frame or freeze while video recovers; game audio should continue.
- **Ultra** requests native pixels up to a 3840px long edge at 30 FPS/12 Mbps, with fallback to smaller supported encoder configurations. A 1080p/2400p phone doesn't become true 4K just by selecting Ultra. Quality depends on actual hardware, game, Wi-Fi, and encoder support.
- **Queue KB** measures Android's pending WebSocket bytes, *not bitrate*. Values around 5–10 KB are low and generally good. Use separate Android/Sent/Incoming/Displayed FPS diagnostics to understand lag.
- This feature is experimental; test both rotating before launch and rotating while already mirroring, preferably with your actual game. If black bars persist, include both phone and desktop screenshots and the current Video resolution from the dashboard.

## v0.5 low-latency + Game View optimizations

For someone playing a game on the phone and watching on Windows, *input-to-display responsiveness* is usually more important than maximum resolution. V0.5 changes:

- Low Latency **720p / 60 FPS / 3 Mbps** is the default Android preset. Use Gaming 1080p60 when quality matters more than responsiveness.
- Game View and the dashboard no longer receive duplicate H.264 streams. The PC forwards video **only to the active viewing window**; audio remains routed to the dashboard.
- Windows WebCodecs drops/re-synchronizes after a shorter decoder backlog, rather than buffering up to 10 queued frames.
- Windows renderer no longer writes resolution DOM/UI attributes 60 times per second, reducing rendering overhead when captured in Streamlabs.
- Receiver TCP sockets use TCP_NODELAY and drop dependent-frame chains if a viewer is deeply backlogged rather than showing a corrupted, delayed picture.

**The 4K/Ultra preset remains optional and is not recommended for twitch/competitive gaming**. 4K30 consumes substantially more resources, which may add latency. Real-world latency is not yet benchmarked.

### Compare against the previous stable build

[Previous v0.4.0 APK + EXE build](https://github.com/windymaster009/streamlabintoandroid/actions/runs/38298961495)

To compare, record a simultaneous shot of the Android phone and Windows screen while tapping a game button. Check **Android FPS / Sent FPS / Incoming FPS / Displayed FPS / Queue KB**. If Android FPS is 60 but Displayed FPS is under 30, the Windows decoder/capture is still overloaded; lowering bitrate alone may not solve it.

### Streamlabs capture tips

Use `Open Game View`, select the dedicated `WindyCast Game View - Streamlabs Capture` window in Streamlabs, keep that window open and not minimized, and avoid recording both WindyCast windows at once. Streamlabs' streaming encoder and Window Capture can use extra GPU time; try a lower Streamlabs output resolution and hardware encoder if the PC struggles.

### Security

The local transport still uses plaintext WebSocket. Do not expose port 47839 outside a trusted LAN. Treat pairing QR images as secrets and regenerate them if shared publicly.
