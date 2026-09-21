package main

import (
	"encoding/json"
	"strings"
	"testing"
)

const fixturePublisher = "75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7"

func TestDeletionTargetsOnlyLegacyApp(t *testing.T) {
	tags := deletionTags(fixturePublisher)
	if len(tags) != 11 {
		t.Fatalf("got %d tags, want 11", len(tags))
	}
	encoded, err := json.Marshal(tags)
	if err != nil {
		t.Fatal(err)
	}
	serialized := string(encoded)
	if !strings.Contains(serialized, legacyAppID) {
		t.Fatal("legacy app coordinate missing")
	}
	if strings.Contains(serialized, currentAppID) {
		t.Fatal("current app must never be a deletion target")
	}
	for _, target := range legacyEvents {
		if !strings.Contains(serialized, target.id) {
			t.Fatalf("legacy event %s missing", target.id)
		}
	}
}

func TestLegacyEventSetIsExact(t *testing.T) {
	if len(legacyEvents) != 5 {
		t.Fatalf("got %d legacy events, want 5", len(legacyEvents))
	}
	wantKinds := map[int]int{32267: 1, 30063: 2, 3063: 2}
	gotKinds := map[int]int{}
	for _, target := range legacyEvents {
		gotKinds[target.kind]++
	}
	for kind, want := range wantKinds {
		if gotKinds[kind] != want {
			t.Fatalf("kind %d count %d, want %d", kind, gotKinds[kind], want)
		}
	}
}
