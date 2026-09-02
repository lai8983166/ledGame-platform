const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("memberAdminStartup", {
  onStatus(listener) {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("member-admin:startup-status", handler);
    return () => ipcRenderer.removeListener("member-admin:startup-status", handler);
  },
});
