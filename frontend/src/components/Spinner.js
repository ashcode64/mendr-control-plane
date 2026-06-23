import React from 'react';

export default function Spinner({ text = 'Loading…' }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
                  padding: '48px', color: 'var(--text-muted)', gap: '12px', fontSize: '13px' }}>
      <div style={{
        width: '20px', height: '20px',
        border: '2px solid var(--border)',
        borderTopColor: 'var(--accent-blue)',
        borderRadius: '50%',
        animation: 'spin 0.7s linear infinite',
      }} />
      {text}
    </div>
  );
}

export function EmptyState({ icon = '📭', text = 'No data yet.', sub }) {
  return (
    <div style={{ textAlign: 'center', padding: '56px 24px', color: 'var(--text-muted)' }}>
      <div style={{ fontSize: '40px', marginBottom: '12px' }}>{icon}</div>
      <div style={{ fontSize: '14px', color: 'var(--text-secondary)', marginBottom: sub ? '6px' : 0 }}>{text}</div>
      {sub && <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>{sub}</div>}
    </div>
  );
}
