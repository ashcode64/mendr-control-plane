use serde::{Deserialize, Serialize};
use serde_json::Value;

/// MendrScript program — mirrors mendrscript/v1 JSON shape.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Program {
    #[serde(default = "default_schema", alias = "schema_version")]
    pub schema_version: String,
    #[serde(default)]
    pub ops: Vec<Op>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rationale: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        alias = "bandit_category"
    )]
    pub bandit_category: Option<String>,
}

fn default_schema() -> String {
    "mendrscript/v1".into()
}

impl Program {
    pub fn with_ops(&self, ops: Vec<Op>) -> Self {
        Self {
            schema_version: self.schema_version.clone(),
            ops,
            rationale: self.rationale.clone(),
            bandit_category: self.bandit_category.clone(),
        }
    }

    pub fn op_count(&self) -> usize {
        self.ops.len()
    }
}

/// Opaque op object — preserve all JSON fields; discriminate on `op`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Op {
    pub op: String,
    #[serde(flatten)]
    pub fields: serde_json::Map<String, Value>,
}

impl Op {
    pub fn get_str(&self, key: &str) -> Option<&str> {
        self.fields.get(key).and_then(|v| v.as_str())
    }

    pub fn path_args(&self) -> Vec<String> {
        let mut out = Vec::new();
        for k in ["path", "from", "to", "key"] {
            if let Some(s) = self.get_str(k) {
                out.push(s.to_string());
            }
        }
        out
    }

    pub fn value_mutating(&self) -> bool {
        matches!(
            self.op.as_str(),
            "coerce" | "scale" | "arith" | "map_value" | "reformat_date" | "string" | "coalesce"
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MinimizeRequest {
    pub program: Program,
    #[serde(default)]
    pub cases: Vec<Case>,
    #[serde(default)]
    pub triggering_payload: Option<Value>,
    #[serde(default)]
    pub spec_trust: Option<f64>,
    #[serde(default)]
    pub allowed_opcodes: Option<Vec<String>>,
    /// High threshold for schema-gated coerce removal (twin gate 1).
    #[serde(default = "default_spec_trust_gate")]
    pub spec_trust_gate: f64,
    #[serde(default = "default_prove_gate")]
    pub prove_minimal_max_ops: usize,
    /// Declared OpenAPI/contract types keyed by JSON pointer — used only when twin gates pass.
    #[serde(default)]
    pub declared_field_types: Option<std::collections::HashMap<String, String>>,
    /// Explicit uninspectable JSON pointers (oneOf/anyOf / polymorphic). Necessity never
    /// drops ops that touch these; path segments containing `oneOf`/`anyOf` are also treated
    /// as unresolvable (parity with Java DdminOracleService).
    #[serde(default)]
    pub unresolvable_paths: Option<Vec<String>>,
}

fn default_spec_trust_gate() -> f64 {
    0.85
}
fn default_prove_gate() -> usize {
    8
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Case {
    pub input: Value,
    #[serde(default)]
    pub expected: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MinimizeResponse {
    pub program: Program,
    pub minimized: bool,
    pub layers_applied: Vec<String>,
    pub original_op_count: usize,
    pub final_op_count: usize,
    pub fell_back: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub draft_program: Option<Program>,
    pub engine: String,
}
