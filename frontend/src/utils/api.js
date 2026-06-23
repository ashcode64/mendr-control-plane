import axios from 'axios';

const gateway = axios.create({ baseURL: '/api/gateway', timeout: 10000 });
const analysis = axios.create({ baseURL: '/api/analysis', timeout: 10000 });
const rules = axios.create({ baseURL: '/api/rules', timeout: 10000 });
const services = axios.create({ baseURL: '/api/services', timeout: 10000 });

// Add response interceptor for error normalisation
[gateway, analysis, rules, services].forEach(client => {
  client.interceptors.response.use(
    r => r,
    err => {
      const msg = err.response?.data?.message || err.message || 'Request failed';
      return Promise.reject(new Error(msg));
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
  approveAnalysis: (id, approvedBy = 'dashboard-user') =>
    analysis.post(`/${id}/approve`, { approvedBy }).then(r => r.data),
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
};
