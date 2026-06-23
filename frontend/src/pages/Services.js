import React, { useState, useEffect, useCallback } from 'react';
import toast from 'react-hot-toast';
import { api } from '../utils/api';
import Badge from '../components/Badge';
import Spinner, { EmptyState } from '../components/Spinner';
import { timeAgo } from '../utils/helpers';

const S = {
  card: { background: 'var(--bg-elevated)', border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)', overflow: 'hidden' },
  th: { padding: '11px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 600,
        letterSpacing: '0.07em', textTransform: 'uppercase', color: 'var(--text-muted)',
        borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' },
  td: { padding: '13px 16px', color: 'var(--text-secondary)', verticalAlign: 'middle', fontSize: '13px' },
  input: {
    background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
    color: 'var(--text-primary)', fontFamily: 'var(--font-sans)', fontSize: '13px',
    padding: '8px 12px', width: '100%', outline: 'none',
  },
  label: { fontSize: '11px', color: 'var(--text-muted)', textTransform: 'uppercase',
           letterSpacing: '0.06em', marginBottom: '5px', display: 'block' },
};

const AUTH_TYPES = ['NONE', 'JWT_BEARER', 'API_KEY_HEADER', 'API_KEY_QUERY', 'BASIC'];

const EMPTY_FORM = {
  name: '', baseUrl: '', namespace: 'default', description: '', teamEmail: '',
  healthEndpoint: '/actuator/health', authType: 'NONE',
  authHeaderName: '', authSecretRef: '', timeoutMs: 10000, retryCount: 2,
};

function RegisterModal({ onClose, onSaved, existing }) {
  const [form, setForm] = useState(existing || EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  const handleSave = async () => {
    if (!form.name || !form.baseUrl) { toast.error('Name and Base URL are required'); return; }
    setSaving(true);
    try {
      await api.registerService(form);
      toast.success(existing ? 'Service updated' : 'Service registered!');
      onSaved();
      onClose();
    } catch (e) { toast.error('Save failed: ' + e.message); }
    finally { setSaving(false); }
  };

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.65)', zIndex: 1000,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}
         onClick={onClose}>
      <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-lg)', padding: '28px', maxWidth: '620px',
                    width: '100%', maxHeight: '90vh', overflowY: 'auto' }}
           onClick={e => e.stopPropagation()}>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
          <div style={{ fontSize: '16px', fontWeight: 700 }}>
            {existing ? '✏️ Update Service' : '➕ Register New Service'}
          </div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontSize: '20px' }}>×</button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
          {[
            ['Service Name *', 'name', 'order-service'],
            ['Base URL *', 'baseUrl', 'http://order-service:8090'],
            ['Namespace', 'namespace', 'default'],
            ['Team Email', 'teamEmail', 'team@company.com'],
            ['Health Endpoint', 'healthEndpoint', '/actuator/health'],
            ['Timeout (ms)', 'timeoutMs', '10000'],
          ].map(([label, key, placeholder]) => (
            <div key={key}>
              <label style={S.label}>{label}</label>
              <input style={S.input} value={form[key] || ''} placeholder={placeholder}
                onChange={e => set(key, e.target.value)} />
            </div>
          ))}

          <div style={{ gridColumn: '1 / -1' }}>
            <label style={S.label}>Description</label>
            <input style={S.input} value={form.description || ''} placeholder="What does this service do?"
              onChange={e => set('description', e.target.value)} />
          </div>
        </div>

        {/* Auth section */}
        <div style={{ marginTop: '20px', padding: '16px', background: 'var(--bg-card)',
                      borderRadius: 'var(--radius-md)', border: '1px solid var(--border)' }}>
          <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)',
                        textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: '12px' }}>
            🔐 Authentication
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
            <div>
              <label style={S.label}>Auth Type</label>
              <select style={{ ...S.input, background: 'var(--bg-card)' }}
                value={form.authType} onChange={e => set('authType', e.target.value)}>
                {AUTH_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            {form.authType !== 'NONE' && (
              <>
                <div>
                  <label style={S.label}>Header Name</label>
                  <input style={S.input} value={form.authHeaderName || ''}
                    placeholder={form.authType === 'JWT_BEARER' ? 'Authorization' : 'X-Api-Key'}
                    onChange={e => set('authHeaderName', e.target.value)} />
                </div>
                <div>
                  <label style={S.label}>Secret Env Var</label>
                  <input style={S.input} value={form.authSecretRef || ''}
                    placeholder="ORDER_SERVICE_API_KEY" onChange={e => set('authSecretRef', e.target.value)} />
                </div>
              </>
            )}
          </div>
          {form.authType !== 'NONE' && (
            <div style={{ marginTop: '10px', fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
              💡 Secret Env Var is the <strong>name of an environment variable</strong> on the gateway server — not the actual secret.
              The gateway reads <code style={{ fontFamily: 'var(--font-mono)' }}>System.getenv("{form.authSecretRef || 'YOUR_ENV_VAR'}")</code> at runtime.
            </div>
          )}
        </div>

        {/* k8s hint */}
        <div style={{ marginTop: '14px', padding: '12px 14px', background: 'rgba(79,124,255,0.07)',
                      border: '1px solid rgba(79,124,255,0.2)', borderRadius: 'var(--radius-sm)',
                      fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          ☸️ <strong>Kubernetes tip:</strong> For services in k8s, set Base URL to the k8s Service DNS:
          <code style={{ fontFamily: 'var(--font-mono)', display: 'block', marginTop: '4px', color: 'var(--accent-cyan)' }}>
            http://{form.name || 'service-name'}.{form.namespace || 'default'}.svc.cluster.local:8080
          </code>
        </div>

        <div style={{ display: 'flex', gap: '12px', marginTop: '24px', paddingTop: '16px', borderTop: '1px solid var(--border)' }}>
          <button onClick={handleSave} disabled={saving} style={{
            flex: 1, padding: '10px', background: 'var(--accent-blue)', border: 'none',
            color: '#050a14', borderRadius: 'var(--radius-sm)', cursor: 'pointer',
            fontWeight: 700, fontSize: '13px', opacity: saving ? 0.6 : 1,
          }}>{saving ? 'Saving…' : (existing ? 'Update Service' : 'Register Service')}</button>
          <button onClick={onClose} style={{
            padding: '10px 20px', background: 'transparent', border: '1px solid var(--border)',
            color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontSize: '13px',
          }}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

function ContractModal({ service, onClose }) {
  const [contracts, setContracts] = useState([]);
  const [form, setForm] = useState({ endpoint: '', direction: 'REQUEST', httpMethod: 'POST', examplePayload: '{}' });
  const [loading, setLoading] = useState(true);

  const fetchContracts = useCallback(async () => {
    try { setContracts(await api.getServiceContracts(service.name)); }
    catch { setContracts([]); }
    finally { setLoading(false); }
  }, [service.name]);

  useEffect(() => { fetchContracts(); }, [fetchContracts]);

  const handleAdd = async () => {
    try {
      const payload = JSON.parse(form.examplePayload);
      await api.addServiceContract(service.name, { ...form, examplePayload: payload });
      toast.success('Contract registered!');
      fetchContracts();
    } catch (e) { toast.error('Invalid JSON or save failed: ' + e.message); }
  };

  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.65)', zIndex: 1000,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}
         onClick={onClose}>
      <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-lg)', padding: '28px', maxWidth: '700px',
                    width: '100%', maxHeight: '90vh', overflowY: 'auto' }}
           onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
          <div style={{ fontSize: '16px', fontWeight: 700 }}>📋 Contracts — {service.name}</div>
          <button onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontSize: '20px' }}>×</button>
        </div>

        {/* Existing contracts */}
        {loading ? <Spinner /> : contracts.length === 0 ? (
          <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
            No contracts registered yet. Add one below.
          </div>
        ) : (
          <div style={{ marginBottom: '20px' }}>
            {contracts.map(c => (
              <div key={c.id} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
                                       borderRadius: 'var(--radius-md)', padding: '12px', marginBottom: '8px' }}>
                <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginBottom: '8px' }}>
                  <Badge status={c.direction} />
                  <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--accent-cyan)' }}>{c.httpMethod} {c.endpoint}</span>
                  <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-muted)' }}>v{c.version}</span>
                </div>
                <pre style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-secondary)',
                              background: 'var(--bg-elevated)', padding: '10px', borderRadius: 'var(--radius-sm)',
                              overflowX: 'auto', margin: 0, lineHeight: 1.5 }}>
                  {JSON.stringify(c.examplePayload, null, 2)}
                </pre>
              </div>
            ))}
          </div>
        )}

        {/* Add new contract */}
        <div style={{ borderTop: '1px solid var(--border)', paddingTop: '20px' }}>
          <div style={{ fontSize: '13px', fontWeight: 600, marginBottom: '14px', color: 'var(--text-primary)' }}>
            + Add Example Payload
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px', marginBottom: '12px' }}>
            <div>
              <label style={S.label}>Endpoint</label>
              <input style={S.input} value={form.endpoint} placeholder="/api/users/lookup"
                onChange={e => setForm(f => ({ ...f, endpoint: e.target.value }))} />
            </div>
            <div>
              <label style={S.label}>Method</label>
              <select style={{ ...S.input, background: 'var(--bg-card)' }}
                value={form.httpMethod} onChange={e => setForm(f => ({ ...f, httpMethod: e.target.value }))}>
                {['GET','POST','PUT','DELETE','PATCH'].map(m => <option key={m}>{m}</option>)}
              </select>
            </div>
            <div>
              <label style={S.label}>Direction</label>
              <select style={{ ...S.input, background: 'var(--bg-card)' }}
                value={form.direction} onChange={e => setForm(f => ({ ...f, direction: e.target.value }))}>
                <option value="REQUEST">REQUEST (what it sends)</option>
                <option value="RESPONSE">RESPONSE (what it returns)</option>
              </select>
            </div>
          </div>
          <div>
            <label style={S.label}>Example JSON Payload</label>
            <textarea value={form.examplePayload}
              onChange={e => setForm(f => ({ ...f, examplePayload: e.target.value }))}
              style={{ ...S.input, minHeight: '120px', resize: 'vertical', fontFamily: 'var(--font-mono)', fontSize: '12px' }}
              placeholder='{ "user_id": "USR-123", "amount": 99.99 }' />
          </div>
          <button onClick={handleAdd} style={{
            marginTop: '12px', padding: '8px 20px', background: 'var(--accent-blue)', border: 'none',
            color: '#050a14', borderRadius: 'var(--radius-sm)', cursor: 'pointer', fontWeight: 600, fontSize: '13px',
          }}>Add Contract</button>
        </div>
      </div>
    </div>
  );
}

