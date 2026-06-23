import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import toast from 'react-hot-toast';
import { api } from '../utils/api';
import Badge from '../components/Badge';
import Spinner, { EmptyState } from '../components/Spinner';
import { timeAgo, truncId, confColor, confLabel } from '../utils/helpers';

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

function ConfBar({ value }) {
  const color = confColor(value);
  return (
    <div>
      <div style={{ fontSize: '12px', color, fontWeight: 600, marginBottom: '4px' }}>
        {Math.round(value * 100)}% <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>({confLabel(value)})</span>
      </div>
      <div style={{ height: '4px', borderRadius: '2px', background: 'var(--bg-hover)', overflow: 'hidden', width: '100px' }}>
        <div style={{ height: '100%', borderRadius: '2px', background: color, width: `${value * 100}%`, transition: 'width 0.6s ease' }} />
      </div>
    </div>
  );
}

function isRoutingDeployable(item) {
  const rules = item?.transformationRules;
  if (!rules || rules.type !== 'ROUTING_OVERRIDE') return true;
  const url = rules.suggestedNewUrl;
  return url != null && url !== '' && url !== 'null';
}

function canDeployOriginOverride(item) {
  const rules = item?.transformationRules;
  if (!rules || rules.type !== 'CORS_ORIGIN_OVERRIDE') return true;
  const { callerOrigin, outboundOrigin, targetService, endpoint, sourceService } = rules;
  if (!callerOrigin || !outboundOrigin || !targetService || !endpoint || !sourceService) return false;
  if (callerOrigin === outboundOrigin) return false;
  if (/\s/.test(endpoint) || /^(GET|POST|PUT|DELETE|PATCH|OPTIONS|HEAD)\s/i.test(endpoint)) return false;
  const meta = rules._analysisMetadata;
  const allowed = meta?.upstreamAllowedOrigins;
  if (Array.isArray(allowed) && allowed.length > 0 && !allowed.includes(outboundOrigin)) return false;
  if (item.requestOrigin && callerOrigin !== item.requestOrigin) return false;
  return true;
}

function originOverrideDeployWarning(item) {
  const rules = item?.transformationRules;
  if (!rules || rules.type !== 'CORS_ORIGIN_OVERRIDE') return null;
  const meta = rules._analysisMetadata;
  const allowed = meta?.upstreamAllowedOrigins;
  if (Array.isArray(allowed) && allowed.length > 0 && !allowed.includes(rules.outboundOrigin)) {
    return `outboundOrigin is not in upstream allowlist: ${allowed.join(', ')}`;
  }
  if (meta?.validationReason) return meta.validationReason;
  if (item.requestOrigin && rules.callerOrigin !== item.requestOrigin) {
    return `callerOrigin must match failure requestOrigin (${item.requestOrigin})`;
  }
  return null;
}

function canDeployAnalysis(item) {
  return isRoutingDeployable(item) && canDeployOriginOverride(item);
}

function OriginOverridePreview({ rules }) {
  if (!rules || rules.type !== 'CORS_ORIGIN_OVERRIDE') return null;
  const route = `${rules.sourceService || '?'} → ${rules.targetService || '?'}`;
  return (
    <div style={{ marginBottom: '16px' }}>
      <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                    letterSpacing: '0.06em', marginBottom: '8px' }}>Origin Override Preview</div>
      <div style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '14px',
                    fontSize: '13px', lineHeight: 1.7, border: '1px solid var(--border)' }}>
        <div style={{ marginBottom: '10px' }}>
          <span style={{ color: 'var(--text-muted)' }}>Route: </span>
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{route}</span>
          <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--text-secondary)', marginLeft: '8px' }}>
            {rules.endpoint}
          </span>
        </div>
        <div style={{ marginBottom: '10px' }}>
          <span style={{ color: 'var(--text-muted)' }}>Outbound Origin to B: </span>
          <span style={{ fontFamily: 'var(--font-mono)', color: '#ff4757', textDecoration: 'line-through' }}>
            {rules.callerOrigin}
          </span>
          <span style={{ color: 'var(--text-muted)', margin: '0 8px' }}>→</span>
          <span style={{ fontFamily: 'var(--font-mono)', color: '#10e88a', fontWeight: 600 }}>
            {rules.outboundOrigin}
          </span>
        </div>
        <div style={{ marginBottom: '10px', color: 'var(--text-secondary)' }}>
          Caller still identified as: <strong>{rules.sourceService || 'source service'}</strong> (unchanged)
        </div>
        <div style={{ marginBottom: '10px', color: 'var(--text-secondary)' }}>
          Response header: rewrite <code style={{ fontFamily: 'var(--font-mono)' }}>Access-Control-Allow-Origin</code> back to the real caller origin
          {rules.rewriteResponseAcao === false ? ' (disabled)' : ''}
        </div>
        <div style={{ background: 'rgba(251,191,36,0.08)', border: '1px solid rgba(251,191,36,0.25)',
                      borderRadius: 'var(--radius-sm)', padding: '10px 12px', color: '#fbbf24', fontSize: '12px' }}>
          Mendr will impersonate an allowed Origin on the wire to B until you apply the permanent fix.
        </div>
      </div>
    </div>
  );
}

