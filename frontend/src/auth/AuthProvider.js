import React, { useEffect } from 'react';
import { AuthKitProvider, useAuth } from '@workos-inc/authkit-react';
import { registerAuth, AUTH_ENABLED } from './tokenBridge';

const CLIENT_ID = process.env.REACT_APP_WORKOS_CLIENT_ID;
const REDIRECT_URI = process.env.REACT_APP_WORKOS_REDIRECT_URI || window.location.origin;

// Registers the AuthKit token getter + sign-in redirect into the module-level
// bridge so the axios clients can attach the bearer token and react to 401s.
function AuthBridge({ children }) {
  const { getAccessToken, signIn } = useAuth();
  useEffect(() => {
    registerAuth({
      getAccessToken,
      onUnauthorized: () => signIn(),
    });
  }, [getAccessToken, signIn]);
  return children;
}

/**
 * Wraps the app in WorkOS AuthKit when REACT_APP_WORKOS_CLIENT_ID is configured.
 * When it is not (dev / pre-rollout, backend MENDR_AUTH_ENFORCE=false), this is a
 * transparent pass-through so the dashboard keeps working with no login wall.
 */
export default function AuthProvider({ children }) {
  if (!AUTH_ENABLED) {
    return children;
  }
  return (
    <AuthKitProvider clientId={CLIENT_ID} redirectUri={REDIRECT_URI}>
      <AuthBridge>{children}</AuthBridge>
    </AuthKitProvider>
  );
}
