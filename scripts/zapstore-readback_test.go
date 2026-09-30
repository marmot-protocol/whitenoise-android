package main

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func signed(t *testing.T, key string, kind int, tags nostr.Tags) *nostr.Event {
	t.Helper()
	event := &nostr.Event{Kind: kind, CreatedAt: nostr.Now(), Tags: tags}
	if err := event.Sign(key); err != nil {
		t.Fatal(err)
	}
	return event
}

func fixture(t *testing.T) (expectation, string) {
	key := nostr.GeneratePrivateKey()
	publisher, _ := nostr.GetPublicKey(key)
	return expectation{Publisher: publisher, App: "dev.example.app", Version: "2026.9.30", Code: "20",
		Commit: strings.Repeat("c", 40), APKSHA256: strings.Repeat("a", 64),
		CertSHA256: strings.Repeat("b", 64), Blossom: "https://cdn.example"}, key
}

func publication(t *testing.T, want expectation, key string, override map[string]string) ([]*nostr.Event, []*nostr.Event, []*nostr.Event) {
	values := map[string]string{"i": want.App, "x": want.APKSHA256, "version": want.Version,
		"version_code": want.Code, "commit": want.Commit, "apk_certificate_hash": want.CertSHA256,
		"url": want.Blossom + "/" + want.APKSHA256}
	for name, value := range override {
		values[name] = value
	}
	var assetTags nostr.Tags
	for name, value := range values {
		assetTags = append(assetTags, nostr.Tag{name, value})
	}
	asset := signed(t, key, 3063, assetTags)
	release := signed(t, key, 30063, nostr.Tags{{"d", want.App + "@" + want.Version}, {"i", want.App},
		{"version", want.Version}, {"e", asset.ID, "wss://relay.example"}})
	app := signed(t, key, 32267, nostr.Tags{{"d", want.App}})
	return []*nostr.Event{app}, []*nostr.Event{release}, []*nostr.Event{asset}
}

func TestMatchingPublicationVerifies(t *testing.T) {
	want, key := fixture(t)
	apps, releases, assets := publication(t, want, key, nil)
	got, err := checkEvents(want, apps, releases, assets)
	if err != nil {
		t.Fatal(err)
	}
	if got.Asset != assets[0].ID || got.URL != want.Blossom+"/"+want.APKSHA256 {
		t.Fatalf("unexpected receipt %+v", got)
	}
}

func TestEveryReviewedAssetPropertyIsRequired(t *testing.T) {
	want, key := fixture(t)
	for _, name := range []string{"i", "x", "version", "version_code", "commit", "apk_certificate_hash", "url"} {
		apps, releases, assets := publication(t, want, key, map[string]string{name: "different"})
		if _, err := checkEvents(want, apps, releases, assets); err == nil {
			t.Fatalf("asset with a different %s verified", name)
		}
	}
}

func TestOtherPublisherAndTamperedEventsAreRejected(t *testing.T) {
	want, key := fixture(t)
	apps, releases, assets := publication(t, want, nostr.GeneratePrivateKey(), nil)
	if _, err := checkEvents(want, apps, releases, assets); err == nil {
		t.Fatal("events from another publisher verified")
	}
	apps, releases, assets = publication(t, want, key, nil)
	assets[0].Tags = append(assets[0].Tags, nostr.Tag{"t", "tampered"})
	if _, err := checkEvents(want, apps, releases, assets); err == nil {
		t.Fatal("tampered asset verified")
	}
}

func TestReleaseMustReferenceTheAsset(t *testing.T) {
	want, key := fixture(t)
	apps, releases, assets := publication(t, want, key, nil)
	unrelated := signed(t, key, 30063, nostr.Tags{{"d", want.App + "@" + want.Version}, {"i", want.App},
		{"version", want.Version}, {"e", strings.Repeat("0", 64)}})
	unrelated.CreatedAt = releases[0].CreatedAt + 1
	if err := unrelated.Sign(key); err != nil {
		t.Fatal(err)
	}
	if _, err := checkEvents(want, apps, append(releases, unrelated), assets); err == nil {
		t.Fatal("newest release without the asset reference verified")
	}
}
