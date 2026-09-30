// Run using the pinned ZSP source module, like rehearse-zsp.go.
// All remote errors and ZSP output stay private: they may contain auth URLs.
package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
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
	listing := fmt.Sprintf("release_source: %q\nname: Offline signer fixture\nsummary: Signing test only - never publish\n", os.Args[2])
	if err := os.WriteFile(filepath.Join(temporary, "zapstore.yaml"), []byte(listing), 0600); err != nil {
		return fmt.Errorf("cannot create fixture listing")
	}
	fmt.Println("Testing pinned ZSP offline signing; CI client public key:", clientPub)
	cmd := exec.CommandContext(ctx, os.Args[1], "publish", "--offline", "--quiet", "--skip-metadata", "--no-compress", "--skip-certificate-linking", "zapstore.yaml")
	cmd.Dir = temporary
	for _, name := range []string{"PATH", "HOME", "TMPDIR"} {
		if value, ok := os.LookupEnv(name); ok {
			cmd.Env = append(cmd.Env, name+"="+value)
		}
	}
	cmd.Env = append(cmd.Env, "SIGN_WITH="+connection, "XDG_CONFIG_HOME="+config)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("offline ZSP signing failed; check Keycast connection/permission audit (raw output withheld)")
	}
	kinds := map[int]int{}
	for _, line := range bytes.Split(bytes.TrimSpace(stdout.Bytes()), []byte("\n")) {
		// ZSP may print a connection-approval URL before its JSONL events.
		if !bytes.HasPrefix(bytes.TrimSpace(line), []byte("{")) {
			continue
		}
		var event nostr.Event
		if json.Unmarshal(line, &event) != nil || !validSignedEvent(event, publisher) {
			return fmt.Errorf("invalid offline event identity, ID, or signature")
		}
		kinds[event.Kind]++
	}
	if len(kinds) != 3 || kinds[3063] != 1 || kinds[30063] != 1 || kinds[32267] != 1 {
		return fmt.Errorf("offline signer did not return exactly the three expected event kinds")
	}
	restored, err := os.ReadFile(keyPath)
	if err != nil || strings.TrimSpace(string(restored)) != clientKey {
		return fmt.Errorf("persistent client identity changed")
	}
	fmt.Println("Verified offline signatures for kinds 3063, 30063, 32267")
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
