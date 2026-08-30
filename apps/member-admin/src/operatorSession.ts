import { shallowRef } from "vue";
import type { OperatorProfile } from "@ledgame/platform-api-client";

export function createOperatorSession() {
  const current = shallowRef<OperatorProfile | null>(null);
  return {
    current,
    login(profile: OperatorProfile) {
      current.value = { ...profile };
    },
    logout() {
      current.value = null;
    },
  };
}

export const operatorSession = createOperatorSession();