export default function Services() {
  const [services, setServices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showRegister, setShowRegister] = useState(false);
  const [editingService, setEditingService] = useState(null);
  const [contractService, setContractService] = useState(null);

  const fetch = useCallback(async () => {
    setLoading(true);
    try { setServices(await api.getServices()); }
    catch { setServices([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetch(); }, [fetch]);

  const handleDeactivate = async (name) => {
    if (!window.confirm(`Deactivate service "${name}"?`)) return;
    try { await api.deactivateService(name); toast.success('Service deactivated'); fetch(); }
    catch { toast.error('Failed'); }
  };

  const healthColor = (status) => {
    if (status === 'UP')    return '#10e88a';
    if (status === 'DOWN')  return '#ff4757';
    return '#f5a623';
  };

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      {showRegister && <RegisterModal onClose={() => setShowRegister(false)} onSaved={fetch} />}
      {editingService && <RegisterModal existing={editingService} onClose={() => setEditingService(null)} onSaved={fetch} />}
      {contractService && <ContractModal service={contractService} onClose={() => setContractService(null)} />}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '28px' }}>
        <div>
          <div style={{ fontSize: '22px', fontWeight: 700 }}>Services Registry</div>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            Register and manage your custom microservices. The gateway uses this registry to resolve URLs,
            inject auth credentials, and run health checks.
          </div>
        </div>
        <button onClick={() => setShowRegister(true)} style={{
          padding: '10px 20px', background: 'var(--accent-blue)', border: 'none',
          color: '#050a14', borderRadius: 'var(--radius-sm)', cursor: 'pointer',
          fontWeight: 700, fontSize: '13px', flexShrink: 0,
        }}>+ Register Service</button>
      </div>

      {/* SDK quickstart */}
      <div style={{ background: 'rgba(79,124,255,0.07)', border: '1px solid rgba(79,124,255,0.2)',
                    borderRadius: 'var(--radius-lg)', padding: '20px', marginBottom: '24px' }}>
        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--accent-blue)', marginBottom: '10px' }}>
          ☸️ How to integrate your Spring Boot microservice
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
          <strong>1.</strong> Build the SDK: <code style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)' }}>cd mendr-client-sdk && mvn install</code>
          <br/>
          <strong>2.</strong> Add to your service's pom.xml:
        </div>
        <pre style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--accent-cyan)',
                      background: 'var(--bg-card)', padding: '10px 14px', borderRadius: 'var(--radius-sm)',
                      margin: '8px 0', overflowX: 'auto' }}>
{`<dependency>
  <groupId>com.selfhealing</groupId>
  <artifactId>mendr-client-sdk</artifactId>
  <version>1.0.0</version>
</dependency>`}</pre>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
          <strong>3.</strong> Add to your <code style={{ fontFamily: 'var(--font-mono)' }}>application.yml</code>:
        </div>
        <pre style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--accent-cyan)',
                      background: 'var(--bg-card)', padding: '10px 14px', borderRadius: 'var(--radius-sm)',
                      margin: '8px 0', overflowX: 'auto' }}>
{`mendr:
  gateway-url: http://mendr-gateway:8080
  service-name: your-service-name
  enabled: true`}</pre>
        <div style={{ fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
          <strong>4.</strong> Use <code style={{ fontFamily: 'var(--font-mono)' }}>@Autowired MendrClient mendr</code> and call:
          <code style={{ fontFamily: 'var(--font-mono)', color: 'var(--accent-cyan)', marginLeft: '8px' }}>
            mendr.post("user-service", "/api/users", payload)
          </code>
        </div>
      </div>

      {/* Services table */}
      <div style={S.card}>
        {loading ? <Spinner /> : services.length === 0 ? (
          <EmptyState icon="🔌" text="No services registered"
            sub="Click '+ Register Service' to add your first microservice" />
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Service', 'Base URL', 'Namespace', 'Auth', 'Health', 'Last Check', 'Contracts', 'Actions'].map(h => (
                    <th key={h} style={S.th}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {services.map(svc => (
                  <tr key={svc.id} style={{ borderBottom: '1px solid rgba(99,130,255,0.07)', transition: 'background 0.1s' }}
                      onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-hover)'}
                      onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                    <td style={{ ...S.td, color: 'var(--text-primary)', fontWeight: 600 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <span style={{ width: '8px', height: '8px', borderRadius: '50%', flexShrink: 0,
                                       background: healthColor(svc.lastHealthStatus),
                                       boxShadow: `0 0 6px ${healthColor(svc.lastHealthStatus)}` }} />
                        {svc.name}
                      </div>
                      {svc.description && <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>{svc.description}</div>}
                    </td>
                    <td style={S.td}>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--accent-cyan)' }}>
                        {svc.baseUrl || '—'}
                      </span>
                    </td>
                    <td style={S.td}>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11px' }}>{svc.namespace || 'default'}</span>
                    </td>
                    <td style={S.td}>
                      <Badge status={svc.authType || 'NONE'} />
                    </td>
                    <td style={S.td}>
                      <span style={{ color: healthColor(svc.lastHealthStatus), fontWeight: 600, fontSize: '12px' }}>
                        {svc.lastHealthStatus || 'UNKNOWN'}
                      </span>
                    </td>
                    <td style={S.td}>
                      <span style={{ fontSize: '12px' }}>{svc.lastHealthCheck ? timeAgo(svc.lastHealthCheck) : '—'}</span>
                    </td>
                    <td style={S.td}>
                      <button onClick={() => setContractService(svc)} style={{
                        background: 'rgba(79,124,255,0.1)', border: '1px solid rgba(79,124,255,0.2)',
                        color: 'var(--accent-blue)', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                        cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                      }}>Manage</button>
                    </td>
                    <td style={S.td}>
                      <div style={{ display: 'flex', gap: '6px' }}>
                        <button onClick={() => setEditingService(svc)} style={{
                          background: 'rgba(245,166,35,0.1)', border: '1px solid rgba(245,166,35,0.2)',
                          color: '#f5a623', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                          cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                        }}>Edit</button>
                        <button onClick={() => handleDeactivate(svc.name)} style={{
                          background: 'rgba(255,71,87,0.08)', border: '1px solid rgba(255,71,87,0.2)',
                          color: '#ff4757', padding: '4px 10px', borderRadius: 'var(--radius-sm)',
                          cursor: 'pointer', fontSize: '11px', fontWeight: 600,
                        }}>Remove</button>
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
