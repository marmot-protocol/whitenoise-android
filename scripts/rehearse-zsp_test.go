package main

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

// TestRehearsalRelayReplaysResponsePublishedBeforeSubscription covers the reconnect race that previously timed out.
func TestRehearsalRelayReplaysResponsePublishedBeforeSubscription(t *testing.T) {
	response := nostr.Event{Kind: 24133, ID: strings.Repeat("a", 64)}
	relay := newRehearsalRelay()
	relay.publish(response)

	var frames [][]any
	relay.subscribe(relay.registerConnection(), "late-subscription", func(values ...any) {
		frames = append(frames, values)
	})

	if len(frames) != 1 || len(frames[0]) != 3 || frames[0][0] != "EVENT" || frames[0][1] != "late-subscription" {
		t.Fatalf("cached response was not replayed to late subscriber: %#v", frames)
	}
	replayed, ok := frames[0][2].(nostr.Event)
	if !ok || replayed.ID != response.ID {
		t.Fatalf("cached response payload was not preserved: %#v", frames[0][2])
	}
}

// TestRehearsalRelayScopesSubscriptionsToConnections covers equal IDs and multi-subscription cleanup.
func TestRehearsalRelayScopesSubscriptionsToConnections(t *testing.T) {
	relay := newRehearsalRelay()
	firstConnection := relay.registerConnection()
	secondConnection := relay.registerConnection()
	var firstFrames, secondFrames [][]any
	relay.subscribe(firstConnection, "shared", func(values ...any) { firstFrames = append(firstFrames, values) })
	relay.subscribe(firstConnection, "another", func(values ...any) { firstFrames = append(firstFrames, values) })
	relay.subscribe(secondConnection, "shared", func(values ...any) { secondFrames = append(secondFrames, values) })

	relay.unsubscribeConnection(firstConnection)
	relay.publish(nostr.Event{Kind: 24133, ID: strings.Repeat("b", 64)})

	if len(firstFrames) != 0 {
		t.Fatalf("closed connection retained subscriptions: %#v", firstFrames)
	}
	if len(secondFrames) != 1 || secondFrames[0][1] != "shared" {
		t.Fatalf("equal subscription ID on live connection was lost: %#v", secondFrames)
	}
}
