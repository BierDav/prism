//! The FFI surface from Rust: what the Kotlin bindings call, with the same
//! JSON bodies. shared/'s JVM check runs the same through JNA.

use rocky_prism_ffi::{ReplicaError, open_replica};

#[tokio::test]
async fn writes_read_back_and_survive_reopening() {
    let dir = tempfile::tempdir().expect("temp dir");
    let path = dir.path().join("prism").to_string_lossy().into_owned();

    let replica = open_replica(path.clone(), false).await.expect("open");
    let changes = replica
        .changes(Some(vec!["profiles".into()]))
        .await
        .expect("changes");
    replica
        .put_placement_profile(
            "night".into(),
            r#"{"label":"Night","entries":[{"instanceId":"claudeAgent","model":"claude-1/claude-opus-5-5","reasoning":"high","enabled":true}]}"#.into(),
        )
        .await
        .expect("put");
    let touched = tokio::time::timeout(std::time::Duration::from_secs(10), changes.next())
        .await
        .expect("a change within 10 s")
        .expect("next change");
    assert!(touched.iter().any(|t| t == "profiles"), "{touched:?}");

    let view: serde_json::Value =
        serde_json::from_str(&replica.placement(String::new()).await.expect("view")).expect("JSON");
    let night = view["profiles"]
        .as_array()
        .expect("profiles")
        .iter()
        .find(|p| p["id"] == "night")
        .expect("night profile");
    assert_eq!(night["label"], "Night");
    assert_eq!(night["entries"][0]["model"], "claude-1/claude-opus-5-5");

    assert!(matches!(
        replica
            .put_placement_profile("Bad Id".into(), r#"{"label":"x"}"#.into())
            .await,
        Err(ReplicaError::InvalidInput(_))
    ));
    assert!(matches!(
        replica.set_placement_policy("{".into()).await,
        Err(ReplicaError::Json(_))
    ));

    drop(changes);
    replica.shutdown().await.expect("shutdown");
    assert!(matches!(
        replica.placement(String::new()).await,
        Err(ReplicaError::Closed)
    ));

    let reopened = open_replica(path, false).await.expect("reopen");
    let view = reopened.placement(String::new()).await.expect("view");
    assert!(view.contains("\"night\""), "{view}");
    reopened.shutdown().await.expect("shutdown");
}
