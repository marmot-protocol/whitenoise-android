package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
	"github.com/nbd-wtf/go-nostr/nip46"
)

// TestOfflineSignerReplyOrderings forces both wire orderings without timing sleeps.
func TestOfflineSignerReplyOrderings(t *testing.T) {
	for _, requestFirst := range []bool{true, false} {
		name := "subscription-first"
		if requestFirst {
			name = "request-first"
		}
		t.Run(name, func(t *testing.T) {
			ctx, conn, relay, signerPub, clientKey := offlineRelayFixture(t)
			request := offlineConnectRequest(t, signerPub, clientKey)
			filter := nostr.Filter{Kinds: []int{24133}, Tags: nostr.TagMap{"p": []string{relay.clientPub}}}
			if !requestFirst {
				writeOfflineFrame(t, ctx, conn, "REQ", "reply", filter)
				readOfflineFrame(t, ctx, conn, "EOSE")
			}
			writeOfflineFrame(t, ctx, conn, "EVENT", request)
			ack := readOfflineFrame(t, ctx, conn, "OK")
			if len(ack) < 3 || string(ack[2]) != "true" {
				t.Fatalf("fixture did not accept connect: %s", ack)
			}
			// Waiting for OK proves the signer already generated its response.
			if requestFirst {
				writeOfflineFrame(t, ctx, conn, "REQ", "reply", filter)
			}
			frame := readOfflineFrame(t, ctx, conn, "EVENT")
			var response nostr.Event
			if len(frame) != 3 || string(frame[1]) != `"reply"` || json.Unmarshal(frame[2], &response) != nil {
				t.Fatalf("invalid reply frame: %s", frame)
			}
			valid, err := response.CheckSignature()
			if err != nil || !valid || response.PubKey != signerPub {
				t.Fatal("reply lost its signer identity or signature")
			}
			key, err := nip44.GenerateConversationKey(signerPub, clientKey)
			if err != nil {
				t.Fatal(err)
			}
			plain, err := nip44.Decrypt(response.Content, key)
			var reply nip46.Response
			if err != nil || json.Unmarshal([]byte(plain), &reply) != nil || reply.ID != "connect-fixture" || reply.Result != "ack" {
				t.Fatal("replayed response did not acknowledge the original connect request")
			}
			if requestFirst {
				readOfflineFrame(t, ctx, conn, "EOSE")
			}
			relay.mu.Lock()
			defer relay.mu.Unlock()
			if relay.requests != 1 || relay.forbidden != 0 || len(relay.responses) != 1 {
				t.Fatal("replay changed request accounting")
			}
		})
	}
}

// TestOfflineSignerRejectsPublicAndForeignEvents preserves the fixture's offline boundary.
func TestOfflineSignerRejectsPublicAndForeignEvents(t *testing.T) {
	for _, foreignClient := range []bool{false, true} {
		name := "public-event"
		if foreignClient {
			name = "foreign-client"
		}
		t.Run(name, func(t *testing.T) {
			ctx, conn, relay, _, clientKey := offlineRelayFixture(t)
			kind := 3063
			if foreignClient {
				kind, clientKey = 24133, nostr.GeneratePrivateKey()
			}
			event := nostr.Event{Kind: kind, CreatedAt: nostr.Now(), Tags: nostr.Tags{}}
			if err := event.Sign(clientKey); err != nil {
				t.Fatal(err)
			}
			writeOfflineFrame(t, ctx, conn, "EVENT", event)
			ack := readOfflineFrame(t, ctx, conn, "OK")
			if len(ack) < 3 || string(ack[2]) != "false" {
				t.Fatal("forbidden event was accepted")
			}
			writeOfflineFrame(t, ctx, conn, "REQ", "reply", nostr.Filter{Kinds: []int{24133}})
			readOfflineFrame(t, ctx, conn, "EOSE")
			relay.mu.Lock()
			defer relay.mu.Unlock()
			if relay.requests != 0 || relay.forbidden != 1 || len(relay.responses) != 0 {
				t.Fatal("forbidden event entered the replay cache")
			}
		})
	}
}

