package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"github.com/coder/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

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

func TestV2APKPathAndExactAcceptedRelease(t *testing.T) {
	want, key := fixture(t)
	apps, releases, assets := publication(t, want, key, map[string]string{"url": want.Blossom + "/" + want.APKSHA256 + ".apk"})
	want.ReleaseID = releases[0].ID
	if _, err := checkEvents(want, apps, releases, assets); err != nil {
		t.Fatal(err)
	}
	want.ReleaseID = strings.Repeat("f", 64)
	if _, err := checkEvents(want, apps, releases, assets); err == nil {
		t.Fatal("different accepted release verified")
	}
	for _, url := range []string{"https://other.example/" + want.APKSHA256 + ".apk", want.Blossom + "/" + want.APKSHA256 + ".apk?redirect=1"} {
		apps, releases, assets = publication(t, want, key, map[string]string{"url": url})
		want.ReleaseID = ""
		if _, err := checkEvents(want, apps, releases, assets); err == nil {
			t.Fatal("unapproved URL verified")
		}
	}
}

func TestRelayMustFinishWithEOSE(t *testing.T) {
	for _, frame := range []string{"EOSE", "CLOSED", "disconnect"} {
		t.Run(frame, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				conn, err := websocket.Accept(w, r, nil)
				if err != nil {
					return
				}
				defer conn.CloseNow()
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				defer cancel()
				if _, _, err := conn.Read(ctx); err != nil {
					return
				}
				if frame == "disconnect" {
					return
				}
				raw, _ := json.Marshal([]any{frame, "readback"})
				_ = conn.Write(ctx, websocket.MessageText, raw)
			}))
			defer server.Close()
			_, err := query(strings.Replace(server.URL, "http://", "ws://", 1), nostr.Filter{Kinds: []int{3063}})
			if (err == nil) != (frame == "EOSE") {
				t.Fatalf("query %s: %v", frame, err)
			}
		})
	}
}

func TestBlobRequiresExactSizeHashAndNoRedirect(t *testing.T) {
	data := []byte("reviewed bytes")
	hash := sha256.Sum256(data)
	expected := hex.EncodeToString(hash[:])
	for _, mode := range []string{"match", "oversize", "corrupt", "redirect"} {
		t.Run(mode, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch mode {
				case "redirect":
					http.Redirect(w, r, "https://unapproved.example", 302)
				case "oversize":
					_, _ = w.Write(append(data, 'x'))
				case "corrupt":
					_, _ = w.Write([]byte(strings.Repeat("x", len(data))))
				default:
					_, _ = w.Write(data)
				}
			}))
			defer server.Close()
			err := checkBlob(server.URL, expected, int64(len(data)))
			if (err == nil) != (mode == "match") {
				t.Fatalf("blob %s: %v", mode, err)
			}
		})
	}
}
