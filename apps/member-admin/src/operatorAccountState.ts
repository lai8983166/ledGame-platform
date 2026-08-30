import type {
  CreateOperatorAccountInput,
  OperatorAccount,
  UpdateOperatorAccountInput,
} from "@ledgame/platform-api-client";

type OperatorAccountApi = {
  listOperatorAccounts(): Promise<OperatorAccount[]>;
  createOperatorAccount(input: CreateOperatorAccountInput): Promise<OperatorAccount>;
  updateOperatorAccount(id: number, input: UpdateOperatorAccountInput): Promise<OperatorAccount>;
  resetOperatorPassword(id: number, password: string): Promise<OperatorAccount>;
  setOperatorEnabled(id: number, enabled: boolean): Promise<OperatorAccount>;
};

export function createOperatorAccountManager(api: OperatorAccountApi) {
  const state = {
    accounts: [] as OperatorAccount[],
    loading: false,
    submitting: false,
    error: "",
    async load() {
      state.loading = true;
      state.error = "";
      try {
        state.accounts = await api.listOperatorAccounts();
      } catch (reason) {
        state.error = message(reason, "账号列表加载失败");
      } finally {
        state.loading = false;
      }
    },
    async create(input: CreateOperatorAccountInput) {
      return submit(async () => {
        state.accounts.push(await api.createOperatorAccount(input));
      });
    },
    async update(id: number, input: UpdateOperatorAccountInput) {
      if (state.accounts.find((account) => account.id === id)?.accountType === "FACTORY_ADMIN") {
        state.error = "出厂管理员资料受保护";
        return false;
      }
      return submit(async () => replace(await api.updateOperatorAccount(id, input)));
    },
    async resetPassword(id: number, password: string) {
      return submit(async () => replace(await api.resetOperatorPassword(id, password)));
    },
    async setEnabled(id: number, enabled: boolean) {
      if (state.accounts.find((account) => account.id === id)?.accountType === "FACTORY_ADMIN") {
        state.error = "出厂管理员不能被停用";
        return false;
      }
      return submit(async () => replace(await api.setOperatorEnabled(id, enabled)));
    },
  };

  const replace = (account: OperatorAccount) => {
    const index = state.accounts.findIndex((item) => item.id === account.id);
    if (index >= 0) state.accounts[index] = account;
  };

  const submit = async (action: () => Promise<void>) => {
    if (state.submitting) return false;
    state.submitting = true;
    state.error = "";
    try {
      await action();
      return true;
    } catch (reason) {
      state.error = message(reason, "账号操作失败");
      return false;
    } finally {
      state.submitting = false;
    }
  };

  return state;
}

function message(reason: unknown, fallback: string) {
  return reason instanceof Error ? reason.message : fallback;
}
