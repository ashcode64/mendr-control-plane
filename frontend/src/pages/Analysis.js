import React, { useState, useEffect, useCallback, useRef } from 'react';
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

/**
 * Conversational refinement panel. The operator describes a different/extra fix in
 * natural language; the conversation engine synthesizes a VERIFIED MendrScript program
 * and streams back the program, its verification result, and a before/after simulation.
 * Deployment is NOT done here — it still goes through the normal approval flow, so this
 * panel is a safe, read-only "propose & inspect" surface.
 */
function MendrScriptChat({ item, onStaged }) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState([]);
  const [streaming, setStreaming] = useState(false);
  const [result, setResult] = useState(null);
  const [flags, setFlags] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [staging, setStaging] = useState(false);
  const [staged, setStaged] = useState(false);
  const [failure, setFailure] = useState(null);
  const ctlRef = useRef(null);

  useEffect(() => () => ctlRef.current?.abort(), []);

  // The analysis row has no service/endpoint/payload — those live on the linked
  // failure. Fetch it when the panel opens so the engine can pull contract examples
  // and so we can seed the simulation with the ACTUAL failing payload (real
  // before/after diff instead of an empty one).
  useEffect(() => {
    if (!open || failure || !item.failureId) return;
    let alive = true;
    api.getFailure(item.failureId).then(f => { if (alive) setFailure(f); }).catch(() => {});
    return () => { alive = false; };
  }, [open, failure, item.failureId]);

  const context = {
    service: failure?.serviceB,
    endpoint: failure?.endpoint,
    direction: 'REQUEST',
  };
  const cases = failure?.requestPayload ? [{ input: failure.requestPayload }] : [];

  const send = () => {
    const msg = input.trim();
    if (!msg || streaming) return;
    setMessages(m => [...m, { role: 'user', text: msg }]);
    setInput('');
    setStreaming(true);
    setResult(null);
    setFlags([]);
    setStaged(false);
    ctlRef.current = api.streamChat({ message: msg, sessionId, context, cases }, (type, data) => {
      if (type === 'session') setSessionId(data.sessionId);
      else if (type === 'security') setFlags(data.flags || []);
      else if (type === 'result') {
        setResult(data);
        if (data.assistantText) setMessages(m => [...m, { role: 'assistant', text: data.assistantText }]);
      } else if (type === 'error') {
        setMessages(m => [...m, { role: 'assistant', text: 'Error: ' + (data.message || 'stream failed') }]);
      } else if (type === 'end') {
        setStreaming(false);
      }
    });
  };

  const v = result?.verification;
  const sim = result?.simulation;

  const stage = async () => {
    if (!result?.program || !v?.valid || staging) return;
    setStaging(true);
    try {
      await api.attachProgram(item.id, {
        program: result.program,
        simulation: result.simulation,
        conversationId: sessionId,
        model: result.model,
      });
      setStaged(true);
      toast.success('Program staged — approve below to deploy');
      onStaged?.(item.id);
    } catch (e) {
      toast.error(e.message || 'Staging failed');
    } finally {
      setStaging(false);
    }
  };

  return (
    <div style={{ marginBottom: '24px', borderTop: '1px solid var(--border)', paddingTop: '16px' }}>
      <button onClick={() => setOpen(o => !o)} style={{
        background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.25)',
        color: 'var(--accent-blue)', padding: '8px 14px', borderRadius: 'var(--radius-sm)',
        cursor: 'pointer', fontSize: '12px', fontWeight: 600,
      }}>
        💬 {open ? 'Hide' : 'Refine with MendrScript chat'}
      </button>

      {open && (
        <div style={{ marginTop: '14px' }}>
          <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '10px', lineHeight: 1.5 }}>
            Describe the transformation you want (e.g. “convert <code>/amount</code> from cents to dollars”).
            The assistant proposes a verified program — it cannot deploy; approve through the normal flow.
          </div>

          <div style={{ maxHeight: '180px', overflowY: 'auto', display: 'flex', flexDirection: 'column',
                        gap: '8px', marginBottom: '10px' }}>
            {messages.map((m, i) => (
              <div key={i} style={{
                alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
                background: m.role === 'user' ? 'rgba(79,124,255,0.12)' : 'var(--bg-card)',
                border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                padding: '8px 12px', fontSize: '13px', color: 'var(--text-primary)', maxWidth: '85%',
              }}>{m.text}</div>
            ))}
            {streaming && (
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>synthesizing & verifying…</div>
            )}
          </div>

          {flags.length > 0 && (
            <div style={{ fontSize: '12px', color: 'var(--accent-yellow)', marginBottom: '10px' }}>
              {flags.map((f, i) => <div key={i}>⚠ {f}</div>)}
            </div>
          )}

          {result && (
            <div style={{ marginBottom: '12px' }}>
              <div style={{ fontSize: '11px', color: v?.valid ? '#10e88a' : '#ff4757', fontWeight: 600,
                            marginBottom: '6px' }}>
                {v?.valid ? '✓ Verified' : '✗ Not deployable'}
                {!v?.valid && Array.isArray(v?.errors) && v.errors.length > 0 && (
                  <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
                    {' '}— {v.errors.slice(0, 4).join('; ')}
                  </span>
                )}
              </div>

              {result.program && (
                <pre style={{ background: '#080c18', borderRadius: 'var(--radius-sm)', padding: '12px',
                              fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--accent-cyan)',
                              overflowX: 'auto', lineHeight: 1.5, border: '1px solid var(--border)' }}>
                  {JSON.stringify(result.program, null, 2)}
                </pre>
              )}

              {Array.isArray(sim?.results) && sim.results.length > 0 && (
                <div style={{ marginTop: '8px' }}>
                  <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                                letterSpacing: '0.06em', marginBottom: '6px' }}>Before → After (simulation)</div>
                  {sim.results.map((r, i) => (
                    <div key={i} style={{ display: 'flex', gap: '8px', fontFamily: 'var(--font-mono)',
                                          fontSize: '11px', marginBottom: '4px' }}>
                      <span style={{ color: 'var(--text-muted)', flex: 1, wordBreak: 'break-all' }}>
                        {JSON.stringify(r.input)}
                      </span>
                      <span style={{ color: 'var(--text-muted)' }}>→</span>
                      <span style={{ color: r.ok ? '#10e88a' : '#ff4757', flex: 1, wordBreak: 'break-all' }}>
                        {r.ok ? JSON.stringify(r.output) : `fail-closed: ${r.error}`}
                      </span>
                    </div>
                  ))}
                </div>
              )}

              {v?.valid && (
                <button onClick={stage} disabled={staging || staged} style={{
                  marginTop: '10px', padding: '8px 14px',
                  background: staged ? 'rgba(16,232,138,0.06)' : 'rgba(16,232,138,0.12)',
                  border: '1px solid rgba(16,232,138,0.3)',
                  color: staged ? 'var(--text-muted)' : '#10e88a', borderRadius: 'var(--radius-sm)',
                  cursor: staging || staged ? 'default' : 'pointer', fontWeight: 600, fontSize: '12px',
                }}>
                  {staged ? '✓ Staged — approve below to deploy'
                    : staging ? 'Staging…'
                    : '↑ Use this program (stage for approval)'}
                </button>
              )}
            </div>
          )}

          <div style={{ display: 'flex', gap: '8px' }}>
            <input
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') send(); }}
              placeholder="Describe the change…"
              disabled={streaming}
              style={{ flex: 1, padding: '8px 12px', borderRadius: 'var(--radius-sm)',
                       border: '1px solid var(--border)', background: 'var(--bg-card)',
                       color: 'var(--text-primary)', fontSize: '13px' }}
            />
            <button onClick={send} disabled={streaming || !input.trim()} style={{
              padding: '8px 16px', background: 'rgba(79,124,255,0.12)', border: '1px solid rgba(79,124,255,0.3)',
              color: 'var(--accent-blue)', borderRadius: 'var(--radius-sm)',
              cursor: streaming ? 'not-allowed' : 'pointer', fontWeight: 600, fontSize: '13px',
            }}>Send</button>
          </div>
        </div>
      )}
    </div>
  );
}

function AnalysisDetail({ item, onApprove, onReject, onClose, onStaged }) {
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

        {/* Conversational refinement (verified, no-deploy) */}
        <MendrScriptChat item={item} onStaged={onStaged} />

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

  // A chat-synthesized program was staged onto the analysis: re-fetch it so the open
  // dialog now shows the DSL_PROGRAM rule and the existing Approve & Deploy button ships it.
  const handleStaged = async (id) => {
    try {
      const updated = await api.getAnalysis(id);
      setSelected(updated);
    } catch { /* keep current view */ }
    fetch();
  };

  const filtered = filter === 'ALL' ? data : data.filter(d => d.status === filter);
  const pendingCount = data.filter(d => d.status === 'PENDING_APPROVAL').length;

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <AnalysisDetail item={selected} onApprove={handleApprove} onReject={handleReject} onClose={() => setSelected(null)} onStaged={handleStaged} />

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
