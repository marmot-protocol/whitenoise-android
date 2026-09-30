// Read back a Zapstore publication from the relay and CDN.
//
//	absent: exit 0 when the relay has no release or asset event for this
//	        version (safe to retry publication), 3 when it has any.
//	verify: require signed app, release and asset events that match the
//	        reviewed candidate, and a CDN blob with the reviewed APK hash.
//
// Any query failure exits 1: an unknown public state is never "absent".
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/nbd-wtf/go-nostr"
)

type expectation struct {
	Publisher, App, Version, Code, Commit, APKSHA256, CertSHA256, Blossom string
}

type receipt struct {
	App, Release, Asset, URL string
}

const exitPresent = 3

func main() {
	if len(os.Args) < 2 || (os.Args[1] != "absent" && os.Args[1] != "verify") {
		fmt.Fprintln(os.Stderr, "usage: zapstore-readback absent|verify [flags]")
		os.Exit(2)
	}
	mode := os.Args[1]
	flags := flag.NewFlagSet(mode, flag.ExitOnError)
	relay := flags.String("relay", "", "Zapstore relay URL")
	var want expectation
	flags.StringVar(&want.Publisher, "publisher", "", "publisher public key (hex)")
	flags.StringVar(&want.App, "app", "", "application identifier")
	flags.StringVar(&want.Version, "version", "", "versionName")
	flags.StringVar(&want.APKSHA256, "apk-sha256", "", "reviewed APK SHA-256")
	flags.StringVar(&want.Code, "version-code", "", "versionCode")
	flags.StringVar(&want.Commit, "commit", "", "source commit")
	flags.StringVar(&want.CertSHA256, "cert-sha256", "", "APK signing certificate SHA-256")
	flags.StringVar(&want.Blossom, "blossom", "", "expected Blossom base URL")
	attempts := flags.Int("attempts", 6, "verification attempts, for relay propagation")
	_ = flags.Parse(os.Args[2:])
	if *relay == "" || want.Publisher == "" || want.App == "" || want.Version == "" || want.APKSHA256 == "" {
		fmt.Fprintln(os.Stderr, "error: relay, publisher, app, version and apk-sha256 are required")
		os.Exit(2)
	}

	if mode == "absent" {
		found, err := present(*relay, want)
		if err != nil {
			fmt.Fprintln(os.Stderr, "error: cannot read Zapstore relay state:", err)
			os.Exit(1)
		}
		if found > 0 {
			fmt.Fprintf(os.Stderr, "Zapstore relay already has %d event(s) for %s\n", found, want.Version)
			os.Exit(exitPresent)
		}
		fmt.Printf("Zapstore relay has no events for %s\n", want.Version)
		return
	}

	if want.Code == "" || want.Commit == "" || want.CertSHA256 == "" || want.Blossom == "" {
		fmt.Fprintln(os.Stderr, "error: verify also requires version-code, commit, cert-sha256 and blossom")
		os.Exit(2)
	}
	var lastErr error
	for attempt := 1; attempt <= *attempts; attempt++ {
		if attempt > 1 {
			time.Sleep(10 * time.Second)
		}
		got, err := verify(*relay, want)
		if err == nil {
			fmt.Printf("Verified Zapstore %s: app %s, release %s, asset %s, blob %s\n",
				want.Version, got.App, got.Release, got.Asset, got.URL)
			return
		}
		lastErr = err
		fmt.Fprintf(os.Stderr, "readback attempt %d: %v\n", attempt, err)
	}
	fmt.Fprintln(os.Stderr, "error: Zapstore readback did not match the reviewed candidate:", lastErr)
	os.Exit(1)
}

func query(relayURL string, filter nostr.Filter) ([]*nostr.Event, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	relay, err := nostr.RelayConnect(ctx, relayURL)
	if err != nil {
		return nil, err
	}
	defer relay.Close()
	return relay.QuerySync(ctx, filter)
}

