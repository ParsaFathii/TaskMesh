import type { UserRole } from '../types';

/** ADMIN/OPERATOR grants (workers, all jobs, logs, metrics). */
export function isOperator(role: UserRole | undefined): boolean {
  return role === 'OPERATOR' || role === 'ADMIN';
}

/** Human-readable grant descriptions per role (SPEC §11). */
export const ROLE_GATES: Record<UserRole, string> = {
  ADMIN: 'users, workers, all projects/jobs, logs, metrics, config',
  OPERATOR: 'workers, all jobs retry/cancel, logs, metrics',
  USER: 'own projects/jobs/results, submit jobs',
};
