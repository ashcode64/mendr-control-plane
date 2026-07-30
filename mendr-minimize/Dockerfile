FROM rust:1.83-bookworm AS builder
WORKDIR /app
COPY Cargo.toml Cargo.lock ./
# Cache deps with empty main (locked resolve)
RUN mkdir src && echo "fn main() {}" > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src
COPY src ./src
COPY fixtures ./fixtures
RUN touch src/main.rs && cargo build --release --locked

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/mendr-minimize /usr/local/bin/mendr-minimize
ENV MENDR_MINIMIZE_BIND=0.0.0.0:8099
EXPOSE 8099
HEALTHCHECK --interval=15s --timeout=5s --retries=5 CMD curl -fsS http://127.0.0.1:8099/health || exit 1
CMD ["mendr-minimize"]
