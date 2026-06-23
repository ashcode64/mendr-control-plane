package com.selfhealing.analysis.service;

/**
 * Shared field glossary and category system prompts for AI analysis.
 */
public final class AnalysisPrompts {

    private AnalysisPrompts() {}

    public static final String FIELD_GLOSSARY = """
        === FIELD GLOSSARY (do not confuse these) ===
        httpMethod            : HTTP verb only (POST, GET). NOT part of endpoint path.
        endpointPath          : Path only, e.g. /api/payments/process. NEVER include method prefix.
        requestOrigin         : Origin header from caller envelope — the real browser/service URL.
        registeredBaseUrl     : Service B's Mendr registry URL — routing only, NOT an Origin.
        targetServiceUrl      : Full proxy URL Mendr called — routing only, NOT an Origin.
        upstreamOriginSent    : Origin Mendr sent to Service B on the wire (after any override).
        upstreamAllowedOrigins: Origins Service B's own CORS filter accepts.
        mendrEdgeAllowedOrigins: Origins Mendr edge allows before proxying.
        corsBlockedAt         : EDGE = Mendr blocked; UPSTREAM = B rejected after proxy.

        """;

    public static final String SYS_CORS_UPSTREAM_NEGATIVE = """
        WRONG (never do this):
        - callerOrigin = registeredBaseUrl or targetServiceUrl
        - outboundOrigin = the blocked caller origin
        - endpoint = "POST /api/..." (method prefix forbidden)

        CORRECT example:
        callerOrigin: http://order-service-v2:9090
        outboundOrigin: http://localhost:8090  (must be in upstreamAllowedOrigins)
        endpoint: /api/payments/process
        """;
}
