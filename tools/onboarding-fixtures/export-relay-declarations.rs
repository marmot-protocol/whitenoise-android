// Append to the pinned MDK's disposable crates/marmot-app/src/tests/invite_recovery.rs.
#[test]
fn export_android_mixed_relay_declarations() {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let keys = nostr::prelude::Keys::new(
        nostr::prelude::SecretKey::from_hex(
            "0000000000000000000000000000000000000000000000000000000000000001",
        )
        .unwrap(),
    );
    let endpoint = "ws://127.0.0.1:44202";
    let sign = |kind: u16, tags: Vec<Vec<&str>>| {
        EventBuilder::new(Kind::from(kind), "preserved opaque content")
            .tags(tags.into_iter().map(|tag| Tag::parse(tag).unwrap()))
            .finalize(&keys)
            .unwrap()
    };
    let outbox = sign(
        10002,
        vec![
            vec!["r", endpoint, "read", "extension"],
            vec!["r", "wss://relay.nostr.band", "write"],
            vec!["x-fixture", "preserve"],
            vec!["r", endpoint, "write"],
            vec!["r", endpoint, "read", "duplicate"],
        ],
    );
    let inbox = sign(
        10050,
        vec![
            vec!["relay", endpoint, "extension"],
            vec!["relay", "wss://relay.nostr.band"],
            vec!["x-fixture", "preserve"],
            vec!["relay", endpoint, "duplicate"],
        ],
    );
    let value = serde_json::json!({
        "mdk": "d4e91c8d90293de1a664a950a134f747dbb197a8",
        "events": [outbox, inbox],
    });
    let path = std::env::var("ANDROID_RELAY_VECTOR_OUTPUT").unwrap();
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .unwrap();
    file.write_all(serde_json::to_string_pretty(&value).unwrap().as_bytes())
        .unwrap();
}
