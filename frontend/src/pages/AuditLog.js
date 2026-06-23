import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { api } from '../utils/api';
import Badge from '../components/Badge';
import Spinner, { EmptyState } from '../components/Spinner';
import { fmtDate, truncId, timeAgo } from '../utils/helpers';

const ACTION_COLORS = {
  DEPLOYED:  { bg: 'rgba(16,232,138,0.12)', color: '#10e88a' },
  DISABLED:  { bg: 'rgba(255,71,87,0.12)',  color: '#ff4757' },
  APPROVED:  { bg: 'rgba(79,124,255,0.12)', color: '#4f7cff' },
  REJECTED:  { bg: 'rgba(245,166,35,0.12)', color: '#f5a623' },
  DEFAULT:   { bg: 'rgba(99,130,255,0.08)', color: '#8a96b8' },
};

const ENTITY_LABELS = {
  TRANSFORMATION_RULE:          'Schema Rule',
  RESPONSE_TRANSFORMATION_RULE: 'Response Rule',
  ROUTING_RULE:                 'Routing Rule',
  CORS_RULE:                    'CORS Rule',
};

const S = {
  card: { background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', overflow: 'hidden' },
  th: { padding: '11px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 600,
        letterSpacing: '0.07em', textTransform: 'uppercase', color: 'var(--text-muted)',
        borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' },
  td: { padding: '13px 16px', color: 'var(--text-secondary)', verticalAlign: 'middle', fontSize: '13px' },
};

function DetailField({ label, children }) {
  return (
    <div style={{ marginBottom: '14px' }}>
      <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                    letterSpacing: '0.06em', marginBottom: '6px' }}>{label}</div>
      <div style={{ fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.55 }}>{children}</div>
    </div>
  );
}

function parseDetails(raw) {
  if (!raw) return {};
  if (typeof raw === 'object' && !Array.isArray(raw)) return raw;
  if (typeof raw === 'string') {
    try { return JSON.parse(raw); } catch { return { raw }; }
  }
  return {};
}

function actionBadge(action) {
  const cfg = ACTION_COLORS[action] || ACTION_COLORS.DEFAULT;
  return (
    <span style={{
      padding: '3px 10px', borderRadius: '20px', fontSize: '11px', fontWeight: 600,
      letterSpacing: '0.05em', textTransform: 'uppercase',
      background: cfg.bg, color: cfg.color,
    }}>{action || 'UNKNOWN'}</span>
  );
}

function entityIcon(entityType) {
  switch (entityType) {
    case 'ROUTING_RULE': return '🌐';
    case 'CORS_RULE': return '🔒';
    case 'RESPONSE_TRANSFORMATION_RULE': return '↩️';
    case 'TRANSFORMATION_RULE': return '⚙️';
    default: return '📋';
  }
}

function buildSummary(entry) {
  const entityType = entry.entity_type || entry.entityType || '';
  const action = entry.action || '';
  const details = parseDetails(entry.details);
  const entityLabel = ENTITY_LABELS[entityType] || entityType.replace(/_/g, ' ');

  if (action === 'DISABLED') {
    if (entityType === 'ROUTING_RULE') {
      return `A routing override was disabled. Gateway traffic for this service reverts to the original registered URL immediately.`;
    }
    if (entityType === 'CORS_RULE') {
      return `A CORS allow rule was disabled. The previously allowed origin will be blocked again on the next request.`;
    }
    if (entityType === 'RESPONSE_TRANSFORMATION_RULE') {
      return `A response transformation rule was disabled. Outbound response bodies will no longer be auto-corrected for this route.`;
    }
    return `A ${entityLabel.toLowerCase()} was disabled. Mendr will stop applying this self-healing fix until a new rule is approved.`;
  }

  if (action === 'DEPLOYED') {
    if (entityType === 'ROUTING_RULE') {
      const svc = details.service || 'the service';
      const from = details.from || 'the old URL';
      const to = details.to || 'the new URL';
      const ttl = details.ttlHours ?? details.ttl_hours;
      return `Mendr deployed a routing override for ${svc}. Traffic is redirected from ${from} to ${to}${ttl != null ? ` for ${ttl} hour(s)` : ''}.`;
    }
    if (entityType === 'CORS_RULE') {
      const target = details.targetService || details.target_service || 'the target service';
      const origin = details.origin || details.allowedOrigin || 'the caller origin';
      const ttl = details.ttlHours ?? details.ttl_hours;
      return `Mendr deployed a CORS allow rule so ${origin} can call ${target}${ttl != null ? ` for ${ttl} hour(s)` : ''}.`;
    }
    if (entityType === 'RESPONSE_TRANSFORMATION_RULE') {
      const type = details.type || 'RESPONSE_TRANSFORM';
      const ttl = details.ttlHours ?? details.ttl_hours;
      return `Mendr deployed a response transformation rule (${type}) to auto-fix outbound response mismatches${ttl != null ? ` for ${ttl} hour(s)` : ''}.`;
    }
    if (entityType === 'TRANSFORMATION_RULE') {
      const type = details.type || 'FIELD_RENAME';
      const ttl = details.ttlHours ?? details.ttl_hours;
      return `Mendr deployed a schema transformation rule (${type}) to auto-fix request payload mismatches between services${ttl != null ? ` for ${ttl} hour(s)` : ''}.`;
    }
  }

  if (action === 'APPROVED') {
    return `An AI analysis was approved and triggered rule deployment for this ${entityLabel.toLowerCase()}.`;
  }
  if (action === 'REJECTED') {
    return `An AI analysis was rejected. No self-healing rule was deployed for this ${entityLabel.toLowerCase()}.`;
  }

  return `Platform action recorded on ${entityLabel.toLowerCase()}: ${action || 'unknown action'}.`;
}

