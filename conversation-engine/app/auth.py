"""Authentication + tenant resolution for the conversation engine.

Mirrors the Java control plane's model:
  - Human (dashboard): WorkOS JWT, validated against the WorkOS JWKS. The
    configured org claim (default ``org_id``) identifies the tenant.
  - Machine/edge: the shared internal API key (same as the gateway's
    ``GATEWAY_INTERNAL_API_KEY``) for trusted service-to-service calls.

Enforcement is gated by ``MENDR_AUTH_ENFORCE`` so this drops in behind the same
safe incremental rollout as the rest of the platform: when disabled, an
unauthenticated request is allowed but still resolves to the default tenant;
when enabled, a request without a valid credential is rejected with 401.

Authorization (what a principal may do) still happens in the downstream systems
(gateway verifier, rule-engine deploy) — this module only establishes identity +
tenant so RLS-scoped reads via MCP are correct (OWASP LLM06: complete mediation).
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Optional

from fastapi import Header, HTTPException, Request

from .config import settings

try:  # JWKS validation is only needed when a WorkOS JWKS URI is configured.
    from jose import jwt as jose_jwt
    from jose.exceptions import JWTError
except Exception:  # pragma: no cover - dependency optional in minimal dev
    jose_jwt = None
    JWTError = Exception


@dataclass
class Principal:
    """The authenticated caller for a request."""
    tenant_id: str
    subject: Optional[str] = None
    kind: str = "anonymous"  # "jwt" | "internal" | "anonymous"


class _JwksCache:
    """Tiny TTL cache for the WorkOS JWKS so we do not fetch per request."""

    def __init__(self, ttl_seconds: int = 3600):
        self._ttl = ttl_seconds
        self._keys = None
        self._fetched_at = 0.0

    async def get(self) -> Optional[dict]:
        if not settings.workos_jwks_uri:
            return None
        now = time.time()
        if self._keys is not None and (now - self._fetched_at) < self._ttl:
            return self._keys
        import httpx
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(settings.workos_jwks_uri)
            resp.raise_for_status()
            self._keys = resp.json()
            self._fetched_at = now
        return self._keys


_jwks_cache = _JwksCache()


async def _tenant_from_jwt(token: str) -> Optional[str]:
    """Validate a WorkOS JWT and return the tenant (org) claim, or None."""
    if jose_jwt is None or not settings.workos_jwks_uri:
        return None
    jwks = await _jwks_cache.get()
    if not jwks:
        return None
    try:
        options = {"verify_aud": bool(settings.workos_audience)}
        claims = jose_jwt.decode(
            token,
            jwks,
            algorithms=["RS256"],
            audience=settings.workos_audience or None,
            issuer=settings.workos_issuer or None,
            options=options,
        )
    except JWTError as e:
        raise HTTPException(status_code=401, detail=f"invalid token: {e}") from e
    org_id = claims.get(settings.workos_org_claim)
    if not org_id:
        return None
    # The org claim maps to a tenant server-side (tenants.workos_org_id). The
    # gateway does the DB lookup; here we forward the org id as the tenant hint so
    # the MCP surface (which shares the DB) can resolve/scope it. When the claim is
    # already a tenant UUID this is a pass-through.
    return str(org_id)


async def authenticate(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None),
    x_internal_api_key: Optional[str] = Header(default=None),
) -> Principal:
    """FastAPI dependency: resolve the caller into a Principal (+ tenant).

    Fails closed with 401 when ``MENDR_AUTH_ENFORCE`` is on and no valid
    credential is present. Otherwise binds the default tenant.
    """
    # 1) Machine/edge: shared internal key.
    provided_internal = x_internal_api_key or x_api_key
    if settings.internal_api_key and provided_internal == settings.internal_api_key:
        return Principal(tenant_id=settings.default_tenant_id, kind="internal")

    # 2) Human: WorkOS bearer JWT.
    token = None
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
        # Support the edge convention of "Bearer mendr_<key>" as a machine key too.
        if settings.internal_api_key and token == settings.internal_api_key:
            return Principal(tenant_id=settings.default_tenant_id, kind="internal")

    if token:
        tenant = await _tenant_from_jwt(token)
        if tenant:
            return Principal(tenant_id=tenant, subject=token[:12], kind="jwt")

    # 3) No (valid) credential.
    if settings.auth_enforce:
        raise HTTPException(status_code=401, detail="authentication required")
    if not settings.tenancy_fallback_to_default:
        raise HTTPException(status_code=401, detail="no tenant context")
    return Principal(tenant_id=settings.default_tenant_id, kind="anonymous")
