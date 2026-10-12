const captureOnly = new URLSearchParams(location.search).get('mode') === 'capture';
if (captureOnly) {
  document.body.classList.add('capture-only');
  document.title = 'WindyCast Game View - Streamlabs Capture';
  document.querySelector('#empty h2').textContent = 'Waiting for Android stream';
  document.querySelector('#empty p').textContent = 'Connect your phone in the main WindyCast window.';
}
let gameViewOpen = false;
const qr = document.getElementById('qr');
const interfaces = document.getElementById('interface');
const empty = document.getElementById('empty');
const notice = document.getElementById('notice');
const canvas = document.getElementById('videoCanvas');
const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
let decoder = null;
let codecConfig = null;
let audioContext = null;
let audioNode = null;
let fpsCount = 0;
let fpsStarted = performance.now();
let active = false;
let lastWidth = 0, lastHeight = 0;
let viewerSocket = null;
let waitingForVideoKeyframe = true;
let codecName = 'avc1.42E02A';
let incomingFrames = 0;
let displayedFrames = 0;
let lastKeyframeRequestAt = 0;
let recoveryCount = 0;

// Incoming, encoded and rendered FPS are separate: this tells us whether the
// Android encoder, network or Windows decoder is behind.
setInterval(() => {
  if (captureOnly) window.windycast.reportGameViewFps(displayedFrames);
  if (!gameViewOpen || captureOnly) document.getElementById('fps').textContent = displayedFrames;
  document.getElementById('incomingFps').textContent = incomingFrames;
  incomingFrames = 0;
  displayedFrames = 0;
}, 1000);

function requestKeyframe() {
  if (!viewerSocket || viewerSocket.readyState !== WebSocket.OPEN) return;
  if (performance.now() - lastKeyframeRequestAt < 700) return;
  lastKeyframeRequestAt = performance.now();
  viewerSocket.send(JSON.stringify({ type: 'keyframe' }));
}

function resynchronizeVideo(reason = '') {
  waitingForVideoKeyframe = true;
  recoveryCount++;
  document.getElementById('resyncs').textContent = String(recoveryCount);
  if (decoder) {
    try { decoder.close(); } catch (_) {}
    decoder = null;
  }
  requestKeyframe();
  if (reason) notice.textContent = reason;
}

function detectCodec(header) {
  // Android H.264 csd-0 contains Annex-B SPS (NAL unit type 7).
  // WebCodecs must be configured for the encoder's actual profile and level.
  for (let i = 0; i < header.length - 8; i++) {
    let n = -1;
    if (header[i] === 0 && header[i + 1] === 0 && header[i + 2] === 1) n = i + 3;
    if (header[i] === 0 && header[i + 1] === 0 && header[i + 2] === 0 && header[i + 3] === 1) n = i + 4;
    if (n >= 0 && (header[n] & 31) === 7 && n + 3 < header.length) {
      return 'avc1.' + Array.from(header.slice(n + 1, n + 4))
        .map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase();
    }
  }
  return 'avc1.42E02A';
}

