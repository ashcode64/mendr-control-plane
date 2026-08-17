import React, { useState, useEffect, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import Sidebar from './components/Sidebar';
import Overview from './pages/Overview';
import Failures from './pages/Failures';
import Analysis from './pages/Analysis';
import Rules from './pages/Rules';
import Services from './pages/Services';
import Simulate from './pages/Simulate';
import AuditLog from './pages/AuditLog';
import DeveloperPortal from './pages/DeveloperPortal';
import RequireAuth from './auth/RequireAuth';
import { api } from './utils/api';

export default function App() {
  const [pendingCount, setPendingCount] = useState(0);

  const fetchPending = useCallback(async () => {
    try {
      const data = await api.getPendingAnalyses();
      setPendingCount(Array.isArray(data) ? data.length : 0);
    } catch {
      // backend may not be up yet
    }
  }, []);

  useEffect(() => {
    fetchPending();
    const id = setInterval(fetchPending, 15000);
    return () => clearInterval(id);
  }, [fetchPending]);

  return (
    <RequireAuth>
    <BrowserRouter>
      <Toaster
        position="top-right"
        toastOptions={{
          style: {
            background: 'var(--surface-2)',
            color: 'var(--text-primary)',
            border: '1px solid var(--border)',
            fontFamily: 'var(--font-mono)',
            fontSize: '13px',
          },
        }}
      />
      <div style={{ display: 'flex', minHeight: '100vh', background: 'var(--bg)' }}>
        <Sidebar pendingCount={pendingCount} />
        <main style={{ flex: 1, overflowY: 'auto', padding: '32px' }}>
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/failures" element={<Failures />} />
            <Route path="/analysis" element={<Analysis onApproval={fetchPending} />} />
            <Route path="/rules" element={<Rules />} />
            <Route path="/services" element={<Services />} />
            <Route path="/portal" element={<DeveloperPortal />} />
            <Route path="/simulate" element={<Simulate />} />
            <Route path="/audit" element={<AuditLog />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
    </RequireAuth>
  );
}
