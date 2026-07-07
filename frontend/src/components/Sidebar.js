import React from 'react';
import { NavLink } from 'react-router-dom';
import UserMenu from '../auth/UserMenu';

const NAV = [
  { to: '/',         icon: '⬡',  label: 'Overview'      },
  { to: '/failures', icon: '⚡',  label: 'Failures'      },
  { to: '/analysis', icon: '🧠',  label: 'AI Analysis'   },
  { to: '/rules',    icon: '⚙️',  label: 'Active Rules'  },
  { to: '/services', icon: '🔌',  label: 'Services'      },
  { to: '/simulate', icon: '🎯',  label: 'Simulate'      },
  { to: '/audit',    icon: '📋',  label: 'Audit Log'     },
];

export default function Sidebar({ pendingCount = 0 }) {
  return (
    <aside style={s.aside}>
      {/* Logo */}
      <div style={s.logo}>
        <div style={s.logoIcon}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M12 2L2 7l10 5 10-5-10-5z" stroke="var(--accent-cyan)" strokeWidth="1.5" strokeLinejoin="round"/>
            <path d="M2 17l10 5 10-5" stroke="var(--accent-blue)" strokeWidth="1.5" strokeLinejoin="round"/>
            <path d="M2 12l10 5 10-5" stroke="var(--accent-purple)" strokeWidth="1.5" strokeLinejoin="round"/>
          </svg>
        </div>
        <div>
          <div style={s.logoName}>Mendr</div>
          <div style={s.logoSub}>Self-Healing API</div>
        </div>
      </div>

      {/* Nav */}
      <nav style={s.nav}>
        {NAV.map(({ to, icon, label }) => (
          <NavLink key={to} to={to} end={to === '/'} style={({ isActive }) => ({
            ...s.link,
            ...(isActive ? s.linkActive : {}),
          })}>
            <span style={s.linkIcon}>{icon}</span>
            <span>{label}</span>
            {label === 'AI Analysis' && pendingCount > 0 && (
              <span style={s.badge}>{pendingCount}</span>
            )}
          </NavLink>
        ))}
      </nav>

      {/* Footer */}
      <div style={s.footer}>
        <div style={s.footerDot} />
        <div>
          <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>System Status</div>
          <div style={{ fontSize: 11, color: 'var(--accent-green)', fontWeight: 600 }}>All Services Online</div>
        </div>
      </div>

      <UserMenu />
    </aside>
  );
}

const s = {
  aside: {
    width: 220,
    minWidth: 220,
    height: '100vh',
    background: 'var(--bg-surface)',
    borderRight: '1px solid var(--border)',
    display: 'flex',
    flexDirection: 'column',
    padding: '20px 12px',
    position: 'sticky',
    top: 0,
  },
  logo: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '4px 8px 20px',
    borderBottom: '1px solid var(--border)',
    marginBottom: 16,
  },
  logoIcon: {
    width: 38,
    height: 38,
    borderRadius: 10,
    background: 'linear-gradient(135deg, rgba(79,124,255,0.2), rgba(139,92,246,0.2))',
    border: '1px solid var(--border-bright)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoName: { fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.2 },
  logoSub: { fontSize: 10, color: 'var(--text-muted)', letterSpacing: '0.06em', textTransform: 'uppercase' },
  nav: { display: 'flex', flexDirection: 'column', gap: 2, flex: 1 },
  link: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '9px 12px',
    borderRadius: 'var(--radius-sm)',
    color: 'var(--text-secondary)',
    textDecoration: 'none',
    fontSize: 13,
    fontWeight: 500,
    transition: 'var(--transition)',
    position: 'relative',
  },
  linkActive: {
    background: 'rgba(79,124,255,0.14)',
    color: 'var(--accent-blue)',
    border: '1px solid rgba(79,124,255,0.2)',
  },
  linkIcon: { fontSize: 16, width: 20, textAlign: 'center' },
  badge: {
    marginLeft: 'auto',
    background: 'var(--accent-red)',
    color: '#fff',
    fontSize: 10,
    fontWeight: 700,
    borderRadius: 10,
    padding: '1px 6px',
    minWidth: 18,
    textAlign: 'center',
  },
  footer: {
    marginTop: 'auto',
    paddingTop: 16,
    borderTop: '1px solid var(--border)',
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '16px 8px 0',
  },
  footerDot: {
    width: 8,
    height: 8,
    borderRadius: '50%',
    background: 'var(--accent-green)',
    boxShadow: '0 0 8px rgba(16,232,138,0.6)',
    animation: 'pulse-glow 2s infinite',
    flexShrink: 0,
  },
};
