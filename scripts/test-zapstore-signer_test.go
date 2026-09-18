package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func TestSignedEventRejectsTampering(t *testing.T) {
	key := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(key)
	event := nostr.Event{Kind: 3063, CreatedAt: nostr.Now(), Tags: nostr.Tags{}, Content: "fixture"}
	if err := event.Sign(key); err != nil {
		t.Fatal(err)
	}
	if !validSignedEvent(event, pub) {
		t.Fatal("valid event rejected")
	}
	for _, mutate := range []func(*nostr.Event){
		func(e *nostr.Event) { e.Content = "altered" },
		func(e *nostr.Event) { e.ID = strings.Repeat("0", 64) },
		func(e *nostr.Event) { e.Sig = strings.Repeat("0", 128) },
	} {
		changed := event
		mutate(&changed)
		if validSignedEvent(changed, pub) {
			t.Fatal("tampered event accepted")
		}
	}
	if validSignedEvent(event, strings.Repeat("0", 64)) {
		t.Fatal("wrong publisher accepted")
	}
}

func TestOfflineFailuresDoNotLeakOrReconnect(t *testing.T) {
	key := nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(key)
	event := nostr.Event{Kind: 3063, CreatedAt: nostr.Now(), Tags: nostr.Tags{}, Content: "fixture"}
	if err := event.Sign(key); err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal(event)
	for _, tc := range []struct {
		name, output, want string
		exit               int
	}{
		{"remote error", "private-auth-URL-do-not-print", "offline ZSP signing failed", 1},
		{"missing kinds", string(payload), "exactly the three expected", 0},
		{"malformed event", "{not-json", "invalid offline event", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			// If the harness ever drops --offline, this stub rejects it.
			stub := "#!/bin/sh\n[ \"$1\" = publish ] && [ \"$2\" = --offline ] || exit 99\n" +
				"cat <<'PAYLOAD'\n" + tc.output + "\nPAYLOAD\n"
			if tc.exit != 0 {
				stub += "exit 1\n"
			}
			binary := filepath.Join(dir, "zsp")
			if err := os.WriteFile(binary, []byte(stub), 0700); err != nil {
				t.Fatal(err)
			}
			t.Setenv("SIGN_WITH", "bunker://"+pub+"?relay=ws://127.0.0.1:1&secret=private-auth-URL-do-not-print")
			t.Setenv("BUNKER_CLIENT_KEY", key)
			t.Setenv("EXPECTED_PUBLISHER", pub)
			before := os.Args
			os.Args = []string{"test", binary, filepath.Join(dir, "fixture.apk")}
			t.Cleanup(func() { os.Args = before })
			err := testSigner()
			if err == nil || !strings.Contains(err.Error(), tc.want) || strings.Contains(err.Error(), "private-auth") {
				t.Fatalf("unexpected or leaking error: %v", err)
			}
		})
	}
}
