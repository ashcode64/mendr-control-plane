package com.selfhealing.analysis.evaluation;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal Mode B cost ledger (plan: enough to run and record a sweep, not the P1 product).
 * Anthropic/Gemini clients record here when a provider usage block is present.
 */
public final class InteropBenchUsageLedger {

    public static final double USD_PER_1K_INPUT = 0.0008;   // Haiku-class default
    public static final double USD_PER_1K_OUTPUT = 0.004;

    private static final AtomicInteger HTTP_CALLS = new AtomicInteger();
    private static final AtomicLong INPUT_TOKENS = new AtomicLong();
    private static final AtomicLong OUTPUT_TOKENS = new AtomicLong();

    private InteropBenchUsageLedger() {}

    public static void reset() {
        HTTP_CALLS.set(0);
        INPUT_TOKENS.set(0);
        OUTPUT_TOKENS.set(0);
    }

    public static void recordHttpCall(int inputTokens, int outputTokens) {
        HTTP_CALLS.incrementAndGet();
        INPUT_TOKENS.addAndGet(Math.max(0, inputTokens));
        OUTPUT_TOKENS.addAndGet(Math.max(0, outputTokens));
    }

    public static int httpCalls() {
        return HTTP_CALLS.get();
    }

    public static long inputTokens() {
        return INPUT_TOKENS.get();
    }

    public static long outputTokens() {
        return OUTPUT_TOKENS.get();
    }

    public static double usd() {
        return (inputTokens() / 1000.0) * USD_PER_1K_INPUT
                + (outputTokens() / 1000.0) * USD_PER_1K_OUTPUT;
    }

    public static Snapshot snapshot() {
        return new Snapshot(httpCalls(), inputTokens(), outputTokens(), usd());
    }

    public record Snapshot(int httpCalls, long inputTokens, long outputTokens, double usd) {}
}