function detailHighlights(entry) {
  const entityType = entry.entity_type || entry.entityType || '';
  const details = parseDetails(entry.details);
  const rows = [];

  if (details.type) rows.push(['Rule Type', <Badge key="type" status={details.type} />]);
  if (details.service) rows.push(['Service', <span key="svc" style={{ fontFamily: 'var(--font-mono)' }}>{details.service}</span>]);
  if (details.from) rows.push(['Original URL', <span key="from" style={{ fontFamily: 'var(--font-mono)', color: '#ff4757' }}>{details.from}</span>]);
  if (details.to) rows.push(['Routed To', <span key="to" style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{details.to}</span>]);
  if (details.targetService || details.target_service) {
    rows.push(['Target Service', <span key="target" style={{ fontFamily: 'var(--font-mono)' }}>{details.targetService || details.target_service}</span>]);
  }
  if (details.origin || details.allowedOrigin) {
    rows.push(['Allowed Origin', <span key="origin" style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{details.origin || details.allowedOrigin}</span>]);
  }
  if (details.analysisId || details.analysis_id) {
    rows.push(['Analysis ID', <span key="aid" style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{details.analysisId || details.analysis_id}</span>]);
  }
  if (details.ttlHours != null || details.ttl_hours != null) {
    rows.push(['TTL', `${details.ttlHours ?? details.ttl_hours} hour(s)`]);
  }

  if (rows.length === 0 && entityType) {
    rows.push(['Entity Type', entityType.replace(/_/g, ' ')]);
  }

  return rows;
}

function AuditDetailModal({ entry, onClose }) {
  if (!entry) return null;

  const entityType = entry.entity_type || entry.entityType || '';
  const action = entry.action || '';
  const details = parseDetails(entry.details);
  const entityLabel = ENTITY_LABELS[entityType] || entityType.replace(/_/g, ' ') || 'Audit Entry';
  const highlights = detailHighlights(entry);
  const hasDetails = details && Object.keys(details).length > 0;

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="audit-dialog-title"
      className="analysis-dialog-overlay"
      onClick={onClose}
    >
      <div className="analysis-dialog" onClick={e => e.stopPropagation()}>
        <div className="analysis-dialog__header">
          <div id="audit-dialog-title" className="analysis-dialog__title">
            {entityIcon(entityType)} Audit Entry Detail
          </div>
          <button type="button" onClick={onClose} aria-label="Close dialog" className="analysis-dialog__close">×</button>
        </div>

        <div className="analysis-dialog__body">
          <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap' }}>
            {actionBadge(action)}
            <Badge status={entityLabel.replace(/\s+/g, '_').toUpperCase()} />
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>
              {truncId(entry.id)}
            </span>
          </div>

          <div style={{ marginBottom: '16px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                          letterSpacing: '0.06em', marginBottom: '8px' }}>What Happened</div>
            <div style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '14px',
                          fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.6 }}>
              {buildSummary(entry)}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '20px' }}>
            {[
              ['Timestamp', fmtDate(entry.created_at || entry.createdAt)],
              ['Relative Time', timeAgo(entry.created_at || entry.createdAt)],
              ['Actor', entry.actor || '—'],
              ['Entity ID', truncId(entry.entity_id || entry.entityId)],
            ].map(([label, val]) => (
              <div key={label} style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '12px' }}>
                <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                              letterSpacing: '0.06em', marginBottom: '4px' }}>{label}</div>
                <div style={{ fontSize: '13px', color: 'var(--text-primary)', fontWeight: 500,
                              fontFamily: ['Entity ID'].includes(label) ? 'var(--font-mono)' : undefined }}>
                  {val || '—'}
                </div>
              </div>
            ))}
          </div>

          {highlights.length > 0 && (
            <div style={{ marginBottom: '16px' }}>
              {highlights.map(([label, content]) => (
                <DetailField key={label} label={label}>{content}</DetailField>
              ))}
            </div>
          )}

          {hasDetails && (
            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                            letterSpacing: '0.06em', marginBottom: '8px' }}>Raw Details</div>
              <pre style={{ background: '#080c18', borderRadius: 'var(--radius-sm)', padding: '14px',
                            fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--accent-cyan)',
                            overflowX: 'auto', lineHeight: 1.5, border: '1px solid var(--border)', margin: 0 }}>
                {JSON.stringify(details, null, 2)}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}

