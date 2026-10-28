// Exercise the checksummed ZSP v2 binary against disposable loopback services.
// The NIP-46 relay rejects software events; a separate fixture catalog and Blossom
// accept only this synthetic APK. No production credentials or services are used.
package main

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip19"
	"github.com/nbd-wtf/go-nostr/nip46"
)

func main() {
	if err := rehearse(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// The disposable publisher needs only a few replies. Keep replay bounded even if
// a faulty client sends requests repeatedly until the rehearsal deadline.
const offlineSignerResponseLimit = 16

type offlineSignerSubscription struct {
	id      string
	filters nostr.Filters
}

// offlineSignerRelay retains this disposable client's recent replies so publishing
// before subscription cannot lose the connect response and consume the deadline.
type offlineSignerRelay struct {
	ctx       context.Context
	signer    *nip46.StaticKeySigner
	clientPub string
	mu        sync.Mutex
	peers     map[*websocket.Conn]offlineSignerSubscription
	responses []nostr.Event
	requests  int
	forbidden int
}

// newOfflineSignerRelay creates one isolated, deadline-bound rehearsal relay.
func newOfflineSignerRelay(ctx context.Context, signer *nip46.StaticKeySigner, clientPub string) *offlineSignerRelay {
	return &offlineSignerRelay{
		ctx: ctx, signer: signer, clientPub: clientPub,
		peers: map[*websocket.Conn]offlineSignerSubscription{},
	}
}

// rememberResponse runs under mu and never keeps more than the fixed reply budget.
func (relay *offlineSignerRelay) rememberResponse(response nostr.Event) {
	if len(relay.responses) == offlineSignerResponseLimit {
		copy(relay.responses, relay.responses[1:])
		relay.responses[len(relay.responses)-1] = response
		return
	}
	relay.responses = append(relay.responses, response)
}

// diagnostics reports only bounded counters, never signer identities or event contents.
func (relay *offlineSignerRelay) diagnostics() string {
	if !relay.mu.TryLock() {
		return "relay_counters=busy"
	}
	defer relay.mu.Unlock()
	return fmt.Sprintf("relay_requests=%d relay_forbidden=%d relay_subscribers=%d relay_responses=%d", relay.requests, relay.forbidden, len(relay.peers), len(relay.responses))
}

// publishFailure preserves the command error and exposes only bounded diagnostic state.
func (relay *offlineSignerRelay) publishFailure(ctx context.Context, err error, started, publishStarted time.Time, stderr string) error {
	return fmt.Errorf("loopback ZSP failed: %w; phase=publish elapsed=%s total_elapsed=%s context=%v %s\n%s", err, time.Since(publishStarted), time.Since(started), ctx.Err(), relay.diagnostics(), stderr)
}

// ServeHTTP serializes request handling and reply replay across connection orderings.
func (relay *offlineSignerRelay) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	defer func() { relay.mu.Lock(); delete(relay.peers, conn); relay.mu.Unlock() }()
	for {
		_, raw, err := conn.Read(relay.ctx)
		if err != nil {
			return
		}
		var parts []json.RawMessage
		if json.Unmarshal(raw, &parts) != nil || len(parts) < 2 {
			return
		}
		var kind string
		json.Unmarshal(parts[0], &kind)
		relay.mu.Lock()
		send := func(c *websocket.Conn, values ...any) {
			payload, _ := json.Marshal(values)
			c.Write(relay.ctx, websocket.MessageText, payload)
		}
		switch kind {
		case "REQ":
			var sub string
			var filters nostr.Filters
			valid := len(parts) >= 3 && len(parts) <= 18 && json.Unmarshal(parts[1], &sub) == nil && sub != "" && len(sub) <= 64
			if !valid {
				break
			}
			for _, rawFilter := range parts[2:] {
				var filter nostr.Filter
				if len(rawFilter) == 0 || rawFilter[0] != '{' || json.Unmarshal(rawFilter, &filter) != nil {
					valid = false
					break
				}
				filters = append(filters, filter)
			}
			if !valid {
				break
			}
			// This fixture has one active reply subscription per client socket.
			// Replacing it discards the previous ID and predicate together.
			relay.peers[conn] = offlineSignerSubscription{id: sub, filters: filters}
			for _, response := range relay.responses {
				if filters.Match(&response) {
					send(conn, "EVENT", sub, response)
				}
			}
			send(conn, "EOSE", sub)
		case "CLOSE":
			var sub string
			if len(parts) == 2 && json.Unmarshal(parts[1], &sub) == nil && relay.peers[conn].id == sub {
				delete(relay.peers, conn)
			}
		case "EVENT":
			var event nostr.Event
			json.Unmarshal(parts[1], &event)
			valid, _ := event.CheckSignature()
			if event.Kind != 24133 || event.PubKey != relay.clientPub || !valid {
				relay.forbidden++
				send(conn, "OK", event.ID, false, "fixture only accepts its preseeded NIP-46 client")
			} else {
				_, _, response, err := relay.signer.HandleRequest(relay.ctx, &event)
				if err == nil {
					relay.requests++
					relay.rememberResponse(response)
					send(conn, "OK", event.ID, true, "")
					for peer, sub := range relay.peers {
						if sub.filters.Match(&response) {
							send(peer, "EVENT", sub.id, response)
						}
					}
				}
			}
		}
		relay.mu.Unlock()
	}
}

func rehearse() error {
	if len(os.Args) != 6 {
		return fmt.Errorf("usage: rehearse-zsp.go PINNED_ZSP DISPOSABLE_APK FIXTURE_KEY_PEM FIXTURE_CERT_DER READBACK_TOOL")
	}
	started := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Second)
	defer cancel()
	binary, _ := filepath.Abs(os.Args[1])
	apk, _ := filepath.Abs(os.Args[2])
	version, err := exec.CommandContext(ctx, binary, "--version").Output()
	if err != nil || !strings.Contains("\n"+string(version), "\nv2.0.0\n") {
		return fmt.Errorf("expected pinned v2.0.0: %q (%v); phase=version elapsed=%s context=%v", version, err, time.Since(started), ctx.Err())
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
	// ZSP v2 special-cases exactly 127.0.0.1/localhost/::1 with a built-in
	// local client key. Use another Linux loopback address to exercise the actual
	// persistent CI identity path without contacting an external service.
	listener, err := net.Listen("tcp", "127.0.0.2:0")
	if err != nil {
		return err
	}
	defer listener.Close()
	relay := newOfflineSignerRelay(ctx, &signer, clientPub)
	server := &http.Server{ReadHeaderTimeout: 5 * time.Second, Handler: relay}
	go server.Serve(listener)
	defer server.Close()
	temporary, err := os.MkdirTemp("", "zsp-offline-listing-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(temporary)
	catalog, blob, public, err := fixtureServices(ctx, apk, os.Args[3], os.Args[4], signerKey)
	if err != nil {
		return err
	}
	defer catalog.Close()
	defer blob.Close()
	listing := fmt.Sprintf("release_source: %q\nmetadata_sources: []\nname: Publisher fixture\nsummary: Disposable local test\n", apk)
	if err := os.WriteFile(filepath.Join(temporary, "zapstore.yaml"), []byte(listing), 0600); err != nil {
		return err
	}
	cmd := exec.CommandContext(ctx, binary, "--json", "publish", "--quiet", "--skip-metadata", "--no-compress", "--channel", "main", "--apk-hash", public.hash, "--commit", strings.Repeat("a", 40), "zapstore.yaml")
	cmd.Dir = temporary
	// Pass no inherited store/signing credentials; HOME is read only so ZSP and
	// this harness agree on the platform config path. The unique key is removed.
	for _, name := range []string{"PATH", "HOME", "XDG_CONFIG_HOME", "TMPDIR"} {
		if value, ok := os.LookupEnv(name); ok {
			cmd.Env = append(cmd.Env, name+"="+value)
		}
	}
	cmd.Env = append(cmd.Env, "SIGN_WITH=bunker://"+signerPub+"?relay=ws://"+listener.Addr().String(), "RELAYS="+strings.Replace(catalog.URL, "http://", "ws://", 1), "BLOSSOM_URL="+blob.URL)
	// A rejected selection cannot publish. Verify the pinned CLI's error stream
	// as well as its successful JSON contract so mocks cannot hide this boundary.
	rejected := exec.CommandContext(ctx, binary, "--json", "check", "--apk-hash", strings.Repeat("0", 64), "zapstore.yaml")
	rejected.Dir, rejected.Env = cmd.Dir, cmd.Env
	var rejectedOut, rejectedErr bytes.Buffer
	rejected.Stdout, rejected.Stderr = &rejectedOut, &rejectedErr
	rejectedStatus := rejected.Run()
	var failure struct {
		OK    bool `json:"ok"`
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	if rejectedStatus == nil || rejectedOut.Len() != 0 || json.Unmarshal(rejectedErr.Bytes(), &failure) != nil || failure.OK || failure.Error.Code != "apk_not_checked" {
		return fmt.Errorf("real v2 error document did not use stderr with apk_not_checked")
	}
	// Exercise the actual production decision gate with the same persisted
	// NIP-46 client, proof and catalog, before invoking the publication flags.
	check := exec.CommandContext(ctx, binary, "--json", "check", "--channel", "main", "zapstore.yaml")
	check.Dir, check.Env = cmd.Dir, cmd.Env
	checked, err := check.Output()
	if err != nil {
		return fmt.Errorf("real v2 read-only preflight failed: %w", err)
	}
	var preflight struct {
		OK         bool   `json:"ok"`
		Operation  string `json:"operation"`
		Signer     string `json:"signer"`
		Authorized bool   `json:"authorized"`
		Proof      struct {
			Owner string `json:"owner"`
		} `json:"proof"`
		Published int64 `json:"published_version_code"`
		APK       struct {
			App     string `json:"app_id"`
			Version string `json:"version_name"`
			Code    int64  `json:"version_code"`
			Hash    string `json:"hash"`
			Cert    string `json:"certificate_hash"`
		} `json:"apk"`
	}
	npub, _ := nip19.EncodePublicKey(signerPub)
	if json.Unmarshal(checked, &preflight) != nil || !preflight.OK || preflight.Operation != "check" ||
		preflight.Signer != npub || !preflight.Authorized || preflight.Proof.Owner != npub || preflight.Published != 0 ||
		preflight.APK.App != "invalid.example.zspfixture" || preflight.APK.Version != "2026.9.9" || preflight.APK.Code != 1 ||
		preflight.APK.Hash != public.hash || preflight.APK.Cert != public.cert {
		return fmt.Errorf("real v2 preflight identity, authorization or published version mismatch")
	}
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	publishStarted := time.Now()
	if err := cmd.Run(); err != nil {
		return relay.publishFailure(ctx, err, started, publishStarted, stderr.String())
	}
	var document struct {
		OK        bool   `json:"ok"`
		Operation string `json:"operation"`
		ID        string `json:"id"`
		Status    string `json:"status"`
		App       string `json:"app_id"`
		Version   string `json:"version_name"`
		Code      int    `json:"version_code"`
	}
	if json.Unmarshal(stdout.Bytes(), &document) != nil || !document.OK || document.Operation != "publish" || document.Status != "pending" || document.App != "invalid.example.zspfixture" || document.Version != "2026.9.9" || document.Code != 1 {
		return fmt.Errorf("invalid v2 publication document: %s", stdout.String())
	}
	kinds, err := public.verify(document.ID, signerPub, blob.URL)
	if err != nil {
		return err
	}
	// Pending is not completion: use the production verifier against the signed
	// CLI-generated events and actual loopback CDN bytes after acceptance.
	reader := exec.CommandContext(ctx, os.Args[5], "verify", "-relay", strings.Replace(catalog.URL, "http://", "ws://", 1),
		"-publisher", signerPub, "-app", "invalid.example.zspfixture", "-version", "2026.9.9", "-version-code", "1",
		"-commit", strings.Repeat("a", 40), "-apk-sha256", public.hash, "-apk-bytes", strconv.Itoa(public.size),
		"-cert-sha256", public.cert, "-blossom", blob.URL, "-release-id", document.ID, "-attempts", "1", "-json")
	verified, err := reader.CombinedOutput()
	if err != nil {
		return fmt.Errorf("production readback rejected v2 fixture: %w: %s", err, verified)
	}
	var receipt struct{ Release string }
	if json.Unmarshal(verified, &receipt) != nil || receipt.Release != document.ID {
		return fmt.Errorf("readback did not confirm accepted release")
	}
	restored, err := os.ReadFile(keyPath)
	if err != nil || strings.TrimSpace(string(restored)) != clientKey {
		return fmt.Errorf("preseeded key changed")
	}
	relay.mu.Lock()
	defer relay.mu.Unlock()
	if kinds[32267] != 1 || kinds[30063] != 1 || kinds[3063] != 1 || len(kinds) != 3 || relay.requests < 3 || relay.forbidden != 0 {
		return fmt.Errorf("unexpected loopback publishing contract: kinds=%v requests=%d forbidden=%d", kinds, relay.requests, relay.forbidden)
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]any{
		"version": "v2.0.0", "eventKinds": kinds,
		"signerAuthor": signerPub, "preseededClientRestored": true,
		"nip46Requests": relay.requests, "eventsSentToSignerRelay": relay.forbidden, "catalog": "loopback only", "blossom": "loopback only", "status": document.Status, "c1ProofVerifiedByCLI": true, "readOnlyPreflightVerified": true, "verifiedPublicReadback": true,
		"signerRelay": "loopback only", "keyDirectory": "os.UserConfigDir()/zsp/bunker-keys", "errorDocumentOnStderrVerified": true,
	})
}

// Catalog and Blossom fixture for the real binary. The first read by release ID
// deliberately withholds it, exercising ZSP's accepted-but-pending JSON contract.
type fixtureCatalog struct {
	mu         sync.Mutex
	events     []nostr.Event
	hidden     bool
	hash, cert string
	size       int
}

func fixtureServices(ctx context.Context, apk, keyPath, certPath, signerKey string) (*httptest.Server, *httptest.Server, *fixtureCatalog, error) {
	data, err := os.ReadFile(apk)
	if err != nil {
		return nil, nil, nil, err
	}
	cert, err := os.ReadFile(certPath)
	if err != nil {
		return nil, nil, nil, err
	}
	pemBytes, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, nil, nil, err
	}
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, nil, nil, fmt.Errorf("missing disposable key PEM")
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, nil, nil, err
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, nil, nil, fmt.Errorf("fixture needs RSA key")
	}
	hash := sha256.Sum256(data)
	certHash := sha256.Sum256(cert)
	pub, _ := nostr.GetPublicKey(signerKey)
	now := nostr.Now()
	expiry := int64(now) + 365*24*3600
	message := sha256.Sum256([]byte(fmt.Sprintf("Verifying at %d until %d that I control the following Nostr public key: %s", now, expiry, pub)))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, message[:])
	if err != nil {
		return nil, nil, nil, err
	}
	proof := nostr.Event{Kind: 30509, CreatedAt: now, Tags: nostr.Tags{{"d", hex.EncodeToString(certHash[:])}, {"signature", base64.StdEncoding.EncodeToString(signature)}, {"expiry", fmt.Sprint(expiry)}}}
	if err := proof.Sign(signerKey); err != nil {
		return nil, nil, nil, err
	}
	public := &fixtureCatalog{events: []nostr.Event{proof}, hash: hex.EncodeToString(hash[:]), cert: hex.EncodeToString(certHash[:]), size: len(data)}
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
			send := func(values ...any) {
				payload, _ := json.Marshal(values)
				_ = conn.Write(ctx, websocket.MessageText, payload)
			}
			switch kind {
			case "REQ":
				_ = json.Unmarshal(parts[1], &id)
				var filter nostr.Filter
				if len(parts) != 3 || json.Unmarshal(parts[2], &filter) != nil {
					return
				}
				public.mu.Lock()
				for _, event := range public.events {
					if event.Kind == 30063 && len(filter.IDs) > 0 && !public.hidden {
						public.hidden = true
						continue
					}
					if filter.Matches(&event) {
						send("EVENT", id, event)
					}
				}
				public.mu.Unlock()
				send("EOSE", id)
			case "EVENT":
				var event nostr.Event
				if json.Unmarshal(parts[1], &event) != nil {
					return
				}
				valid, _ := event.CheckSignature()
				if !valid || event.ID != event.GetID() || event.PubKey != pub || (event.Kind != 3063 && event.Kind != 30063 && event.Kind != 32267) {
					send("OK", event.ID, false, "invalid fixture event")
					continue
				}
				public.mu.Lock()
				public.events = append(public.events, event)
				public.mu.Unlock()
				send("OK", event.ID, true, "")
			}
		}
	}))
	var blob *httptest.Server
	blob = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/upload" {
			encoded := strings.TrimPrefix(r.Header.Get("Authorization"), "Nostr ")
			raw, err := base64.StdEncoding.DecodeString(encoded)
			var auth nostr.Event
			if err != nil || json.Unmarshal(raw, &auth) != nil {
				w.WriteHeader(401)
				return
			}
			valid, _ := auth.CheckSignature()
			if !valid || auth.Kind != 24242 || auth.PubKey != pub {
				w.WriteHeader(403)
				return
			}
			if r.Method == http.MethodHead {
				w.WriteHeader(200)
				return
			}
			body, _ := io.ReadAll(io.LimitReader(r.Body, int64(len(data)+1)))
			if r.Method != http.MethodPut || !bytes.Equal(body, data) {
				w.WriteHeader(400)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(201)
			_ = json.NewEncoder(w).Encode(map[string]any{"url": blob.URL + "/" + public.hash + ".apk", "sha256": public.hash, "size": len(data), "type": "application/vnd.android.package-archive", "uploaded": time.Now().Unix()})
			return
		}
		if r.URL.Path == "/"+public.hash+".apk" {
			_, _ = w.Write(data)
			return
		}
		w.WriteHeader(404)
	}))
	return catalog, blob, public, nil
}

