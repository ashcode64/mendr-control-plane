import axios from 'axios';
import { getAccessToken, handleUnauthorized } from '../auth/tokenBridge';

const gateway = axios.create({ baseURL: '/api/gateway', timeout: 10000 });
const analysis = axios.create({ baseURL: '/api/analysis', timeout: 10000 });
const rules = axios.create({ baseURL: '/api/rules', timeout: 10000 });
const services = axios.create({ baseURL: '/api/services', timeout: 10000 });
const portal = axios.create({ baseURL: '/api/portal', timeout: 10000 });

// Attach the WorkOS bearer token (when a session exists) to every API call so the
// backend can authenticate the caller and resolve the tenant from the org claim.
[gateway, analysis, rules, services, portal].forEach(client => {
  client.interceptors.request.use(async config => {
    const token = await getAccessToken();
    if (token) {
      config.headers = config.headers || {};
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
});

// Response interceptor: normalise errors and redirect to login on 401.
[gateway, analysis, rules, services, portal].forEach(client => {
  client.interceptors.response.use(
    r => r,
    err => {
      if (err.response?.status === 401) {
        handleUnauthorized();
      }
      const data = err.response?.data;
      const msg = data?.message
        || (Array.isArray(data?.errors) && data.errors.length ? data.errors.join('; ') : null)
        || err.message || 'Request failed';
      const normalized = new Error(msg);
      normalized.response = err.response;
      return Promise.reject(normalized);
    }
  );
});

/* ── Gateway / Failures ─────────────────────────────── */
export const api = {
  // Stats
  getStats: () => gateway.get('/stats').then(r => r.data),

  // Failures
  getFailures: (page = 0, size = 20) =>
    gateway.get('/failures', { params: { page, size } }).then(r => r.data),
  getFailure: id => gateway.get(`/failures/${id}`).then(r => r.data),

  // Simulate
  simulateFailure: body => gateway.post('/simulate-failure', body).then(r => r.data),
  simulateRoutingFailure: body => gateway.post('/simulate-routing-failure', body).then(r => r.data),
  simulateCorsFailure: body => gateway.post('/simulate-cors-failure', body).then(r => r.data),
  simulateAny: (endpoint, body) => gateway.post(endpoint.replace('/api/gateway', ''), body).then(r => r.data),
  probeUrl: body => gateway.post('/probe', body).then(r => r.data),

  // Gateway rules
  getGatewayRules: () => gateway.get('/rules').then(r => r.data),
  disableGatewayRule: id => gateway.delete(`/rules/${id}`).then(r => r.data),

  // Routing rules
  getRoutingRules: () => gateway.get('/routing-rules').then(r => r.data),
  disableRoutingRule: id => gateway.delete(`/routing-rules/${id}`).then(r => r.data),

  // CORS rules
  getCorsRules: () => gateway.get('/cors-rules').then(r => r.data),
  disableCorsRule: id => gateway.delete(`/cors-rules/${id}`).then(r => r.data),

  // Origin override rules
  getOriginOverrideRules: () => gateway.get('/origin-override-rules').then(r => r.data),
  disableOriginOverrideRule: id => gateway.delete(`/origin-override-rules/${id}`).then(r => r.data),

  // Analysis
  getAnalyses: (page = 0, size = 20) =>
    analysis.get('', { params: { page, size } }).then(r => r.data),
  getPendingAnalyses: () => analysis.get('/pending').then(r => r.data),
  getAnalysis: id => analysis.get(`/${id}`).then(r => r.data),
  getAnalysisConversation: (id, limit = 20) =>
    analysis.get(`/${id}/conversation`, { params: { limit } }).then(r => r.data),
  approveAnalysis: (id, approvedBy = 'dashboard-user') =>
    analysis.post(`/${id}/approve`, { approvedBy }).then(r => r.data),
  // Stage a chat-synthesized, verified MendrScript program onto an analysis so the
  // existing approve→deploy flow can ship it. Server re-verifies before persisting.
  attachProgram: (id, body) =>
    analysis.post(`/${id}/program`, body).then(r => r.data),
  rejectAnalysis: (id, reason) =>
    analysis.post(`/${id}/reject`, { reason }).then(r => r.data),
  getAnalysisStats: () => analysis.get('/stats').then(r => r.data),

  // Rules engine
  getRules: () => rules.get('/active').then(r => r.data),
  getAllRules: () => rules.get('').then(r => r.data),
  disableRule: (id, actor) => rules.delete(`/${id}`, { params: { actor } }).then(r => r.data),
  getRuleStats: () => rules.get('/stats').then(r => r.data),
  getAuditLog: () => rules.get('/audit').then(r => r.data),

  // ── Service Registry ───────────────────────────────────────────────────────
  getServices:        ()       => services.get('').then(r => r.data),
  getService:         name     => services.get(`/${name}`).then(r => r.data),
  registerService:    body     => services.post('', body).then(r => r.data),
  updateService:      (id, b)  => services.put(`/${id}`, b).then(r => r.data),
  deactivateService:  name     => services.delete(`/${name}`).then(r => r.data),
  healthCheckService: name     => services.post(`/${name}/health-check`).then(r => r.data),

  // ── Service Contracts ──────────────────────────────────────────────────────
  getServiceContracts:  name        => services.get(`/${name}/contracts`).then(r => r.data),
  addServiceContract:   (name, body)=> services.post(`/${name}/contracts`, body).then(r => r.data),
  deleteContract:       id          => services.delete(`/contracts/${id}`).then(r => r.data),

  // ── Manifest import ────────────────────────────────────────────────────────
  importManifest: (file) => {
    const form = new FormData();
    form.append('file', file);
    return services
      .post('/import-manifest', form, { headers: { 'Content-Type': 'multipart/form-data' } })
      .then(r => r.data);
  },

  // ── MendrScript conversation engine (SSE) ───────────────────────────────────
  // Streams the synth loop (propose → verify → simulate → present) from the
  // conversation engine. The engine NEVER deploys; it returns a verified program +
  // before/after diff for the operator to approve through the normal flow.
  // `onEvent(type, data)` is called for: session | security | progress | result |
  // done | error | end. Returns an AbortController to cancel the stream.
  streamChat: ({ analysisId, message, sessionId, context, cases }, onEvent) => {
    const controller = new AbortController();
    (async () => {
      try {
        const headers = { 'Content-Type': 'application/json' };
        const token = await getAccessToken();
        if (token) headers.Authorization = `Bearer ${token}`;
        const resp = await fetch('/api/chat/stream', {
          method: 'POST',
          headers,
          body: JSON.stringify({ analysisId, sessionId, message, context, cases }),
          signal: controller.signal,
        });
        if (resp.status === 401) {
          handleUnauthorized();
          onEvent('error', { message: 'Unauthorized' });
          return;
        }
        if (!resp.ok || !resp.body) {
          onEvent('error', { message: `HTTP ${resp.status}` });
          return;
        }
        const reader = resp.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        // Parse one SSE event block (fields separated by single newlines).
        const emit = block => {
          let ev = 'message';
          let data = '';
          block.split('\n').forEach(line => {
            if (line.startsWith('event:')) ev = line.slice(6).trim();
            else if (line.startsWith('data:')) data += line.slice(5).trim();
          });
          if (data) {
            try { onEvent(ev, JSON.parse(data)); }
            catch { onEvent(ev, { raw: data }); }
          }
        };
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          // Normalize CRLF -> LF: sse_starlette delimits events with \r\n\r\n, so a
          // naive indexOf('\n\n') would never match and every event would be dropped.
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          let idx;
          while ((idx = buffer.indexOf('\n\n')) >= 0) {
            emit(buffer.slice(0, idx));
            buffer = buffer.slice(idx + 2);
          }
        }
        // Flush a trailing event that wasn't terminated by a blank line.
        if (buffer.trim()) emit(buffer);
      } catch (e) {
        if (e.name !== 'AbortError') onEvent('error', { message: e.message });
      } finally {
        onEvent('end', {});
      }
    })();
    return controller;
  },

  // ── Developer Portal ───────────────────────────────────────────────────────
  getPortalCatalog: () => portal.get('/catalog').then(r => r.data),
  getPortalSpecs: () => portal.get('/specs').then(r => r.data),
  getPortalUsage: () => portal.get('/usage').then(r => r.data),
  issuePortalApiKey: body => portal.post('/api-keys', body).then(r => r.data),
  getAiRoutes: () => gateway.get('/ai-routes').then(r => r.data),
  upsertAiRoute: body => gateway.post('/ai-routes', body).then(r => r.data),
};
