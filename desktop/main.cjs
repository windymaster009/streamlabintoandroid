const { app, BrowserWindow, ipcMain, screen } = require('electron');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const WebSocket = require('ws');
const QRCode = require('qrcode');

const PORT = 47839;
let win;
let gameView = null;
let token = crypto.randomBytes(24).toString('hex');
let sender = null;
let codecHeader = null;
let latestVideoStart = null;
const viewers = new Set();
let server, wss;

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
// Keep the receiver and its capture window rendering in the background.
app.setAppUserModelId('com.windycast.desktop');

function localAddresses() {
  const items = [];
  for (const [name, interfaces] of Object.entries(os.networkInterfaces())) {
    for (const net of interfaces || []) {
      if (net.family === 'IPv4' && !net.internal) {
        items.push({ name, address: net.address });
      }
    }
  }
  return items.sort((a, b) => {
    const score = (v) => /^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(v.address) ? 0 : 1;
    return score(a) - score(b);
  });
}
function sendStatus() {
  const state = { connected: sender?.readyState === WebSocket.OPEN, viewers: viewers.size };
  if (win && !win.isDestroyed()) win.webContents.send('receiver:status', state);
  for (const ws of viewers) {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'status', ...state }));
  }
}
function stopSender() {
  if (sender) {
    const current = sender;
    sender = null;
    current.close(1000, 'Receiver reset');
    current.terminate();
  }
  codecHeader = null;
  latestVideoStart = null;
  sendStatus();
}
function buildServer() {
  server = http.createServer((_req, res) => {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('WindyCast desktop receiver');
  });
  wss = new WebSocket.Server({ noServer: true, maxPayload: 8 * 1024 * 1024 });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url || '/', 'http://localhost');
    const isViewer = url.pathname === '/viewer';
    const isSender = url.pathname === '/stream' && url.searchParams.get('token') === token;
    const peer = req.socket.remoteAddress || '';
    const isLoopback = peer === '127.0.0.1' || peer === '::1' || peer === '::ffff:127.0.0.1';
    if ((!isViewer && !isSender) || (isViewer && !isLoopback)) {
      socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      if (isViewer) {
        ws.role = url.searchParams.get('role') === 'game' ? 'game' : 'dashboard';
        ws.awaitingKeyframe = false;
        ws._socket?.setNoDelay(true);
        viewers.add(ws);
        ws.on('message', (payload, binary) => {
          if (binary) return;
          try {
            const message = JSON.parse(payload.toString());
            if (message.type === 'keyframe' && sender?.readyState === WebSocket.OPEN) {
              sender.send(JSON.stringify({ type: 'keyframe' }));
            }
          } catch (_) {}
        });
        if (latestVideoStart) ws.send(latestVideoStart);
        if (codecHeader) ws.send(codecHeader);
        ws.on('close', () => { viewers.delete(ws); sendStatus(); });
        if (sender?.readyState === WebSocket.OPEN) sender.send(JSON.stringify({ type: 'keyframe' }));
        sendStatus();
        return;
      }
      if (sender) stopSender();
      sender = ws;
      ws._socket?.setNoDelay(true);
      codecHeader = null;
      latestVideoStart = null;
      ws.on('message', (payload, binary) => {
        if (sender !== ws) return;
        if (!binary) {
          const message = payload.toString();
          try {
            const command = JSON.parse(message);
            if (command.type === 'start' &&
                Number.isFinite(command.width) && Number.isFinite(command.height) &&
                command.width > 0 && command.height > 0) {
              latestVideoStart = message;
              codecHeader = null;
              resizeGameView(command.width, command.height);
            }
          } catch (_) {}
          for (const viewer of viewers) {
            if (viewer.readyState === WebSocket.OPEN) viewer.send(message);
          }
          return;
        }
        if (payload.length < 10) return;
        const packetType = payload[0];
        const isVideo = packetType === 1 || packetType === 3;
        const hasGameView = !!gameView && !gameView.isDestroyed();
        if (packetType === 3) codecHeader = Buffer.from(payload);
        for (const viewer of viewers) {
          if (viewer.readyState !== WebSocket.OPEN) continue;
          // There must only be ONE video consumer. A separate Electron
          // capture window should not double the H.264 traffic and work.
          if (isVideo) {
            if (hasGameView && viewer.role !== 'game') continue;
            if (!hasGameView && viewer.role === 'game') continue;
            if (packetType === 1) {
              if (viewer.bufferedAmount > 320 * 1024) {
                if (!viewer.awaitingKeyframe) {
                  viewer.awaitingKeyframe = true;
                  viewer.send(JSON.stringify({ type: 'resync' }));
                  if (sender?.readyState === WebSocket.OPEN) {
                    sender.send(JSON.stringify({ type: 'keyframe' }));
                  }
                }
                continue;
              }
              if (viewer.awaitingKeyframe) {
                // H.264 P-frames cannot recover after dropping one. Wait for
                // an independent IDR when the local queue has drained.
                if ((payload[1] & 1) === 0 || viewer.bufferedAmount > 100 * 1024) continue;
                viewer.awaitingKeyframe = false;
                if (codecHeader) viewer.send(codecHeader);
              }
            }
          } else if (packetType === 2 && viewer.role === 'game') {
            // Audio playback is owned by dashboard to prevent echoes.
            continue;
          }
          viewer.send(payload, { binary: true });
        }
      });
      ws.on('close', () => {
        if (sender === ws) {
          sender = null;
          codecHeader = null;
          latestVideoStart = null;
          for (const viewer of viewers) if (viewer.readyState === WebSocket.OPEN) viewer.send(JSON.stringify({ type: 'stopped' }));
          sendStatus();
        }
      });
      ws.on('error', () => {});
      sendStatus();
    });
  });
  server.on('error', (error) => {
    if (win && !win.isDestroyed()) win.webContents.send('receiver:error', error.message);
  });
  server.listen(PORT, '0.0.0.0');
}
async function receiverInfo() {
  const addresses = localAddresses();
  const items = await Promise.all(addresses.map(async (item) => {
    const payload = 'windycast://connect?host=' + encodeURIComponent(item.address) +
      '&port=' + PORT + '&token=' + token;
    return { ...item, qr: await QRCode.toDataURL(payload, { width: 256, margin: 1, errorCorrectionLevel: 'M' }) };
  }));
  return { port: PORT, addresses: items, connected: sender?.readyState === WebSocket.OPEN };
}
// Keep a 16:9 landscape game wide and a portrait screen tall. This also
// maintains the correct aspect ratio in Streamlabs' Window Capture source.
function resizeGameView(width, height) {
  if (!gameView || gameView.isDestroyed()) return;
  if (!(width > 0 && height > 0)) return;
  const ratio = width / height;
  const work = screen.getDisplayMatching(gameView.getBounds()).workArea;
  const maxWidth = Math.max(320, work.width - 100);
  const maxHeight = Math.max(240, work.height - 120);
  let w = width > height ? Math.min(1280, maxWidth) : Math.min(520, maxWidth);
  let h = Math.round(w / ratio);
  if (h > maxHeight) {
    h = maxHeight;
    w = Math.round(h * ratio);
  }
  w = Math.max(240, Math.round(w));
  h = Math.max(180, Math.round(h));
  gameView.setAspectRatio(0);
  gameView.setContentSize(w, h);
  gameView.setAspectRatio(ratio);
}

