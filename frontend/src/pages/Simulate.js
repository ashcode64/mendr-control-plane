import React, { useState } from 'react';
import toast from 'react-hot-toast';
import { api } from '../utils/api';

const SCENARIOS = [
  {
    id: 'field_rename',
    label: 'Field Rename Mismatch',
    icon: '🔤',
    description: 'Service A sends "user_id" but Service B expects "customer_id"',
    color: '#8b5cf6',
    endpoint: '/api/gateway/simulate-failure',
    body: {
      serviceA: 'order-service', serviceB: 'user-service', endpoint: '/api/users/lookup',
      payload: { user_id: 'USR-982', order_total: 149.99, currency: 'USD' },
    },
  },
  {
    id: 'dns_routing',
    label: 'DNS / URL Change (Routing)',
    icon: '🌐',
    description: 'Service B moved to a new URL — Service A still points to the old address',
    color: '#f97316',
    endpoint: '/api/gateway/simulate-routing-failure',
    body: {
      serviceA: 'order-service', serviceB: 'payment-service',
      oldUrl: 'http://payment-service:8092', newUrl: 'http://payment-service-v2:8093',
      endpoint: '/api/payments/charge',
    },
  },
  {
    id: 'cors_origin',
    label: 'CORS — Caller URL Changed',
    icon: '🔒',
    description: "Service A's URL changed → Service B blocks it with CORS 403",
    color: '#ef4444',
    endpoint: '/api/gateway/simulate-cors-failure',
    body: {
      serviceA: 'order-service', serviceB: 'user-service',
      newOrigin: 'http://order-service-v2:9090', endpoint: '/api/users/profile',
    },
  },
  {
    id: 'missing_field',
    label: 'Missing Required Field',
    icon: '❌',
    description: 'Required field "customer_email" missing from payload',
    color: '#ff4757',
    endpoint: '/api/gateway/simulate-failure',
    body: {
      serviceA: 'payment-service', serviceB: 'notification-service', endpoint: '/api/notify/payment',
      payload: { transaction_id: 'TXN-441', amount: 299.00, status: 'SUCCESS' },
    },
  },
  {
    id: 'type_mismatch',
    label: 'Type Mismatch',
    icon: '🔢',
    description: 'Amount sent as string "99.99" but target expects numeric 99.99',
    color: '#f5a623',
    endpoint: '/api/gateway/simulate-failure',
    body: {
      serviceA: 'inventory-service', serviceB: 'order-service', endpoint: '/api/orders/create',
      payload: { product_id: 'PROD-771', quantity: '3', price: '49.99', warehouse_id: 'WH-02' },
    },
  },
  {
    id: 'custom',
    label: 'Custom Scenario',
    icon: '🛠',
    description: 'Define your own failure scenario',
    color: '#00d4ff',
    endpoint: '/api/gateway/simulate-failure',
    body: null,
  },
];

const DEFAULT_CUSTOM = JSON.stringify({
  serviceA: 'service-a', serviceB: 'service-b', endpoint: '/api/endpoint',
  payload: { field_name: 'value' },
}, null, 2);

