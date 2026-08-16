const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("registrationDesktop", {
  windowKind: "kiosk",
  request: (request) => ipcRenderer.invoke("registration:api-request", request),
  staffExit: (password) => ipcRenderer.invoke("registration:staff-exit", password),
  onConnectionState: (listener) => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("registration:connection-state", handler);
    return () => ipcRenderer.removeListener("registration:connection-state", handler);
  },
  onStaffExitRequest: (listener) => {
    const handler = () => listener();
    ipcRenderer.on("registration:staff-exit-requested", handler);
    return () => ipcRenderer.removeListener("registration:staff-exit-requested", handler);
  },
});
