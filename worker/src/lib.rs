//! The prism worker as a library: the binary (`main.rs`) serves Prism, and
//! the apps' replica (`rocky-prism-ffi`) runs the same request handling with
//! a device's own T3 sessions when the workers are unreachable (`direct`).

pub mod activate;
pub mod admin;
pub mod agents;
pub mod android;
pub mod android_settings;
pub mod approvals;
pub mod auth;
pub mod config;
pub mod direct;
pub mod health;
pub mod http;
pub mod leader;
pub mod lease;
pub mod machines;
pub mod magic;
pub mod metrics;
pub mod pairing;
pub mod placement_log;
pub mod placement_stream;
pub mod provision;
pub mod push;
pub mod questions;
pub mod rocky_route;
pub mod rocky_status;
pub mod t3;
pub mod telemetry;
#[cfg(test)]
mod testutil;
pub mod usage;
