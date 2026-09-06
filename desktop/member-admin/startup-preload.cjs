const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("memberAdminStartup", {
  activate: (code) => ipcRenderer.invoke("member-admin:activation", code),
  retry: () => ipcRenderer.invoke("member-admin:activation"),
  copyMachine: () => ipcRenderer.invoke("member-admin:activation-clipboard", "copy-machine"),
  paste: () => ipcRenderer.invoke("member-admin:activation-clipboard", "paste"),
  exit: () => ipcRenderer.invoke("member-admin:activation-exit"),
  onStatus(listener) {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("member-admin:startup-status", handler);
    return () => ipcRenderer.removeListener("member-admin:startup-status", handler);
  },
});
