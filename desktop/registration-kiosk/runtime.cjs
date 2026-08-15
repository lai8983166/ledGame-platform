const WINDOW_CAPABILITIES = {
  operator: ["read-settings", "save-settings", "test-connection", "start-kiosk", "request-api"],
  kiosk: ["request-api", "staff-exit", "connection-state"],
};

function capabilitiesForWindow(kind) {
  return [...(WINDOW_CAPABILITIES[kind] || [])];
}

function createKioskLifecycle() {
  let operatorVisible = true;
  let kioskOpen = false;
  let connectionTested = false;
  return {
    connectionSucceeded() { connectionTested = true; },
    connectionFailed() { connectionTested = false; },
    startKiosk() {
      if (!connectionTested) throw new Error("connection test required");
      kioskOpen = true;
      operatorVisible = false;
    },
    staffExit() {
      kioskOpen = false;
      operatorVisible = true;
      connectionTested = false;
    },
    snapshot() { return { operatorVisible, kioskOpen, connectionTested }; },
  };
}

module.exports = { WINDOW_CAPABILITIES, capabilitiesForWindow, createKioskLifecycle };
