const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("registrationDesktop", {
  windowKind: "operator",
  request: (request) => ipcRenderer.invoke("registration:api-request", request),
  readSettings: () => ipcRenderer.invoke("registration:read-settings"),
  saveSettings: (settings) => ipcRenderer.invoke("registration:save-settings", settings),
  testConnection: (settings) => ipcRenderer.invoke("registration:test-connection", settings),
  startKiosk: () => ipcRenderer.invoke("registration:start-kiosk"),
  onConnectionState: (listener) => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("registration:connection-state", handler);
    return () => ipcRenderer.removeListener("registration:connection-state", handler);
  },
});
