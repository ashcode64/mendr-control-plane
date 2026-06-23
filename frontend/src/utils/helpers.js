import { formatDistanceToNow, format } from 'date-fns';

export const timeAgo = dt => {
  if (!dt) return '—';
  try { return formatDistanceToNow(new Date(dt), { addSuffix: true }); }
  catch { return '—'; }
};

export const fmtDate = dt => {
  if (!dt) return '—';
  try { return format(new Date(dt), 'MMM d, yyyy HH:mm'); }
  catch { return '—'; }
};

export const confColor = v => {
  if (v >= 0.85) return 'var(--accent-green)';
  if (v >= 0.70) return 'var(--accent-yellow)';
  return 'var(--accent-red)';
};

export const confLabel = v => {
  if (v >= 0.85) return 'High';
  if (v >= 0.70) return 'Medium';
  return 'Low';
};

export const statusClass = s => {
  if (!s) return '';
  return 'badge-' + s.toLowerCase().replace('_approval', '').replace('pending', 'pending');
};

export const truncId = id => id ? id.substring(0, 8) + '…' : '—';

export const errorType = t => {
  const map = {
    SCHEMA_MISMATCH: 'Schema Mismatch',
    FIELD_RENAME: 'Field Rename',
    MISSING_FIELD: 'Missing Field',
    TYPE_MISMATCH: 'Type Mismatch',
    INTERNAL_ERROR: 'Internal Error',
  };
  return map[t] || t || 'Unknown';
};
