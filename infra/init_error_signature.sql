-- ErrorSignature diagnosis: template catalog + taxonomy KB (Phases 4–5)
-- Idempotent so it can run on existing deployments.

CREATE TABLE IF NOT EXISTS error_templates (
    template_id     TEXT PRIMARY KEY,
    skeleton        TEXT NOT NULL,
    mask_names      JSONB DEFAULT '[]'::jsonb,
    reviewed        BOOLEAN NOT NULL DEFAULT false,
    taxonomy_id     TEXT,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_error_templates_reviewed
    ON error_templates (reviewed);

CREATE TABLE IF NOT EXISTS error_taxonomy (
    template_id         TEXT PRIMARY KEY,
    meaning             TEXT NOT NULL,
    root_causes         JSONB DEFAULT '[]'::jsonb,
    suggested_opcode    TEXT,
    severity            TEXT DEFAULT 'medium',
    layer               TEXT DEFAULT 'serialization',
    causes_template_id  TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed from existing category → rule-type mappings (human-reviewed primitives).
INSERT INTO error_taxonomy (template_id, meaning, root_causes, suggested_opcode, severity, layer)
VALUES
    ('TYPE_COERCE',
     'Observed value type does not match the receiver contract / deserializer expectation',
     '["sender serializes as string","receiver expects number/boolean","jackson or pydantic type mismatch"]'::jsonb,
     'type-coerce', 'high', 'serialization'),
    ('FIELD_RENAME',
     'Field name mismatch between sender payload and receiver contract',
     '["snake_case vs camelCase","semantic alias (total_amount vs amount)"]'::jsonb,
     'rename', 'high', 'serialization'),
    ('ADD_DEFAULT',
     'Required field missing from the request payload relative to receiver contract',
     '["sender omitted optional-looking required field","schema required not in examples"]'::jsonb,
     'add-default', 'medium', 'validation'),
    ('FIELD_MOVE',
     'Field exists under a different nesting depth than the receiver expects',
     '["nesting vs flattening","credentials.token vs token"]'::jsonb,
     'move', 'high', 'serialization'),
    ('RESPONSE_TYPE_COERCE',
     'Response field type does not match caller expected contract',
     '["provider returned string for numeric field"]'::jsonb,
     'response-type-coerce', 'high', 'serialization'),
    ('RESPONSE_FIELD_RENAME',
     'Response field name mismatch vs caller expected contract',
     '["provider renamed response field"]'::jsonb,
     'response-rename', 'high', 'serialization'),
    ('RESPONSE_ADD_DEFAULT',
     'Response missing fields the caller contract requires',
     '["provider dropped optional-required field"]'::jsonb,
     'response-add-default', 'medium', 'validation'),
    ('jackson_deserialize_type_mismatch',
     'Jackson cannot deserialize a JSON value into the target Java type',
     '["string sent for int/long","empty string for number"]'::jsonb,
     'type-coerce', 'high', 'serialization'),
    ('UNKNOWN_OPAQUE',
     'Opaque upstream error string with no deterministic schema/response diff',
     '["third-party error body","legacy free-text message"]'::jsonb,
     NULL, 'medium', 'unknown')
ON CONFLICT (template_id) DO NOTHING;

-- Optional: store latest ErrorSignature per failure for fast MCP lookup
ALTER TABLE IF EXISTS analysis_results
    ADD COLUMN IF NOT EXISTS error_signature JSONB;

CREATE INDEX IF NOT EXISTS idx_analysis_results_error_signature_gin
    ON analysis_results USING GIN (error_signature)
    WHERE error_signature IS NOT NULL;
