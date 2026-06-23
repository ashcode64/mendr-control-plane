import React from 'react';

const S = {
  card: {
    background: 'var(--bg-elevated)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)',
    padding: '20px 24px',
    position: 'relative',
    overflow: 'hidden',
    transition: 'border-color 0.2s, box-shadow 0.2s',
    cursor: 'default',
  },
  topBar: {
    position: 'absolute',
    top: 0, left: 0, right: 0,
    height: '2px',
  },
  icon: {
    position: 'absolute',
    top: '16px', right: '16px',
    width: '36px', height: '36px',
    borderRadius: '8px',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    fontSize: '18px',
  },
  value: {
    fontSize: '30px',
    fontWeight: 700,
    lineHeight: 1,
    color: 'var(--text-primary)',
    fontVariantNumeric: 'tabular-nums',
    marginTop: '6px',
  },
  label: {
    fontSize: '11px',
    color: 'var(--text-secondary)',
    textTransform: 'uppercase',
    letterSpacing: '0.08em',
    marginTop: '6px',
  },
  sub: {
    fontSize: '12px',
    color: 'var(--text-muted)',
    marginTop: '4px',
  },
};

export default function StatCard({ label, value, icon, color = 'var(--accent-blue)', sub }) {
  return (
    <div style={S.card}
      onMouseEnter={e => { e.currentTarget.style.borderColor = color; e.currentTarget.style.boxShadow = `0 0 20px ${color}22`; }}
      onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.boxShadow = 'none'; }}>
      <div style={{ ...S.topBar, background: color }} />
      <div style={{ ...S.icon, background: `${color}18` }}>{icon}</div>
      <div style={S.label}>{label}</div>
      <div style={{ ...S.value, color }}>{value ?? '—'}</div>
      {sub && <div style={S.sub}>{sub}</div>}
    </div>
  );
}