func (public *fixtureCatalog) verify(id, pub, blossom string) (map[int]int, error) {
	public.mu.Lock()
	defer public.mu.Unlock()
	kinds := map[int]int{}
	assetID := ""
	var release nostr.Event
	for _, event := range public.events {
		if event.Kind == 30509 {
			continue
		}
		valid, err := event.CheckSignature()
		if err != nil || !valid || event.ID != event.GetID() || event.PubKey != pub {
			return nil, fmt.Errorf("invalid fixture signature/ID")
		}
		kinds[event.Kind]++
		if event.Kind == 30063 {
			release = event
		}
		if event.Kind == 3063 {
			for name, want := range map[string]string{"i": "invalid.example.zspfixture", "version": "2026.9.9", "version_code": "1", "x": public.hash, "commit": strings.Repeat("a", 40), "apk_certificate_hash": public.cert, "size": strconv.Itoa(public.size), "url": blossom + "/" + public.hash + ".apk"} {
				found := event.Tags.Find(name)
				if len(found) < 2 || found[1] != want {
					return nil, fmt.Errorf("fixture asset %s mismatch", name)
				}
			}
			assetID = event.ID
		}
	}
	if release.ID != id || assetID == "" {
		return nil, fmt.Errorf("missing returned release ID or asset")
	}
	ref := release.Tags.Find("e")
	if len(ref) < 2 || ref[1] != assetID {
		return nil, fmt.Errorf("release does not reference fixture asset")
	}
	return kinds, nil
}
