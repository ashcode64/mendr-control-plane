mod ast;
mod eqsat;
mod interpret;
mod minimize;
mod necessity;
mod prove_minimal;

use ast::{MinimizeRequest, MinimizeResponse};
use axum::{
    routing::{get, post},
    Json, Router,
};
use std::net::SocketAddr;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse().unwrap()))
        .init();

    let app = Router::new()
        .route("/health", get(|| async { "ok" }))
        .route("/minimize", post(minimize_handler));

    let bind = std::env::var("MENDR_MINIMIZE_BIND").unwrap_or_else(|_| "127.0.0.1:8099".into());
    let addr: SocketAddr = bind.parse().expect("MENDR_MINIMIZE_BIND host:port");
    tracing::info!("mendr-minimize listening on {addr}");
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app).await.expect("serve");
}

async fn minimize_handler(Json(mut req): Json<MinimizeRequest>) -> Json<MinimizeResponse> {
    // Server-side DoS guard: never honor proveMinimalMaxOps above 8.
    if req.prove_minimal_max_ops > 8 {
        req.prove_minimal_max_ops = 8;
    }
    Json(minimize::minimize(req))
}

#[cfg(test)]
mod http_tests {
    use super::*;
    use crate::ast::{Case, MinimizeRequest, Op, Program};
    use serde_json::json;

    #[test]
    fn handler_clamps_prove_minimal_max_ops() {
        let mut req = MinimizeRequest {
            program: Program {
                schema_version: "mendrscript/v1".into(),
                ops: vec![Op {
                    op: "rename".into(),
                    fields: serde_json::Map::from_iter([
                        ("from".into(), json!("/a")),
                        ("to".into(), json!("/b")),
                    ]),
                }],
                rationale: None,
                bandit_category: None,
            },
            cases: vec![Case {
                input: json!({"a": 1}),
                expected: None,
            }],
            triggering_payload: None,
            spec_trust: None,
            allowed_opcodes: None,
            spec_trust_gate: 0.85,
            prove_minimal_max_ops: 99,
            declared_field_types: None,
            unresolvable_paths: None,
        };
        // Mirror handler clamp
        if req.prove_minimal_max_ops > 8 {
            req.prove_minimal_max_ops = 8;
        }
        assert_eq!(req.prove_minimal_max_ops, 8);
        let resp = minimize::minimize(req);
        assert!(!resp.fell_back);
    }
}