export default function AuditLog() {
  const [log, setLog] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null);

  const fetch = useCallback(async () => {
    setLoading(true);
    try { setLog(await api.getAuditLog()); }
    catch { setLog([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  const viewBtn = (entry) => (
    <button onClick={() => setSelected(entry)} style={{
      background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.2)',
      color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
      cursor: 'pointer', fontSize: '11px', fontWeight: 600,
    }}>View</button>
  );

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <AuditDetailModal entry={selected} onClose={() => setSelected(null)} />

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '24px' }}>
        <div>
          <div style={{ fontSize: '22px', fontWeight: 700 }}>📋 Audit Log</div>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            Complete history of all platform actions and rule changes
          </div>
        </div>
        <button onClick={fetch} style={{
          background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
          padding: '8px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontSize: '12px',
        }}>↻ Refresh</button>
      </div>

      <div style={S.card}>
        {loading ? <Spinner /> : log.length === 0 ? (
          <EmptyState icon="📋" text="No audit entries" sub="Actions will appear here as rules are deployed" />
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Timestamp', 'Entity Type', 'Entity ID', 'Action', 'Actor', 'Summary', ''].map(h => (
                    <th key={h} style={S.th}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {log.map((entry, i) => {
                  const action = entry.action || entry.ACTION;
                  const entityType = entry.entity_type || entry.entityType || '';
                  const cfg = ACTION_COLORS[action] || ACTION_COLORS.DEFAULT;
                  const summary = buildSummary(entry);
                  return (
                    <tr key={entry.id || i}
                        style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s', cursor: 'pointer' }}
                        onClick={() => setSelected(entry)}
                        onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
                        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                      <td style={S.td}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>
                          {fmtDate(entry.created_at || entry.createdAt)}
                        </span>
                      </td>
                      <td style={S.td}>
                        <span style={{ fontSize: '11px', fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)' }}>
                          {ENTITY_LABELS[entityType] || entityType.replace(/_/g, ' ')}
                        </span>
                      </td>
                      <td style={S.td}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>
                          {truncId(entry.entity_id || entry.entityId)}
                        </span>
                      </td>
                      <td style={S.td}>
                        <span style={{
                          padding: '3px 10px', borderRadius: '20px', fontSize: '11px', fontWeight: 600,
                          letterSpacing: '0.05em', textTransform: 'uppercase',
                          background: cfg.bg, color: cfg.color,
                        }}>{action}</span>
                      </td>
                      <td style={S.td}>
                        <span style={{ color: 'var(--text-primary)', fontSize: '13px' }}>
                          {entry.actor || '—'}
                        </span>
                      </td>
                      <td style={{ ...S.td, maxWidth: '320px' }}>
                        <div style={{ color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis',
                                      whiteSpace: 'nowrap', fontSize: '13px' }} title={summary}>
                          {summary}
                        </div>
                      </td>
                      <td style={S.td} onClick={e => e.stopPropagation()}>
                        {viewBtn(entry)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
