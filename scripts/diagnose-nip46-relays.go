// Diagnose NIP-46 reply delivery through public relays, without any real signer.
//
// A throwaway in-process responder answers like a remote signer. Each round
// launches a fresh client process with the same go-nostr code ZSP uses, back to
// back, as publication does. When a client misses a reply, the relays are
// queried to show whether that reply was stored there anyway.
//
// Build with `-tags debug` so go-nostr logs every relay frame (REQ, EVENT,
// EOSE, CLOSED, OK, NOTICE). All keys are generated per run; no secret is read.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip46"
)

type roundResult struct {
	Round      int    `json:"round"`
	OK         bool   `json:"ok"`
	Stage      string `json:"stage"`
	ConnectMS  int64  `json:"connect_ms"`
	PubkeyMS   int64  `json:"get_public_key_ms"`
	SignMS     int64  `json:"sign_event_ms"`
	Error      string `json:"error,omitempty"`
	Replies    int    `json:"replies_published"`
	Stored     string `json:"replies_stored_on_relays,omitempty"`
	ClientLogs string `json:"client_log"`
}

func main() {
	if len(os.Args) >= 2 && os.Args[1] == "client" {
		os.Exit(client(os.Args[2:]))
	}
	if len(os.Args) != 4 {
		fmt.Fprintln(os.Stderr, "usage: diagnose-nip46-relays <comma-separated relays> <rounds> <output dir>")
		os.Exit(2)
	}
	if err := run(strings.Split(os.Args[1], ","), os.Args[2], os.Args[3]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

// client mirrors ZSP: a fresh pool, ConnectBunker with the invitation secret,
// then get_public_key and one sign_event. It prints one JSON result line.
func client(args []string) int {
	if len(args) != 3 {
		return 2
	}
	bunkerURI, clientKey, timeout := args[0], args[1], args[2]
	seconds, err := strconv.Atoi(timeout)
	if err != nil {
		return 2
	}
	nostr.DebugLogger = log.New(os.Stderr, "", log.Ltime|log.Lmicroseconds)
	nostr.InfoLogger = log.New(os.Stderr, "info ", log.Ltime|log.Lmicroseconds)
	result := roundResult{Stage: "connect"}
	emit := func() int {
		line, _ := json.Marshal(result)
		fmt.Println(string(line))
		if result.OK {
			return 0
		}
		return 1
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(seconds)*time.Second)
	defer cancel()
	started := time.Now()
	bunker, err := nip46.ConnectBunker(ctx, clientKey, bunkerURI, nil, func(string) {})
	result.ConnectMS = time.Since(started).Milliseconds()
	if err != nil {
		result.Error = err.Error()
		return emit()
	}
	result.Stage = "get_public_key"
	started = time.Now()
	publisher, err := bunker.GetPublicKey(ctx)
	result.PubkeyMS = time.Since(started).Milliseconds()
	if err != nil {
		result.Error = err.Error()
		return emit()
	}
	result.Stage = "sign_event"
	started = time.Now()
	event := nostr.Event{Kind: 1, PubKey: publisher, CreatedAt: nostr.Now(), Content: "NIP-46 relay diagnostic"}
	err = bunker.SignEvent(ctx, &event)
	result.SignMS = time.Since(started).Milliseconds()
	if err != nil {
		result.Error = err.Error()
		return emit()
	}
	result.Stage, result.OK = "done", true
	return emit()
}

type responder struct {
	mu      sync.Mutex
	logger  *log.Logger
	replies map[string][]string // client-visible reply IDs, by request ID
	count   int
}

func run(relays []string, roundsText, outputDir string) error {
	rounds, err := strconv.Atoi(roundsText)
	if err != nil || rounds < 1 || rounds > 50 {
		return fmt.Errorf("rounds must be 1-50")
	}
	for _, relay := range relays {
		if u, err := url.Parse(relay); err != nil || (u.Scheme != "wss" && u.Scheme != "ws") || u.Host == "" {
			return fmt.Errorf("invalid relay %q", relay)
		}
	}
	if err := os.MkdirAll(outputDir, 0o755); err != nil {
		return err
	}
	logFile, err := os.Create(filepath.Join(outputDir, "responder.log"))
	if err != nil {
		return err
	}
	defer logFile.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	signerKey, clientKey := nostr.GeneratePrivateKey(), nostr.GeneratePrivateKey()
	signerPub, _ := nostr.GetPublicKey(signerKey)
	clientPub, _ := nostr.GetPublicKey(clientKey)
	r := &responder{logger: log.New(logFile, "", log.Ltime|log.Lmicroseconds), replies: map[string][]string{}}
	fmt.Printf("Throwaway responder %s, client %s, relays %s\n", signerPub[:12], clientPub[:12], strings.Join(relays, " "))

	// Connect the responder directly and wait for EOSE on every relay, so it is
	// certainly listening before any client runs.
	connected := make([]*nostr.Relay, 0, len(relays))
	subs := make([]*nostr.Subscription, 0, len(relays))
	now := nostr.Now()
	for _, address := range relays {
		relay, err := nostr.RelayConnect(ctx, address, nostr.WithNoticeHandler(func(notice string) {
			r.logger.Printf("%s NOTICE %q", address, notice)
		}))
		if err != nil {
			return fmt.Errorf("responder cannot connect to %s: %w", address, err)
		}
		sub, err := relay.Subscribe(ctx, nostr.Filters{{Kinds: []int{nostr.KindNostrConnect},
			Tags: nostr.TagMap{"p": []string{signerPub}}, Since: &now}})
		if err != nil {
			return fmt.Errorf("responder cannot subscribe on %s: %w", address, err)
		}
		select {
		case <-sub.EndOfStoredEvents:
		case reason := <-sub.ClosedReason:
			return fmt.Errorf("%s closed the responder subscription: %s", address, reason)
		case <-time.After(20 * time.Second):
			return fmt.Errorf("%s did not confirm the responder subscription", address)
		}
		connected = append(connected, relay)
		subs = append(subs, sub)
	}
	for i, sub := range subs {
		go r.serve(ctx, relays[i], sub, signerKey, connected)
	}
	// The go-nostr static signer accepts any secret; that is fine for a throwaway key.
	query := url.Values{"secret": {"diagnostic"}}
	for _, relay := range relays {
		query.Add("relay", relay)
	}
	bunkerURI := "bunker://" + signerPub + "?" + query.Encode()

	var results []roundResult
	for round := 1; round <= rounds; round++ {
		roundStart := nostr.Now()
		before := r.published()
		logPath := filepath.Join(outputDir, fmt.Sprintf("client-round-%02d.log", round))
		result := runClient(ctx, round, bunkerURI, clientKey, logPath)
		time.Sleep(2 * time.Second) // let late replies land before counting them
		result.Replies = r.published() - before
		if !result.OK {
			result.Stored = storedReplies(ctx, relays, signerPub, clientPub, roundStart)
		}
		results = append(results, result)
		status := "ok"
		if !result.OK {
			status = "MISSED at " + result.Stage
		}
		fmt.Printf("round %02d: %-22s connect=%dms get_public_key=%dms sign=%dms replies_published=%d %s\n",
			round, status, result.ConnectMS, result.PubkeyMS, result.SignMS, result.Replies, result.Stored)
	}
	summary, _ := json.MarshalIndent(results, "", "  ")
	if err := os.WriteFile(filepath.Join(outputDir, "results.json"), summary, 0o644); err != nil {
		return err
	}
	failed := 0
	for _, result := range results {
		if !result.OK {
			failed++
		}
	}
	fmt.Printf("%d of %d rounds missed a reply\n", failed, rounds)
	return nil
}

// serve answers requests like a remote signer and records every relay's OK.
func (r *responder) serve(ctx context.Context, address string, sub *nostr.Subscription, signerKey string, relays []*nostr.Relay) {
	signer := nip46.NewStaticKeySigner(signerKey)
	for {
		select {
		case <-ctx.Done():
			return
		case reason := <-sub.ClosedReason:
			r.logger.Printf("%s CLOSED responder subscription: %s", address, reason)
			return
		case event, ok := <-sub.Events:
			if !ok {
				r.logger.Printf("%s responder subscription ended", address)
				return
			}
			r.logger.Printf("%s request %s from %s", address, event.ID[:8], event.PubKey[:8])
			if !r.claim(event.ID) {
				continue // the same request arrived from another relay
			}
			req, _, response, err := signer.HandleRequest(ctx, event)
			if err != nil {
				r.logger.Printf("request %s rejected: %v", event.ID[:8], err)
				continue
			}
			r.logger.Printf("request %s method %s -> reply %s", event.ID[:8], req.Method, response.ID[:8])
			r.record(event.ID, response.ID)
			for _, relay := range relays {
				go func(relay *nostr.Relay) {
					publishCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
					defer cancel()
					started := time.Now()
					err := relay.Publish(publishCtx, response)
					r.logger.Printf("%s reply %s OK=%t in %dms %v", relay.URL, response.ID[:8], err == nil,
						time.Since(started).Milliseconds(), err)
				}(relay)
			}
		}
	}
}

func (r *responder) claim(requestID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, seen := r.replies[requestID]; seen {
		return false
	}
	r.replies[requestID] = nil
	return true
}

func (r *responder) record(requestID, replyID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.replies[requestID] = append(r.replies[requestID], replyID)
	r.count++
}

func (r *responder) published() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.count
}

func runClient(ctx context.Context, round int, bunkerURI, clientKey, logPath string) roundResult {
	result := roundResult{Round: round, ClientLogs: filepath.Base(logPath)}
	logFile, err := os.Create(logPath)
	if err != nil {
		result.Error = err.Error()
		return result
	}
	defer logFile.Close()
	cmd := exec.CommandContext(ctx, os.Args[0], "client", bunkerURI, clientKey, "45")
	cmd.Stderr = logFile
	output, _ := cmd.Output()
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	if json.Unmarshal([]byte(lines[len(lines)-1]), &result) != nil {
		result.Error = "client produced no result"
	}
	result.Round, result.ClientLogs = round, filepath.Base(logPath)
	return result
}

// storedReplies asks each relay, with a fresh connection and no limit, for
// replies addressed to the client since the round started.
func storedReplies(ctx context.Context, relays []string, signerPub, clientPub string, since nostr.Timestamp) string {
	var parts []string
	for _, address := range relays {
		queryCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		relay, err := nostr.RelayConnect(queryCtx, address)
		if err != nil {
			parts = append(parts, fmt.Sprintf("%s=unreachable", address))
			cancel()
			continue
		}
		events, err := relay.QuerySync(queryCtx, nostr.Filter{Kinds: []int{nostr.KindNostrConnect},
			Authors: []string{signerPub}, Tags: nostr.TagMap{"p": []string{clientPub}}, Since: &since})
		relay.Close()
		cancel()
		if err != nil {
			parts = append(parts, fmt.Sprintf("%s=query-failed", address))
			continue
		}
		parts = append(parts, fmt.Sprintf("%s=%d", address, len(events)))
	}
	return "stored_replies[" + strings.Join(parts, " ") + "]"
}
