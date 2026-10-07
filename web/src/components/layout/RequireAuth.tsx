import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../stores/auth';
import { ForbiddenPanel } from '../ui/States';
import type { UserRole } from '../../types';

/** Redirects to /login (remembering the target) when no session token exists. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  const location = useLocation();
  if (!token) {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }
  return <>{children}</>;
}

/** UI-level role gate for OPERATOR/ADMIN-only sections (backend always enforces too). */
export function RoleGate({
  roles,
  children,
  fallback,
}: {
  roles: UserRole[];
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const role = useAuthStore((s) => s.user?.role);
  if (role == null) return null;
  if (roles.includes(role)) return <>{children}</>;
  return <>{fallback ?? <ForbiddenPanel required={roles} current={role} />}</>;
}
