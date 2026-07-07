"""JSON-RPC 2.0 client for the ai-analysis-service MCP server.

The conversation engine reaches verify_program / simulate_transform / context tools
exclusively through MCP, so the engine itself holds no privileged capability and there
is a single audited surface for what it can do.
"""
from __future__ import annotations

import json
import uuid

import httpx

from .config import settings


class McpClient:
    def __init__(self, base_url: str | None = None, path: str | None = None,
                 tenant_id: str | None = None):
        self._url = (base_url or settings.mcp_base_url).rstrip("/") + (path or settings.mcp_path)
        # Bound per-request so RLS-scoped reads on the ai-analysis side resolve to the
        # right tenant. The engine holds no privileged capability; the internal key
        # simply authenticates it as a trusted service caller (not an anonymous one).
        self._tenant_id = tenant_id
        self._headers = {}
        if settings.internal_api_key:
            self._headers["X-Internal-Api-Key"] = settings.internal_api_key
        if tenant_id:
            self._headers["X-Tenant-Id"] = tenant_id

    def for_tenant(self, tenant_id: str | None) -> "McpClient":
        """Return a client bound to a specific tenant for one request's calls.

        The full endpoint URL is already resolved on this instance, so pass it as
        the base with an empty path (path="" is preserved, not defaulted).
        """
        clone = McpClient.__new__(McpClient)
        clone._url = self._url
        clone._tenant_id = tenant_id
        clone._headers = dict(self._headers)
        if tenant_id:
            clone._headers["X-Tenant-Id"] = tenant_id
        else:
            clone._headers.pop("X-Tenant-Id", None)
        return clone

    async def call_tool(self, name: str, arguments: dict) -> dict:
        payload = {
            "jsonrpc": "2.0",
            "id": str(uuid.uuid4()),
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        }
        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.post(self._url, json=payload, headers=self._headers)
            resp.raise_for_status()
            body = resp.json()
        if "error" in body:
            return {"error": body["error"]}
        # MCP tools return {"content": [{"type":"text","text": "<json>"}]}
        content = body.get("result", {}).get("content", [])
        if content and content[0].get("type") == "text":
            try:
                return json.loads(content[0]["text"])
            except (ValueError, KeyError):
                return {"raw": content[0].get("text")}
        return body.get("result", {})

    async def verify_program(self, program: dict) -> dict:
        return await self.call_tool("verify_program", {"program": program})

    async def simulate_transform(self, program: dict, cases: list[dict]) -> dict:
        return await self.call_tool("simulate_transform", {"program": program, "cases": cases})

    async def get_contract(self, service: str, endpoint: str, direction: str = "REQUEST") -> dict:
        return await self.call_tool(
            "get_contract", {"service": service, "endpoint": endpoint, "direction": direction}
        )
