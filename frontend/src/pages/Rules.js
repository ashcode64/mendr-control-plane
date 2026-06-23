import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import toast from 'react-hot-toast';
import { api } from '../utils/api';
import Badge from '../components/Badge';
import Spinner, { EmptyState } from '../components/Spinner';
import { timeAgo, truncId, fmtDate } from '../utils/helpers';

const S = {
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' },
  title: { fontSize: '22px', fontWeight: 700, letterSpacing: '-0.01em' },
  sub: { fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' },
  card: { background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', overflow: 'hidden' },
  th: { padding: '11px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 600,
        letterSpacing: '0.07em', textTransform: 'uppercase', color: 'var(--text-muted)',
        borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' },
  td: { padding: '13px 16px', color: 'var(--text-secondary)', verticalAlign: 'middle', fontSize: '13px' },
};

const TABS = [
  { id: 'schema',  label: '⚙️ Schema Rules',  color: '#4f7cff' },
  { id: 'routing', label: '🌐 Routing Rules', color: '#f97316' },
  { id: 'cors',    label: '🔒 CORS Rules',    color: '#ef4444' },
  { id: 'origin',  label: '↔️ Origin Override', color: '#a855f7' },
];

/** Jackson serializes boolean isActive as "active" — handle all variants */
function ruleIsActive(rule) {
  if (typeof rule.active === 'boolean') return rule.active;
  if (typeof rule.is_active === 'boolean') return rule.is_active;
  if (typeof rule.isActive === 'boolean') return rule.isActive;
  return false;
}

function DetailField({ label, children }) {
  return (
    <div style={{ marginBottom: '14px' }}>
      <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                    letterSpacing: '0.06em', marginBottom: '6px' }}>{label}</div>
      <div style={{ fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.55 }}>{children}</div>
    </div>
  );
}

function ruleSummary(type, rule) {
  if (type === 'routing') {
    return (
      <>
        <DetailField label="Service">{rule.serviceName}</DetailField>
        <DetailField label="URL Change">
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: '#ff4757', textDecoration: 'line-through' }}>{rule.originalUrl}</span>
          <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: '#10e88a' }}>{rule.newUrl}</span>
        </DetailField>
      </>
    );
  }
  if (type === 'cors') {
    return (
      <>
        <DetailField label="Target Service"><span style={{ fontFamily: 'var(--font-mono)' }}>{rule.targetService}</span></DetailField>
        <DetailField label="Allowed Origin"><span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{rule.allowedOrigin}</span></DetailField>
      </>
    );
  }
  if (type === 'origin') {
    return (
      <>
        <DetailField label="Route">
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{rule.sourceService}</span>
          <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-blue)' }}>{rule.targetService}</span>
          <span style={{ fontFamily: 'var(--font-mono)', marginLeft: '8px' }}>{rule.endpoint}</span>
        </DetailField>
        <DetailField label="Origin Rewrite">
          <span style={{ fontFamily: 'var(--font-mono)', color: '#ff4757', textDecoration: 'line-through' }}>{rule.callerOrigin}</span>
          <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
          <span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{rule.outboundOrigin}</span>
        </DetailField>
      </>
    );
  }
  const sA = rule.service_a || rule.serviceA;
  const sB = rule.service_b || rule.serviceB;
  return (
    <>
      <DetailField label="Route">
        <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{sA}</span>
        <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
        <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-blue)' }}>{sB}</span>
      </DetailField>
      <DetailField label="Endpoint"><span style={{ fontFamily: 'var(--font-mono)' }}>{rule.endpoint}</span></DetailField>
      <DetailField label="Rule Type"><Badge status={rule.rule_type || rule.ruleType || 'FIELD_RENAME'} /></DetailField>
    </>
  );
}

function disableImpact(type) {
  if (type === 'routing') return 'Traffic will revert to the original URL immediately. Self-healing routing will stop for this service.';
  if (type === 'cors') return 'The allowed origin will be blocked again on the next request.';
  if (type === 'origin') return 'Outbound Origin will no longer be rewritten; upstream CORS may reject the real caller origin again.';
  return 'Request/response transformations for this route will no longer be applied.';
}

