import React from 'react';
import { useAuth } from '@workos-inc/authkit-react';
import { AUTH_ENABLED } from './tokenBridge';

const centered = {
  display: 'flex', flexDirection: 'column', gap: 16,
  alignItems: 'center', justifyContent: 'center',
  minHeight: '100vh', fontFamily: 'var(--font-mono)', color: 'var(--text-primary)',
};

const button = {
  padding: '10px 20px', borderRadius: 8, cursor: 'pointer',
  background: 'var(--accent, #4f46e5)', color: '#fff', border: 'none',
  fontFamily: 'var(--font-mono)', fontSize: 14,
};

function AuthGate({ children }) {
  const { user, isLoading, signIn } = useAuth();

  if (isLoading) {
    return <div style={centered}>Loading…</div>;
  }
  if (!user) {
    return (
      <div style={centered}>
        <div style={{ fontSize: 18 }}>Mendr Control Plane</div>
        <div style={{ opacity: 0.7, fontSize: 13 }}>Sign in to continue</div>
        <button style={button} onClick={() => signIn()}>Sign in</button>
      </div>
    );
  }
  return children;
}

/**
 * Gates the app behind an authenticated WorkOS session. When auth is not configured
 * (no client id), it renders children directly — the safe incremental-rollout state.
 */
export default function RequireAuth({ children }) {
  if (!AUTH_ENABLED) {
    return children;
  }
  return <AuthGate>{children}</AuthGate>;
}
