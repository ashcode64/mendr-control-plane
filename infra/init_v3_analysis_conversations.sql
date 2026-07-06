-- ============================================================================
-- Persisted MendrScript chat history per analysis.
-- ----------------------------------------------------------------------------
-- Stores the dashboard-visible conversation transcript (bounded to the latest
-- 20 messages) separately from the deployable MendrScript AST, which continues
-- to live in transform_programs / transformation_rules.
-- ============================================================================

CREATE TABLE IF NOT EXISTS analysis_conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_id UUID NOT NULL REFERENCES analysis_results(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES tenants(id),
    session_id VARCHAR(255) NOT NULL UNIQUE,
    last_result JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analysis_conversations_analysis UNIQUE (analysis_id)
);

CREATE INDEX IF NOT EXISTS idx_analysis_conversations_analysis
    ON analysis_conversations(analysis_id);
CREATE INDEX IF NOT EXISTS idx_analysis_conversations_tenant
    ON analysis_conversations(tenant_id);

ALTER TABLE analysis_conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_conversations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON analysis_conversations;
CREATE POLICY tenant_isolation ON analysis_conversations
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE TABLE IF NOT EXISTS analysis_conversation_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES analysis_conversations(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES tenants(id),
    seq INTEGER NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analysis_conversation_messages_seq UNIQUE (conversation_id, seq),
    CONSTRAINT ck_analysis_conversation_messages_role
        CHECK (role IN ('user', 'assistant', 'system'))
);

CREATE INDEX IF NOT EXISTS idx_analysis_conversation_messages_conversation
    ON analysis_conversation_messages(conversation_id, seq DESC);
CREATE INDEX IF NOT EXISTS idx_analysis_conversation_messages_tenant
    ON analysis_conversation_messages(tenant_id);

ALTER TABLE analysis_conversation_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_conversation_messages FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON analysis_conversation_messages;
CREATE POLICY tenant_isolation ON analysis_conversation_messages
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON analysis_conversations TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON analysis_conversation_messages TO app_user;
