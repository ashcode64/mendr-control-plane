from __future__ import annotations

import httpx

from .config import settings


class AnalysisClient:
    def __init__(self, base_url: str | None = None, tenant_id: str | None = None):
        self._base_url = (base_url or settings.analysis_base_url).rstrip("/")
        self._tenant_id = tenant_id
        self._headers = {}
        if settings.internal_api_key:
            self._headers["X-Internal-Api-Key"] = settings.internal_api_key
        if tenant_id:
            self._headers["X-Tenant-Id"] = tenant_id

    def for_tenant(self, tenant_id: str | None) -> "AnalysisClient":
        clone = AnalysisClient.__new__(AnalysisClient)
        clone._base_url = self._base_url
        clone._tenant_id = tenant_id
        clone._headers = dict(self._headers)
        if tenant_id:
            clone._headers["X-Tenant-Id"] = tenant_id
        else:
            clone._headers.pop("X-Tenant-Id", None)
        return clone

    async def get_conversation(self, analysis_id: str, limit: int = 10) -> dict:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                f"{self._base_url}/api/internal/analysis/{analysis_id}/conversation",
                params={"limit": limit},
                headers=self._headers,
            )
            resp.raise_for_status()
            return resp.json()

    async def append_messages(self, analysis_id: str, messages: list[dict], last_result: dict | None = None) -> dict:
        payload = {"messages": messages, "lastResult": last_result}
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{self._base_url}/api/internal/analysis/{analysis_id}/conversation/messages",
                json=payload,
                headers=self._headers,
            )
            resp.raise_for_status()
            return resp.json()
