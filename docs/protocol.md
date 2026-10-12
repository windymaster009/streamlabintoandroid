# Local transport protocol (experimental v0.1)

The Windows receiver opens TCP port 47839 and listens for WebSocket upgrades.

- Android sender: \`ws://<pc-ip>:47839/stream?token=<48 hex chars>\`
- Windows local preview: \`ws://127.0.0.1:47839/viewer\`
- QR payload: \`windycast://connect?host=<pc-ip>&port=47839&token=<48 hex chars>\`

The Windows server allows only one Android sender; subsequent valid senders replace it. Viewer endpoint is loopback-only. Regenerating a token disconnects the existing sender.

## Binary packet frame

\`\`\`
offset   length  meaning
0        1       kind: 1=H.264 frame, 2=PCM16 mono audio, 3=H.264 codec config
1        1       flags: bit0=video keyframe
2        8       signed timestamp microseconds, network big-endian
10       n       encoded payload
\`\`\`

Video uses Android MediaCodec AVC encoder output; frame packet data should contain Annex-B NAL units. The Windows renderer decodes these using Chromium WebCodecs and a hardware decoder when available.

Audio contains little-endian 16-bit signed mono PCM samples at 48 kHz, generally in 20 ms chunks, played through WebAudio AudioWorklet. Audio is best-effort; unsupported applications may refuse Android playback capture.

## Text control messages

- Android -> Windows: JSON \`{"type":"start","width":1920,"height":1080,"fps":60,"bitrate":12000000}\`
- Windows -> Android: JSON \`{"type":"keyframe"}\` on viewer join.
- Windows -> preview: JSON \`{"type":"status","connected":true,"viewers":1}\` and \`{"type":"stopped"}\`.
- Android -> Windows: JSON \`{"type":"audio-disabled"}\` when disabled/unavailable.

## Security warning

WebSocket pairing authenticates the sender with an ephemeral random secret but is not encrypted. Capture packets, token QR, and game audio can be intercepted by an attacker on the same network. Do not forward this port to the Internet. This is a trusted-home-LAN protocol for initial development, not production-grade security.