async function renderCode(selected) {
  const info = await window.windycast.info();
  interfaces.replaceChildren();
  for (const item of info.addresses) {
    const option = document.createElement('option');
    option.value = item.address;
    option.textContent = item.name + ' — ' + item.address;
    interfaces.appendChild(option);
  }
  interfaces.value = info.addresses.find((x) => x.address === selected)?.address || info.addresses[0]?.address || '';
  displayQr(info);
}
function displayQr(info) {
  const match = info.addresses.find((x) => x.address === interfaces.value);
  qr.hidden = !match;
  document.getElementById('qrMissing').hidden = !!match;
  if (match) qr.src = match.qr;
}
function updateStatus(connected) {
  document.getElementById('statusText').textContent = connected ? 'Android connected' : 'Waiting for Android';
  document.getElementById('statusDot').classList.toggle('online', connected);
  if (!connected) {
    active = false;
    empty.style.display = 'flex';
    document.getElementById('fps').textContent = '—';
    document.getElementById('audioStatus').textContent = 'Waiting';
    document.getElementById('resolution').textContent = '—';
    if (decoder) { try { decoder.close(); } catch (_) {} decoder = null; }
    codecConfig = null;
    waitingForVideoKeyframe = true;
    document.getElementById('encodedFps').textContent = '—';
    document.getElementById('incomingFps').textContent = '—';
    document.getElementById('queueKb').textContent = '—';
    if (audioNode) { audioNode.port.postMessage({ reset: true }); }
  }
}
function initDecoder() {
  if (decoder) { try { decoder.close(); } catch (_) {} }
  if (!('VideoDecoder' in window)) {
    notice.textContent = 'WebCodecs unavailable on this PC';
    return;
  }
  decoder = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      lastWidth = frame.displayWidth; lastHeight = frame.displayHeight;
      document.getElementById('resolution').textContent = lastWidth + '×' + lastHeight;
      empty.style.display = 'none';
      active = true;
      displayedFrames++;
      frame.close();
    },
    error: (e) => { notice.textContent = 'Video decoder: ' + e.message; }
  });
  try {
    decoder.configure({ codec: codecName, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' });
  } catch (error) {
    notice.textContent = 'Unsupported H.264 format: ' + codecName + ' (' + error.message + ')';
  }
}
async function prepareAudio() {
  if (audioContext) return;
  try {
    audioContext = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
    await audioContext.audioWorklet.addModule('audio-worklet.js');
    audioNode = new AudioWorkletNode(audioContext, 'cast-audio');
    audioNode.connect(audioContext.destination);
    await audioContext.resume();
  } catch (e) {
    document.getElementById('audioStatus').textContent = 'Unavailable';
    console.warn('Audio init failed', e);
  }
}
function handleVideo(bytes) {
  incomingFrames++;
  const isKeyframe = (bytes[1] & 1) !== 0;
  if (waitingForVideoKeyframe && !isKeyframe) {
    requestKeyframe();
    return;
  }
  if (!decoder || decoder.state !== 'configured') initDecoder();
  if (!decoder || decoder.state !== 'configured') return;
  if (decoder.decodeQueueSize > 10) {
    // Do not discard individual H.264 P-frames. Their dependent frames would
    // decode as corrupted mosaics until the next clean IDR frame.
    resynchronizeVideo('Decoder fell behind; requesting a clean keyframe…');
    return;
  }
  const timestamp = Number(new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getBigInt64(2, false));
  let payload = bytes.subarray(10);
  if (isKeyframe && codecConfig && codecConfig.length) {
    const completeKeyframe = new Uint8Array(codecConfig.length + payload.length);
    completeKeyframe.set(codecConfig);
    completeKeyframe.set(payload, codecConfig.length);
    payload = completeKeyframe;
  }
  try {
    decoder.decode(new EncodedVideoChunk({ type: isKeyframe ? 'key' : 'delta', timestamp, data: payload }));
    if (isKeyframe) {
      waitingForVideoKeyframe = false;
      notice.textContent = '';
    }
  } catch (error) {
    resynchronizeVideo('Video decoder: ' + error.message);
  }
}
async function handlePacket(event) {
  const bytes = new Uint8Array(event.data);
  if (bytes.byteLength < 10) return;
  if (bytes[0] === 3) {
    codecConfig = bytes.slice(10);
    const detected = detectCodec(codecConfig);
    if (detected !== codecName) {
      codecName = detected;
      if (decoder) { try { decoder.close(); } catch (_) {} decoder = null; }
      waitingForVideoKeyframe = true;
      requestKeyframe();
    }
  } else if (bytes[0] === 1) {
    // Only decode in Game View when it is open; keep the dashboard receiving
    // diagnostics and playing audio, rather than decoding the stream twice.
    if (captureOnly || !gameViewOpen) handleVideo(bytes);
  } else if (bytes[0] === 2) {
    // Audio lives in the main dashboard only to avoid doubling game sound.
    if (captureOnly) return;
    if (!audioContext) await prepareAudio();
    if (!audioNode) return;
    const data = bytes.subarray(10);
    const samples = new Float32Array(Math.floor(data.length / 2));
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    for (let i = 0; i < samples.length; i++) samples[i] = view.getInt16(i * 2, true) / 32768;
    audioNode.port.postMessage({ samples }, [samples.buffer]);
    document.getElementById('audioStatus').textContent = 'Game audio';
  }
}
function openViewer() {
  const socket = new WebSocket('ws://127.0.0.1:47839/viewer');
  viewerSocket = socket;
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => { notice.textContent = ''; };
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'status') updateStatus(msg.connected);
        if (msg.type === 'stopped') updateStatus(false);
        if (msg.type === 'resync') resynchronizeVideo('Recovering from network congestion…');
        if (msg.type === 'stats') {
          document.getElementById('encodedFps').textContent = String(msg.encodedFps ?? '—');
          document.getElementById('sentFps').textContent = String(msg.sentFps ?? '—');
          document.getElementById('queueKb').textContent = String(msg.queueKb ?? '—');
          document.getElementById('dropCount').textContent = String(msg.dropped ?? 0);
        }
        if (msg.type === 'start') {
          if (decoder) { decoder.close(); decoder = null; }
          codecConfig = null;
          codecName = 'avc1.42E02A';
          waitingForVideoKeyframe = true;
          recoveryCount = 0;
          document.getElementById('resyncs').textContent = '0';
          notice.textContent = '';
        }
      } catch (_) {}
    } else {
      handlePacket(event).catch((error) => { notice.textContent = error.message; });
    }
  };
  socket.onclose = () => {
    viewerSocket = null;
    updateStatus(false);
    notice.textContent = 'Receiver reconnecting…';
    setTimeout(openViewer, 1500);
  };
}
document.getElementById('captureBtn').addEventListener('click', async () => {
  if (captureOnly) return;
  try {
    await window.windycast.openGameView();
  } catch (error) {
    notice.textContent = 'Cannot open Game View: ' + error.message;
  }
});
window.windycast.onGameViewStatus((open) => {
  if (captureOnly) return;
  gameViewOpen = open;
  if (open) {
    if (decoder) { try { decoder.close(); } catch (_) {} decoder = null; }
    empty.style.display = 'flex';
    document.querySelector('#empty h2').textContent = 'Game View active';
    document.querySelector('#empty p').textContent = 'Use the separate WindyCast Game View window in Streamlabs. Video decoding is paused here to save resources.';
    document.getElementById('captureBtn').textContent = '▣ Focus Game View';
  } else {
    waitingForVideoKeyframe = true;
    if (decoder) { try { decoder.close(); } catch (_) {} decoder = null; }
    document.querySelector('#empty h2').textContent = 'Ready to cast';
    document.querySelector('#empty p').textContent = 'Scan the QR code with WindyCast on Android, then allow screen sharing.';
    document.getElementById('captureBtn').textContent = '▣ Open Game View';
    requestKeyframe();
  }
});
window.windycast.onGameViewFps((fps) => {
  if (!captureOnly && gameViewOpen) document.getElementById('fps').textContent = fps;
});
interfaces.addEventListener('change', async () => displayQr(await window.windycast.info()));
document.getElementById('rotateBtn').addEventListener('click', async () => {
  const current = interfaces.value;
  const info = await window.windycast.rotate();
  const selected = info.addresses.find((a) => a.address === current) || info.addresses[0];
  interfaces.value = selected?.address || '';
  displayQr(info);
});
document.getElementById('fullBtn').addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.getElementById('screen').requestFullscreen();
});
window.windycast.onStatus((status) => updateStatus(status.connected));
window.windycast.onError((error) => { notice.textContent = 'Port 47839 unavailable: ' + error; });
renderCode().catch((error) => { notice.textContent = error.message; });
openViewer();
