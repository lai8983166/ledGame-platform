export const FORMAT_VERSION = 1;
export const SAFETY_CONFIRMATION = "I_UNDERSTAND_THIS_USES_ISOLATED_TEST_DATA";

export type ProfileName = "smoke" | "load" | "quick" | "standard" | "soak" | "overnight";
export type FlowType = "registration" | "game";
export type RequestOutcomeKind = "http" | "timeout" | "network";

export interface LoadProfile {
  scheduledDurationSeconds?: number;
  registrationWorkers: number;
  gameWorkers: number;
  iterationsPerWorker: number;
  maxDurationSeconds: number;
  requestTimeoutMs: number;
  durationMinutes: number;
}

export interface CenterConfig {
  activationLicensePath?: string;
  runId: string;
  memberAdminExecutable: string;
  testRoot: string;
  lanHost: string;
  testPort: number;
  normalPlatformUrl: string;
  startupTimeoutMs: number;
  safetyConfirmation: string;
}

export interface CenterRunPaths {
  runRoot: string;
  userData: string;
  backupRoot: string;
  centerLog: string;
  connectionFile: string;
}

export interface ConnectionInfo {
  formatVersion: number;
  runId: string;
  platformBaseUrl: string;
  testPort: number;
  centerLogPath: string;
  runRoot: string;
  generatedAt: string;
  safetyConfirmation: string;
}

export interface VerifyConfig {
  connectionFile: string;
  agentDirectories: string[];
  outputDirectory: string;
  requestTimeoutMs: number;
  performanceWarningP95Ms: number;
}

export interface AgentConfig extends LoadProfile {
  runId: string;
  agentId: string;
  profile: ProfileName;
  platformBaseUrl: string;
  outputRoot: string;
  safetyConfirmation: string;
}

export interface BasePlanItem {
  memberMode?: "new" | "existing" | "replay";
  dependsOn?: string;
  scheduledSteps?: Array<{ name: string; atMs: number }>;
  playDurationMs?: number;
  operationId: string;
  flowType: FlowType;
  worker: number;
  iteration: number;
  phone: string;
  uid: string;
  memberName: string;
  durationMinutes: number;
}

export interface RegistrationPlanItem extends BasePlanItem {
  flowType: "registration";
}

export interface GamePlanItem extends BasePlanItem {
  flowType: "game";
  deviceId: string;
  roomId: string;
  externalSessionId: string;
  rawScore: number;
}

export type PlanItem = RegistrationPlanItem | GamePlanItem;

export interface PlanFile {
  schedule?: {
    durationMs: number;
    graceMs: number;
    lagThresholdMs: number;
    seed: string;
    registrationWorkers: number;
    gameWorkers: number;
    queries: Array<{ name: string; atMs: number; path: string }>;
  };
  expected?: ExpectedTotals;
  formatVersion: number;
  runId: string;
  agentId: string;
  profile: ProfileName;
  platformBaseUrl: string;
  generatedAt: string;
  items: PlanItem[];
}

export interface StepResult {
  plannedOffsetMs?: number;
  actualOffsetMs?: number;
  startDelayMs?: number;
  name: string;
  method: string;
  path: string;
  kind: RequestOutcomeKind;
  startedAt: string;
  endedAt: string;
  durationMs: number;
  status?: number;
  response?: unknown;
  error?: string;
}

export interface FlowResult {
  formatVersion: number;
  operationId: string;
  flowType: FlowType;
  startedAt: string;
  endedAt: string;
  success: boolean;
  steps: StepResult[];
  error?: string;
}

export interface AgentSummary {
  schedule?: ScheduleMetrics;
  queryResults?: StepResult[];
  formatVersion: number;
  runId: string;
  agentId: string;
  profile: ProfileName;
  platformBaseUrl: string;
  startedAt: string;
  endedAt: string;
  planned: number;
  attempted: number;
  succeeded: number;
  failed: number;
  incomplete: number;
  http5xx: number;
  timeouts: number;
  networkErrors: number;
  durationSamplesMs: number[];
}

export interface Difference {
  code: string;
  message: string;
  agentId?: string;
  operationId?: string;
  expected?: unknown;
  actual?: unknown;
}

export type VerificationConclusion = "PASSED" | "FAILED" | "INVALID";

export interface VerificationReport {
  expected?: ExpectedTotals;
  actual?: ExpectedTotals;
  executionPassed?: boolean;
  formatVersion: number;
  runId: string;
  generatedAt: string;
  conclusion: VerificationConclusion;
  dataIntegrityPassed: boolean;
  agents: AgentSummary[];
  overlapSeconds: number;
  counts: {
    planned: number;
    attempted: number;
    succeeded: number;
    failed: number;
    incomplete: number;
    uncertainButCommitted: number;
  };
  performance: {
    requests: number;
    requestsPerSecond: number;
    p50Ms: number;
    p95Ms: number;
    p99Ms: number;
    warning: boolean;
  };
  sqliteLockErrors: string[];
  differences: Difference[];
  coverageBoundary: string[];
  dataDirectories: string[];
  flowCounts: Record<FlowType, { planned: number; attempted: number; succeeded: number; failed: number }>;
}

export interface ExpectedTotals {
  members: number;
  wristbands: number;
  charges: number;
  bindings: number;
  games: number;
  points: number;
}

export interface ScheduleMetrics {
  durationMs: number;
  actualDurationMs: number;
  graceMs: number;
  lagThresholdMs: number;
  p95StartDelayMs: number;
  maxStartDelayMs: number;
  maxPending: number;
  pendingAtDeadline: number;
  skippedSteps: number;
  completedSteps: number;
  plannedSteps: number;
  executionPassed: boolean;
  samples: Array<{ elapsedMs: number; pending: number; inFlight: number; completed: number }>;
}
