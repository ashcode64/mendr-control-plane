// Bridges the AuthKit React session (a hook) to the module-level axios clients in
// utils/api.js, which live outside the React tree and cannot call hooks. A React
// component registers the token getter + a sign-in redirect here on mount; the
// axios interceptors read them per request.

let _getAccessToken = null;   // async () => string | null
let _onUnauthorized = null;   // () => void  (redirect to login)

export function registerAuth({ getAccessToken, onUnauthorized }) {
  _getAccessToken = getAccessToken || null;
  _onUnauthorized = onUnauthorized || null;
}

export async function getAccessToken() {
  if (!_getAccessToken) return null;
  try {
    return await _getAccessToken();
  } catch {
    return null;
  }
}

export function handleUnauthorized() {
  if (_onUnauthorized) _onUnauthorized();
}

// True when a WorkOS client id is configured, i.e. auth is expected to be active.
export const AUTH_ENABLED = Boolean(process.env.REACT_APP_WORKOS_CLIENT_ID);
