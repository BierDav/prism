# The prism worker (worker/) and the `prism` CLI (cli/), whose admin
# commands (pair, machines, device-token, device-revoke) run in the worker's
# pod: `kubectl exec prism-worker-0 -- /prism machines`. Built natively per
# architecture: CI runs this once on an x64 runner and once on an arm64
# runner. The context is the
# repository root, where the cargo workspace is.
FROM docker.io/library/rust:1.99-trixie@sha256:8a4e90c7946b74626488b1446f712c0fe0ed1a596cbd3d871684bc9c5433ae07 AS chef
# Rocky's bundled SQLite generates its bindings with bindgen.
RUN apt-get update && apt-get install -y --no-install-recommends libclang-dev clang \
    && rm -rf /var/lib/apt/lists/*
RUN cargo install cargo-chef --locked --version 0.1.77
WORKDIR /src
ENV LIBSQLITE3_SYS_USE_PKG_CONFIG=0
# The runners have about 14 GB of disk: no debug info in the tests' build.
ENV CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0

# The dependencies only (recipe.json: the manifests and the lockfile), so the
# layer that compiles them, Rocky and SQLite included, comes from CI's
# registry cache until Cargo.toml or Cargo.lock change.
FROM chef AS plan
# .dockerignore keeps the apps and build output out.
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM chef AS build
COPY --from=plan /src/recipe.json recipe.json
RUN cargo chef cook --locked --workspace --tests --recipe-path recipe.json \
    && cargo chef cook --locked --release -p prism-worker -p prism-cli --recipe-path recipe.json
COPY . .
RUN cargo test --locked --workspace \
    && cargo build --locked --release -p prism-worker -p prism-cli \
    && install -D target/release/prism-worker /out/prism-worker \
    && install -D target/release/prism /out/prism

FROM gcr.io/distroless/cc-debian13:nonroot@sha256:cb87acc574abe719b6e5c547384defa2f7b1f24af9605d3f618cf852495d8be2
COPY --from=build /out/prism-worker /prism-worker
COPY --from=build /out/prism /prism
EXPOSE 7411 8080
ENTRYPOINT ["/prism-worker"]
