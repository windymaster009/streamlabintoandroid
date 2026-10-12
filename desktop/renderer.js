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
      fpsCount++;
      frame.close();
      const now = performance.now();
      if (now - fpsStarted >= 1000) {
        document.getElementById('fps').textContent = Math.round(fpsCount * 1000 / (now - fpsStarted));
        fpsStarted = now;
        fpsCount = 0;
      }
    },
    error: (e) => { notice.textContent = 'Video decoder: ' + e.message; }
  });
  decoder.configure({ codec: 'avc1.42E02A', optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' });
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
  if (!decoder || decoder.state === 'closed') initDecoder();
  if (!decoder || decoder.state !== 'configured') return;
  const flag = bytes[1] & 1;
  const timestamp = Number(new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getBigInt64(2, false));
  let payload = bytes.subarray(10);
  if (flag && codecConfig && codecConfig.length > 0) {
    const merged = new Uint8Array(codecConfig.length + payload.length);
    merged.set(codecConfig); merged.set(payload, codecConfig.length); payload = merged;
  }
  try {
    if (decoder.decodeQueueSize > 5 && !flag) return;
    decoder.decode(new EncodedVideoChunk({ type: flag ? 'key' : 'delta', timestamp, data: payload }));
  } catch (e) { notice.textContent = 'Decode: ' + e.message; }
}
async function handlePacket(event) {
  const bytes = new Uint8Array(event.data);
  if (bytes.byteLength < 10) return;
  if (bytes[0] === 3) {
    codecConfig = bytes.slice(10);
  } else if (bytes[0] === 1) {
    handleVideo(bytes);
  } else if (bytes[0] === 2) {
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
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => { notice.textContent = ''; };
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'status') updateStatus(msg.connected);
        if (msg.type === 'stopped') updateStatus(false);
        if (msg.type === 'start') {
          if (decoder) { decoder.close(); decoder = null; }
          codecConfig = null;
          fpsCount = 0; fpsStarted = performance.now();
          notice.textContent = '';
        }
      } catch (_) {}
    } else {
      handlePacket(event).catch((error) => { notice.textContent = error.message; });
    }
  };
  socket.onclose = () => {
    updateStatus(false);
    notice.textContent = 'Receiver reconnecting…';
    setTimeout(openViewer, 1500);
  };
}
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