function DisableConfirmModal({ pending, onConfirm, onCancel, confirming }) {
  if (!pending) return null;
  const { type, rule } = pending;
  const title = type === 'routing' ? '🌐 Disable Routing Rule'
    : type === 'cors' ? '🔒 Disable CORS Rule'
    : type === 'origin' ? '↔️ Disable Origin Override'
    : '⚙️ Disable Schema Rule';

  return createPortal(
    <div role="dialog" aria-modal="true" className="analysis-dialog-overlay" onClick={onCancel}>
      <div className="analysis-dialog" onClick={e => e.stopPropagation()}>
        <div className="analysis-dialog__header">
          <div className="analysis-dialog__title">{title}</div>
          <button type="button" onClick={onCancel} aria-label="Close dialog" className="analysis-dialog__close">×</button>
        </div>
        <div className="analysis-dialog__body">
          <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '20px' }}>
            <Badge status="ACTIVE" />
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{rule.id}</span>
          </div>

          {ruleSummary(type, rule)}

          <div style={{ marginTop: '16px', marginBottom: '24px', background: 'rgba(255,71,87,0.06)',
                        border: '1px solid rgba(255,71,87,0.2)', borderRadius: 'var(--radius-sm)',
                        padding: '14px', fontSize: '13px', color: '#ff4757', lineHeight: 1.6 }}>
            ⚠ {disableImpact(type)}
          </div>

          <div style={{ display: 'flex', gap: '12px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
            <button onClick={onCancel} disabled={confirming} style={{
              flex: 1, padding: '10px', background: 'var(--bg-card)', border: '1px solid var(--border)',
              color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', cursor: 'pointer',
              fontWeight: 600, fontSize: '13px',
            }}>Cancel</button>
            <button onClick={onConfirm} disabled={confirming} style={{
              flex: 1, padding: '10px', background: 'rgba(255,71,87,0.12)', border: '1px solid rgba(255,71,87,0.3)',
              color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: confirming ? 'not-allowed' : 'pointer',
              fontWeight: 600, fontSize: '13px', opacity: confirming ? 0.7 : 1,
            }}>✓ Confirm Disable</button>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}

function RuleDetailModal({ selection, onClose, onRequestDisable }) {
  if (!selection) return null;
  const { type, rule } = selection;
  const active = ruleIsActive(rule);

  const title = type === 'routing' ? '🌐 Routing Rule'
    : type === 'cors' ? '🔒 CORS Rule'
    : type === 'origin' ? '↔️ Origin Override Rule'
    : '⚙️ Schema Rule';

  return createPortal(
    <div role="dialog" aria-modal="true" className="analysis-dialog-overlay" onClick={onClose}>
      <div className="analysis-dialog" onClick={e => e.stopPropagation()}>
        <div className="analysis-dialog__header">
          <div className="analysis-dialog__title">{title}</div>
          <button type="button" onClick={onClose} aria-label="Close dialog" className="analysis-dialog__close">×</button>
        </div>
        <div className="analysis-dialog__body">
          <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '20px' }}>
            <Badge status={active ? 'ACTIVE' : 'IGNORED'} />
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{rule.id}</span>
          </div>

          {type === 'routing' && (
            <>
              <DetailField label="Service">{rule.serviceName}</DetailField>
              <DetailField label="Original URL">
                <span style={{ fontFamily: 'var(--font-mono)', color: '#ff4757' }}>{rule.originalUrl || '—'}</span>
              </DetailField>
              <DetailField label="Routed To">
                <span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{rule.newUrl || '—'}</span>
              </DetailField>
              <DetailField label="Discovery Method"><Badge status={rule.discoveryMethod || 'AI_SUGGESTED'} /></DetailField>
              <DetailField label="Approved By">{rule.approvedBy || '—'}</DetailField>
              <DetailField label="Approved At">{fmtDate(rule.approvedAt)}</DetailField>
              <DetailField label="Expires">{rule.expiresAt ? `${timeAgo(rule.expiresAt)} (${fmtDate(rule.expiresAt)})` : 'Never'}</DetailField>
              {rule.failureId && <DetailField label="Failure ID"><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.failureId}</span></DetailField>}
              {rule.analysisId && <DetailField label="Analysis ID"><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.analysisId}</span></DetailField>}
              {active && (
                <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '12px', lineHeight: 1.5 }}>
                    All gateway traffic to <strong>{rule.serviceName}</strong> is redirected to the new URL until expiry or disable.
                  </div>
                  <button onClick={() => { onRequestDisable('routing', rule); onClose(); }} style={{
                    padding: '10px 16px', background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.25)',
                    color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
                  }}>Disable Rule & Revert URL</button>
                </div>
              )}
            </>
          )}

          {type === 'cors' && (
            <>
              <DetailField label="Target Service"><span style={{ fontFamily: 'var(--font-mono)' }}>{rule.targetService}</span></DetailField>
              <DetailField label="Allowed Origin"><span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{rule.allowedOrigin}</span></DetailField>
              {rule.previousOrigin && <DetailField label="Previous Origin"><span style={{ fontFamily: 'var(--font-mono)', color: '#ff4757' }}>{rule.previousOrigin}</span></DetailField>}
              <DetailField label="Methods"><span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.allowedMethods}</span></DetailField>
              <DetailField label="Approved By">{rule.approvedBy || '—'}</DetailField>
              <DetailField label="Expires">{rule.expiresAt ? timeAgo(rule.expiresAt) : 'Never'}</DetailField>
              {active && (
                <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
                  <button onClick={() => { onRequestDisable('cors', rule); onClose(); }} style={{
                    padding: '10px 16px', background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.25)',
                    color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
                  }}>Disable CORS Rule</button>
                </div>
              )}
            </>
          )}

          {type === 'origin' && (
            <>
              <DetailField label="Route">
                <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{rule.sourceService}</span>
                <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
                <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-blue)' }}>{rule.targetService}</span>
              </DetailField>
              <DetailField label="Endpoint"><span style={{ fontFamily: 'var(--font-mono)' }}>{rule.endpoint}</span></DetailField>
              <DetailField label="Outbound Origin to B">
                <span style={{ fontFamily: 'var(--font-mono)', color: '#ff4757', textDecoration: 'line-through' }}>{rule.callerOrigin}</span>
                <span style={{ color: 'var(--text-muted)', margin: '0 8px' }}>→</span>
                <span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a' }}>{rule.outboundOrigin}</span>
              </DetailField>
              <DetailField label="Rewrite ACAO">{rule.rewriteResponseAcao !== false ? 'Yes — restore real caller origin to A' : 'No'}</DetailField>
              <DetailField label="Approved By">{rule.approvedBy || '—'}</DetailField>
              <DetailField label="Expires">{rule.expiresAt ? timeAgo(rule.expiresAt) : 'Never'}</DetailField>
              {active && (
                <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
                  <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '12px', lineHeight: 1.5 }}>
                    Mendr rewrites the outbound <code>Origin</code> header to B while keeping <strong>{rule.sourceService}</strong> as the caller identity.
                  </div>
                  <button onClick={() => { onRequestDisable('origin', rule); onClose(); }} style={{
                    padding: '10px 16px', background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.25)',
                    color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
                  }}>Disable Origin Override</button>
                </div>
              )}
            </>
          )}

          {type === 'schema' && (
            <>
              <DetailField label="Route">
                <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{rule.service_a || rule.serviceA}</span>
                <span style={{ color: 'var(--text-muted)', margin: '0 6px' }}>→</span>
                <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-blue)' }}>{rule.service_b || rule.serviceB}</span>
              </DetailField>
              <DetailField label="Endpoint"><span style={{ fontFamily: 'var(--font-mono)' }}>{rule.endpoint}</span></DetailField>
              <DetailField label="Rule Type"><Badge status={rule.rule_type || rule.ruleType || 'FIELD_RENAME'} /></DetailField>
              {(rule.rule_definition || rule.ruleDefinition) && (
                <DetailField label="Rule Definition">
                  <pre style={{ background: '#080c18', borderRadius: 'var(--radius-sm)', padding: '14px',
                                fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--accent-cyan)',
                                overflowX: 'auto', lineHeight: 1.5, border: '1px solid var(--border)', margin: 0 }}>
                    {JSON.stringify(rule.rule_definition || rule.ruleDefinition, null, 2)}
                  </pre>
                </DetailField>
              )}
              <DetailField label="Approved By">{rule.approved_by || rule.approvedBy || '—'}</DetailField>
              <DetailField label="Expires">{(rule.expires_at || rule.expiresAt) ? timeAgo(rule.expires_at || rule.expiresAt) : 'Never'}</DetailField>
              {active && (
                <div style={{ marginTop: '20px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
                  <button onClick={() => { onRequestDisable('schema', rule); onClose(); }} style={{
                    padding: '10px 16px', background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.25)',
                    color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
                  }}>Disable Schema Rule</button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}

function RuleTable({ columns, rows, renderRow }) {
  if (rows.length === 0) return <EmptyState icon="📭" text="No rules yet" sub="Rules appear here after approval" />;
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>{columns.map(h => <th key={h} style={S.th}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map(row => renderRow(row))}
        </tbody>
      </table>
    </div>
  );
}

export default function Rules() {
  const [tab, setTab] = useState('schema');
  const [schemaRules, setSchemaRules] = useState([]);
  const [routingRules, setRoutingRules] = useState([]);
  const [corsRules, setCorsRules] = useState([]);
  const [originRules, setOriginRules] = useState([]);
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null);
  const [pendingDisable, setPendingDisable] = useState(null);
  const [confirmingDisable, setConfirmingDisable] = useState(false);

  const fetch = useCallback(async () => {
    setLoading(true);
    try {
      const [schema, routing, cors, origin, s] = await Promise.allSettled([
        api.getRules(), api.getRoutingRules(), api.getCorsRules(), api.getOriginOverrideRules(), api.getRuleStats(),
      ]);
      if (schema.status === 'fulfilled')  setSchemaRules(Array.isArray(schema.value) ? schema.value : []);
      if (routing.status === 'fulfilled') setRoutingRules(Array.isArray(routing.value) ? routing.value : []);
      if (cors.status === 'fulfilled')    setCorsRules(Array.isArray(cors.value) ? cors.value : []);
      if (origin.status === 'fulfilled')  setOriginRules(Array.isArray(origin.value) ? origin.value : []);
      if (s.status === 'fulfilled')       setStats(s.value);
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  const disableBtn = (onClick) => (
    <button onClick={onClick} style={{
      background: 'rgba(255,71,87,0.08)', border: '1px solid rgba(255,71,87,0.2)',
      color: '#ff4757', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
      cursor: 'pointer', fontSize: '11px', fontWeight: 600,
    }}>Disable</button>
  );

  const disableSchema  = async id => { await api.disableRule(id, 'dashboard-user'); };
  const disableRouting = async id => { await api.disableRoutingRule(id); };
  const disableCors    = async id => { await api.disableCorsRule(id); };
  const disableOrigin  = async id => { await api.disableOriginOverrideRule(id); };

  const requestDisable = (type, rule) => setPendingDisable({ type, rule });

  const confirmDisable = async () => {
    if (!pendingDisable) return;
    const { type, rule } = pendingDisable;
    setConfirmingDisable(true);
    try {
      if (type === 'routing') {
        await disableRouting(rule.id);
        toast.success('Routing rule disabled — URL reverted');
      } else if (type === 'cors') {
        await disableCors(rule.id);
        toast.success('CORS rule disabled');
      } else if (type === 'origin') {
        await disableOrigin(rule.id);
        toast.success('Origin override rule disabled');
      } else {
        await disableSchema(rule.id);
        toast.success('Schema rule disabled');
      }
      setPendingDisable(null);
      setSelected(null);
      fetch();
    } catch {
      toast.error('Failed to disable rule');
    } finally {
      setConfirmingDisable(false);
    }
  };

  const viewBtn = (onClick) => (
    <button onClick={onClick} style={{
      background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.2)',
      color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
      cursor: 'pointer', fontSize: '11px', fontWeight: 600,
    }}>View</button>
  );

  const routingRow = (rule) => (
    <tr key={rule.id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s', cursor: 'pointer' }}
        onClick={() => setSelected({ type: 'routing', rule })}
        onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
      <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(rule.id)}</span></td>
      <td style={{ ...S.td, color: 'var(--text-primary)', fontWeight: 500 }}>{rule.serviceName}</td>
      <td style={S.td}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#ff4757', textDecoration: 'line-through' }}>{rule.originalUrl}</span>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#10e88a' }}>→ {rule.newUrl}</span>
        </div>
      </td>
      <td style={S.td}><Badge status={rule.discoveryMethod || 'AI_SUGGESTED'} /></td>
      <td style={S.td}>{rule.approvedBy || '—'}</td>
      <td style={S.td}>{rule.expiresAt ? timeAgo(rule.expiresAt) : '∞'}</td>
      <td style={S.td}><Badge status={ruleIsActive(rule) ? 'ACTIVE' : 'IGNORED'} /></td>
      <td style={S.td} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', gap: '6px' }}>
          {viewBtn(() => setSelected({ type: 'routing', rule }))}
          {ruleIsActive(rule) && disableBtn(() => requestDisable('routing', rule))}
        </div>
      </td>
    </tr>
  );

  const corsRow = (rule) => (
    <tr key={rule.id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s', cursor: 'pointer' }}
        onClick={() => setSelected({ type: 'cors', rule })}
        onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
      <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(rule.id)}</span></td>
      <td style={{ ...S.td, color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.targetService}</td>
      <td style={S.td}>
        {rule.previousOrigin && <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#ff4757', textDecoration: 'line-through', display: 'block' }}>{rule.previousOrigin}</span>}
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#10e88a' }}>{rule.allowedOrigin}</span>
      </td>
      <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{rule.allowedMethods}</span></td>
      <td style={S.td}>{rule.approvedBy || '—'}</td>
      <td style={S.td}>{rule.expiresAt ? timeAgo(rule.expiresAt) : '∞'}</td>
      <td style={S.td}><Badge status={ruleIsActive(rule) ? 'ACTIVE' : 'IGNORED'} /></td>
      <td style={S.td} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', gap: '6px' }}>
          {viewBtn(() => setSelected({ type: 'cors', rule }))}
          {ruleIsActive(rule) && disableBtn(() => requestDisable('cors', rule))}
        </div>
      </td>
    </tr>
  );

  const originRow = (rule) => (
    <tr key={rule.id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s', cursor: 'pointer' }}
        onClick={() => setSelected({ type: 'origin', rule })}
        onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
      <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(rule.id)}</span></td>
      <td style={S.td}>
        <span style={{ color: 'var(--accent-cyan)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.sourceService}</span>
        <span style={{ color: 'var(--text-muted)', margin: '0 4px' }}>→</span>
        <span style={{ color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{rule.targetService}</span>
      </td>
      <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{rule.endpoint}</span></td>
      <td style={S.td}>
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#ff4757', textDecoration: 'line-through' }}>{rule.callerOrigin}</span>
        <span style={{ color: 'var(--text-muted)', margin: '0 4px' }}>→</span>
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: '#10e88a' }}>{rule.outboundOrigin}</span>
      </td>
      <td style={S.td}>{rule.approvedBy || '—'}</td>
      <td style={S.td}>{rule.expiresAt ? timeAgo(rule.expiresAt) : '∞'}</td>
      <td style={S.td}><Badge status={ruleIsActive(rule) ? 'ACTIVE' : 'IGNORED'} /></td>
      <td style={S.td} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', gap: '6px' }}>
          {viewBtn(() => setSelected({ type: 'origin', rule }))}
          {ruleIsActive(rule) && disableBtn(() => requestDisable('origin', rule))}
        </div>
      </td>
    </tr>
  );

  const schemaRow = (rule) => {
    const id = rule.id;
    const sA = rule.service_a || rule.serviceA;
    const sB = rule.service_b || rule.serviceB;
    const endpoint = rule.endpoint;
    const ruleType = rule.rule_type || rule.ruleType;
    const isActive = ruleIsActive(rule);
    return (
      <tr key={id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s', cursor: 'pointer' }}
          onClick={() => setSelected({ type: 'schema', rule })}
          onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
          onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
        <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(id)}</span></td>
        <td style={S.td}>
          <span style={{ color: 'var(--accent-cyan)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{sA}</span>
          <span style={{ color: 'var(--text-muted)', margin: '0 4px' }}>→</span>
          <span style={{ color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{sB}</span>
        </td>
        <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{endpoint}</span></td>
        <td style={S.td}><Badge status={ruleType || 'FIELD_RENAME'} /></td>
        <td style={S.td}>{rule.approved_by || rule.approvedBy || '—'}</td>
        <td style={S.td}>{(rule.expires_at || rule.expiresAt) ? timeAgo(rule.expires_at || rule.expiresAt) : '∞'}</td>
        <td style={S.td}><Badge status={isActive ? 'ACTIVE' : 'IGNORED'} /></td>
        <td style={S.td} onClick={e => e.stopPropagation()}>
          <div style={{ display: 'flex', gap: '6px' }}>
            {viewBtn(() => setSelected({ type: 'schema', rule }))}
            {isActive && disableBtn(() => requestDisable('schema', rule))}
          </div>
        </td>
      </tr>
    );
  };

  const currentRules   = tab === 'schema' ? schemaRules
    : tab === 'routing' ? routingRules
    : tab === 'cors' ? corsRules
    : originRules;
  const currentColumns = tab === 'routing'
    ? ['ID', 'Service', 'URL Change', 'Discovery', 'Approved By', 'Expires', 'Status', '']
    : tab === 'cors'
    ? ['ID', 'Target Service', 'Origin (Old → New)', 'Methods', 'Approved By', 'Expires', 'Status', '']
    : tab === 'origin'
    ? ['ID', 'Route', 'Endpoint', 'Origin (Caller → Outbound)', 'Approved By', 'Expires', 'Status', '']
    : ['ID', 'Service A → B', 'Endpoint', 'Rule Type', 'Approved By', 'Expires', 'Status', ''];
  const currentRowFn = tab === 'routing' ? routingRow
    : tab === 'cors' ? corsRow
    : tab === 'origin' ? originRow
    : schemaRow;

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <DisableConfirmModal
        pending={pendingDisable}
        onConfirm={confirmDisable}
        onCancel={() => setPendingDisable(null)}
        confirming={confirmingDisable}
      />
      <RuleDetailModal
        selection={selected}
        onClose={() => setSelected(null)}
        onRequestDisable={requestDisable}
      />
      <div style={S.header}>
        <div>
          <div style={S.title}>Active Rules</div>
          <div style={S.sub}>Schema transformations, dynamic routing overrides, and CORS patches</div>
        </div>
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          {stats && (
            <div style={{ display: 'flex', gap: '8px' }}>
              {[['Schema', stats.active ?? 0, '#4f7cff'], ['Routing', routingRules.length, '#f97316'], ['CORS', corsRules.length, '#ef4444'], ['Origin', originRules.length, '#a855f7']].map(([l, v, c]) => (
                <div key={l} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                                      borderRadius: 'var(--radius-sm)', padding: '6px 14px', textAlign: 'center' }}>
                  <div style={{ fontSize: '16px', fontWeight: 700, color: c }}>{v}</div>
                  <div style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase' }}>{l}</div>
                </div>
              ))}
            </div>
          )}
          <button onClick={fetch} style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
            padding: '8px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontSize: '12px',
            display: 'flex', alignItems: 'center', gap: '6px',
          }}>↻ Refresh</button>
        </div>
      </div>

      <div style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
        {TABS.map(t => {
          const count = t.id === 'schema' ? schemaRules.length
            : t.id === 'routing' ? routingRules.length
            : t.id === 'cors' ? corsRules.length
            : originRules.length;
          return (
            <button key={t.id} onClick={() => setTab(t.id)} style={{
              padding: '8px 18px', borderRadius: 'var(--radius-sm)', fontSize: '13px', fontWeight: 600,
              cursor: 'pointer', border: 'none', transition: 'all 0.15s',
              background: tab === t.id ? t.color : 'var(--bg-card)',
              color: tab === t.id ? '#050a14' : 'var(--text-secondary)',
            }}>
              {t.label}{count > 0 ? ` (${count})` : ''}
            </button>
          );
        })}
      </div>

      {tab === 'routing' && (
        <div style={{ background: 'rgba(249,115,22,0.08)', border: '1px solid rgba(249,115,22,0.25)',
                      borderRadius: 'var(--radius-md)', padding: '12px 16px', marginBottom: '16px',
                      fontSize: '13px', color: '#fb923c', display: 'flex', gap: '10px' }}>
          <span style={{ fontSize: '18px' }}>🌐</span>
          <div><strong>Dynamic Routing Rules</strong> redirect all traffic for a service to a new URL for the TTL period. Disabling a rule reverts traffic to the original URL immediately.</div>
        </div>
      )}
      {tab === 'cors' && (
        <div style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.25)',
                      borderRadius: 'var(--radius-md)', padding: '12px 16px', marginBottom: '16px',
                      fontSize: '13px', color: '#f87171', display: 'flex', gap: '10px' }}>
          <span style={{ fontSize: '18px' }}>🔒</span>
          <div><strong>Dynamic CORS Rules</strong> temporarily allow a new caller origin to reach a target service. Disabling blocks the origin again immediately.</div>
        </div>
      )}

      {tab === 'origin' && (
        <div style={{ background: 'rgba(168,85,247,0.08)', border: '1px solid rgba(168,85,247,0.25)',
                      borderRadius: 'var(--radius-md)', padding: '12px 16px', marginBottom: '16px',
                      fontSize: '13px', color: '#c084fc', display: 'flex', gap: '10px' }}>
          <span style={{ fontSize: '18px' }}>↔️</span>
          <div><strong>Origin Override Rules</strong> rewrite the outbound <code>Origin</code> header to an allowed value when Service B rejects the real caller URL. Caller identity (<code>sourceService</code>) is never changed.</div>
        </div>
      )}

      <div style={S.card}>
        {loading ? <Spinner /> : (
          <RuleTable
            columns={currentColumns}
            rows={currentRules}
            renderRow={currentRowFn}
          />
        )}
      </div>
    </div>
  );
}
