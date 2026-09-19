package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip46"
)

func TestReconnectOmitsConsumedInvitation(t *testing.T) {
	key, clientKey := nostr.GeneratePrivateKey(), nostr.GeneratePrivateKey()
	pub, _ := nostr.GetPublicKey(key)
	clientPub, _ := nostr.GetPublicKey(clientKey)
	signer := nip46.NewStaticKeySigner(key)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	seen := make(chan string, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		sub := ""
		send := func(values ...any) { data, _ := json.Marshal(values); _ = conn.Write(ctx, websocket.MessageText, data) }
		for {
			_, raw, err := conn.Read(ctx)
			if err != nil {
				return
			}
			var parts []json.RawMessage
			if json.Unmarshal(raw, &parts) != nil || len(parts) < 2 {
				return
			}
			var kind string
			_ = json.Unmarshal(parts[0], &kind)
			if kind == "REQ" {
				_ = json.Unmarshal(parts[1], &sub)
				send("EOSE", sub)
			}
			if kind != "EVENT" {
				continue
			}
			var event nostr.Event
			if json.Unmarshal(parts[1], &event) != nil || event.Kind != 24133 || event.PubKey != clientPub {
				return
			}
			req, _, response, err := signer.HandleRequest(ctx, &event)
			if err != nil {
				return
			}
			if req.Method == "connect" {
				invitation := ""
				if len(req.Params) > 1 {
					invitation = req.Params[1]
				}
				seen <- invitation
				if invitation != "" {
					return
				}
			}
			send("OK", event.ID, true, "")
			send("EVENT", sub, response)
		}
	}))
	defer server.Close()
	dir := t.TempDir()
	stub := "#!/bin/sh\n[ \"$1\" = publish ] && [ \"$2\" = --offline ] || exit 99\ncat <<'PAYLOAD'\n"
	for _, kind := range []int{3063, 30063, 32267} {
		event := nostr.Event{Kind: kind, CreatedAt: nostr.Now(), Tags: nostr.Tags{}, Content: "fixture"}
		if err := event.Sign(key); err != nil {
			t.Fatal(err)
		}
		payload, _ := json.Marshal(event)
		stub += string(payload) + "\n"
	}
	stub += "PAYLOAD\n"
	binary := filepath.Join(dir, "zsp")
	if err := os.WriteFile(binary, []byte(stub), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SIGN_WITH", "bunker://"+pub+"?relay="+strings.Replace(server.URL, "http://", "ws://", 1)+"&secret=original-invitation")
	t.Setenv("BUNKER_CLIENT_KEY", clientKey)
	t.Setenv("EXPECTED_PUBLISHER", pub)
	before := os.Args
	os.Args = []string{"test", binary, filepath.Join(dir, "fixture.apk")}
	t.Cleanup(func() { os.Args = before })
	if err := testSigner(); err != nil {
		t.Fatal(err)
	}
	if <-seen != "" {
		t.Fatal("reconnect reused consumed invitation")
	}
}

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
