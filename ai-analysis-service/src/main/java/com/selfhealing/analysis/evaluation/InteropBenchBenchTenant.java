package com.selfhealing.analysis.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bench-tenant admission overrides for InteropBench (plan step 0).
 * Applied for the JVM via system properties consumed by tests and documented
 * for Spring ({@code application-interop.yml}).
 */
public final class InteropBenchBenchTenant {

    public static final int TENANT_PER_MIN = 120;
    public static final int GLOBAL_PER_MIN = 300;
    public static final int SEMAPHORE = 2;
    public static final int PRODUCTION_TENANT_PER_MIN = 10;
    public static final int PRODUCTION_GLOBAL_PER_MIN = 30;

    private InteropBenchBenchTenant() {}

    /** Apply raised limits for this process (Mode B sweep / pilot). */
    public static Map<String, Integer> apply() {
        System.setProperty("mendr.analysis.llm.tenant-per-minute", String.valueOf(TENANT_PER_MIN));
        System.setProperty("mendr.analysis.llm.global-per-minute", String.valueOf(GLOBAL_PER_MIN));
        System.setProperty("MENDR_ANALYSIS_LLM_TENANT_PER_MIN", String.valueOf(TENANT_PER_MIN));
        System.setProperty("MENDR_ANALYSIS_LLM_GLOBAL_PER_MIN", String.valueOf(GLOBAL_PER_MIN));
        return snapshot();
    }

    public static Map<String, Integer> snapshot() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("semaphore", SEMAPHORE);
        m.put("tenantPerMinuteApplied", TENANT_PER_MIN);
        m.put("globalPerMinuteApplied", GLOBAL_PER_MIN);
        m.put("productionTenantPerMinute", PRODUCTION_TENANT_PER_MIN);
        m.put("productionGlobalPerMinute", PRODUCTION_GLOBAL_PER_MIN);
        return m;
    }
}
