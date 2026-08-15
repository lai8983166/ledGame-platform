const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("registrationDesktop", {
  windowKind: "kiosk",
  request: (request) => ipcRenderer.invoke("registration:api-request", request),
  staffExit: () => ipcRenderer.invoke("registration:staff-exit"),
  onConnectionState: (listener) => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("registration:connection-state", handler);
    return () => ipcRenderer.removeListener("registration:connection-state", handler);
  },
});