// TestOfflineSignerSubscriptionLifecycle uses protocol barriers, not timing sleeps.
func TestOfflineSignerSubscriptionLifecycle(t *testing.T) {
	ctx, conn, relay, signerPub, clientKey := offlineRelayFixture(t)
	matching := nostr.Filter{Kinds: []int{24133}, Tags: nostr.TagMap{"p": []string{relay.clientPub}}}
	writeOfflineFrame(t, ctx, conn, "REQ", "first", matching)
	readOfflineFrame(t, ctx, conn, "EOSE")
	// A stale CLOSE cannot remove the current subscription.
	writeOfflineFrame(t, ctx, conn, "CLOSE", "old")
	writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequestID(t, signerPub, clientKey, "first"))
	readOfflineFrame(t, ctx, conn, "OK")
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EVENT"), "first")
	// Replacing the socket's subscription must replace its filter as well as ID.
	writeOfflineFrame(t, ctx, conn, "REQ", "unmatched", nostr.Filter{Kinds: []int{0}})
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "unmatched")
	writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequestID(t, signerPub, clientKey, "second"))
	readOfflineFrame(t, ctx, conn, "OK")
	writeOfflineFrame(t, ctx, conn, "REQ", "reply", matching)
	for range 2 {
		assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EVENT"), "reply")
	}
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "reply")
	writeOfflineFrame(t, ctx, conn, "CLOSE", "reply")
	writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequestID(t, signerPub, clientKey, "third"))
	// The ACK follows CLOSE processing; any stale delivery would precede the next EOSE.
	readOfflineFrame(t, ctx, conn, "OK")
	relay.mu.Lock()
	peerCount := len(relay.peers)
	relay.mu.Unlock()
	if peerCount != 0 {
		t.Fatal("CLOSE retained a reply subscription")
	}
	writeOfflineFrame(t, ctx, conn, "REQ", "barrier", nostr.Filter{Kinds: []int{0}})
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "barrier")
	writeOfflineFrame(t, ctx, conn, "REQ", "reopened", matching)
	for range 3 {
		assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EVENT"), "reopened")
	}
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "reopened")
}

func TestOfflineSignerReplayHasFixedBound(t *testing.T) {
	ctx, conn, relay, signerPub, clientKey := offlineRelayFixture(t)
	for index := range offlineSignerResponseLimit + 3 {
		writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequestID(t, signerPub, clientKey, string(rune('a'+index))))
		readOfflineFrame(t, ctx, conn, "OK")
	}
	relay.mu.Lock()
	if len(relay.responses) != offlineSignerResponseLimit {
		relay.mu.Unlock()
		t.Fatal("reply retention exceeded its fixed bound")
	}
	relay.mu.Unlock()
	key, err := nip44.GenerateConversationKey(signerPub, clientKey)
	if err != nil {
		t.Fatal(err)
	}
	writeOfflineFrame(t, ctx, conn, "REQ", "bounded", nostr.Filter{Kinds: []int{24133}})
	for index := range offlineSignerResponseLimit {
		frame := readOfflineFrame(t, ctx, conn, "EVENT")
		assertOfflineSubscriptionID(t, frame, "bounded")
		var response nostr.Event
		if len(frame) != 3 || json.Unmarshal(frame[2], &response) != nil {
			t.Fatal("invalid retained response")
		}
		plain, err := nip44.Decrypt(response.Content, key)
		var reply nip46.Response
		if err != nil || json.Unmarshal([]byte(plain), &reply) != nil || reply.ID != string(rune('a'+index+3)) {
			t.Fatal("replay changed the bounded response order")
		}
	}
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "bounded")
}

func TestOfflineSignerFiltersRecipientsAndKeepsSubscriptionAfterMalformedREQ(t *testing.T) {
	ctx, conn, relay, signerPub, clientKey := offlineRelayFixture(t)
	wrongRecipient := nostr.Filter{Kinds: []int{24133}, Tags: nostr.TagMap{"p": []string{signerPub}}}
	writeOfflineFrame(t, ctx, conn, "REQ", "wrong-recipient", wrongRecipient)
	readOfflineFrame(t, ctx, conn, "EOSE")
	writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequest(t, signerPub, clientKey))
	readOfflineFrame(t, ctx, conn, "OK")
	// EOSE is an ordered barrier: no live or retained reply may match the wrong recipient.
	writeOfflineFrame(t, ctx, conn, "REQ", "wrong-recipient", wrongRecipient)
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EOSE"), "wrong-recipient")
	writeOfflineFrame(t, ctx, conn, "REQ", "current", nostr.Filter{
		Kinds: []int{24133}, Tags: nostr.TagMap{"p": []string{relay.clientPub}},
	})
	assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EVENT"), "current")
	readOfflineFrame(t, ctx, conn, "EOSE")
	for _, malformed := range [][]any{
		{"REQ", "missing-filter"},
		{"REQ", "null-filter", nil},
		{"REQ", "bad-filter", "not an object"},
		{"REQ", "", nostr.Filter{}},
	} {
		writeOfflineFrame(t, ctx, conn, malformed...)
		writeOfflineFrame(t, ctx, conn, "EVENT", offlineConnectRequestID(t, signerPub, clientKey, "barrier"))
		readOfflineFrame(t, ctx, conn, "OK")
		assertOfflineSubscriptionID(t, readOfflineFrame(t, ctx, conn, "EVENT"), "current")
	}
}

