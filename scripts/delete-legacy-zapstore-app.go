// Delete the retired org.parres.whitenoise Zapstore listing and its releases.
// This deliberately cannot target the current dev.ipf.whitenoise.android app.
package main

import (
	"context"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"slices"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip46"
)

const (
	legacyAppID     = "org.parres.whitenoise"
	currentAppID    = "dev.ipf.whitenoise.android"
	relayURL        = "wss://relay.zapstore.dev"
	expectedConfirm = "DELETE ZAPSTORE " + legacyAppID
)

type legacyEvent struct {
	kind int
	id   string
	dTag string
}

var legacyEvents = []legacyEvent{
	{kind: 32267, id: "34bbd1962e010e7ccf6ea586f92dbba7ae21248b90b9832ebe6fa513021b0a22", dTag: legacyAppID},
	{kind: 30063, id: "5bdcb42ca419b86bc0eb95bce25b6399c92598f28c81d11994139cb49bd881c2", dTag: legacyAppID + "@2026.5.22"},
	{kind: 30063, id: "b9ec41be7817e7b24b1455abebda8b97b077152f6bdfd09536dce8bedd5aedac", dTag: legacyAppID + "@2026.5.7"},
	{kind: 3063, id: "b47646edfd1fd9d12ff6bd4ab1e5936bbd7f765519e8e4ef44dab8a6f3a463c6"},
	{kind: 3063, id: "5cee373f442c665dbc69b9f90f3f6e2252523bc911426a984e9624bcc474782d"},
}