export default function Simulate() {
  const [selected, setSelected] = useState(SCENARIOS[0]);
  const [customJson, setCustomJson] = useState(DEFAULT_CUSTOM);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const handleRun = async () => {
    setLoading(true);
    setResult(null);
    try {
      let body = selected.body;
      if (selected.id === 'custom') {
        body = JSON.parse(customJson);
      }
      const endpoint = selected.endpoint || '/api/gateway/simulate-failure';
      const res = await api.simulateAny(endpoint, body);
      setResult({ ok: true, data: res });
      toast.success('🎯 Failure simulated! AI analysis triggered.');
    } catch (e) {
      setResult({ ok: false, error: e.message });
      toast.error('Simulation failed: ' + e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      <div style={{ marginBottom: '28px' }}>
        <div style={{ fontSize: '22px', fontWeight: 700 }}>🎯 Failure Simulator</div>
        <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
          Inject test failures to see the self-healing loop in action — detection → AI analysis → approval → rule deployment
        </div>
      </div>

      {/* Flow diagram */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '0', marginBottom: '28px',
                    background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-lg)', padding: '16px 20px' }}>
        {[
          ['1', '⚡', 'Failure Detected', '#ff4757'],
          ['2', '📡', 'Kafka Event', '#f5a623'],
          ['3', '🧠', 'Claude AI Analysis', '#8b5cf6'],
          ['4', '👤', 'Human Approval', '#4f7cff'],
          ['5', '⚙️', 'Rule Deployed', '#10e88a'],
          ['6', '✅', 'Service Healed', '#10e88a'],
        ].map(([num, icon, label, color], i, arr) => (
          <React.Fragment key={num}>
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '6px', minWidth: '80px' }}>
              <div style={{ width: '36px', height: '36px', borderRadius: '50%', background: `${color}18`,
                            border: `2px solid ${color}50`, display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontSize: '16px' }}>{icon}</div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', textAlign: 'center', lineHeight: 1.3 }}>{label}</div>
            </div>
            {i < arr.length - 1 && (
              <div style={{ flex: 1, height: '1px', background: 'var(--border)', margin: '0 4px', marginBottom: '20px' }} />
            )}
          </React.Fragment>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
        {/* Scenario Picker */}
        <div>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                        letterSpacing: '0.07em', marginBottom: '12px' }}>Choose Scenario</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {SCENARIOS.map(s => (
              <div key={s.id} onClick={() => setSelected(s)} style={{
                background: selected.id === s.id ? `${s.color}12` : 'var(--bg-elevated)',
                border: `1px solid ${selected.id === s.id ? s.color + '50' : 'var(--border)'}`,
                borderRadius: 'var(--radius-md)', padding: '14px 16px', cursor: 'pointer',
                transition: 'all 0.15s',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <span style={{ fontSize: '20px' }}>{s.icon}</span>
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 600, color: selected.id === s.id ? s.color : 'var(--text-primary)' }}>
                      {s.label}
                    </div>
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>{s.description}</div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Config + Run */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {/* Payload preview */}
          <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', padding: '20px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
                          letterSpacing: '0.07em', marginBottom: '12px' }}>
              {selected.id === 'custom' ? 'Custom JSON Payload' : 'Payload Preview'}
            </div>
            {selected.id === 'custom' ? (
              <textarea value={customJson} onChange={e => setCustomJson(e.target.value)}
                style={{
                  width: '100%', minHeight: '200px', background: '#080c18',
                  border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                  color: 'var(--accent-cyan)', fontFamily: 'var(--font-mono)', fontSize: '12px',
                  padding: '12px', resize: 'vertical', outline: 'none', lineHeight: 1.5,
                }} />
            ) : (
              <pre style={{ background: '#080c18', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                            padding: '14px', fontFamily: 'var(--font-mono)', fontSize: '12px',
                            color: 'var(--accent-cyan)', overflowX: 'auto', lineHeight: 1.5 }}>
                {JSON.stringify(selected.body, null, 2)}
              </pre>
            )}
          </div>

          {/* Run button */}
          <button onClick={handleRun} disabled={loading} style={{
            width: '100%', padding: '14px', fontSize: '15px', fontWeight: 700,
            background: loading ? 'var(--bg-card)' : `linear-gradient(135deg, ${selected.color}, ${selected.color}99)`,
            border: 'none', borderRadius: 'var(--radius-md)', color: '#fff',
            cursor: loading ? 'not-allowed' : 'pointer', transition: 'all 0.2s',
            boxShadow: loading ? 'none' : `0 4px 20px ${selected.color}40`,
          }}>
            {loading ? '⏳ Running Simulation…' : `🚀 Simulate: ${selected.label}`}
          </button>

          {/* Result */}
          {result && (
            <div style={{
              background: result.ok ? 'rgba(16,232,138,0.07)' : 'rgba(255,71,87,0.07)',
              border: `1px solid ${result.ok ? 'rgba(16,232,138,0.25)' : 'rgba(255,71,87,0.25)'}`,
              borderRadius: 'var(--radius-md)', padding: '16px',
            }}>
              <div style={{ fontWeight: 600, marginBottom: '8px', color: result.ok ? '#10e88a' : '#ff4757' }}>
                {result.ok ? '✓ Failure Injected Successfully' : '✗ Simulation Error'}
              </div>
              {result.ok ? (
                <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
                  <div>📌 Failure ID: <span style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>{result.data.failureId}</span></div>
                  <div>🧠 AI analysis triggered — check the <strong style={{ color: 'var(--accent-blue)' }}>AI Analysis</strong> tab in ~5s</div>
                  <div>👤 Approve or reject the rule in the <strong style={{ color: 'var(--accent-blue)' }}>AI Analysis</strong> tab</div>
                </div>
              ) : (
                <div style={{ fontSize: '12px', color: '#ff4757', fontFamily: 'var(--font-mono)' }}>{result.error}</div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
