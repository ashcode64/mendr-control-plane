# Mendr Control Plane

This repository contains the cloud/on-prem control-plane services for Mendr:

- `api-gateway`
- `ai-analysis-service`
- `rule-engine`
- `notification-service`
- `frontend`
- supporting infrastructure: PostgreSQL, Redis, Zookeeper, Kafka

## Purpose

The control plane owns:

- service registration and contracts (including optional `allowedCallerOrigins` per service)
- failure ingestion and response validation
- AI analysis
- rule approval and deployment
- dashboard UI

The separate `mendr-data-plane` repository should be deployed at the customer edge and forwards registration calls here while keeping proxy traffic local.

### Service registration CORS

When registering a service, include optional `allowedCallerOrigins` in the JSON body:

```json
{
  "name": "payment-service",
  "baseUrl": "http://localhost:8091",
  "allowedCallerOrigins": ["http://localhost:8090"]
}
```

The control plane stores this on the service record and syncs it into `cors_rules`. The edge data plane enforces it from route snapshots (no per-request control-plane call).

## Run

```powershell
docker compose up -d --build
```

## Required environment

- `ANTHROPIC_API_KEY`
- `GATEWAY_INTERNAL_API_KEY` for trusted edge/control-plane calls

## Ports

- `3000` dashboard
- `8095` api-gateway
- `8082` ai-analysis-service
- `8083` notification-service
- `8084` rule-engine
