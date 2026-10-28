// Run using the pinned ZSP source module, like rehearse-zsp.go.
// All remote errors and ZSP output stay private: they may contain auth URLs.
package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr/nip19"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip46"
)

const (
	reconnectAttempts = 3
	reconnectTimeout  = 45 * time.Second
)

func main() {
	if err := testSigner(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func validSignedEvent(event nostr.Event, publisher string) bool {
	valid, err := event.CheckSignature()
	return err == nil && valid && event.PubKey == publisher && event.ID == event.GetID()
}

func testSigner() error {
	if len(os.Args) != 3 {
		return fmt.Errorf("expected pinned ZSP and fixture APK")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	connection := os.Getenv("SIGN_WITH")
	clientKey := os.Getenv("BUNKER_CLIENT_KEY")
	publisher := os.Getenv("EXPECTED_PUBLISHER")
	u, err := url.Parse(connection)
	if err != nil || u.Scheme != "bunker" || len(u.Host) != 64 {
		return fmt.Errorf("invalid bunker URI")
	}
	if _, err := hex.DecodeString(u.Host); err != nil {
		return fmt.Errorf("invalid bunker transport public key")
	}
	clientPub, err := nostr.GetPublicKey(clientKey)
	if err != nil || len(clientKey) != 64 || len(publisher) != 64 {
		return fmt.Errorf("invalid client key or expected publisher")
	}
	temporary, err := os.MkdirTemp("", "zapstore-signer-test-")
	if err != nil {
		return fmt.Errorf("cannot create private test directory")
	}
	defer os.RemoveAll(temporary)
	// The workflow runs on Linux. Set only a private XDG_CONFIG_HOME; do not
	// change HOME or touch the operator's existing ZSP identities.
	config := filepath.Join(temporary, "config")
	keyPath := filepath.Join(config, "zsp", "bunker-keys", u.Host+".key")
	if err := os.MkdirAll(filepath.Dir(keyPath), 0700); err != nil {
		return fmt.Errorf("cannot prepare client identity")
	}
	if err := os.WriteFile(keyPath, []byte(clientKey+"\n"), 0600); err != nil {
		return fmt.Errorf("cannot restore client identity")
	}
	// ZSP v2 has no offline publish. Exercise its actual bunker/client path with
	// read-only check against an empty loopback catalog and a disposable APK.
	catalog := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		for {
			_, raw, err := conn.Read(ctx)
			if err != nil {
				return
			}
			var parts []json.RawMessage
			if json.Unmarshal(raw, &parts) != nil || len(parts) < 2 {
				return
			}
			var kind, id string
			_ = json.Unmarshal(parts[0], &kind)
			_ = json.Unmarshal(parts[1], &id)
			if kind == "REQ" {
				payload, _ := json.Marshal([]any{"EOSE", id})
				_ = conn.Write(ctx, websocket.MessageText, payload)
			}
			if kind == "EVENT" {
				return
			} // Catalog never accepts public writes.
		}
	}))
	defer catalog.Close()
	fmt.Println("Testing pinned ZSP v2 read-only signer connection; CI client public key:", clientPub)
	cmd := exec.CommandContext(ctx, os.Args[1], "--json", "check", os.Args[2])
	cmd.Dir = temporary
	for _, name := range []string{"PATH", "HOME", "TMPDIR"} {
		if value, ok := os.LookupEnv(name); ok {
			cmd.Env = append(cmd.Env, name+"="+value)
		}
	}
	cmd.Env = append(cmd.Env, "SIGN_WITH="+connection, "XDG_CONFIG_HOME="+config, "RELAYS="+strings.Replace(catalog.URL, "http://", "ws://", 1))
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ZSP v2 read-only check failed; inspect signer audit privately (raw output withheld)")
	}
	var check struct {
		OK        bool   `json:"ok"`
		Operation string `json:"operation"`
		Signer    string `json:"signer"`
	}
	npub, err := nip19.EncodePublicKey(publisher)
	if err != nil || json.Unmarshal(stdout.Bytes(), &check) != nil || !check.OK || check.Operation != "check" || check.Signer != npub {
		return fmt.Errorf("invalid ZSP v2 check identity or document")
	}
	restored, err := os.ReadFile(keyPath)
	if err != nil || strings.TrimSpace(string(restored)) != clientKey {
		return fmt.Errorf("persistent client identity changed")
	}
	fmt.Println("Verified ZSP v2 read-only check and persistent client identity")
	// Reconnect exactly as the publisher does: reuse the persistent client key
	// with the original bunker URI for the second ZSP invocation.
	// A reply can be lost between the relays and a new client process, so bound
	// each attempt and retry with a fresh connection, as publication does.
	var bunker *nip46.BunkerClient
	for attempt := 1; attempt <= reconnectAttempts; attempt++ {
		// The bunker's reply subscription lives on this context, so bound only
		// the connect wait and keep a successful attempt's context open.
		attemptCtx, attemptCancel := context.WithCancel(ctx)
		timer := time.AfterFunc(reconnectTimeout, attemptCancel)
		bunker, err = nip46.ConnectBunker(attemptCtx, clientKey, connection, nil, func(string) {})
		if err == nil && timer.Stop() {
			defer attemptCancel()
			fmt.Printf("Reconnected on attempt %d of %d\n", attempt, reconnectAttempts)
			break
		}
		attemptCancel()
		if err == nil {
			err = fmt.Errorf("connect timed out")
		}
		fmt.Printf("Reconnect attempt %d of %d got no signer reply within %s\n", attempt, reconnectAttempts, reconnectTimeout)
	}
	if err != nil {
		return fmt.Errorf("paired-client reconnect failed (remote details withheld)")
	}
	actualPublisher, err := bunker.GetPublicKey(ctx)
	if err != nil || actualPublisher != publisher {
		return fmt.Errorf("reconnected signer identity does not match White Noise")
	}
	// Test permissions with synthetic, unusable invalid.example software events.
	// They are returned by the signer only and never sent to any catalog relay.
	for _, kind := range []int{3063, 30063, 32267} {
		event := nostr.Event{Kind: kind, PubKey: publisher, CreatedAt: nostr.Now(), Content: "White Noise signer test - never publish",
			Tags: nostr.Tags{{"d", "invalid.example.zspfixture"}, {"i", "invalid.example.zspfixture"}}}
		if err := bunker.SignEvent(ctx, &event); err != nil || !validSignedEvent(event, publisher) {
			return fmt.Errorf("software event signing permission failed (remote details withheld)")
		}
	}
	fmt.Println("Verified synthetic signatures for kinds 3063, 30063, 32267; no publication")
	// Expired auth for a nonexistent fixture hash: proves permission without
	// creating a usable upload authorization. Never transmitted to Blossom.
	auth := nostr.Event{Kind: 24242, PubKey: publisher, CreatedAt: nostr.Now(),
		Content: "White Noise offline signer test - no upload",
		Tags:    nostr.Tags{{"t", "upload"}, {"x", strings.Repeat("0", 64)}, {"expiration", fmt.Sprint(time.Now().Add(-time.Minute).Unix())}}}
	if err := bunker.SignEvent(ctx, &auth); err != nil || !validSignedEvent(auth, publisher) {
		return fmt.Errorf("kind 24242 signing failed (remote details withheld)")
	}
	fmt.Println("Verified kind 24242 signature and reconnect with persistent client and URI")
	fmt.Println("Signer:", publisher)
	fmt.Println("PASS: no files uploaded; no release events published")
	return nil
}
