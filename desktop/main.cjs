const { app, BrowserWindow, ipcMain } = require('electron');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const WebSocket = require('ws');
const QRCode = require('qrcode');

const PORT = 47839;
let win;
let token = crypto.randomBytes(24).toString('hex');
let sender = null;
let codecHeader = null;
const viewers = new Set();
let server, wss;

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

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
        viewers.add(ws);
        if (codecHeader) ws.send(codecHeader);
        ws.on('close', () => { viewers.delete(ws); sendStatus(); });
        if (sender?.readyState === WebSocket.OPEN) sender.send(JSON.stringify({ type: 'keyframe' }));
        sendStatus();
        return;
      }
      if (sender) stopSender();
      sender = ws;
      codecHeader = null;
      ws.on('message', (payload, binary) => {
        if (sender !== ws) return;
        if (!binary) {
          for (const viewer of viewers) {
            if (viewer.readyState === WebSocket.OPEN) viewer.send(payload.toString());
          }
          return;
        }
        if (payload.length < 10) return;
        if (payload[0] === 3) codecHeader = Buffer.from(payload);
        for (const viewer of viewers) {
          if (viewer.readyState === WebSocket.OPEN && viewer.bufferedAmount < 2 * 1024 * 1024) {
            viewer.send(payload, { binary: true });
          }
        }
      });
      ws.on('close', () => {
        if (sender === ws) {
          sender = null;
          codecHeader = null;
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
app.whenReady().then(() => {
  buildServer();
  ipcMain.handle('receiver:info', receiverInfo);
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
      sandbox: true
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
