// Read back a Zapstore publication from the relay and CDN.
//
//	absent: exit 0 when the relay has no release or asset event for this
//	        version, 3 when it has any. Absence cannot exclude a pending submission.
//	verify: require signed app, release and asset events that match the
//	        reviewed candidate, and a CDN blob with the reviewed APK hash.
//
// Any query failure exits 1: an unknown public state is never "absent".
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/coder/websocket"
	"github.com/nbd-wtf/go-nostr"
)

type expectation struct {
	Publisher, App, Version, Code, Commit, APKSHA256, CertSHA256, Blossom string
	ReleaseID                                                             string
	APKBytes                                                              int64
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
	flags.StringVar(&want.ReleaseID, "release-id", "", "exact accepted release event ID, if known")
	flags.Int64Var(&want.APKBytes, "apk-bytes", 0, "reviewed APK size")
	jsonOutput := flags.Bool("json", false, "emit a public verification receipt")
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

	if want.Code == "" || want.Commit == "" || want.CertSHA256 == "" || want.Blossom == "" || want.APKBytes <= 0 {
		fmt.Fprintln(os.Stderr, "error: verify also requires version-code, commit, cert-sha256, blossom and apk-bytes")
		os.Exit(2)
	}
	var lastErr error
	for attempt := 1; attempt <= *attempts; attempt++ {
		if attempt > 1 {
			time.Sleep(10 * time.Second)
		}
		got, err := verify(*relay, want)
		if err == nil {
			if *jsonOutput {
				_ = json.NewEncoder(os.Stdout).Encode(got)
				return
			}
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
	conn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return nil, err
	}
	defer conn.CloseNow()
	conn.SetReadLimit(2 << 20)
	payload, _ := json.Marshal([]any{"REQ", "readback", filter})
	if err := conn.Write(ctx, websocket.MessageText, payload); err != nil {
		return nil, err
	}
	var events []*nostr.Event
	for {
		_, raw, err := conn.Read(ctx)
		if err != nil {
			return nil, errors.New("relay read ended before EOSE")
		}
		var parts []json.RawMessage
		if json.Unmarshal(raw, &parts) != nil || len(parts) < 2 {
			return nil, errors.New("invalid relay frame")
		}
		var kind, sub string
		_ = json.Unmarshal(parts[0], &kind)
		_ = json.Unmarshal(parts[1], &sub)
		if sub != "readback" {
			continue
		}
		switch kind {
		case "EOSE":
			return events, nil
		case "CLOSED":
			return nil, errors.New("relay closed readback subscription")
		case "EVENT":
			var event nostr.Event
			if len(parts) != 3 || json.Unmarshal(parts[2], &event) != nil || !filter.Matches(&event) {
				return nil, errors.New("relay returned an invalid or unrelated event")
			}
			events = append(events, &event)
			if len(events) > 1000 {
				return nil, errors.New("relay readback exceeded event limit")
			}
		}
	}
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
	return got, checkBlob(got.URL, want.APKSHA256, want.APKBytes)
}

func tag(event *nostr.Event, name string) string {
	value := ""
	for _, found := range event.Tags {
		if len(found) > 1 && found[0] == name {
			if value != "" {
				return ""
			}
			value = found[1]
		}
	}
	return value
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
		if app.Kind == 32267 && signedBy(app, want.Publisher) && tag(app, "d") == want.App {
			got.App = app.ID
		}
	}
	if got.App == "" {
		return got, errors.New("no signed app event")
	}
	var release *nostr.Event
	for _, candidate := range releases {
		if candidate.Kind == 30063 && signedBy(candidate, want.Publisher) && tag(candidate, "d") == want.App+"@"+want.Version &&
			(release == nil || candidate.CreatedAt > release.CreatedAt) {
			release = candidate
		}
	}
	if release == nil {
		return got, errors.New("no signed release event")
	}
	if want.ReleaseID != "" && release.ID != want.ReleaseID {
		return got, errors.New("served release differs from accepted event ID")
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
		if asset.Kind != 3063 || !referenced[asset.ID] || !signedBy(asset, want.Publisher) {
			continue
		}
		expectedURL := strings.TrimRight(want.Blossom, "/") + "/" + want.APKSHA256
		actualURL := tag(asset, "url")
		if actualURL != expectedURL && actualURL != expectedURL+".apk" {
			return got, errors.New("asset URL differs from the approved CDN hash path")
		}
		checks := map[string]string{"i": want.App, "x": want.APKSHA256, "version": want.Version,
			"version_code": want.Code, "commit": want.Commit, "apk_certificate_hash": want.CertSHA256}
		if want.APKBytes > 0 {
			checks["size"] = fmt.Sprint(want.APKBytes)
		}
		for name, value := range checks {
			if tag(asset, name) != value {
				return got, fmt.Errorf("asset event %s differs from the reviewed candidate", name)
			}
		}
		got.Asset, got.URL = asset.ID, actualURL
		return got, nil
	}
	return got, errors.New("release does not reference a signed asset with the reviewed APK hash")
}

func checkBlob(url, expected string, size int64) error {
	client := &http.Client{Timeout: 90 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Get(url)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("CDN returned HTTP %d", response.StatusCode)
	}
	digest := sha256.New()
	if size <= 0 {
		return errors.New("reviewed APK size required for blob verification")
	}
	n, err := io.Copy(digest, io.LimitReader(response.Body, size+1))
	if err != nil {
		return err
	}
	if n != size {
		return errors.New("CDN blob size differs from reviewed APK")
	}
	if hex.EncodeToString(digest.Sum(nil)) != expected {
		return errors.New("CDN blob hash differs from the reviewed APK")
	}
	return nil
}