func main() {
	if err := deleteLegacyApp(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func deleteLegacyApp() error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	if os.Getenv("EXPECTED_APP_ID") != legacyAppID || os.Getenv("CONFIRMATION") != expectedConfirm {
		return fmt.Errorf("exact legacy app ID and confirmation are required")
	}
	publisher := os.Getenv("EXPECTED_PUBLISHER")
	if len(publisher) != 64 {
		return fmt.Errorf("invalid expected publisher")
	}
	if _, err := hex.DecodeString(publisher); err != nil {
		return fmt.Errorf("invalid expected publisher")
	}
	clientKey := os.Getenv("BUNKER_CLIENT_KEY")
	if len(clientKey) != 64 {
		return fmt.Errorf("invalid bunker client key")
	}
	if _, err := nostr.GetPublicKey(clientKey); err != nil {
		return fmt.Errorf("invalid bunker client key")
	}
	connection := os.Getenv("SIGN_WITH")
	u, err := url.Parse(connection)
	if err != nil || u.Scheme != "bunker" || len(u.Host) != 64 {
		return fmt.Errorf("invalid bunker URI")
	}
	if _, err := hex.DecodeString(u.Host); err != nil {
		return fmt.Errorf("invalid bunker transport public key")
	}

	relay, err := nostr.RelayConnect(ctx, relayURL)
	if err != nil {
		return fmt.Errorf("could not connect to Zapstore relay")
	}
	defer relay.Close()

	if err := verifyDeletionTargets(ctx, relay, publisher); err != nil {
		return err
	}
	current, err := queryAddress(ctx, relay, publisher, 32267, currentAppID)
	if err != nil || len(current) != 1 || !validSignedEvent(*current[0], publisher) {
		return fmt.Errorf("current White Noise listing was not independently visible before legacy deletion")
	}
	currentID := current[0].ID

	bunker, err := nip46.ConnectBunker(ctx, clientKey, connection, nil, func(string) {})
	if err != nil {
		return fmt.Errorf("paired-client reconnect failed (remote details withheld)")
	}
	actualPublisher, err := bunker.GetPublicKey(ctx)
	if err != nil || actualPublisher != publisher {
		return fmt.Errorf("reconnected signer identity does not match White Noise")
	}

	deletion := nostr.Event{
		Kind:      5,
		PubKey:    publisher,
		CreatedAt: nostr.Now(),
		Content:   "Retired legacy White Noise Android package org.parres.whitenoise after migration to dev.ipf.whitenoise.android.",
		Tags:      deletionTags(publisher),
	}
	if err := bunker.SignEvent(ctx, &deletion); err != nil || !validSignedEvent(deletion, publisher) {
		return fmt.Errorf("legacy deletion signing failed (remote details withheld)")
	}
	if err := relay.Publish(ctx, deletion); err != nil {
		return fmt.Errorf("Zapstore relay rejected legacy deletion request")
	}

	receipt, err := relay.QuerySync(ctx, nostr.Filter{IDs: []string{deletion.ID}, Authors: []string{publisher}, Kinds: []int{5}, Limit: 1})
	if err != nil || len(receipt) != 1 || !validSignedEvent(*receipt[0], publisher) {
		return fmt.Errorf("legacy deletion request was not readable from the Zapstore relay")
	}
	remaining, err := relay.QuerySync(ctx, nostr.Filter{IDs: legacyEventIDs(), Authors: []string{publisher}, Limit: len(legacyEvents)})
	if err != nil || len(remaining) != 0 {
		return fmt.Errorf("one or more legacy events remained readable after deletion")
	}
	legacy, err := queryAddress(ctx, relay, publisher, 32267, legacyAppID)
	if err != nil || len(legacy) != 0 {
		return fmt.Errorf("legacy app coordinate remained readable after deletion")
	}
	current, err = queryAddress(ctx, relay, publisher, 32267, currentAppID)
	if err != nil || len(current) != 1 || current[0].ID != currentID || !validSignedEvent(*current[0], publisher) {
		return fmt.Errorf("current White Noise listing changed during legacy deletion")
	}

	fmt.Println("Deleted legacy Zapstore app:", legacyAppID)
	fmt.Println("Deletion event:", deletion.ID)
	fmt.Println("Current Zapstore app preserved:", currentAppID, currentID)
	return nil
}

func verifyDeletionTargets(ctx context.Context, relay *nostr.Relay, publisher string) error {
	events, err := relay.QuerySync(ctx, nostr.Filter{IDs: legacyEventIDs(), Authors: []string{publisher}, Limit: len(legacyEvents)})
	if err != nil || len(events) != len(legacyEvents) {
		return fmt.Errorf("the exact reviewed legacy event set was not present")
	}
	for _, target := range legacyEvents {
		index := slices.IndexFunc(events, func(event *nostr.Event) bool { return event.ID == target.id })
		if index < 0 {
			return fmt.Errorf("reviewed legacy event %s was not present", target.id)
		}
		event := events[index]
		if event.Kind != target.kind || !validSignedEvent(*event, publisher) || !boundToLegacyApp(*event, target) {
			return fmt.Errorf("reviewed legacy event %s changed identity or scope", target.id)
		}
	}
	return nil
}

func boundToLegacyApp(event nostr.Event, target legacyEvent) bool {
	if target.dTag != "" && event.Tags.FindWithValue("d", target.dTag) == nil {
		return false
	}
	if target.kind == 30063 || target.kind == 3063 {
		return event.Tags.FindWithValue("i", legacyAppID) != nil
	}
	return target.kind == 32267
}

func queryAddress(ctx context.Context, relay *nostr.Relay, publisher string, kind int, dTag string) ([]*nostr.Event, error) {
	return relay.QuerySync(ctx, nostr.Filter{
		Kinds:   []int{kind},
		Authors: []string{publisher},
		Tags:    nostr.TagMap{"d": []string{dTag}},
		Limit:   5,
	})
}

func deletionTags(publisher string) nostr.Tags {
	tags := nostr.Tags{}
	for _, target := range legacyEvents {
		tags = append(tags, nostr.Tag{"e", target.id, relayURL, publisher})
		if target.dTag != "" {
			tags = append(tags, nostr.Tag{"a", fmt.Sprintf("%d:%s:%s", target.kind, publisher, target.dTag), relayURL})
		}
	}
	tags = append(tags, nostr.Tag{"k", "32267"}, nostr.Tag{"k", "30063"}, nostr.Tag{"k", "3063"})
	return tags
}

func legacyEventIDs() []string {
	ids := make([]string, 0, len(legacyEvents))
	for _, target := range legacyEvents {
		ids = append(ids, target.id)
	}
	return ids
}

func validSignedEvent(event nostr.Event, publisher string) bool {
	valid, err := event.CheckSignature()
	return err == nil && valid && event.PubKey == publisher && event.ID == event.GetID()
}