function AnalysisDetail({ item, onApprove, onReject, onClose }) {
  if (!item) return null;
  const isPending = item.status === 'PENDING_APPROVAL';
  const canDeploy = canDeployAnalysis(item);

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="analysis-dialog-title"
      className="analysis-dialog-overlay"
      onClick={onClose}
    >
      <div className="analysis-dialog" onClick={e => e.stopPropagation()}>
        <div className="analysis-dialog__header">
          <div id="analysis-dialog-title" className="analysis-dialog__title">
            🧠 AI Analysis Result
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="analysis-dialog__close"
          >
            ×
          </button>
        </div>

        <div className="analysis-dialog__body">
        {/* Status + confidence */}
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center', marginBottom: '20px' }}>
          <Badge status={item.status} />
          <ConfBar value={item.confidence ?? 0} />
        </div>

        {/* Root Cause */}
        <div style={{ marginBottom: '16px' }}>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '8px' }}>Root Cause</div>
          <div style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '14px',
                        fontSize: '13px', color: 'var(--text-primary)', lineHeight: 1.6 }}>
            {item.rootCause}
          </div>
        </div>

        {/* Transformation Rules */}
        {item.transformationRules && (
          <div style={{ marginBottom: '16px' }}>
            <OriginOverridePreview rules={item.transformationRules} />
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '8px' }}>
              {item.transformationRules.type === 'CORS_ORIGIN_OVERRIDE' ? 'Rule JSON (advanced)' : 'Proposed Transformation Rule'}
            </div>
            <pre style={{ background: '#080c18', borderRadius: 'var(--radius-sm)', padding: '14px',
                          fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--accent-cyan)',
                          overflowX: 'auto', lineHeight: 1.5, border: '1px solid var(--border)' }}>
              {JSON.stringify(item.transformationRules, null, 2)}
            </pre>
          </div>
        )}

        {/* Permanent Fix */}
        {item.suggestedPermanentFix && (
          <div style={{ marginBottom: '24px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '8px' }}>
              Suggested Permanent Fix
            </div>
            <div style={{ background: 'rgba(16,232,138,0.06)', border: '1px solid rgba(16,232,138,0.2)',
                          borderRadius: 'var(--radius-sm)', padding: '14px', fontSize: '13px',
                          color: '#10e88a', lineHeight: 1.6 }}>
              💡 {item.suggestedPermanentFix}
            </div>
          </div>
        )}

        {/* Actions */}
        {isPending && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
            {!canDeploy && item.transformationRules?.type === 'ROUTING_OVERRIDE' && (
              <div style={{ fontSize: '12px', color: 'var(--accent-yellow)', lineHeight: 1.5 }}>
                Rule cannot be deployed without a target URL. Re-run analysis after services are registered in Mendr.
              </div>
            )}
            {!canDeploy && item.transformationRules?.type === 'CORS_ORIGIN_OVERRIDE' && (
              <div style={{ fontSize: '12px', color: 'var(--accent-yellow)', lineHeight: 1.5 }}>
                {originOverrideDeployWarning(item) || 'Origin override is incomplete or invalid — check callerOrigin, outboundOrigin, endpoint path, and upstream allowlist.'}
              </div>
            )}
            <div style={{ display: 'flex', gap: '12px' }}>
            <button onClick={() => onApprove(item.id)} disabled={!canDeploy} style={{
              flex: 1, padding: '10px', background: canDeploy ? 'rgba(16,232,138,0.12)' : 'rgba(16,232,138,0.04)',
              border: '1px solid rgba(16,232,138,0.3)',
              color: canDeploy ? '#10e88a' : 'var(--text-muted)', borderRadius: 'var(--radius-sm)',
              cursor: canDeploy ? 'pointer' : 'not-allowed', fontWeight: 600, fontSize: '13px',
              transition: 'all 0.15s',
            }}>✓ Approve & Deploy Rule</button>
            <button onClick={() => onReject(item.id)} style={{
              flex: 1, padding: '10px', background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.25)',
              color: '#ff4757', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
              transition: 'all 0.15s',
            }}>✗ Reject</button>
            </div>
          </div>
        )}
        </div>
      </div>
    </div>,
    document.body
  );
}

