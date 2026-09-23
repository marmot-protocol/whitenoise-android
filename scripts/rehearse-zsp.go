// Run from the checked-out ZSP v0.4.17 source directory to use its pinned Go
// dependencies: go run /path/to/rehearse-zsp.go /path/to/zsp /path/to/fixture.apk
// This only invokes --offline, uses disposable keys and a loopback NIP-46
// signer, and rejects any release event sent to its relay.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip46"
)

func main() {
	if err := rehearse(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func rehearse() error {
	if len(os.Args) != 3 {
		return fmt.Errorf("usage: rehearse-zsp.go PINNED_ZSP DISPOSABLE_APK")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	binary, _ := filepath.Abs(os.Args[1])
	apk, _ := filepath.Abs(os.Args[2])
	version, err := exec.CommandContext(ctx, binary, "--version").Output()
	if err != nil || !strings.Contains("\n"+string(version), "\nv0.4.17\n") {
		return fmt.Errorf("expected pinned v0.4.17: %q (%v)", version, err)
	}
	signerKey, clientKey := nostr.GeneratePrivateKey(), nostr.GeneratePrivateKey()
	signerPub, _ := nostr.GetPublicKey(signerKey)
	clientPub, _ := nostr.GetPublicKey(clientKey)
	signer := nip46.NewStaticKeySigner(signerKey)
	signer.AuthorizeRequest = func(_ bool, from, _ string) bool { return from == clientPub }
	config, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	keyPath := filepath.Join(config, "zsp", "bunker-keys", signerPub+".key")
	if err := os.MkdirAll(filepath.Dir(keyPath), 0700); err != nil {
		return err
	}
	key, err := os.OpenFile(keyPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer os.Remove(keyPath)
	_, writeErr := key.WriteString(clientKey + "\n")
	key.Close()
	if writeErr != nil {
		return writeErr
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	var mu sync.Mutex
	peers := map[*websocket.Conn]string{}
	requests, forbidden := 0, 0
	server := &http.Server{ReadHeaderTimeout: 5 * time.Second}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.CloseNow()
		defer func() { mu.Lock(); delete(peers, conn); mu.Unlock() }()
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
			json.Unmarshal(parts[0], &kind)
			mu.Lock()
			send := func(c *websocket.Conn, values ...any) {
				payload, _ := json.Marshal(values)
				c.Write(ctx, websocket.MessageText, payload)
			}
			switch kind {
			case "REQ":
				var sub string
				json.Unmarshal(parts[1], &sub)
				peers[conn] = sub
				send(conn, "EOSE", sub)
			case "EVENT":
				var event nostr.Event
				json.Unmarshal(parts[1], &event)
				valid, _ := event.CheckSignature()
				if event.Kind != 24133 || event.PubKey != clientPub || !valid {
					forbidden++
					send(conn, "OK", event.ID, false, "fixture only accepts its preseeded NIP-46 client")
				} else {
					_, _, response, err := signer.HandleRequest(ctx, &event)
					if err == nil {
						requests++
						send(conn, "OK", event.ID, true, "")
						for peer, sub := range peers {
							send(peer, "EVENT", sub, response)
						}
					}
				}
			}
			mu.Unlock()
		}
	})
	go server.Serve(listener)
	defer server.Close()
	temporary, err := os.MkdirTemp("", "zsp-offline-listing-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temporary)
	listing := fmt.Sprintf("release_source: %q\nname: Offline signer fixture\nsummary: Disposable local test\n", apk)
	if err := os.WriteFile(filepath.Join(temporary, "zapstore.yaml"), []byte(listing), 0600); err != nil {
		return err
	}
	cmd := exec.CommandContext(ctx, binary, "publish", "--offline", "--quiet", "--skip-metadata", "--no-compress", "--skip-certificate-linking", "--commit", strings.Repeat("a", 40), "zapstore.yaml")
	cmd.Dir = temporary
	// Pass no inherited store/signing credentials; HOME is read only so ZSP and
	// this harness agree on the platform config path. The unique key is removed.
	for _, name := range []string{"PATH", "HOME", "XDG_CONFIG_HOME", "TMPDIR"} {
		if value, ok := os.LookupEnv(name); ok {
			cmd.Env = append(cmd.Env, name+"="+value)
		}
	}
	cmd.Env = append(cmd.Env, "SIGN_WITH=bunker://"+signerPub+"?relay=ws://"+listener.Addr().String())
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("offline ZSP failed: %w\n%s", err, stderr.String())
	}
	kinds := map[int]int{}
	for _, line := range bytes.Split(bytes.TrimSpace(stdout.Bytes()), []byte("\n")) {
		var event nostr.Event
		if err := json.Unmarshal(line, &event); err != nil {
			return fmt.Errorf("non-JSON stdout: %w", err)
		}
		valid, err := event.CheckSignature()
		if err != nil || !valid || event.PubKey != signerPub {
			return fmt.Errorf("invalid event identity/signature")
		}
		kinds[event.Kind]++
	}
	restored, err := os.ReadFile(keyPath)
	if err != nil || strings.TrimSpace(string(restored)) != clientKey {
		return fmt.Errorf("preseeded key changed")
	}
	mu.Lock()
	defer mu.Unlock()
	if kinds[32267] != 1 || kinds[30063] != 1 || kinds[3063] != 1 || len(kinds) != 3 || requests < 3 || forbidden != 0 {
		return fmt.Errorf("unexpected offline contract: kinds=%v requests=%d forbidden=%d", kinds, requests, forbidden)
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{
		"version": "v0.4.17", "eventKinds": kinds,
		"signerAuthor": signerPub, "preseededClientRestored": true,
		"nip46Requests": requests, "publicEventsSent": forbidden,
		"signerRelay": "loopback only", "keyDirectory": "os.UserConfigDir()/zsp/bunker-keys",
	})
}
