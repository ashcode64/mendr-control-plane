"""In-process sliding-window rate limiter (per tenant + client).

Bounds cost/DoS abuse of the LLM synthesis endpoint (OWASP LLM10: Unbounded
Consumption). Deliberately simple and dependency-free; for multi-replica
deployments this would move to Redis with the same key shape.
"""
from __future__ import annotations

import threading
import time
from collections import deque


class SlidingWindowRateLimiter:
    def __init__(self, max_events: int, window_seconds: float = 60.0):
        self._max = max_events
        self._window = window_seconds
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        if self._max <= 0:
            return True
        now = time.monotonic()
        cutoff = now - self._window
        with self._lock:
            dq = self._hits.setdefault(key, deque())
            while dq and dq[0] < cutoff:
                dq.popleft()
            if len(dq) >= self._max:
                return False
            dq.append(now)
            # Opportunistic cleanup of idle buckets to bound memory.
            if len(self._hits) > 4096:
                for k in [k for k, v in self._hits.items() if not v or v[-1] < cutoff]:
                    self._hits.pop(k, None)
            return True
