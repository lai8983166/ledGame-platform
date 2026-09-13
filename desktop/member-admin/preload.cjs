const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("memberAdminDesktop", {
  request: (request) => ipcRenderer.invoke("member-admin:api-request", request),
  diagnostics: () => ipcRenderer.invoke("member-admin:diagnostics"),
  restartBackend: (port) => ipcRenderer.invoke("member-admin:restart-backend", { port }),
  retryBackend: () => ipcRenderer.invoke("member-admin:retry-backend"),
  chooseBackupDatabase: (operatorId) => ipcRenderer.invoke("member-admin:choose-backup-database", { operatorId }),
  importBackupDatabase: (candidateId, operatorId) => ipcRenderer.invoke("member-admin:import-backup-database", { candidateId, operatorId }),
  createDatabaseRecoveryRequest: (operatorId) => ipcRenderer.invoke("member-admin:create-database-recovery-request", { operatorId }),
  cancelDatabaseRecoveryRequest: (requestId, operatorId) => ipcRenderer.invoke("member-admin:cancel-database-recovery-request", { requestId, operatorId }),
  selectDatabaseRecoveryResponse: (operatorId) => ipcRenderer.invoke("member-admin:select-database-recovery-response", { operatorId }),
  importDatabaseRecoveryResponse: (operatorId, backupPath, responsePath) => ipcRenderer.invoke("member-admin:import-database-recovery-response", { operatorId, backupPath, responsePath }),
  exportData: (dataset, operatorId) => ipcRenderer.invoke("member-admin:export-data", { dataset, operatorId }),
  onStatus: (listener) => {
    const handler = (_event, value) => listener(value);
    ipcRenderer.on("member-admin:status", handler);
    return () => ipcRenderer.removeListener("member-admin:status", handler);
  },
});
