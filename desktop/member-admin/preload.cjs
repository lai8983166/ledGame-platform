const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("memberAdminDesktop", {
  request: (request) => ipcRenderer.invoke("member-admin:api-request", request),
  diagnostics: () => ipcRenderer.invoke("member-admin:diagnostics"),
  restartBackend: (port) => ipcRenderer.invoke("member-admin:restart-backend", { port }),
  retryBackend: () => ipcRenderer.invoke("member-admin:retry-backend"),
  onStatus: (listener) => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("member-admin:status", handler);
    return () => ipcRenderer.removeListener("member-admin:status", handler);
  },
});
