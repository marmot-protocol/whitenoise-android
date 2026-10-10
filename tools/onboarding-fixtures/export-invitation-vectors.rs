#[tokio::test]
async fn export_android_3137_vectors() {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let (directory, app, accounts, _) = discovery_fixture(4).await;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let outbox = "ws://127.0.0.1:44112";
    let inbox = "ws://127.0.0.1:44111";
    let mut cases = Vec::new();
    for (i, name) in ["legacy", "incompatible", "expired", "replacement"]
        .iter()
        .enumerate()
    {
        let account = &accounts[i];
        let keys = app
            .account_home()
            .load_signing_keys(&account.label)
            .unwrap();
        let package = match *name {
            "legacy" => fresh_key_package_for_account(&app, account, true).await,
            "incompatible" => {
                fresh_key_package_with_components(
                    &app,
                    account,
                    false,
                    app.supported_app_component_ids()
                        .into_iter()
                        .filter(|id| *id != GROUP_ENCRYPTED_MEDIA_V2_COMPONENT_ID)
                        .collect(),
                )
                .await
            }
            "expired" => package_expiring_at(&app, account, now + 15),
            _ => fresh_key_package_for_account(&app, account, false).await,
        };
        let package_event = member_resolution_key_package_event(account, package);
        let sign = |kind: u16, tags: Vec<Vec<String>>, content: String, at: u64| {
            EventBuilder::new(Kind::from(kind), content)
                .tags(tags.into_iter().map(|tag| Tag::parse(tag).unwrap()))
                .custom_created_at(NostrTimestamp::from_secs(at))
                .finalize(&keys)
                .unwrap()
        };
        let mut packages = vec![
            serde_json::to_value(sign(
                30443,
                package_event.tags.clone(),
                package_event.content,
                now - 2,
            ))
            .unwrap(),
        ];
        if *name == "replacement" {
            packages.push(
                serde_json::to_value(sign(
                    30443,
                    package_event.tags,
                    "not a KeyPackage".into(),
                    now - 1,
                ))
                .unwrap(),
            );
        }
        let nip65 = sign(
            10002,
            vec![vec!["r".into(), outbox.into(), "write".into()]],
            "".into(),
            now - 2,
        );
        let inbox_event = sign(
            10050,
            vec![vec!["relay".into(), inbox.into()]],
            "".into(),
            now - 2,
        );
        cases.push(
            serde_json::json!({"name": name, "account": account.account_id_hex,
            "packages": packages, "relays": [nip65, inbox_event]}),
        );
    }
    let fixture = serde_json::json!({"mdk": "d4e91c8d90293de1a664a950a134f747dbb197a8", "generatedAt": now,
        "expiredAfter": now + 16, "cases": cases});
    let path = std::env::var("ANDROID_VECTOR_OUTPUT").unwrap();
    let mut out = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .unwrap();
    out.write_all(serde_json::to_string_pretty(&fixture).unwrap().as_bytes())
        .unwrap();
    drop(directory);
}