export default function Analysis({ onApproval }) {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null);
  const [filter, setFilter] = useState('ALL');

  const fetch = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.getAnalyses(0, 50);
      setData(res?.content ?? []);
    } catch { setData([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  const handleApprove = async (id) => {
    try {
      await api.approveAnalysis(id);
      toast.success('✓ Rule approved and deployed!');
      setSelected(null);
      fetch();
      onApproval?.();
    } catch { toast.error('Approval failed'); }
  };

  const handleReject = async (id) => {
    try {
      await api.rejectAnalysis(id);
      toast.error('Rule rejected.');
      setSelected(null);
      fetch();
      onApproval?.();
    } catch { toast.error('Rejection failed'); }
  };

  const filtered = filter === 'ALL' ? data : data.filter(d => d.status === filter);
  const pendingCount = data.filter(d => d.status === 'PENDING_APPROVAL').length;

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <AnalysisDetail item={selected} onApprove={handleApprove} onReject={handleReject} onClose={() => setSelected(null)} />

      <div style={S.header}>
        <div>
          <div style={S.title}>AI Analysis</div>
          <div style={S.sub}>Claude-powered schema mismatch analysis and transformation suggestions</div>
        </div>
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          {pendingCount > 0 && (
            <div style={{ background: 'rgba(245,166,35,0.12)', border: '1px solid rgba(245,166,35,0.3)',
                          borderRadius: 'var(--radius-md)', padding: '8px 16px', fontSize: '13px',
                          color: 'var(--accent-yellow)', display: 'flex', alignItems: 'center', gap: '8px',
                          animation: 'pulse-glow 2s infinite' }}>
              ⚠ {pendingCount} pending approval{pendingCount > 1 ? 's' : ''}
            </div>
          )}
          <button onClick={fetch} style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            color: 'var(--text-secondary)', padding: '8px 14px', borderRadius: 'var(--radius-sm)',
            cursor: 'pointer', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px',
          }}>
            ↻ Refresh
          </button>
        </div>
      </div>

      {/* Filter tabs */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
        {['ALL', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED'].map(f => (
          <button key={f} onClick={() => setFilter(f)} style={{
            padding: '6px 14px', borderRadius: 'var(--radius-sm)', fontSize: '12px', fontWeight: 600,
            cursor: 'pointer', border: 'none', transition: 'all 0.15s',
            background: filter === f ? 'var(--accent-blue)' : 'var(--bg-card)',
            color: filter === f ? '#050a14' : 'var(--text-secondary)',
          }}>
            {f.replace('_', ' ')} {f !== 'ALL' && `(${data.filter(d => d.status === f).length})`}
          </button>
        ))}
      </div>

      <div style={S.card}>
        {loading ? <Spinner /> : filtered.length === 0 ? (
          <EmptyState icon="🧠" text="No analyses yet" sub="Failures are automatically analyzed by Claude AI" />
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['ID', 'Failure ID', 'Root Cause', 'Confidence', 'Status', 'Analyzed', 'Actions'].map(h => (
                    <th key={h} style={S.th}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map(item => (
                  <tr key={item.id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s' }}
                      onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
                      onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                    <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(item.id)}</span></td>
                    <td style={S.td}><span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>{truncId(item.failureId)}</span></td>
                    <td style={{ ...S.td, maxWidth: '280px' }}>
                      <div style={{ color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap', fontSize: '13px' }} title={item.rootCause}>
                        {item.rootCause}
                      </div>
                    </td>
                    <td style={S.td}><ConfBar value={item.confidence ?? 0} /></td>
                    <td style={S.td}><Badge status={item.status} /></td>
                    <td style={S.td}>{timeAgo(item.analyzedAt)}</td>
                    <td style={S.td}>
                      <div style={{ display: 'flex', gap: '6px' }}>
                        <button onClick={() => setSelected(item)} style={{
                          background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.2)',
                          color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                          cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                        }}>View</button>
                        {item.status === 'PENDING_APPROVAL' && (
                          <>
                            <button onClick={() => handleApprove(item.id)} disabled={!canDeployAnalysis(item)} style={{
                              background: canDeployAnalysis(item) ? 'rgba(16,232,138,0.1)' : 'rgba(16,232,138,0.04)',
                              border: '1px solid rgba(16,232,138,0.25)',
                              color: canDeployAnalysis(item) ? '#10e88a' : 'var(--text-muted)',
                              padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                              cursor: canDeployAnalysis(item) ? 'pointer' : 'not-allowed', fontSize: '11px', fontWeight: 600,
                            }}>✓</button>
                            <button onClick={() => handleReject(item.id)} style={{
                              background: 'rgba(255,71,87,0.1)', border: '1px solid rgba(255,71,87,0.2)',
                              color: '#ff4757', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                              cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                            }}>✗</button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
