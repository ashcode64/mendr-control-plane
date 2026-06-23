import React from 'react';

const COLORS = {
  OPEN:             { bg: 'rgba(245,166,35,0.15)',  color: '#f5a623', dot: true, pulse: true },
  ANALYZING:        { bg: 'rgba(79,124,255,0.15)',  color: '#4f7cff', dot: true, pulse: true },
  RESOLVED:         { bg: 'rgba(16,232,138,0.15)',  color: '#10e88a', dot: true },
  IGNORED:          { bg: 'rgba(74,85,104,0.15)',   color: '#8a96b8', dot: true },
  PENDING_APPROVAL: { bg: 'rgba(245,166,35,0.15)',  color: '#f5a623', dot: true, pulse: true },
  APPROVED:         { bg: 'rgba(16,232,138,0.15)',  color: '#10e88a', dot: true },
  REJECTED:         { bg: 'rgba(255,71,87,0.15)',   color: '#ff4757', dot: true },
  HIGH:             { bg: 'rgba(255,71,87,0.15)',   color: '#ff4757' },
  MEDIUM:           { bg: 'rgba(245,166,35,0.15)',  color: '#f5a623' },
  LOW:              { bg: 'rgba(16,232,138,0.15)',  color: '#10e88a' },
  ACTIVE:           { bg: 'rgba(79,124,255,0.15)',  color: '#4f7cff', dot: true },
  SCHEMA_MISMATCH:  { bg: 'rgba(249,115,22,0.15)',  color: '#fb923c' },
  FIELD_RENAME:     { bg: 'rgba(168,85,247,0.15)',  color: '#c084fc' },
  TYPE_COERCE:      { bg: 'rgba(79,124,255,0.15)',  color: '#4f7cff' },
  ADD_DEFAULT:      { bg: 'rgba(16,232,138,0.15)',  color: '#10e88a' },
  REMOVE_FIELD:     { bg: 'rgba(255,71,87,0.15)',   color: '#ff4757' },
};

export default function Badge({ status }) {
  if (!status) return null;
  const key = status.toUpperCase().replace(/ /g, '_');
  const cfg = COLORS[key] || { bg: 'rgba(99,130,255,0.1)', color: '#8a96b8' };
  const label = status.replace(/_/g, ' ');

  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: '5px',
      padding: '3px 10px', borderRadius: '20px',
      fontSize: '11px', fontWeight: 600,
      letterSpacing: '0.05em', textTransform: 'uppercase',
      background: cfg.bg, color: cfg.color,
      whiteSpace: 'nowrap',
    }}>
      {cfg.dot && (
        <span style={{
          width: '6px', height: '6px', borderRadius: '50%',
          background: cfg.color, flexShrink: 0,
          ...(cfg.pulse ? { animation: 'pulse-glow 1.5s infinite' } : {}),
        }} />
      )}
      {label}
    </span>
  );
}
