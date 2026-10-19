const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('listenstat', {
  platform: process.platform,
  version: '0.2.5',
  getData: () => ipcRenderer.invoke('listenstat:getData'),
  getMedia: () => ipcRenderer.invoke('listenstat:getMedia'),
  setProfile: (patch) => ipcRenderer.invoke('listenstat:setProfile', patch),
  setSyncEnabled: (enabled) => ipcRenderer.invoke('listenstat:setSyncEnabled', enabled),
  syncNow: () => ipcRenderer.invoke('listenstat:syncNow'),
  pairCreate: () => ipcRenderer.invoke('listenstat:pairCreate'),
  pairClaim: (code) => ipcRenderer.invoke('listenstat:pairClaim', code),
  openExternal: (url) => ipcRenderer.invoke('listenstat:openExternal', url),
  clearLocalRecords: () => ipcRenderer.invoke('listenstat:clearLocalRecords'),
  onData: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on('listenstat:data', handler);
    return () => ipcRenderer.removeListener('listenstat:data', handler);
  },
});
