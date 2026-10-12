const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('windycast', {
  info: () => ipcRenderer.invoke('receiver:info'),
  rotate: () => ipcRenderer.invoke('receiver:rotate'),
  onStatus: (callback) => ipcRenderer.on('receiver:status', (_event, payload) => callback(payload)),
  onError: (callback) => ipcRenderer.on('receiver:error', (_event, payload) => callback(payload))
});
