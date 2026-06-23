import React, { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { api } from '../utils/api';
import Badge from '../components/Badge';
import Spinner, { EmptyState } from '../components/Spinner';
import { timeAgo, truncId, errorType } from '../utils/helpers';

const S = {
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' },
  title: { fontSize: '22px', fontWeight: 700, letterSpacing: '-0.01em' },
  sub: { fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' },
  card: { background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', overflow: 'hidden' },
  th: { padding: '11px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 600,
        letterSpacing: '0.07em', textTransform: 'uppercase', color: 'var(--text-muted)',
        borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' },
  td: { padding: '13px 16px', color: 'var(--text-secondary)', verticalAlign: 'middle', fontSize: '13px' },
  tdPrimary: { padding: '13px 16px', color: 'var(--text-primary)', fontWeight: 500, fontSize: '13px', verticalAlign: 'middle' },
};

function DetailModal({ failure, onClose }) {
  if (!failure) return null;
  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="failure-dialog-title"
      className="analysis-dialog-overlay"
      onClick={onClose}
    >
      <div className="analysis-dialog" onClick={e => e.stopPropagation()}>
        <div className="analysis-dialog__header">
          <div id="failure-dialog-title" className="analysis-dialog__title">Failure Detail</div>
          <button type="button" onClick={onClose} aria-label="Close dialog" className="analysis-dialog__close">×</button>
        </div>
        <div className="analysis-dialog__body">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginBottom: '20px' }}>
          {[
            ['ID', truncId(failure.id)],
            ['Status', null, <Badge status={failure.status} />],
            ['Service A', failure.serviceA],
            ['Service B', failure.serviceB],
            ['Endpoint', failure.endpoint],
            ['Method', failure.httpMethod],
            ['Error Code', failure.errorCode],
            ['Error Type', errorType(failure.errorType)],
            ['Detected', timeAgo(failure.detectedAt)],
          ].map(([label, val, el]) => (
            <div key={label} style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '12px' }}>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '4px' }}>{label}</div>
              <div style={{ fontSize: '13px', color: 'var(--text-primary)', fontWeight: 500, fontFamily: ['ID','Endpoint'].includes(label) ? 'var(--font-mono)' : undefined }}>
                {el ?? val ?? '—'}
              </div>
            </div>
          ))}
        </div>
        {failure.errorMessage && (
          <div style={{ marginBottom: '16px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '8px' }}>Error Message</div>
            <div style={{ background: 'var(--bg-card)', borderRadius: 'var(--radius-sm)', padding: '12px',
                          fontFamily: 'var(--font-mono)', fontSize: '12px', color: '#ff4757', lineHeight: 1.5,
                          wordBreak: 'break-word' }}>
              {failure.errorMessage}
            </div>
          </div>
        )}
        {failure.requestPayload && Object.keys(failure.requestPayload).length > 0 && (
          <div>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '8px' }}>Request Payload</div>
            <pre style={{ background: '#080c18', borderRadius: 'var(--radius-sm)', padding: '14px',
                          fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--accent-cyan)',
                          overflowX: 'auto', lineHeight: 1.5, border: '1px solid var(--border)', margin: 0 }}>
              {JSON.stringify(failure.requestPayload, null, 2)}
            </pre>
          </div>
        )}
        </div>
      </div>
    </div>,
    document.body
  );
}

export default function Failures() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(null);

  const fetch = useCallback(async () => {
    setLoading(true);
    try { setData(await api.getFailures(page, 15)); }
    catch { /* backend offline */ }
    finally { setLoading(false); }
  }, [page]);

  useEffect(() => { fetch(); }, [fetch]);

  const rows = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <DetailModal failure={selected} onClose={() => setSelected(null)} />

      <div style={S.header}>
        <div>
          <div style={S.title}>API Failures</div>
          <div style={S.sub}>All detected failures across your service mesh</div>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={fetch} style={{
          background: 'var(--bg-card)', border: '1px solid var(--border)',
          color: 'var(--text-secondary)', padding: '8px 14px', borderRadius: 'var(--radius-sm)',
          cursor: 'pointer', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px',
        }}>
          ↻ Refresh
        </button>
      </div>

      <div style={S.card}>
        {loading ? <Spinner /> : rows.length === 0 ? (
          <EmptyState icon="🎉" text="No failures detected" sub="Your services are all healthy!" />
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['ID', 'Service A → B', 'Endpoint', 'Error', 'Type', 'Status', 'Detected', ''].map(h => (
                      <th key={h} style={S.th}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((f, i) => (
                    <tr key={f.id} style={{
                      borderBottom: '1px solid rgba(99,130,255,0.07)',
                      transition: 'background 0.1s',
                      cursor: 'pointer',
                    }}
                      onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
                      onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                      <td style={S.td}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-muted)' }}>
                          {truncId(f.id)}
                        </span>
                      </td>
                      <td style={S.tdPrimary}>
                        <span style={{ color: 'var(--accent-cyan)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{f.serviceA}</span>
                        <span style={{ color: 'var(--text-muted)', margin: '0 4px' }}>→</span>
                        <span style={{ color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)', fontSize: '12px' }}>{f.serviceB}</span>
                      </td>
                      <td style={S.td}>
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{f.endpoint}</span>
                      </td>
                      <td style={S.td}>
                        <span style={{ color: '#ff4757', fontWeight: 600 }}>{f.errorCode}</span>
                      </td>
                      <td style={S.td}><Badge status={f.errorType || 'SCHEMA_MISMATCH'} /></td>
                      <td style={S.td}><Badge status={f.status} /></td>
                      <td style={S.td}>{timeAgo(f.detectedAt)}</td>
                      <td style={S.td}>
                        <button onClick={() => setSelected(f)} style={{
                          background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.2)',
                          color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                          cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                        }}>View</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {/* Pagination */}
            {totalPages > 1 && (
              <div style={{ display: 'flex', justifyContent: 'center', gap: '8px', padding: '16px',
                            borderTop: '1px solid var(--border)' }}>
                <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={{
                  background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
                  padding: '6px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontSize: '12px',
                  opacity: page === 0 ? 0.4 : 1,
                }}>← Prev</button>
                <span style={{ color: 'var(--text-muted)', fontSize: '12px', display: 'flex', alignItems: 'center', padding: '0 8px' }}>
                  {page + 1} / {totalPages}
                </span>
                <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} style={{
                  background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
                  padding: '6px 14px', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontSize: '12px',
                  opacity: page >= totalPages - 1 ? 0.4 : 1,
                }}>Next →</button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
