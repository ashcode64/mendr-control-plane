import React, { useState, useEffect, useCallback } from 'react';
import {
  AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, PieChart, Pie, Cell, Legend,
} from 'recharts';
import { api } from '../utils/api';
import StatCard from '../components/StatCard';
import Spinner from '../components/Spinner';

const MOCK_TREND = [
  { time: '00:00', failures: 2, resolved: 1 },
  { time: '04:00', failures: 4, resolved: 2 },
  { time: '08:00', failures: 9, resolved: 6 },
  { time: '12:00', failures: 14, resolved: 11 },
  { time: '16:00', failures: 7, resolved: 5 },
  { time: '20:00', failures: 3, resolved: 3 },
  { time: 'Now',   failures: 1, resolved: 0 },
];

const MOCK_SERVICES = [
  { name: 'order→user',    failures: 14 },
  { name: 'payment→order', failures: 9  },
  { name: 'user→notif',    failures: 6  },
  { name: 'inv→order',     failures: 4  },
  { name: 'pay→notif',     failures: 2  },
];

const PIE_DATA = [
  { name: 'Resolved',  value: 68, color: '#10e88a' },
  { name: 'Pending',   value: 18, color: '#f5a623' },
  { name: 'Open',      value: 14, color: '#ff4757' },
];

const TT_STYLE = {
  contentStyle: { background: '#141c2e', border: '1px solid rgba(99,130,255,0.2)', borderRadius: '8px', fontSize: '12px' },
  labelStyle: { color: '#8a96b8' },
};

export default function Overview() {
  const [stats, setStats] = useState(null);
  const [aStats, setAStats] = useState(null);
  const [loading, setLoading] = useState(true);

  const fetchAll = useCallback(async () => {
    try {
      const [s, a] = await Promise.allSettled([api.getStats(), api.getAnalysisStats()]);
      if (s.status === 'fulfilled') setStats(s.value);
      if (a.status === 'fulfilled') setAStats(a.value);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleRefresh = () => {
    setLoading(true);
    fetchAll();
  };

  useEffect(() => { fetchAll(); const id = setInterval(fetchAll, 20000); return () => clearInterval(id); }, [fetchAll]);

  const totalFailures = stats?.totalFailures ?? '—';
  const openFailures  = stats?.openFailures  ?? '—';
  const activeRules   = stats?.activeRules   ?? '—';
  const resolved      = stats?.resolvedFailures ?? '—';
  const pending       = aStats?.pending ?? '—';
  const approved      = aStats?.approved ?? '—';
  const mttr = '4.2 min';
  const uptime = '99.7%';

  return (
    <div style={{ animation: 'slide-in-up 0.35s ease forwards' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '28px' }}>
        <div>
          <div style={{ fontSize: '22px', fontWeight: 700, letterSpacing: '-0.01em' }}>
            Platform Overview
          </div>
          <div style={{ fontSize: '13px', color: 'var(--text-secondary)', marginTop: '4px' }}>
            Real-time visibility into your self-healing API infrastructure
          </div>
        </div>
        <button onClick={handleRefresh} style={{
          background: 'var(--bg-card)', border: '1px solid var(--border)',
          color: 'var(--text-secondary)', padding: '8px 14px', borderRadius: 'var(--radius-sm)',
          cursor: 'pointer', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px',
        }}>
          ↻ Refresh
        </button>
      </div>

      {/* Live indicator */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '20px',
                    fontSize: '12px', color: 'var(--text-muted)' }}>
        <span style={{ width: '7px', height: '7px', borderRadius: '50%', background: '#10e88a',
                       boxShadow: '0 0 6px #10e88a', animation: 'pulse-glow 2s infinite' }} />
        Live — refreshing every 20s
      </div>

      {/* Stat Cards */}
      {loading ? <Spinner /> : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', marginBottom: '24px' }}>
            <StatCard label="Total Failures" value={totalFailures} icon="⚡" color="var(--accent-red)" sub="All time" />
            <StatCard label="Open Failures"  value={openFailures}  icon="🔴" color="var(--accent-yellow)" sub="Needs attention" />
            <StatCard label="Active Rules"   value={activeRules}   icon="⚙️" color="var(--accent-blue)" sub="Auto-applied" />
            <StatCard label="Resolved"       value={resolved}      icon="✅" color="var(--accent-green)" sub="Successfully healed" />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '16px', marginBottom: '28px' }}>
            <StatCard label="Pending Approval" value={pending}  icon="🧠" color="var(--accent-yellow)" />
            <StatCard label="Rules Approved"   value={approved} icon="🎯" color="var(--accent-green)" />
            <StatCard label="Avg MTTR"         value={mttr}     icon="⏱"  color="var(--accent-cyan)" />
            <StatCard label="Uptime"           value={uptime}   icon="🛡" color="var(--accent-purple)" />
          </div>
        </>
      )}

      {/* Charts row */}
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px', marginBottom: '20px' }}>

        {/* Failure trend */}
        <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-lg)', padding: '24px' }}>
          <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-secondary)',
                        textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: '20px' }}>
            Failure Trend (24h)
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <AreaChart data={MOCK_TREND}>
              <defs>
                <linearGradient id="gFail" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#ff4757" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#ff4757" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="gRes" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#10e88a" stopOpacity={0.3} />
                  <stop offset="95%" stopColor="#10e88a" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="rgba(99,130,255,0.07)" />
              <XAxis dataKey="time" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} />
              <Tooltip {...TT_STYLE} />
              <Area type="monotone" dataKey="failures" stroke="#ff4757" strokeWidth={2} fill="url(#gFail)" name="Failures" />
              <Area type="monotone" dataKey="resolved" stroke="#10e88a" strokeWidth={2} fill="url(#gRes)" name="Resolved" />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        {/* Resolution status pie */}
        <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-lg)', padding: '24px' }}>
          <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-secondary)',
                        textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: '20px' }}>
            Resolution Status
          </div>
          <ResponsiveContainer width="100%" height={200}>
            <PieChart>
              <Pie data={PIE_DATA} cx="50%" cy="50%" innerRadius={55} outerRadius={80}
                   paddingAngle={3} dataKey="value">
                {PIE_DATA.map((entry, i) => <Cell key={i} fill={entry.color} />)}
              </Pie>
              <Tooltip contentStyle={TT_STYLE.contentStyle} />
              <Legend iconType="circle" iconSize={8}
                formatter={(v) => <span style={{ color: 'var(--text-secondary)', fontSize: '12px' }}>{v}</span>} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Bottom row: top failing service pairs */}
      <div style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius-lg)', padding: '24px' }}>
        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-secondary)',
                      textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: '20px' }}>
          Top Failing Service Pairs
        </div>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={MOCK_SERVICES} layout="vertical" margin={{ left: 20 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(99,130,255,0.07)" horizontal={false} />
            <XAxis type="number" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} axisLine={false} tickLine={false} />
            <YAxis type="category" dataKey="name" tick={{ fontSize: 11, fill: 'var(--text-secondary)' }} axisLine={false} tickLine={false} width={100} />
            <Tooltip {...TT_STYLE} />
            <Bar dataKey="failures" fill="var(--accent-blue)" radius={[0, 4, 4, 0]} barSize={18} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
