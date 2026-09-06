const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('signer', {
  status: () => ipcRenderer.invoke('signer:status'),
  chooseKey: () => ipcRenderer.invoke('signer:choose-key'),
  issue: machine => ipcRenderer.invoke('signer:issue', machine),
  copy: () => ipcRenderer.invoke('signer:copy'),
});
