import React from 'react';
import { useAuth } from '@workos-inc/authkit-react';
import { AUTH_ENABLED } from './tokenBridge';

function Menu() {
  const { user, organizationId, signOut } = useAuth();
  if (!user) return null;
  const label = user.email || user.firstName || 'Account';
  return (
    <div style={s.wrap}>
      <div style={s.email} title={label}>{label}</div>
      {organizationId && <div style={s.org}>org: {String(organizationId).slice(0, 12)}…</div>}
      <button style={s.btn} onClick={() => signOut()}>Sign out</button>
    </div>
  );
}

/** Renders the signed-in user + sign-out only when WorkOS auth is active. */
export default function UserMenu() {
  if (!AUTH_ENABLED) return null;
  return <Menu />;
}

const s = {
  wrap: { padding: '12px 8px 0', borderTop: '1px solid var(--border)', marginTop: 8 },
  email: {
    fontSize: 11, color: 'var(--text-primary)', fontWeight: 600,
    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
  },
  org: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 },
  btn: {
    marginTop: 8, width: '100%', padding: '6px 8px', borderRadius: 6, cursor: 'pointer',
    background: 'transparent', color: 'var(--text-secondary)',
    border: '1px solid var(--border)', fontSize: 12,
  },
};