function openGameView() {
  if (gameView && !gameView.isDestroyed()) {
    if (gameView.isMinimized()) gameView.restore();
    gameView.show();
    gameView.focus();
    return;
  }
  const view = new BrowserWindow({
    title: 'WindyCast Game View - Streamlabs Capture',
    width: 1280,
    height: 760,
    minWidth: 320,
    minHeight: 240,
    backgroundColor: '#000000',
    show: true,
    frame: true,
    transparent: false,
    skipTaskbar: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false
    }
  });
  gameView = view;
  view.removeMenu();
  view.webContents.on('page-title-updated', (event) => event.preventDefault());
  view.loadFile(path.join(__dirname, 'index.html'), { query: { mode: 'capture' } });
  if (latestVideoStart) {
    try {
      const dimensions = JSON.parse(latestVideoStart);
      resizeGameView(dimensions.width, dimensions.height);
    } catch (_) {}
  }
  if (win && !win.isDestroyed()) win.webContents.send('receiver:game-view-status', true);
  view.on('closed', () => {
    if (gameView === view) gameView = null;
    if (win && !win.isDestroyed()) win.webContents.send('receiver:game-view-status', false);
  });
}

app.whenReady().then(() => {
  buildServer();
  ipcMain.on('receiver:game-view:fps', (event, fps) => {
    if (gameView && !gameView.isDestroyed() && event.sender === gameView.webContents &&
        Number.isInteger(fps) && fps >= 0 && fps <= 240 && win && !win.isDestroyed()) {
      win.webContents.send('receiver:game-view:fps', fps);
    }
  });
  ipcMain.handle('receiver:info', receiverInfo);
  ipcMain.handle('receiver:game-view:open', (event) => {
    if (!win || event.sender !== win.webContents) return false;
    openGameView();
    return true;
  });
  ipcMain.handle('receiver:rotate', () => {
    token = crypto.randomBytes(24).toString('hex');
    stopSender();
    return receiverInfo();
  });
  win = new BrowserWindow({
    width: 1200, height: 780, minWidth: 860, minHeight: 590,
    backgroundColor: '#090d19',
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false
    }
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'index.html'));
  win.webContents.on('did-finish-load', sendStatus);
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', () => {
  stopSender();
  for (const ws of viewers) ws.terminate();
  if (wss) wss.close();
  if (server) server.close();
});
