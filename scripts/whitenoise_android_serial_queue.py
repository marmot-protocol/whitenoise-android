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
MAX_ENTRIES = 5
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
        elif self.entry_id is not None and (
                not isinstance(self.entry_id, str) or not self.entry_id):
            raise Held('invalid-entry')
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
        if self.kind in {'authorize-source', 'enqueue'} and (
                self.identity.integration is not None or self.identity.entry_id is not None):
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
    """Validate complete ordered inventory, including unmapped waiting entries."""
    if (not isinstance(snapshot, dict) or snapshot.get('repo') != REPO
            or snapshot.get('repository_id') != REPOSITORY_ID
            or snapshot.get('actor_id') != ACTOR_ID
            or snapshot.get('protection_verified') is not True
            or snapshot.get('prerequisites_verified') is not True
            or not serial_configuration(snapshot.get('configuration'))
            or not SHA.fullmatch(str(snapshot.get('master', '')))
            or ('rules_sha256' in snapshot
                and not DIGEST.fullmatch(str(snapshot['rules_sha256'])))
            or snapshot.get('has_next_page') is not False
            or type(snapshot.get('total_count')) is not int
            or not isinstance(snapshot.get('entries'), list)
            or snapshot['total_count'] != len(snapshot['entries'])
            or not 0 <= snapshot['total_count'] <= MAX_ENTRIES):
        raise Held('queue-proof-held')
    numbers, nodes, entries = set(), set(), set()
    for position, entry in enumerate(snapshot['entries'], 1):
        if (not isinstance(entry, dict)
                or type(entry.get('position')) is not int
                or entry['position'] != position
                or entry.get('jump') is not False
                or entry.get('state') not in {'QUEUED', 'AWAITING_CHECKS', 'MERGEABLE'}
                or type(entry.get('number')) is not int
                or not 0 < entry['number'] < 2**31
                or not isinstance(entry.get('pull_request_id'), str)
                or not entry['pull_request_id']
                or not isinstance(entry.get('entry_id'), str) or not entry['entry_id']
                or not SHA.fullmatch(str(entry.get('source', '')))
                or any(entry.get(k) is not None and not SHA.fullmatch(str(entry[k]))
                       for k in ('base', 'integration'))
                or (entry.get('integration') is not None and entry.get('base') is None)
                or (entry.get('integration') is not None
                    and entry['integration'] in {entry['source'], entry['base']})
                or entry['number'] in numbers or entry['pull_request_id'] in nodes
                or entry['entry_id'] in entries):
            raise Held('queue-entry-held')
        numbers.add(entry['number'])
        nodes.add(entry['pull_request_id'])
        entries.add(entry['entry_id'])
    return snapshot


def entry_identity(snapshot):
    """Return integration identity only for a mapped current front."""
    validate_snapshot(snapshot)
    entry = snapshot['entries'][0] if snapshot['entries'] else None
    if (entry is None
            or entry.get('base') is None or entry.get('integration') is None):
        return None
    if entry['base'] != snapshot['master']:
        raise Held('queue-entry-held')
    return Identity(entry['number'], entry['pull_request_id'], entry['source'],
                    entry['base'], entry['integration'], entry['entry_id']).validate()


def generation_for(snapshot, identity):
    """Read a PR's recovery generation without borrowing another PR's grant."""
    if 'generations' in snapshot:
        generations = snapshot['generations']
        if (not isinstance(generations, dict) or any(
                not isinstance(number, str) or not 1 <= len(number) <= 10
                or not number.isascii() or not number.isdecimal()
                or not 0 < int(number) < 2**31 or str(int(number)) != number
                or type(value) is not int or value < 0
                for number, value in generations.items())):
            raise Held('generations-held')
        return generations.get(str(identity.number), 0)
    generation = snapshot.get('generation', 0)
    if type(generation) is not int or generation < 0:
        raise Held('generation-held')
    if generation > 0:
        identities = {(entry['number'], entry['pull_request_id']) for entry in snapshot['entries']}
        identities.add((identity.number, identity.pull_request_id))
        candidate = snapshot.get('candidate')
        if candidate is not None:
            if (not isinstance(candidate, dict) or type(candidate.get('number')) is not int
                    or not isinstance(candidate.get('pull_request_id'), str)):
                raise Held('generation-map-required')
            identities.add((candidate['number'], candidate['pull_request_id']))
        if len(identities) != 1:
            raise Held('generation-map-required')
    return generation


