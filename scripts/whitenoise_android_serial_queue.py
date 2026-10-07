#!/usr/bin/env python3
"""Serial native queue policy inside the existing leased GitHub producer.

This module never acquires a lease, invokes inference, edits a branch, bypasses
protection, or treats PR prose as proof. The adapter owns authenticated live
reads and the existing source/testing evidence checks. One effect per tick.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import asdict, dataclass
from typing import Callable

REPO = 'marmot-protocol/whitenoise-android'
REPOSITORY_ID = 1249490895
ACTOR_ID = 222291538
AUTH_CONTEXT = 'Android merge authorization'
CI_CONTEXT = 'Compile, test, ktlint, detekt, Android lint'
POLICY_VERSION = 1
SHA = re.compile(r'[a-f0-9]{40}')
DIGEST = re.compile(r'[a-f0-9]{64}')
UNCERTAIN = {'attempted', 'unknown'}
MAX_NOT_SENT_ATTEMPTS = 3


class Held(ValueError):
    """A content-free gate; never permission to replay an effect."""


class DefiniteRefusal(Held):
    def __init__(self, response_sha256):
        self.response_sha256 = response_sha256
        super().__init__('verified-api-refusal')


class NotSent(DefiniteRefusal):
    """Positive local proof that upstream mutation was never invoked."""


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


@dataclass(frozen=True)
class Identity:
    number: int
    pull_request_id: str
    source: str
    base: str
    integration: str | None = None
    entry_id: str | None = None

    def validate(self):
        if (type(self.number) is not int or not 0 < self.number < 2**31
                or not isinstance(self.pull_request_id, str) or not self.pull_request_id
                or not SHA.fullmatch(self.source) or not SHA.fullmatch(self.base)):
            raise Held('invalid-identity')
        if self.integration is not None:
            if (not SHA.fullmatch(self.integration) or not isinstance(self.entry_id, str)
                    or not self.entry_id or self.integration in {self.source, self.base}):
                raise Held('invalid-integration')
        elif self.entry_id is not None:
            raise Held('partial-integration')
        return self


@dataclass(frozen=True)
class Effect:
    kind: str
    identity: Identity
    proof_sha256: str
    generation: int = 0

    def payload(self):
        self.identity.validate()
        if type(self.generation) is not int or not 0 <= self.generation < 2**31:
            raise Held('invalid-generation')
        if not DIGEST.fullmatch(self.proof_sha256):
            raise Held('invalid-proof-digest')
        if self.kind not in {'authorize-source', 'enqueue', 'authorize-integration', 'revoke-integration', 'dequeue'}:
            raise Held('unsupported-effect')
        if self.kind in {'authorize-integration', 'revoke-integration'} and self.identity.integration is None:
            raise Held('missing-integration')
        if self.kind in {'authorize-source', 'enqueue'} and self.identity.integration is not None:
            raise Held('unexpected-integration')
        return {'schema': POLICY_VERSION, 'repo': REPO, **asdict(self)}

    def key(self):
        return digest(self.payload())


def serial_configuration(configuration):
    return configuration == {
        'maximumEntriesToBuild': 1, 'maximumEntriesToMerge': 1,
        'minimumEntriesToMerge': 1, 'minimumEntriesToMergeWaitTime': 0,
        'mergeMethod': 'SQUASH', 'mergingStrategy': 'ALLGREEN',
        'checkResponseTimeout': 120,
    }


def validate_snapshot(snapshot):
    """The adapter must provide complete bounded live reads, not cache hits."""
    if (not isinstance(snapshot, dict) or snapshot.get('repo') != REPO
            or snapshot.get('repository_id') != REPOSITORY_ID
            or snapshot.get('actor_id') != ACTOR_ID
            or snapshot.get('protection_verified') is not True
            or snapshot.get('prerequisites_verified') is not True
            or not serial_configuration(snapshot.get('configuration'))
            or not SHA.fullmatch(str(snapshot.get('master', '')))
            or snapshot.get('has_next_page') is not False
            or type(snapshot.get('total_count')) is not int
            or not isinstance(snapshot.get('entries'), list)
            or snapshot['total_count'] != len(snapshot['entries'])
            or snapshot['total_count'] > 1):
        raise Held('queue-proof-held')
    return snapshot


def entry_identity(snapshot):
    validate_snapshot(snapshot)
    if not snapshot['entries']:
        return None
    entry = snapshot['entries'][0]
    if (not isinstance(entry, dict) or entry.get('position') != 1
            or entry.get('state') not in {'QUEUED', 'AWAITING_CHECKS', 'MERGEABLE'}
            or entry.get('base') != snapshot['master']
            or entry.get('jump') is not False):
        raise Held('queue-entry-held')
    return Identity(entry['number'], entry['pull_request_id'], entry['source'],
                    entry['base'], entry['integration'], entry['entry_id']).validate()


def plan(snapshot, candidate, verify_source, verify_integration):
    """Evidence verifiers return immutable digests, never bare approval flags.

    The source verifier retains latest Opus, disjoint-family source review,
    signatures, full H CI, discussion, feature and required human proof. The
    integration verifier additionally binds exact entry/H/B/G, fresh discussion,
    compatibility, actual G tree mapping and successful current G Actions CI.
    These are trusted local adapters, not data supplied by a PR or workflow.
    """
    generation = snapshot.get('generation', 0)
    identity = entry_identity(snapshot)
    if identity:
        proof = verify_integration(identity, snapshot)
        if not isinstance(proof, str) or not DIGEST.fullmatch(proof):
            raise Held('integration-evidence-held')
        return Effect('authorize-integration', identity, proof, generation)
    if candidate is None:
        return None
    candidate.validate()
    if (candidate.integration is not None or candidate.base != snapshot['master']
            or snapshot.get('candidate') != asdict(candidate)):
        raise Held('candidate-changed')
    proof = verify_source(candidate, snapshot)
    if not isinstance(proof, str) or not DIGEST.fullmatch(proof):
        raise Held('source-evidence-held')
    authorized = snapshot.get('source_authorization')
    if authorized is not None:
        expected = Effect('authorize-source', candidate, proof, generation)
        if (not isinstance(authorized, dict) or authorized.get('source') != candidate.source
                or authorized.get('creator_id') != ACTOR_ID
                or authorized.get('state') != 'success'
                or not DIGEST.fullmatch(str(authorized.get('effect_key', '')))):
            raise Held('source-authorization-changed')
        if authorized['effect_key'] == expected.key():
            return Effect('enqueue', candidate, proof, generation)
    return Effect('authorize-source', candidate, proof, generation)


def validate_journal(journal):
    if (not isinstance(journal, dict) or journal.get('schema') != POLICY_VERSION
            or not isinstance(journal.get('effects'), dict)):
        raise Held('invalid-journal')
    for key, record in journal['effects'].items():
        if (not isinstance(record, dict) or record.get('state') not in
                {'attempted', 'unknown', 'confirmed', 'refused', 'retired-closed',
                 'not-sent', 'not-sent-exhausted'}
                or digest(record.get('payload')) != key
                or record.get('payload', {}).get('repo') != REPO):
            raise Held('invalid-journal-record')
        attempts = record.get('not_sent_attempts', 0)
        if type(attempts) is not int or attempts < 0:
            raise Held('invalid-not-sent-attempts')
        budget = record.get('not_sent_budget', MAX_NOT_SENT_ATTEMPTS)
        if type(budget) is not int or budget != MAX_NOT_SENT_ATTEMPTS:
            raise Held('invalid-not-sent-budget')
        if record['state'] in {'not-sent', 'not-sent-exhausted'}:
            if not DIGEST.fullmatch(str(record.get('response_sha256', ''))):
                raise Held('not-sent-proof-required')
            if attempts == 0 or (record['state'] == 'not-sent-exhausted' and attempts < budget):
                raise Held('invalid-not-sent-attempts')
        if record.get('state')=='retired-closed' and (not DIGEST.fullmatch(str(record.get('terminal_proof_sha256',''))) or not isinstance(record.get('retired_by'),dict) or record.get('terminal_unmerged') is not True):
            raise Held('retired-terminal-proof-required')
        payload = record['payload']
        identity = Identity(**payload['identity'])
        rebuilt = Effect(payload['kind'], identity, payload['proof_sha256'], payload['generation'])
        if rebuilt.payload() != payload:
            raise Held('invalid-journal-payload')
    return journal


def previous_result(record):
    if record is None:
        return None
    if record['state'] == 'not-sent-exhausted' or (
            record['state'] == 'not-sent'
            and record['not_sent_attempts'] >= MAX_NOT_SENT_ATTEMPTS):
        return 'not-sent-exhausted-held'
    if record['state'] != 'not-sent':
        return {'refused': 'known-refusal-held',
                'retired-closed': 'terminal-retired-held'}.get(record['state'], 'observed-confirmed')
    if time.time() < record.get('retry_after', 0):
        return 'not-sent-backoff'
    return None


def tick(journal, observe: Callable, candidate, verify_source: Callable,
         verify_integration: Callable, write: Callable, readback: Callable,
         persist: Callable, lease_held: Callable, *, shadow=False):
    """Fsync through canonical persist before invocation; never repeat attempts.

    A write exception leaves its intent attempted, including process death.
    Unknown or attempted records may only enter read-only reconciliation. Even
    authoritative absence is not a retry grant. Confirmations require an exact
    effect identity/creator/readback; the adapter validates the remote object.
    """
    validate_journal(journal)
    uncertain = [(k, r) for k, r in journal['effects'].items() if r['state'] in UNCERTAIN]
    if uncertain:
        for key, record in uncertain:
            confirmed = readback(record['payload'], key)
            if confirmed is True:
                record['state'] = 'confirmed'
                persist()
        return 'reconciled' if all(r['state'] == 'confirmed' for _, r in uncertain) else 'unknown-held'
    if not lease_held():
        return 'lease-held'
    first = validate_snapshot(observe())
    effect = plan(first, candidate, verify_source, verify_integration)
    if effect is None:
        return 'idle'
    key = effect.key()
    held = previous_result(journal['effects'].get(key))
    if held:
        return held
    if shadow:
        return 'shadow-eligible'
    # A second full fresh read and proof verification must agree. Unknown reads
    # raise before intent persistence; no mutation is attempted on uncertainty.
    second = validate_snapshot(observe())
    current = plan(second, candidate, verify_source, verify_integration)
    if current != effect or not lease_held():
        return 'identity-changed'
    return execute(journal, effect, write, readback, persist)


def execute(journal, effect, write, readback, persist):
    """Apply one already freshly checked effect, persisting before invocation."""
    validate_journal(journal)
    key = effect.key()
    if any(r['state'] in UNCERTAIN for r in journal['effects'].values()):
        return 'unknown-held'
    previous=journal['effects'].get(key)
    held = previous_result(previous)
    if held:
        return held
    journal['effects'][key] = {'payload': effect.payload(), 'state': 'attempted', 'attempted_at': time.time(),
        'not_sent_attempts':(previous or {}).get('not_sent_attempts',0),
        'not_sent_budget': MAX_NOT_SENT_ATTEMPTS,
        'last_not_sent_proof':(previous or {}).get('response_sha256')}
    persist()  # Any failure propagates: write has not been invoked.
    try:
        write(effect, key)
    except NotSent as refusal:
        attempts=journal['effects'][key].get('not_sent_attempts',0)+1
        exhausted = attempts >= MAX_NOT_SENT_ATTEMPTS
        journal['effects'][key].update(state='not-sent-exhausted' if exhausted else 'not-sent',response_sha256=refusal.response_sha256,
            not_sent_attempts=attempts,retry_after=time.time()+min(60*2**min(attempts-1,4),900))
        persist()
        return 'not-sent-exhausted-held' if exhausted else 'not-sent-backoff'
    except DefiniteRefusal as refusal:
        journal['effects'][key].update(state='refused', response_sha256=refusal.response_sha256)
        persist()
        return 'known-refusal-held'
    except Exception:
        # Catch only normal call failures. KeyboardInterrupt/SystemExit/process
        # death preserve the fsynced attempted record for read-only recovery.
        journal['effects'][key]['state'] = 'unknown'
        persist()
        return 'unknown-held'
    journal['effects'][key]['state'] = 'unknown'
    persist()
    if readback(effect.payload(), key) is True:
        journal['effects'][key]['state'] = 'confirmed'
        persist()
        return effect.kind + '-confirmed'
    return 'unknown-held'
