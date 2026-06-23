package com.selfhealing.gateway.transform;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.selfhealing.gateway.util.TypeCoercer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Transforms a top-level JSON object in one streaming pass: untouched subtrees
 * are bulk-copied via copyCurrentStructure, only matched tokens are rewritten.
 */
@Component
public class StreamingJsonTransformer {

    private final JsonFactory factory = new JsonFactory();

    public byte[] transform(byte[] input, TransformProgram p) throws IOException {
        if (input == null || input.length == 0 || p.isEmpty()) {
            return input;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length + 64);
        boolean ok = transform(input, p, out);
        return ok ? out.toByteArray() : input;
    }

    public boolean transform(byte[] input, TransformProgram p, OutputStream target) throws IOException {
        try (JsonParser parser = factory.createParser(input);
             JsonGenerator gen = factory.createGenerator(target)) {

            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            gen.writeStartObject();
            Set<String> seen = new HashSet<>();

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();

                if (p.getRemovals().contains(field)) {
                    parser.skipChildren();
                    continue;
                }
                String outName = p.getRenames().getOrDefault(field, field);
                seen.add(field);
                seen.add(outName);

                String coerceTo = p.getCoercions().get(outName);
                if (coerceTo == null) {
                    coerceTo = p.getCoercions().get(field);
                }
                if (coerceTo != null && parser.currentToken().isScalarValue()) {
                    gen.writeFieldName(outName);
                    writeScalar(gen, TypeCoercer.coerce(parser.getValueAsString(), coerceTo));
                } else {
                    gen.writeFieldName(outName);
                    gen.copyCurrentStructure(parser);
                }
            }

            for (Map.Entry<String, Object> e : p.getDefaults().entrySet()) {
                if (!seen.contains(e.getKey())) {
                    gen.writeFieldName(e.getKey());
                    writeScalar(gen, e.getValue());
                }
            }
            gen.writeEndObject();
        }
        return true;
    }

    private void writeScalar(JsonGenerator gen, Object v) throws IOException {
        if (v == null) {
            gen.writeNull();
            return;
        }
        if (v instanceof Integer i) {
            gen.writeNumber(i);
            return;
        }
        if (v instanceof Long l) {
            gen.writeNumber(l);
            return;
        }
        if (v instanceof Double d) {
            gen.writeNumber(d);
            return;
        }
        if (v instanceof BigDecimal bd) {
            gen.writeNumber(bd);
            return;
        }
        if (v instanceof Number n) {
            gen.writeNumber(n.doubleValue());
            return;
        }
        if (v instanceof Boolean b) {
            gen.writeBoolean(b);
            return;
        }
        gen.writeString(v.toString());
    }
}
