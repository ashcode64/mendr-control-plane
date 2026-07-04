package com.selfhealing.gateway.transform.dsl;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;
import java.util.Set;

/**
 * Structured predicate grammar for the {@code conditional} opcode (Gap 3, Option C).
 *
 * <p>Deliberately NOT free-form regex: the closed set below is trivially mirror-able
 * between the Java executor and the Lua edge interpreter and has no ReDoS surface.
 * {@code matches_format} resolves to a named format whose pattern is defined ONCE in
 * the codebase ({@link NamedFormats}) — never authored by the LLM. The grammar
 * reserves room for a future RE2-backed {@code regex-match} if a real need appears.
 *
 * <p>Every predicate exposes the static path literal(s) it reads so the verifier's
 * tree-walking protected-path scan and dataflow checks are complete.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Predicate.Eq.class, name = "eq"),
        @JsonSubTypes.Type(value = Predicate.Exists.class, name = "exists"),
        @JsonSubTypes.Type(value = Predicate.In.class, name = "in"),
        @JsonSubTypes.Type(value = Predicate.MatchesFormat.class, name = "matches_format"),
        @JsonSubTypes.Type(value = Predicate.StartsWith.class, name = "starts_with"),
        @JsonSubTypes.Type(value = Predicate.EndsWith.class, name = "ends_with"),
        @JsonSubTypes.Type(value = Predicate.Contains.class, name = "contains"),
        @JsonSubTypes.Type(value = Predicate.LengthBetween.class, name = "length_between"),
})
public sealed interface Predicate
        permits Predicate.Eq, Predicate.Exists, Predicate.In, Predicate.MatchesFormat,
                Predicate.StartsWith, Predicate.EndsWith, Predicate.Contains, Predicate.LengthBetween {

    /** The opcode discriminator (matches the JSON {@code op} field). */
    String op();

    /** Static path literal this predicate reads. */
    String path();

    /** Paths read — always the single {@link #path()} for the current grammar. */
    default Set<String> reads() {
        return path() == null ? Set.of() : Set.of(path());
    }

    @JsonTypeName("eq")
    record Eq(String path, Object value) implements Predicate {
        public String op() { return "eq"; }
    }

    @JsonTypeName("exists")
    record Exists(String path) implements Predicate {
        public String op() { return "exists"; }
    }

    @JsonTypeName("in")
    record In(String path, List<Object> values) implements Predicate {
        public String op() { return "in"; }
    }

    /** Matches a closed, named format (see {@link NamedFormats}). */
    @JsonTypeName("matches_format")
    record MatchesFormat(String path, String format) implements Predicate {
        public String op() { return "matches_format"; }
    }

    @JsonTypeName("starts_with")
    record StartsWith(String path, String value) implements Predicate {
        public String op() { return "starts_with"; }
    }

    @JsonTypeName("ends_with")
    record EndsWith(String path, String value) implements Predicate {
        public String op() { return "ends_with"; }
    }

    @JsonTypeName("contains")
    record Contains(String path, String value) implements Predicate {
        public String op() { return "contains"; }
    }

    @JsonTypeName("length_between")
    record LengthBetween(String path, Integer min, Integer max) implements Predicate {
        public String op() { return "length_between"; }
    }
}