func TestOfflineSignerDisconnectRemovesSubscription(t *testing.T) {
	ctx, conn, relay, _, _, ended := offlineRelayFixtureWithDone(t)
	writeOfflineFrame(t, ctx, conn, "REQ", "reply", nostr.Filter{Kinds: []int{24133}})
	readOfflineFrame(t, ctx, conn, "EOSE")
	conn.CloseNow()
	select {
	case <-ended:
	case <-ctx.Done():
		t.Fatal("disconnected relay handler did not finish")
	}
	relay.mu.Lock()
	defer relay.mu.Unlock()
	if len(relay.peers) != 0 {
		t.Fatal("disconnected socket retained a subscription")
	}
}

func assertOfflineSubscriptionID(t *testing.T, frame []json.RawMessage, expected string) {
	t.Helper()
	var actual string
	if len(frame) < 2 || json.Unmarshal(frame[1], &actual) != nil || actual != expected {
		t.Fatal("response used a replaced or unexpected subscription ID")
	}
}

// offlineRelayFixture uses the real rehearsal handler with disposable identities and loopback transport.
func offlineRelayFixture(t *testing.T) (context.Context, *websocket.Conn, *offlineSignerRelay, string, string) {
	ctx, conn, relay, signerPub, clientKey, _ := offlineRelayFixtureWithDone(t)
	return ctx, conn, relay, signerPub, clientKey
}

func offlineRelayFixtureWithDone(t *testing.T) (context.Context, *websocket.Conn, *offlineSignerRelay, string, string, <-chan struct{}) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	signerKey, clientKey := nostr.GeneratePrivateKey(), nostr.GeneratePrivateKey()
	signerPub, _ := nostr.GetPublicKey(signerKey)
	clientPub, _ := nostr.GetPublicKey(clientKey)
	signer := nip46.NewStaticKeySigner(signerKey)
	signer.AuthorizeRequest = func(_ bool, from, _ string) bool { return from == clientPub }
	relay := newOfflineSignerRelay(ctx, &signer, clientPub)
	ended := make(chan struct{})
	var endOnce sync.Once
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer endOnce.Do(func() { close(ended) })
		relay.ServeHTTP(w, r)
	}))
	t.Cleanup(server.Close)
	conn, _, err := websocket.Dial(ctx, strings.Replace(server.URL, "http://", "ws://", 1), nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	return ctx, conn, relay, signerPub, clientKey, ended
}

// offlineConnectRequest matches the pinned client's encrypted and signed NIP-46 request.
func offlineConnectRequest(t *testing.T, signerPub, clientKey string) nostr.Event {
	return offlineConnectRequestID(t, signerPub, clientKey, "connect-fixture")
}

func offlineConnectRequestID(t *testing.T, signerPub, clientKey, id string) nostr.Event {
	t.Helper()
	key, err := nip44.GenerateConversationKey(signerPub, clientKey)
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(nip46.Request{ID: id, Method: "connect", Params: []string{signerPub}})
	if err != nil {
		t.Fatal(err)
	}
	content, err := nip44.Encrypt(string(payload), key)
	if err != nil {
		t.Fatal(err)
	}
	event := nostr.Event{Kind: 24133, CreatedAt: nostr.Now(), Content: content, Tags: nostr.Tags{{"p", signerPub}}}
	if err := event.Sign(clientKey); err != nil {
		t.Fatal(err)
	}
	return event
}

// writeOfflineFrame sends one complete protocol frame on the test connection.
func writeOfflineFrame(t *testing.T, ctx context.Context, conn *websocket.Conn, values ...any) {
	t.Helper()
	payload, err := json.Marshal(values)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		t.Fatal(err)
	}
}

// readOfflineFrame checks ordering explicitly so a missing replay fails at its expected frame.
func readOfflineFrame(t *testing.T, ctx context.Context, conn *websocket.Conn, kind string) []json.RawMessage {
	t.Helper()
	_, payload, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("waiting for %s: %v", kind, err)
	}
	var frame []json.RawMessage
	if json.Unmarshal(payload, &frame) != nil || len(frame) < 2 || string(frame[0]) != `"`+kind+`"` {
		t.Fatalf("expected %s, received %s", kind, payload)
	}
	return frame
}
