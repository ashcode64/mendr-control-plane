import React, { useCallback, useEffect, useState } from 'react';
import toast from 'react-hot-toast';
import { api } from '../utils/api';
import Spinner, { EmptyState } from '../components/Spinner';
import Badge from '../components/Badge';

const card = {
  background: 'var(--bg-elevated)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-lg)',
  padding: 20,
  marginBottom: 20,
};
const input = {
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)',
  color: 'var(--text-primary)',
  fontSize: 13,
  padding: '8px 12px',
  width: '100%',
  outline: 'none',
};
const label = {
  fontSize: 11,
  color: 'var(--text-muted)',
  textTransform: 'uppercase',
  letterSpacing: '0.06em',
  marginBottom: 5,
  display: 'block',
};

export default function DeveloperPortal() {
  const [catalog, setCatalog] = useState([]);
  const [specs, setSpecs] = useState([]);
  const [usage, setUsage] = useState(null);
  const [aiRoutes, setAiRoutes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [sourceService, setSourceService] = useState('');
  const [issuedKey, setIssuedKey] = useState(null);
  const [aiForm, setAiForm] = useState({
    virtualPath: '/v1/chat/completions',
    tokensPerMinute: 100000,
    requestsPerMinute: 60,
    semanticCacheEnabled: true,
    blockJailbreak: true,
    redactPii: true,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [c, s, u, ai] = await Promise.all([
        api.getPortalCatalog(),
        api.getPortalSpecs().catch(() => []),
        api.getPortalUsage().catch(() => ({})),
        api.getAiRoutes().catch(() => []),
      ]);
      setCatalog(Array.isArray(c) ? c : []);
      setSpecs(Array.isArray(s) ? s : []);
      setUsage(u || {});
      setAiRoutes(Array.isArray(ai) ? ai : []);
    } catch (e) {
      toast.error(e.message || 'Failed to load portal');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const issueKey = async () => {
    if (!sourceService) { toast.error('sourceService required'); return; }
    try {
      const res = await api.issuePortalApiKey({ sourceService });
      setIssuedKey(res);
      toast.success('API key issued — copy it now; secret is shown once');
    } catch (e) {
      toast.error(e.message || 'Failed to issue key');
    }
  };

  const saveAiRoute = async () => {
    try {
      await api.upsertAiRoute(aiForm);
      toast.success('AI route saved');
      load();
    } catch (e) {
      toast.error(e.message || 'Failed to save AI route');
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      <div style={{ marginBottom: 28 }}>
        <h1 style={{ margin: 0, fontSize: 24, color: 'var(--text-primary)' }}>Developer Portal</h1>
        <p style={{ margin: '8px 0 0', color: 'var(--text-secondary)', fontSize: 14 }}>
          API catalog, OpenAPI specs, self-service keys, usage, and AI gateway policies.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16, marginBottom: 24 }}>
        <div style={card}>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>Active services</div>
          <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--accent-cyan)' }}>
            {usage?.activeServices ?? catalog.length}
          </div>
        </div>
        <div style={card}>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>Failures (24h)</div>
          <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--accent-orange, #f59e0b)' }}>
            {usage?.failures24h ?? 0}
          </div>
        </div>
        <div style={card}>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>OpenAPI specs</div>
          <div style={{ fontSize: 28, fontWeight: 700 }}>{specs.length}</div>
        </div>
      </div>

      <div style={card}>
        <h2 style={{ marginTop: 0, fontSize: 16 }}>API Catalog</h2>
        {catalog.length === 0 ? (
          <EmptyState title="No published APIs" />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                {['Name', 'Protocol', 'Base URL', 'Team'].map(h => (
                  <th key={h} style={{ textAlign: 'left', fontSize: 11, color: 'var(--text-muted)', padding: '8px 0', borderBottom: '1px solid var(--border)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {catalog.map(s => (
                <tr key={s.name}>
                  <td style={{ padding: '10px 0', color: 'var(--text-primary)' }}>{s.name}</td>
                  <td><Badge>{s.protocol || 'HTTP'}</Badge></td>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>{s.baseUrl}</td>
                  <td style={{ color: 'var(--text-secondary)' }}>{s.teamEmail || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div style={card}>
        <h2 style={{ marginTop: 0, fontSize: 16 }}>Self-service API key</h2>
        <label style={label}>Source service</label>
        <div style={{ display: 'flex', gap: 10 }}>
          <input
            style={input}
            placeholder="order-service"
            value={sourceService}
            onChange={e => setSourceService(e.target.value)}
            list="portal-services"
          />
          <datalist id="portal-services">
            {catalog.map(s => <option key={s.name} value={s.name} />)}
          </datalist>
          <button
            type="button"
            onClick={issueKey}
            style={{
              background: 'var(--accent-cyan)',
              color: '#0b1220',
              border: 'none',
              borderRadius: 8,
              padding: '8px 16px',
              fontWeight: 600,
              cursor: 'pointer',
              whiteSpace: 'nowrap',
            }}
          >
            Issue key
          </button>
        </div>
        {issuedKey && (
          <pre style={{
            marginTop: 12,
            background: 'var(--bg-card)',
            padding: 12,
            borderRadius: 8,
            fontSize: 12,
            overflow: 'auto',
          }}>
            {JSON.stringify(issuedKey, null, 2)}
          </pre>
        )}
      </div>

      <div style={card}>
        <h2 style={{ marginTop: 0, fontSize: 16 }}>OpenAPI specs</h2>
        {specs.length === 0 ? (
          <EmptyState title="No specs imported" />
        ) : (
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {specs.map((sp, i) => (
              <li key={sp.id || i} style={{ marginBottom: 8, color: 'var(--text-secondary)' }}>
                <strong style={{ color: 'var(--text-primary)' }}>{sp.sourceApp}</strong>
                {' · '}{sp.ingressHost || 'no host'}{' · '}{sp.enforceMode || 'observe'}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div style={card}>
        <h2 style={{ marginTop: 0, fontSize: 16 }}>AI Gateway routes</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
          <div>
            <label style={label}>Virtual path</label>
            <input style={input} value={aiForm.virtualPath}
              onChange={e => setAiForm(f => ({ ...f, virtualPath: e.target.value }))} />
          </div>
          <div>
            <label style={label}>Tokens / minute</label>
            <input style={input} type="number" value={aiForm.tokensPerMinute}
              onChange={e => setAiForm(f => ({ ...f, tokensPerMinute: Number(e.target.value) }))} />
          </div>
          <div>
            <label style={label}>Requests / minute</label>
            <input style={input} type="number" value={aiForm.requestsPerMinute}
              onChange={e => setAiForm(f => ({ ...f, requestsPerMinute: Number(e.target.value) }))} />
          </div>
          <div style={{ display: 'flex', gap: 16, alignItems: 'end', paddingBottom: 8 }}>
            <label style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              <input type="checkbox" checked={aiForm.semanticCacheEnabled}
                onChange={e => setAiForm(f => ({ ...f, semanticCacheEnabled: e.target.checked }))} /> Semantic cache
            </label>
            <label style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              <input type="checkbox" checked={aiForm.blockJailbreak}
                onChange={e => setAiForm(f => ({ ...f, blockJailbreak: e.target.checked }))} /> Jailbreak block
            </label>
            <label style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              <input type="checkbox" checked={aiForm.redactPii}
                onChange={e => setAiForm(f => ({ ...f, redactPii: e.target.checked }))} /> Redact PII
            </label>
          </div>
        </div>
        <button
          type="button"
          onClick={saveAiRoute}
          style={{
            background: 'var(--accent-blue, #3b82f6)',
            color: '#fff',
            border: 'none',
            borderRadius: 8,
            padding: '8px 16px',
            fontWeight: 600,
            cursor: 'pointer',
          }}
        >
          Save AI route
        </button>
        {aiRoutes.length > 0 && (
          <ul style={{ marginTop: 16, paddingLeft: 18 }}>
            {aiRoutes.map((r, i) => (
              <li key={r.id || i} style={{ color: 'var(--text-secondary)', marginBottom: 6 }}>
                <code>{r.virtual_path}</code>
                {' · TPM '}{r.tokens_per_minute}
                {' · RPM '}{r.requests_per_minute}
                {r.semantic_cache_enabled ? ' · semantic-cache' : ''}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
