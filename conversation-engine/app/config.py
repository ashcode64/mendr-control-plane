"""Runtime configuration for the MendrScript conversation engine.

The engine SYNTHESIZES and VERIFIES programs only — it has NO deploy capability.
Deployment goes exclusively through the existing control-plane approval flow
(`api.transformations.approved` -> rule-engine -> gateway), which re-verifies
server-side. This separation is a security invariant, not an accident.
"""
import os


class Settings:
    # MCP server on ai-analysis-service exposes verify_program / simulate_transform
    # and the read-only context tools (get_contract, get_active_rules, ...).
    mcp_base_url: str = os.getenv("MCP_BASE_URL", "http://ai-analysis-service:8082")
    mcp_path: str = os.getenv("MCP_PATH", "/mcp")

    anthropic_api_key: str = os.getenv("ANTHROPIC_API_KEY", "")
    anthropic_model: str = os.getenv("ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")
    max_tokens: int = int(os.getenv("MAX_TOKENS", "2000"))

    # Bounded refine loop: how many times the LLM may revise after a verify failure.
    max_refine_iterations: int = int(os.getenv("MAX_REFINE_ITERATIONS", "3"))

    session_ttl_seconds: int = int(os.getenv("SESSION_TTL_SECONDS", "3600"))


settings = Settings()
