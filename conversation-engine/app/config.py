"""Runtime configuration for the MendrScript conversation engine.

The engine SYNTHESIZES and VERIFIES programs only — it has NO deploy capability.
Deployment goes exclusively through the existing control-plane approval flow
(`api.transformations.approved` -> rule-engine -> gateway), which re-verifies
server-side. This separation is a security invariant, not an accident.
"""
import os


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _csv(name: str, default: str) -> list[str]:
    raw = os.getenv(name, default)
    return [item.strip() for item in raw.split(",") if item.strip()]


class Settings:
    analysis_base_url: str = os.getenv("ANALYSIS_BASE_URL", "http://ai-analysis-service:8082")

    # MCP server on ai-analysis-service exposes verify_program / simulate_transform
    # and the read-only context tools (get_contract, get_active_rules, ...).
    mcp_base_url: str = os.getenv("MCP_BASE_URL", "http://ai-analysis-service:8082")
    mcp_path: str = os.getenv("MCP_PATH", "/mcp")

    # Shared internal key presented to the ai-analysis MCP surface so the engine is
    # not an unauthenticated caller. Mirrors GATEWAY_INTERNAL_API_KEY on the gateway.
    internal_api_key: str = os.getenv("GATEWAY_INTERNAL_API_KEY", "")

    anthropic_api_key: str = os.getenv("ANTHROPIC_API_KEY", "")
    anthropic_model: str = os.getenv("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")
    max_tokens: int = int(os.getenv("MAX_TOKENS", "2000"))

    # Bounded refine loop: how many times the LLM may revise after a verify failure.
    max_refine_iterations: int = int(os.getenv("MAX_REFINE_ITERATIONS", "3"))

    session_ttl_seconds: int = int(os.getenv("SESSION_TTL_SECONDS", "3600"))

    # ── CORS ────────────────────────────────────────────────────────────────
    # Never "*": the dashboard origin(s) only. Comma-separated.
    cors_allowed_origins: list[str] = _csv(
        "MENDR_CORS_ALLOWED_ORIGINS", "http://localhost:3000")

    # ── Auth / tenancy (mirrors the Java control plane) ───────────────────────
    # When true, /chat/stream requires a valid WorkOS JWT (or the internal key for
    # machine callers). When false (default), the endpoint stays open for the safe
    # incremental rollout but any credential present still binds the real tenant.
    auth_enforce: bool = _bool("MENDR_AUTH_ENFORCE", False)
    workos_jwks_uri: str = os.getenv("MENDR_AUTH_WORKOS_JWKS_URI", "")
    workos_issuer: str = os.getenv("MENDR_AUTH_WORKOS_ISSUER", "")
    workos_audience: str = os.getenv("MENDR_AUTH_WORKOS_AUDIENCE", "")
    workos_org_claim: str = os.getenv("MENDR_AUTH_WORKOS_ORG_CLAIM", "org_id")

    # The well-known default tenant, mirrored from the DB migration + Java config.
    default_tenant_id: str = os.getenv(
        "MENDR_TENANCY_DEFAULT_TENANT_ID", "00000000-0000-0000-0000-000000000001")
    tenancy_fallback_to_default: bool = _bool("MENDR_TENANCY_FALLBACK_TO_DEFAULT", True)

    # ── Abuse / cost controls (OWASP LLM10 Unbounded Consumption) ─────────────
    max_message_chars: int = int(os.getenv("MENDR_CHAT_MAX_MESSAGE_CHARS", "8000"))
    max_context_chars: int = int(os.getenv("MENDR_CHAT_MAX_CONTEXT_CHARS", "16000"))
    rate_limit_per_min: int = int(os.getenv("MENDR_CHAT_RATE_LIMIT_PER_MIN", "20"))


settings = Settings()