func present(relay string, want expectation) (int, error) {
	releases, err := query(relay, nostr.Filter{Kinds: []int{30063}, Authors: []string{want.Publisher},
		Tags: nostr.TagMap{"d": []string{want.App + "@" + want.Version}}})
	if err != nil {
		return 0, err
	}
	assets, err := query(relay, nostr.Filter{Kinds: []int{3063}, Authors: []string{want.Publisher},
		Tags: nostr.TagMap{"x": []string{want.APKSHA256}}})
	if err != nil {
		return 0, err
	}
	return len(releases) + len(assets), nil
}

func verify(relay string, want expectation) (receipt, error) {
	apps, err := query(relay, nostr.Filter{Kinds: []int{32267}, Authors: []string{want.Publisher},
		Tags: nostr.TagMap{"d": []string{want.App}}})
	if err != nil {
		return receipt{}, err
	}
	releases, err := query(relay, nostr.Filter{Kinds: []int{30063}, Authors: []string{want.Publisher},
		Tags: nostr.TagMap{"d": []string{want.App + "@" + want.Version}}})
	if err != nil {
		return receipt{}, err
	}
	assets, err := query(relay, nostr.Filter{Kinds: []int{3063}, Authors: []string{want.Publisher},
		Tags: nostr.TagMap{"x": []string{want.APKSHA256}}})
	if err != nil {
		return receipt{}, err
	}
	got, err := checkEvents(want, apps, releases, assets)
	if err != nil {
		return receipt{}, err
	}
	return got, checkBlob(got.URL, want.APKSHA256)
}

func tag(event *nostr.Event, name string) string {
	if found := event.Tags.Find(name); len(found) > 1 {
		return found[1]
	}
	return ""
}

func signedBy(event *nostr.Event, publisher string) bool {
	valid, err := event.CheckSignature()
	return err == nil && valid && event.PubKey == publisher && event.ID == event.GetID()
}

// checkEvents requires the newest release for this version to reference a
// signed asset that matches every reviewed candidate property.
func checkEvents(want expectation, apps, releases, assets []*nostr.Event) (receipt, error) {
	var got receipt
	for _, app := range apps {
		if signedBy(app, want.Publisher) && tag(app, "d") == want.App {
			got.App = app.ID
		}
	}
	if got.App == "" {
		return got, errors.New("no signed app event")
	}
	var release *nostr.Event
	for _, candidate := range releases {
		if signedBy(candidate, want.Publisher) && tag(candidate, "d") == want.App+"@"+want.Version &&
			(release == nil || candidate.CreatedAt > release.CreatedAt) {
			release = candidate
		}
	}
	if release == nil {
		return got, errors.New("no signed release event")
	}
	if tag(release, "version") != want.Version || tag(release, "i") != want.App {
		return got, errors.New("release event version or app differs")
	}
	got.Release = release.ID
	referenced := map[string]bool{}
	for reference := range release.Tags.FindAll("e") {
		if len(reference) > 1 {
			referenced[reference[1]] = true
		}
	}
	for _, asset := range assets {
		if !referenced[asset.ID] || !signedBy(asset, want.Publisher) {
			continue
		}
		expectedURL := want.Blossom + "/" + want.APKSHA256
		checks := map[string]string{"i": want.App, "x": want.APKSHA256, "version": want.Version,
			"version_code": want.Code, "commit": want.Commit, "apk_certificate_hash": want.CertSHA256, "url": expectedURL}
		for name, value := range checks {
			if tag(asset, name) != value {
				return got, fmt.Errorf("asset event %s differs from the reviewed candidate", name)
			}
		}
		got.Asset, got.URL = asset.ID, expectedURL
		return got, nil
	}
	return got, errors.New("release does not reference a signed asset with the reviewed APK hash")
}

func checkBlob(url, expected string) error {
	client := &http.Client{Timeout: 5 * time.Minute}
	response, err := client.Get(url)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("CDN returned HTTP %d", response.StatusCode)
	}
	digest := sha256.New()
	if _, err := io.Copy(digest, response.Body); err != nil {
		return err
	}
	if hex.EncodeToString(digest.Sum(nil)) != expected {
		return errors.New("CDN blob hash differs from the reviewed APK")
	}
	return nil
}
