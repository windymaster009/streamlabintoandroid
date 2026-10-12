const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('windycast', {
  info: () => ipcRenderer.invoke('receiver:info'),
  getCrop: () => ipcRenderer.invoke('receiver:crop:get'),
  setCrop: (percent) => ipcRenderer.invoke('receiver:crop:set', percent),
  onCrop: (callback) => ipcRenderer.on('receiver:crop:changed', (_event, percent) => callback(percent)),
  rotate: () => ipcRenderer.invoke('receiver:rotate'),
  openGameView: () => ipcRenderer.invoke('receiver:game-view:open'),
  reportGameViewFps: (fps) => ipcRenderer.send('receiver:game-view:fps', fps),
  onGameViewFps: (callback) => ipcRenderer.on('receiver:game-view:fps', (_event, fps) => callback(fps)),
  onGameViewStatus: (callback) => ipcRenderer.on('receiver:game-view-status', (_event, open) => callback(open)),
  onStatus: (callback) => ipcRenderer.on('receiver:status', (_event, payload) => callback(payload)),
  onError: (callback) => ipcRenderer.on('receiver:error', (_event, payload) => callback(payload))
});