def plan(snapshot, candidate, verify_source, verify_integration, *, target_number=None):
    """Evidence verifiers return immutable digests, never bare approval flags.

    The source verifier checks source review, signatures, applicable CI,
    discussion and required feature evidence. Source admission evidence and
    native queue rules remain separate from integration authorization. The
    integration verifier additionally binds exact entry/H/B/G, current discussion,
    reviewed compatibility, actual G tree mapping and successful current G CI.
    These are trusted local adapters, not data supplied by a PR or workflow.
    """
    front = entry_identity(snapshot)
    scoped = target_number is not None
    if scoped and (type(target_number) is not int or target_number <= 0
                   or candidate is None or candidate.number != target_number):
        raise Held('target-candidate-changed')
    use_front = front is not None and (not scoped or front.number == target_number)
    if candidate is not None and (scoped or not use_front):
        candidate.validate()
        if (candidate.integration is not None or candidate.entry_id is not None
                or snapshot.get('candidate') != asdict(candidate)):
            raise Held('candidate-changed')
    if use_front:
        if (scoped and (front.pull_request_id, front.source)
                != (candidate.pull_request_id, candidate.source)):
            raise Held('candidate-changed')
        generation = generation_for(snapshot, front)
        proof = verify_integration(front, snapshot)
        if not isinstance(proof, str) or not DIGEST.fullmatch(proof):
            raise Held('integration-evidence-held')
        return Effect('authorize-integration', front, proof, generation)
    if candidate is None:
        return None
    entry = next((item for item in snapshot['entries']
                  if item['number'] == candidate.number), None)
    if entry is not None:
        if (entry['pull_request_id'], entry['source']) != (candidate.pull_request_id, candidate.source):
            raise Held('candidate-changed')
        return None
    if len(snapshot['entries']) >= MAX_ENTRIES:
        raise Held('queue-capacity-held')
    if candidate.base != snapshot['master']:
        raise Held('candidate-changed')
    generation = generation_for(snapshot, candidate)
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
        if (not isinstance(record, dict) or not isinstance(record.get('payload'), dict)
                or record.get('state') not in
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
        withdrawal = record.get('exhaustion_withdrawal')
        if withdrawal is not None and (
                record['state'] not in {'not-sent', 'not-sent-exhausted'}
                or attempts < budget or not isinstance(withdrawal, dict)
                or not DIGEST.fullmatch(str(withdrawal.get('proof_sha256', '')))
                or not isinstance(withdrawal.get('owner'), dict) or not withdrawal['owner']):
            raise Held('invalid-exhaustion-withdrawal')
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


def exhaustion_unresolved(journal, record):
    """One shared latch predicate for policy and adapter selection retirement."""
    exhausted = record['state'] == 'not-sent-exhausted' or (
        record['state'] == 'not-sent'
        and record['not_sent_attempts'] >= MAX_NOT_SENT_ATTEMPTS)
    if not exhausted or record.get('exhaustion_withdrawal') is not None:
        return False
    previous = record['payload']
    identity = previous['identity']
    return not any(
        newer['state'] == 'confirmed'
        and newer['payload']['kind'] == previous['kind']
        and newer['payload']['identity']['number'] == identity['number']
        and newer['payload']['identity']['pull_request_id'] == identity['pull_request_id']
        and (previous['kind'] not in {'revoke-integration', 'dequeue'}
             or newer['payload']['identity'] == identity)
        and newer['payload']['generation'] > previous['generation']
        for newer in journal['effects'].values())


def refused_selection_result(journal, effect):
    """A known refusal holds its PR until a reviewed newer admission generation."""
    if effect is None:
        return None
    for record in journal['effects'].values():
        previous = record['payload']
        if (record['state'] == 'refused'
                and previous['identity']['number'] == effect.identity.number
                and previous['identity']['pull_request_id'] == effect.identity.pull_request_id
                and previous['generation'] >= effect.generation
                and (effect.kind not in {'revoke-integration', 'dequeue'}
                     or (previous['kind'] == effect.kind
                         and previous['identity'] == asdict(effect.identity)))):
            return 'known-refusal-held'
    return None


def exhausted_selection_result(journal, effect):
    """Keep exhausted retry budgets attached to their PR and generation."""
    # Cleanup bypasses admission holds, not its own exhausted retry budget.
    # A changed proof is fresh validation rather than explicit recovery.
    if effect is not None and effect.kind in {'revoke-integration', 'dequeue'}:
        for record in journal['effects'].values():
            previous = record['payload']
            if (exhaustion_unresolved(journal, record)
                    and previous['kind'] == effect.kind
                    and previous['identity'] == asdict(effect.identity)
                    and previous['generation'] >= effect.generation):
                return 'not-sent-exhausted-held'
        return None
    for record in journal['effects'].values():
        if not exhaustion_unresolved(journal, record):
            continue
        previous = record['payload']
        identity = previous['identity']
        if (effect is not None
                and effect.identity.number == identity['number']
                and effect.identity.pull_request_id == identity['pull_request_id']
                and effect.generation <= previous['generation']):
            return 'not-sent-exhausted-held'
    return None


def tick(journal, observe: Callable, candidate, verify_source: Callable,
         verify_integration: Callable, write: Callable, readback: Callable,
         persist: Callable, lease_held: Callable, *, shadow=False, target_number=None):
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
    def choose(snapshot):
        effect = plan(snapshot, candidate, verify_source, verify_integration,
                      target_number=target_number)
        if (target_number is None and candidate is not None and effect is not None
                and effect.kind == 'authorize-integration'
                and effect.identity.number != candidate.number
                and journal['effects'].get(effect.key(), {}).get('state') == 'confirmed'):
            return plan(snapshot, candidate, verify_source, verify_integration,
                        target_number=candidate.number)
        return effect

    first = observe()
    effect = choose(first)
    held = refused_selection_result(journal, effect)
    if held:
        return held
    held = exhausted_selection_result(journal, effect)
    if held:
        return held
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
    second = observe()
    current = choose(second)
    if (current != effect or ('rules_sha256' in first, first.get('rules_sha256'))
            != ('rules_sha256' in second, second.get('rules_sha256'))
            or not lease_held()):
        return 'identity-changed'
    return execute(journal, effect, write, readback, persist)


def execute(journal, effect, write, readback, persist):
    """Apply one already freshly checked effect, persisting before invocation."""
    validate_journal(journal)
    key = effect.key()
    if any(r['state'] in UNCERTAIN for r in journal['effects'].values()):
        return 'unknown-held'
    held = refused_selection_result(journal, effect)
    if held:
        return held
    held = exhausted_selection_result(journal, effect)
    if held:
        return held
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
