/** Domain types mirroring docs/SPEC.md §4/§8/§9. Timestamps are ISO-8601 strings; enums uppercase. */

export type UserRole = 'ADMIN' | 'OPERATOR' | 'USER';
export type JobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'RETRYING' | 'TIMED_OUT';
export type JobPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';
export type WorkerStatus = 'STARTING' | 'IDLE' | 'BUSY' | 'DRAINING' | 'OFFLINE' | 'ERROR';
export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
export type ResultKind = 'inline' | 'file';

export const JOB_STATUSES: readonly JobStatus[] = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRYING', 'TIMED_OUT', 'CANCELLED'] as const;
export const JOB_PRIORITIES: readonly JobPriority[] = ['LOW', 'NORMAL', 'HIGH', 'CRITICAL'] as const;
export const LOG_LEVELS: readonly LogLevel[] = ['DEBUG', 'INFO', 'WARN', 'ERROR'] as const;
export const WORKER_STATUSES: readonly WorkerStatus[] = ['STARTING', 'IDLE', 'BUSY', 'DRAINING', 'OFFLINE', 'ERROR'] as const;
export const TERMINAL_JOB_STATUSES: readonly JobStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'] as const;

export function isJobStatus(v: unknown): v is JobStatus {
  return typeof v === 'string' && (JOB_STATUSES as readonly string[]).includes(v);
}
export function isJobPriority(v: unknown): v is JobPriority {
  return typeof v === 'string' && (JOB_PRIORITIES as readonly string[]).includes(v);
}
export function isLogLevel(v: unknown): v is LogLevel {
  return typeof v === 'string' && (LOG_LEVELS as readonly string[]).includes(v);
}

export interface SessionUser {
  id: string;
  username: string;
  role: UserRole;
}

export interface LoginResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: SessionUser;
}

export interface UserRecord {
  id: string;
  username: string;
  email: string;
  role: UserRole;
  createdAt?: string;
  updatedAt?: string;
}

export interface Project {
  id: string;
  name: string;
  description: string;
  ownerId: string;
  ownerName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkerSummary {
  id: string;
  name: string;
  status: WorkerStatus;
}

export interface Job {
  id: string;
  projectId: string;
  projectName?: string;
  ownerId: string;
  ownerName?: string;
  type: string;
  priority: JobPriority;
  payload: Record<string, unknown> | null;
  status: JobStatus;
  idempotencyKey?: string | null;
  createdAt: string;
  queuedAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  retryCount: number;
  maxRetries: number;
  timeoutSeconds: number;
  workerId?: string | null;
  worker?: WorkerSummary | null;
  progress?: number | null;
  cancelRequested: boolean;
  availableAt: string;
  leaseExpiresAt?: string | null;
  lastError?: string | null;
}

export interface Attempt {
  id: number;
  jobId: string;
  attemptNumber: number;
  workerId?: string | null;
  startedAt: string;
  finishedAt?: string | null;
  outcome?: string | null;
  error?: string | null;
}

export interface JobLog {
  id?: number;
  jobId: string;
  workerId?: string | null;
  level: LogLevel;
  message: string;
  metadata?: Record<string, unknown> | null;
  createdAt: string;
}

export interface AuditLog {
  id: number;
  actorId?: string | null;
  actorName?: string | null;
  action: string;
  resourceType?: string | null;
  resourceId?: string | null;
  result: 'SUCCESS' | 'FAILURE';
  metadata?: Record<string, unknown> | null;
  createdAt: string;
}

export interface Worker {
  id: string;
  name: string;
  hostname: string;
  version: string;
  status: WorkerStatus;
  capabilities: string[];
  startedAt: string;
  lastHeartbeat: string;
  heartbeatIntervalS: number;
  currentJobId?: string | null;
  controlPort?: number | null;
  stale?: boolean;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Health {
  status: string;
  db: string;
  version: string;
}

export interface JobResultView {
  kind: 'inline' | 'file';
  data?: unknown;
  blobUrl?: string;
  sha256?: string | null;
  sizeBytes?: number | null;
  contentType?: string | null;
}

export interface JobFilters {
  projectId?: string;
  status?: JobStatus[];
  type?: string;
  priority?: JobPriority;
  page?: number;
  size?: number;
}

export interface AuditFilters {
  actor?: string;
  action?: string;
  result?: 'SUCCESS' | 'FAILURE';
  page?: number;
  size?: number;
}

/* ----------------------------- job type catalog --------------------------- */

export type JobFieldType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array';

export interface JobTypeField {
  name: string;
  type: JobFieldType;
  required: boolean;
  description?: string;
  enumValues?: string[];
  example?: unknown;
  defaultValue?: unknown;
  min?: number;
  max?: number;
  maxLength?: number;
  /** Render as a multi-line editor (large text / base64 payloads). */
  long?: boolean;
  /** Fields of a group where exactly one must be provided (hash_sha256). */
  exactlyOneOf?: string[];
}

export interface JobType {
  type: string;
  description: string;
  fields: JobTypeField[];
  examplePayload?: Record<string, unknown>;
  source: 'api' | 'spec';
}

/* ------------------------------- websocket -------------------------------- */

/** Raw WS frame per SPEC §8: {"type":"job.updated","jobId":...,"timestamp":...} */
export interface WsFrame {
  type: string;
  timestamp?: string;
  [key: string]: unknown;
}

export type TickerTone = 'amber' | 'emerald' | 'teal' | 'rose' | 'yellow' | 'mute' | 'dim';

export interface TickerItem {
  id: number;
  at: number;
  kind: string;
  tone: TickerTone;
  text: string;
  jobId?: string;
  workerId?: string;
}
