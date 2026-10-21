"""Failure-boundary tests for the actual serial queue state machine."""
import copy
import json
from dataclasses import asdict
import unittest

from scripts import whitenoise_android_serial_queue as q


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.identity = q.Identity(10, 'PR_node', 'a' * 40, 'b' * 40)
        self.journal = {'schema': 1, 'effects': {}}
        self.snapshot = {'repo': q.REPO, 'repository_id': q.REPOSITORY_ID,
                         'actor_id': q.ACTOR_ID, 'protection_verified': True,
                         'prerequisites_verified': True, 'master': 'b' * 40,
                         'rules_sha256': 'a' * 64,
                         'configuration': {'maximumEntriesToBuild': 1,
                             'maximumEntriesToMerge': 1, 'minimumEntriesToMerge': 1,
                             'minimumEntriesToMergeWaitTime': 0, 'mergeMethod': 'SQUASH',
                             'mergingStrategy': 'ALLGREEN', 'checkResponseTimeout': 120},
                         'has_next_page': False, 'total_count': 0, 'entries': [],
                         'candidate': asdict(self.identity)}
        self.writes, self.saved = [], []
        self.source = lambda *_: 'c' * 64
        self.integration = lambda *_: None
        self.lease = lambda: True
        self.readback = lambda *_: True

    def save(self):
        self.saved.append(copy.deepcopy(self.journal))

    def write(self, effect, key):
        self.assertEqual(self.saved[-1]['effects'][key]['state'], 'attempted')
        self.writes.append((effect, key))

    def tick(self, **kw):
        args = dict(journal=self.journal, observe=lambda: copy.deepcopy(self.snapshot),
                    candidate=self.identity, verify_source=self.source,
                    verify_integration=self.integration, write=self.write,
                    readback=self.readback, persist=self.save, lease_held=self.lease)
        args.update(kw)
        return q.tick(**args)

    def queued(self, head='d' * 40):
        self.snapshot.update(total_count=1, entries=[{
            'position': 1, 'state': 'AWAITING_CHECKS', 'base': 'b' * 40,
            'jump': False, 'number': 10,
            'pull_request_id': 'PR_node', 'source': 'a' * 40,
            'integration': head, 'entry_id': 'ENTRY_node'}])

    def test_journal_rejects_non_mapping_payload_and_record(self):
        for payload in (None, 7, 'payload', [], True):
            with self.subTest(payload=payload):
                record = {'state': 'confirmed', 'payload': payload}
                journal = {'schema': 1, 'effects': {q.digest(payload): record}}
                with self.assertRaisesRegex(q.Held, 'invalid-journal-record'):
                    q.validate_journal(journal)
        for record in (None, 7, 'record', [], True):
            with self.subTest(record=record):
                with self.assertRaisesRegex(q.Held, 'invalid-journal-record'):
                    q.validate_journal({'schema': 1, 'effects': {'a' * 64: record}})

    def test_boolean_journal_schema_holds(self):
        self.journal['schema'] = True
        with self.assertRaisesRegex(q.Held, 'invalid-journal'):
            self.tick()
        self.assertFalse(self.writes)

    def test_non_string_record_state_holds(self):
        payload = q.Effect('authorize-source', self.identity, 'c' * 64).payload()
        for state in ([], {}, None, True, 7):
            with self.subTest(state=state):
                self.journal = {'schema': 1, 'effects': {q.digest(payload):
                    {'payload': payload, 'state': state}}}
                with self.assertRaisesRegex(q.Held, 'invalid-journal-record'):
                    self.tick()
        self.assertFalse(self.writes)

    def test_malformed_mapping_payload_holds_before_any_write(self):
        valid = q.Effect('authorize-source', self.identity, 'c' * 64).payload()
        payloads = [{'repo': q.REPO}, dict(valid, identity=[]),
                    dict(valid, identity=dict(valid['identity'], unexpected=1)),
                    dict(valid, identity=dict(valid['identity'], source=None)),
                    dict(valid, proof_sha256=[]), dict(valid, generation=True),
                    dict(valid, kind=[]), dict(valid, schema=True),
                    dict(valid, identity=dict(valid['identity'], base=[])),
                    dict(valid, identity=dict(valid['identity'], integration=[]))]
        for payload in payloads:
            with self.subTest(payload=payload):
                self.journal = {'schema': 1, 'effects': {q.digest(payload):
                    {'state': 'confirmed', 'payload': payload}}}
                with self.assertRaisesRegex(q.Held, 'invalid-journal-payload'):
                    self.tick()
        self.assertFalse(self.writes)

    def test_map_and_scalar_input_is_ambiguous(self):
        self.snapshot.update(generations={'10': 1}, generation=0)
        with self.assertRaisesRegex(q.Held, 'generations-held'):
            self.tick()
        self.assertFalse(self.writes)

    def test_held_front_proof_drift_blocks_peer_before_write(self):
        self.queued()
        front = q.entry_identity(self.snapshot)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        for proof in ('d' * 64, 'e' * 64):
            effect = q.Effect('authorize-integration', front, proof)
            self.journal['effects'][effect.key()] = {'payload': effect.payload(), 'state': 'refused'}
        proofs = iter(('d' * 64, 'e' * 64))
        self.assertEqual(self.tick(candidate=other, verify_integration=lambda *_: next(proofs)),
                         'identity-changed')
        self.assertFalse(self.writes)

    def test_unknown_front_never_falls_through_to_peer(self):
        self.queued()
        front = q.entry_identity(self.snapshot)
        effect = q.Effect('authorize-integration', front, 'e' * 64)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.journal['effects'][effect.key()] = {'payload': effect.payload(), 'state': 'unknown'}
        self.assertEqual(self.tick(candidate=other, readback=lambda *_: False), 'unknown-held')
        self.assertFalse(self.writes)

    def test_default_held_front_admits_peer_without_replaying_front(self):
        self.queued()
        front = q.entry_identity(self.snapshot)
        effect = q.Effect('authorize-integration', front, 'e' * 64)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.integration = lambda *_: 'e' * 64
        for state in ('refused', 'not-sent-exhausted'):
            with self.subTest(state=state):
                record = {'payload': effect.payload(), 'state': state}
                if state == 'not-sent-exhausted':
                    record.update(not_sent_attempts=3, response_sha256='d' * 64)
                self.journal = {'schema': 1, 'effects': {effect.key(): record}}
                original = copy.deepcopy(record)
                self.writes.clear()
                self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
                self.assertEqual(self.writes[-1][0].identity, other)
                self.assertEqual(self.journal['effects'][effect.key()], original)

    def test_default_mapped_front_precedes_absent_or_different_candidate(self):
        self.queued()
        front = q.entry_identity(self.snapshot)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        for candidate in (None, other):
            with self.subTest(candidate=candidate):
                self.snapshot['candidate'] = asdict(candidate) if candidate else None
                effect = q.plan(self.snapshot, candidate,
                                lambda *_: self.fail('source must not precede front'),
                                lambda identity, _: 'e' * 64 if identity == front else None)
                self.assertEqual(effect.kind, 'authorize-integration')
                self.assertEqual(effect.identity, front)

    def test_named_target_front_then_peer_admission_keeps_front_key(self):
        self.queued()
        self.integration = lambda identity, _: 'e' * 64 if identity.number == 10 else None
        self.assertEqual(self.tick(target_number=10), 'authorize-integration-confirmed')
        original = copy.deepcopy(self.journal)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other, target_number=11,
                                  verify_integration=lambda *_: self.fail('foreign front credit')),
                         'authorize-source-confirmed')
        peer = self.writes[-1][0]
        self.snapshot['source_authorization'] = {'effect_key': peer.key(),
            'source': other.source, 'creator_id': q.ACTOR_ID, 'state': 'success'}
        self.assertEqual(self.tick(candidate=other, target_number=11,
                                  verify_integration=lambda *_: self.fail('foreign front credit')),
                         'enqueue-confirmed')
        for key, record in original['effects'].items():
            self.assertEqual(self.journal['effects'][key], record)
        self.assertEqual([effect.kind for effect, _ in self.writes],
                         ['authorize-integration', 'authorize-source', 'enqueue'])

    def test_named_front_must_match_candidate_source_and_node(self):
        self.queued()
        for source, node in (('f' * 40, self.identity.pull_request_id),
                             (self.identity.source, 'different-node')):
            candidate = q.Identity(10, node, source, self.identity.base)
            self.snapshot['candidate'] = asdict(candidate)
            with self.subTest(source=source, node=node), self.assertRaisesRegex(q.Held, 'candidate-changed'):
                self.tick(candidate=candidate, target_number=10,
                          verify_source=lambda *_: self.fail('unbound source proof'),
                          verify_integration=lambda *_: self.fail('unbound integration proof'))
        self.assertFalse(self.writes)
        self.assertFalse(self.journal['effects'])

    def test_named_target_is_exact_positive_integer_bound_to_candidate(self):
        for target in (True, False, 0, -1, '10', 11):
            with self.subTest(target=target), self.assertRaises(q.Held):
                self.tick(target_number=target)
        with self.assertRaises(q.Held):
            self.tick(candidate=None, target_number=10)
        self.assertFalse(self.writes)

    def test_exhausted_peer_does_not_hold_default_front(self):
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        effect = q.Effect('authorize-source', other, 'c' * 64)
        self.journal['effects'][effect.key()] = {'payload': effect.payload(),
            'state': 'not-sent-exhausted', 'not_sent_attempts': 3,
            'not_sent_budget': 3, 'response_sha256': 'e' * 64, 'retry_after': 0}
        self.queued()
        self.integration = lambda *_: 'e' * 64
        self.assertEqual(self.tick(candidate=None), 'authorize-integration-confirmed')
        self.assertEqual(self.journal['effects'][effect.key()]['not_sent_attempts'], 3)

    def test_exhausted_front_does_not_hold_named_peer(self):
        self.queued()
        front = q.entry_identity(self.snapshot)
        effect = q.Effect('authorize-integration', front, 'e' * 64)
        self.journal['effects'][effect.key()] = {'payload': effect.payload(),
            'state': 'not-sent-exhausted', 'not_sent_attempts': 3,
            'not_sent_budget': 3, 'response_sha256': 'e' * 64, 'retry_after': 0}
        self.assertEqual(self.tick(candidate=None, verify_integration=lambda *_: 'e' * 64),
                         'not-sent-exhausted-held')
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other, target_number=11,
                                  verify_integration=lambda *_: self.fail('foreign front credit')),
                         'authorize-source-confirmed')
        self.assertEqual(self.journal['effects'][effect.key()]['not_sent_attempts'], 3)

    def test_generation_binding_does_not_release_another_pr_hold(self):
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        for state in ('refused', 'not-sent-exhausted'):
            with self.subTest(state=state):
                held = q.Effect('authorize-source', other, 'c' * 64)
                record = {'payload': held.payload(), 'state': state}
                if state == 'not-sent-exhausted':
                    record.update(not_sent_attempts=3, not_sent_budget=3,
                                  response_sha256='e' * 64, retry_after=0)
                self.journal = {'schema': 1, 'effects': {held.key(): record}}
                original = copy.deepcopy(self.journal)
                self.snapshot.update(generations={'10': 1}, candidate=asdict(other))
                expected = 'known-refusal-held' if state == 'refused' else 'not-sent-exhausted-held'
                self.assertEqual(self.tick(candidate=other), expected)
                self.assertEqual(self.journal, original)
        self.assertFalse(self.writes)


    def test_named_recovery_releases_its_pr_without_releasing_peer(self):
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        for state in ('refused', 'not-sent-exhausted'):
            with self.subTest(state=state):
                effects = [q.Effect('authorize-source', identity, 'c' * 64)
                           for identity in (self.identity, other)]
                records = {}
                for effect in effects:
                    record = {'payload': effect.payload(), 'state': state}
                    if state == 'not-sent-exhausted':
                        record.update(not_sent_attempts=3, not_sent_budget=3,
                                      response_sha256='e' * 64, retry_after=0)
                    records[effect.key()] = record
                self.journal = {'schema': 1, 'effects': records}
                original = copy.deepcopy(self.journal)
                self.snapshot.update(generations={'10': 1},
                                     candidate=asdict(self.identity))
                self.assertEqual(self.tick(target_number=10), 'authorize-source-confirmed')
                for key, record in original['effects'].items():
                    self.assertEqual(self.journal['effects'][key], record)
                self.snapshot.update(candidate=asdict(other))
                expected = 'known-refusal-held' if state == 'refused' else 'not-sent-exhausted-held'
                self.assertEqual(self.tick(candidate=other, target_number=11), expected)
                self.assertEqual(self.journal['effects'][effects[1].key()],
                                 original['effects'][effects[1].key()])
        self.assertEqual(len(self.writes), 2)

    def test_foreign_generation_cannot_reauthorize_default_front(self):
        self.queued()
        self.integration = lambda *_: 'e' * 64
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot.update(generations={'11': 1}, candidate=asdict(other))
        self.assertEqual(self.tick(candidate=other), 'authorize-integration-confirmed')
        front_record = copy.deepcopy(self.journal)
        self.assertEqual(self.writes[-1][0].generation, 0)
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        peer = self.writes[-1][0]
        self.assertEqual(peer.generation, 1)
        self.snapshot['source_authorization'] = {'effect_key': peer.key(),
            'source': other.source, 'creator_id': q.ACTOR_ID, 'state': 'success'}
        self.assertEqual(self.tick(candidate=other), 'enqueue-confirmed')
        self.assertEqual([effect.kind for effect, _ in self.writes],
                         ['authorize-integration', 'authorize-source', 'enqueue'])
        for key, record in front_record['effects'].items():
            self.assertEqual(self.journal['effects'][key], record)


    def test_generations_map_rejects_noncanonical_keys_and_values(self):
        for generations in (None, [], {'0': 1}, {'01': 1}, {'-1': 1},
                            {'2147483648': 1}, {'9' * 5000: 1}, {10: 1}, {'10': True},
                            {'10': -1}, {'10': '1'}):
            self.snapshot['generations'] = generations
            with self.subTest(generations=generations), self.assertRaisesRegex(q.Held, 'generations-held'):
                self.tick(verify_source=lambda *_: self.fail('invalid generation proof'))
        self.assertFalse(self.writes)
        self.assertFalse(self.journal['effects'])


    def test_positive_scalar_requires_map_even_for_singleton_refusal(self):
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        effect = q.Effect('authorize-source', other, 'c' * 64, 0)
        self.journal['effects'][effect.key()] = {'payload': effect.payload(), 'state': 'refused'}
        original = copy.deepcopy(self.journal)
        self.snapshot['generation'] = 1
        with self.assertRaisesRegex(q.Held, 'generation-map-required'):
            self.tick(candidate=other)
        self.assertEqual(self.journal, original)
        self.assertFalse(self.writes)

    def test_legacy_positive_scalar_with_multiple_prs_requires_map(self):
        self.queued()
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot.update(generation=1, candidate=asdict(other))
        with self.assertRaisesRegex(q.Held, 'generation-map-required'):
            self.tick(candidate=other,
                      verify_integration=lambda *_: self.fail('ambiguous legacy grant'))
        self.assertFalse(self.writes)
        self.snapshot.pop('generation')
        self.snapshot.update(generations={'11': 1})
        self.assertEqual(self.tick(candidate=other, verify_integration=lambda *_: 'e' * 64),
                         'authorize-integration-confirmed')
        self.assertEqual(self.writes[-1][0].generation, 0)

    def test_optional_rules_digest_supports_legacy_and_detects_presence_drift(self):
        self.snapshot.pop('rules_sha256')
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        self.setUp()
        first = copy.deepcopy(self.snapshot)
        first.pop('rules_sha256')
        observations = iter([first, copy.deepcopy(self.snapshot)])
        self.assertEqual(self.tick(observe=lambda: next(observations)), 'identity-changed')
        self.assertFalse(self.writes)
        self.assertFalse(self.journal['effects'])

    def test_default_peer_admission_requires_same_fresh_front_proof(self):
        self.queued()
        self.integration = lambda *_: 'e' * 64
        self.assertEqual(self.tick(), 'authorize-integration-confirmed')
        original = copy.deepcopy(self.journal)
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        proofs = iter(['e' * 64, 'f' * 64])
        self.assertEqual(self.tick(candidate=other,
                                  verify_integration=lambda *_: next(proofs)),
                         'identity-changed')
        self.assertEqual(self.journal, original)
        self.assertEqual(len(self.writes), 1)

    def test_changed_front_proof_is_authorized_before_default_peer(self):
        self.queued()
        self.integration = lambda *_: 'e' * 64
        self.assertEqual(self.tick(), 'authorize-integration-confirmed')
        other = q.Identity(11, 'PR_11', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other, verify_integration=lambda *_: 'f' * 64),
                         'authorize-integration-confirmed')
        self.assertEqual([effect.kind for effect, _ in self.writes],
                         ['authorize-integration', 'authorize-integration'])

    def test_source_authorization_is_a_separate_effect_before_enqueue(self):
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        source_effect = self.writes[0][0]
        self.assertIsNone(source_effect.identity.integration)
        self.snapshot['source_authorization'] = {'effect_key': source_effect.key(),
            'source': self.identity.source, 'creator_id': q.ACTOR_ID, 'state': 'success'}
        self.assertEqual(self.tick(), 'enqueue-confirmed')
        self.assertEqual([e.kind for e, _ in self.writes], ['authorize-source', 'enqueue'])

    def test_ci_alone_and_h_admission_never_authorize_g(self):
        self.tick()
        self.queued()
        with self.assertRaises(q.Held):
            self.tick()
        self.assertEqual(len(self.writes), 1)

    def test_g_needs_its_exact_compatibility_proof(self):
        self.queued()
        valid = q.entry_identity(self.snapshot)
        self.integration = lambda identity, _: 'e' * 64 if identity == valid else None
        self.assertEqual(self.tick(), 'authorize-integration-confirmed')
        self.snapshot['entries'][0]['integration'] = 'f' * 40
        with self.assertRaises(q.Held):
            self.tick()
        self.assertEqual(len(self.writes), 1)

    def test_changed_h_invalidates_the_source_candidate(self):
        self.snapshot['candidate']['source'] = 'f' * 40
        with self.assertRaises(q.Held):
            self.tick()
        self.assertFalse(self.writes)

    def test_changed_h_invalidates_integration_proof(self):
        self.queued()
        valid = q.entry_identity(self.snapshot)
        self.integration = lambda i, _: 'e' * 64 if i == valid else None
        self.snapshot['entries'][0]['source'] = 'f' * 40
        with self.assertRaises(q.Held):
            self.tick()
        self.assertFalse(self.writes)

    def test_changed_base_holds_existing_queue_integration(self):
        self.queued()
        self.snapshot['master'] = 'f' * 40
        with self.assertRaises(q.Held):
            self.tick()
        self.assertFalse(self.writes)

    def test_identity_change_between_live_reads_cannot_write(self):
        second = copy.deepcopy(self.snapshot)
        second['candidate']['source'] = 'f' * 40
        reads = iter([self.snapshot, second])
        with self.assertRaises(q.Held):
            self.tick(observe=lambda: next(reads))
        self.assertFalse(self.saved)
        self.assertFalse(self.writes)

    def test_missing_or_boolean_review_is_not_evidence(self):
        for proof in [None, False, True, '', 'a' * 40, 'x' * 64]:
            with self.subTest(proof=proof), self.assertRaises(q.Held):
                self.tick(verify_source=lambda *_, p=proof: p)
        self.assertFalse(self.writes)

    def test_serial_protection_and_bounded_inventory_are_mandatory(self):
        for key, value in [('actor_id', 5), ('repository_id', 4), ('has_next_page', True),
                           ('protection_verified', False), ('prerequisites_verified', False),
                           ('total_count', 2), ('configuration', {})]:
            bad = {**self.snapshot, key: value}
            with self.subTest(key=key), self.assertRaises(q.Held):
                self.tick(observe=lambda: bad)
        self.assertFalse(self.writes)

    def test_unmapped_foreign_or_partial_queue_entry_is_held(self):
        self.queued()
        for key, value in [('position', 2), ('state', 'unknown'), ('jump', True),
                           ('entry_id', None), ('source', 'invalid')]:
            bad = copy.deepcopy(self.snapshot)
            bad['entries'][0][key] = value
            with self.subTest(key=key), self.assertRaises((q.Held, TypeError)):
                self.tick(observe=lambda: bad)
        self.assertFalse(self.writes)

    def test_foreign_source_authorization_is_held(self):
        self.snapshot['source_authorization'] = {'effect_key': 'c' * 64,
            'source': self.identity.source, 'creator_id': 5, 'state': 'success'}
        with self.assertRaises(q.Held):
            self.tick()
        self.assertFalse(self.writes)

    def test_own_old_source_status_requires_fresh_authorization(self):
        self.snapshot['source_authorization'] = {'effect_key': 'f' * 64,
            'source': self.identity.source, 'creator_id': q.ACTOR_ID, 'state': 'success'}
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        self.assertEqual(self.writes[0][0].kind, 'authorize-source')

    def test_lost_write_response_never_replays_even_when_remote_absent(self):
        def lost(effect, key):
            self.write(effect, key)
            raise TimeoutError('lost response')
        self.assertEqual(self.tick(write=lost, readback=lambda *_: False), 'unknown-held')
        self.assertEqual(self.tick(readback=lambda *_: False), 'unknown-held')
        self.assertEqual(len(self.writes), 1)
        self.assertEqual(self.tick(), 'reconciled')
        self.assertEqual(self.tick(), 'observed-confirmed')
        self.assertEqual(len(self.writes), 1)

    def test_process_death_after_intent_only_reconciles(self):
        def killed(effect, key):
            self.write(effect, key)
            raise SystemExit(137)
        with self.assertRaises(SystemExit):
            self.tick(write=killed)
        self.assertEqual(next(iter(self.journal['effects'].values()))['state'], 'attempted')
        self.assertEqual(self.tick(readback=lambda *_: False), 'unknown-held')
        self.assertEqual(len(self.writes), 1)

    def test_failed_fsync_prevents_effect(self):
        def failing_save():
            raise OSError('cannot persist')
        with self.assertRaises(OSError):
            self.tick(persist=failing_save)
        self.assertFalse(self.writes)

    def test_corrupt_confirmed_intent_cannot_be_ignored(self):
        self.tick()
        record = next(iter(self.journal['effects'].values()))
        record['payload']['identity']['source'] = 'f' * 40
        with self.assertRaises(q.Held):
            self.tick()
        self.assertEqual(len(self.writes), 1)

    def test_incomplete_readback_is_not_confirmation(self):
        self.assertEqual(self.tick(readback=lambda *_: None), 'unknown-held')
        self.assertEqual(self.tick(readback=lambda *_: 'success'), 'unknown-held')
        self.assertEqual(len(self.writes), 1)

    def test_shadow_and_missing_lease_never_write(self):
        self.assertEqual(self.tick(shadow=True), 'shadow-eligible')
        self.assertEqual(self.tick(lease_held=lambda: False), 'lease-held')
        self.assertFalse(self.writes)
        self.assertFalse(self.saved)

    def test_lost_lease_after_revalidation_cannot_write(self):
        lease = iter([True, False])
        self.assertEqual(self.tick(lease_held=lambda: next(lease)), 'identity-changed')
        self.assertFalse(self.writes)
        self.assertFalse(self.saved)

    def test_confirmed_effect_never_repeats_after_a_duplicate_tick(self):
        self.tick()
        self.assertEqual(self.tick(), 'observed-confirmed')
        self.assertEqual(len(self.writes), 1)


    def test_known_api_refusal_is_not_an_unknown_write_or_automatic_retry(self):
        def rejected(*_):
            raise q.DefiniteRefusal('e' * 64)
        self.assertEqual(self.tick(write=rejected), 'known-refusal-held')
        record = next(iter(self.journal['effects'].values()))
        self.assertEqual(record['state'], 'refused')
        self.assertEqual(record['response_sha256'], 'e' * 64)
        self.assertEqual(self.tick(), 'known-refusal-held')
        self.assertFalse(self.writes)

    def test_two_positive_no_send_failures_recover_after_fresh_validation(self):
        from unittest.mock import patch
        attempts=[];proofs=[]
        def write(effect,key):
            self.assertEqual(self.saved[-1]['effects'][key]['state'],'attempted')
            attempts.append(key)
            if len(attempts)<3:raise q.NotSent('e'*64)
            self.write(effect,key)
        def proof(*args):proofs.append(args);return 'c'*64
        with patch.object(q.time,'time',return_value=1000):
            self.assertEqual(self.tick(write=write,verify_source=proof),'not-sent-backoff')
            self.assertEqual(self.tick(write=write,verify_source=proof),'not-sent-backoff')
        self.assertEqual(len(attempts),1)
        with patch.object(q.time,'time',return_value=1060):
            self.assertEqual(self.tick(write=write,verify_source=proof),'not-sent-backoff')
        with patch.object(q.time,'time',return_value=1180):
            before=len(proofs)
            self.assertEqual(self.tick(write=write,verify_source=proof),'authorize-source-confirmed')
            self.assertEqual(len(proofs)-before,2)
        self.assertEqual(len(set(attempts)),1)
        record=next(iter(self.journal['effects'].values()))
        self.assertEqual(record['not_sent_attempts'],2)
        self.assertEqual(record['last_not_sent_proof'],'e'*64)
        self.assertEqual(record['state'],'confirmed')

    def test_positive_no_send_retry_cannot_skip_fresh_identity_checks(self):
        from unittest.mock import patch
        with patch.object(q.time,'time',return_value=1000):
            self.assertEqual(self.tick(write=lambda *_: (_ for _ in ()).throw(q.NotSent('e'*64))),'not-sent-backoff')
        second=copy.deepcopy(self.snapshot);second['candidate']['source']='f'*40
        with patch.object(q.time,'time',return_value=2000), self.assertRaises(q.Held):
            reads=iter([self.snapshot,second]);self.tick(observe=lambda:next(reads))
        self.assertFalse(self.writes)
        self.assertEqual(next(iter(self.journal['effects'].values()))['state'],'not-sent')
        with patch.object(q.time,'time',return_value=2000), self.assertRaises(q.Held):
            self.tick(verify_source=lambda *_:None)
        self.assertFalse(self.writes)

    def exhaust_never_sent_budget(self):
        from unittest.mock import patch
        attempts = []

        def never_sent(effect, key):
            self.assertEqual(self.saved[-1]['effects'][key]['state'], 'attempted')
            attempts.append(effect)
            raise q.NotSent('e' * 64)

        for index in range(q.MAX_NOT_SENT_ATTEMPTS):
            with patch.object(q.time, 'time', return_value=1000 + index * 1000):
                result = self.tick(write=never_sent)
            self.assertEqual(result, 'not-sent-exhausted-held' if index == 2 else 'not-sent-backoff')
            # Load only the last persisted state, as a replacement process does.
            self.journal = copy.deepcopy(self.saved[-1])
        return attempts

    def test_never_sent_exhaustion_survives_restart_and_later_ticks(self):
        from unittest.mock import patch
        attempts = self.exhaust_never_sent_budget()
        record = next(iter(self.journal['effects'].values()))
        self.assertEqual(record['state'], 'not-sent-exhausted')
        self.assertEqual(record['not_sent_budget'], 3)
        self.assertEqual(record['not_sent_attempts'], 3)
        saved = copy.deepcopy(self.journal)
        with patch.object(q.time, 'time', return_value=1000000):
            for _ in range(5):
                self.assertEqual(self.tick(), 'not-sent-exhausted-held')
            self.assertEqual(self.tick(shadow=True), 'not-sent-exhausted-held')
        self.assertEqual(len(attempts), 3)
        self.assertEqual(self.journal, saved)
        self.assertFalse(self.writes)

    def test_direct_execution_cannot_bypass_exhausted_budget(self):
        attempts = self.exhaust_never_sent_budget()
        self.assertEqual(q.execute(self.journal, attempts[-1], self.write,
                                  self.readback, self.save), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_legacy_never_sent_counter_at_limit_cannot_retry(self):
        from unittest.mock import patch
        self.exhaust_never_sent_budget()
        record = next(iter(self.journal['effects'].values()))
        record['state'] = 'not-sent'
        record.pop('not_sent_budget')
        with patch.object(q.time, 'time', return_value=1000000):
            self.assertEqual(self.tick(), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_corrupt_retry_budget_or_counter_holds_without_invocation(self):
        self.exhaust_never_sent_budget()
        original = copy.deepcopy(self.journal)
        for field, values in [('not_sent_attempts', [True, -1, '3', 0]),
                              ('not_sent_budget', [True, -1, '3', 4])]:
            for value in values:
                with self.subTest(field=field, value=value):
                    self.journal = copy.deepcopy(original)
                    next(iter(self.journal['effects'].values()))[field] = value
                    with self.assertRaises(q.Held):
                        self.tick()
        self.assertFalse(self.writes)

    def test_owner_generation_recovery_retains_exhaustion_and_revalidates(self):
        self.exhaust_never_sent_budget()
        old_key = next(iter(self.journal['effects']))
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        with self.assertRaises(q.Held):
            self.tick(verify_source=lambda *_: None)
        self.assertFalse(self.writes)
        calls = []
        self.assertEqual(self.tick(verify_source=lambda *args: calls.append(args) or 'c' * 64),
                         'authorize-source-confirmed')
        self.assertEqual(len(calls), 2)
        self.assertEqual(self.journal['effects'][old_key]['state'], 'not-sent-exhausted')
        self.assertEqual(self.writes[-1][0].generation, 1)

    def test_generation_change_does_not_release_unknown_sent_effect(self):
        self.assertEqual(self.tick(readback=lambda *_: False), 'unknown-held')
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(readback=lambda *_: False), 'unknown-held')
        self.assertEqual(len(self.writes), 1)

    def test_exhaustion_is_scoped_to_its_pr_in_tick_and_execute(self):
        self.exhaust_never_sent_budget()
        old = copy.deepcopy(self.journal)
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        effect = q.Effect('enqueue', other, 'c' * 64, 1)
        self.assertEqual(q.execute(self.journal, effect, self.write,
                                  self.readback, self.save), 'enqueue-confirmed')
        for key, record in old['effects'].items():
            self.assertEqual(self.journal['effects'][key], record)
        self.snapshot['candidate'] = asdict(self.identity)
        self.snapshot['generations'] = {}
        self.assertEqual(self.tick(), 'not-sent-exhausted-held')

    def test_confirmed_explicit_recovery_releases_historical_exhaustion_hold(self):
        self.exhaust_never_sent_budget()
        old_key = next(iter(self.journal['effects']))
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.snapshot['generations'] = {}
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        self.assertEqual(self.journal['effects'][old_key]['state'], 'not-sent-exhausted')
        self.assertEqual(len(self.writes), 2)

    def test_never_sent_recovery_keeps_own_history_and_allows_other_pr(self):
        self.exhaust_never_sent_budget()
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(write=lambda *_: (_ for _ in ()).throw(q.NotSent('e' * 64))),
                         'not-sent-backoff')
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.snapshot['generations'] = {}
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        self.assertEqual(len(self.writes), 1)

    def test_changed_proof_cannot_reset_exhausted_candidate_budget(self):
        self.exhaust_never_sent_budget()
        self.assertEqual(self.tick(verify_source=lambda *_: 'f' * 64), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_exhausted_history_is_retained_when_no_candidate_is_selected(self):
        self.exhaust_never_sent_budget()
        self.snapshot.pop('candidate')
        old = copy.deepcopy(self.journal)
        self.assertEqual(self.tick(candidate=None), 'idle')
        self.assertEqual(self.journal, old)
        self.assertFalse(self.writes)

    def test_exhausted_admission_does_not_block_known_revocation_or_drain(self):
        self.exhaust_never_sent_budget()
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base, 'd' * 40, 'ENTRY')
        for kind in ('revoke-integration', 'dequeue'):
            effect = q.Effect(kind, other, 'c' * 64)
            self.assertEqual(q.execute(self.journal, effect, self.write,
                                      self.readback, self.save), kind + '-confirmed')
        self.assertEqual(len(self.writes), 2)
        self.assertEqual(self.tick(), 'not-sent-exhausted-held')

    def test_receipt_bound_withdrawal_releases_hold_without_erasing_exhaustion(self):
        self.exhaust_never_sent_budget()
        record = next(iter(self.journal['effects'].values()))
        record['exhaustion_withdrawal'] = {'proof_sha256': 'e' * 64, 'owner': {'run_id': 1}}
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        self.assertEqual(record['state'], 'not-sent-exhausted')
        self.assertEqual(record['not_sent_attempts'], 3)

    def test_corrupt_withdrawal_proof_cannot_release_exhaustion(self):
        self.exhaust_never_sent_budget()
        record = next(iter(self.journal['effects'].values()))
        for proof in ({'proof_sha256': 'bad', 'owner': {'run_id': 1}},
                      {'proof_sha256': 'e' * 64, 'owner': {}}, True):
            record['exhaustion_withdrawal'] = proof
            with self.assertRaises(q.Held):
                self.tick()
        self.assertFalse(self.writes)

    def test_drain_maintenance_retains_its_own_finite_retry_budget(self):
        from unittest.mock import patch
        self.exhaust_never_sent_budget()
        identity = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base, 'd' * 40, 'ENTRY')
        for kind in ('revoke-integration', 'dequeue'):
            effect = q.Effect(kind, identity, 'c' * 64)
            attempts = []

            def never_sent(effect, key):
                attempts.append(key)
                raise q.NotSent('e' * 64)

            for index in range(q.MAX_NOT_SENT_ATTEMPTS):
                with patch.object(q.time, 'time', return_value=1000 + index * 1000):
                    result = q.execute(self.journal, effect, never_sent, self.readback, self.save)
                self.assertEqual(result, 'not-sent-exhausted-held' if index == 2 else 'not-sent-backoff')
                self.journal = copy.deepcopy(self.saved[-1])
            with patch.object(q.time, 'time', return_value=1000000):
                self.assertEqual(q.execute(self.journal, effect, never_sent, self.readback,
                                          self.save), 'not-sent-exhausted-held')
            self.assertEqual(len(attempts), 3)
        self.assertFalse(self.writes)

    def test_changed_proof_cannot_reset_exhausted_maintenance_budget(self):
        from unittest.mock import patch
        identity = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base, 'd' * 40, 'ENTRY')
        for kind in ('revoke-integration', 'dequeue'):
            with self.subTest(kind=kind):
                self.journal = {'schema': 1, 'effects': {}}
                attempts = []

                def never_sent(effect, key):
                    attempts.append(key)
                    raise q.NotSent('e' * 64)

                original = q.Effect(kind, identity, 'c' * 64)
                for index in range(q.MAX_NOT_SENT_ATTEMPTS):
                    with patch.object(q.time, 'time', return_value=1000 + index * 1000):
                        q.execute(self.journal, original, never_sent, self.readback, self.save)
                    self.journal = json.loads(json.dumps(self.saved[-1]))
                exhausted = copy.deepcopy(self.journal)
                for proof in ('f' * 64, 'a' * 64):
                    changed = q.Effect(kind, identity, proof)
                    with patch.object(q.time, 'time', return_value=1000000):
                        self.assertEqual(q.execute(self.journal, changed, never_sent,
                                                  self.readback, self.save),
                                         'not-sent-exhausted-held')
                    self.assertEqual(self.journal, exhausted)
                    self.assertEqual(q.exhausted_selection_result(self.journal, changed),
                                     'not-sent-exhausted-held')
                    self.journal = json.loads(json.dumps(self.saved[-1]))
                self.assertEqual(len(attempts), q.MAX_NOT_SENT_ATTEMPTS)
                changed = q.Effect(kind, identity, 'f' * 64)
                for state in ('attempted', 'unknown'):
                    self.journal = copy.deepcopy(exhausted)
                    self.journal['effects'][original.key()]['state'] = state
                    self.assertEqual(q.execute(self.journal, changed, self.write,
                                              lambda *_: False, self.save), 'unknown-held')
                    self.assertFalse(self.writes)
                self.journal = copy.deepcopy(exhausted)
                with self.assertRaises(q.Held):
                    q.execute(self.journal, q.Effect(kind, identity, 'bad'),
                              self.write, self.readback, self.save)
                for field, value in (('number', 12), ('pull_request_id', 'another_PR'),
                                     ('source', 'a' * 40), ('base', 'e' * 40),
                                     ('integration', 'a' * 40), ('entry_id', 'ANOTHER_ENTRY')):
                    self.journal = copy.deepcopy(exhausted)
                    unrelated = q.Identity(**{**asdict(identity), field: value})
                    effect = q.Effect(kind, unrelated, 'f' * 64)
                    self.assertEqual(q.execute(self.journal, effect, self.write,
                                              self.readback, self.save), kind + '-confirmed')
                    self.assertEqual(self.journal['effects'][original.key()],
                                     exhausted['effects'][original.key()])
                self.journal = copy.deepcopy(exhausted)
                other_kind = 'dequeue' if kind == 'revoke-integration' else 'revoke-integration'
                self.assertEqual(q.execute(self.journal, q.Effect(other_kind, identity, 'f' * 64),
                                          self.write, self.readback, self.save),
                                 other_kind + '-confirmed')
                self.journal = copy.deepcopy(exhausted)
                recovered = q.Effect(kind, identity, 'f' * 64, generation=1)
                self.assertEqual(q.execute(self.journal, recovered, self.write,
                                          self.readback, self.save), kind + '-confirmed')
                self.assertFalse(q.exhaustion_unresolved(self.journal,
                                                       self.journal['effects'][original.key()]))
                self.assertEqual(self.journal['effects'][original.key()],
                                 exhausted['effects'][original.key()])
                self.journal = copy.deepcopy(exhausted)
                self.journal['effects'][original.key()]['exhaustion_withdrawal'] = {
                    'proof_sha256': 'a' * 64, 'owner': {'run_id': 1}}
                self.assertEqual(q.execute(self.journal, changed, self.write,
                                          self.readback, self.save), kind + '-confirmed')
                self.assertEqual(self.journal['effects'][original.key()]['not_sent_attempts'],
                                 q.MAX_NOT_SENT_ATTEMPTS)
                self.writes.clear()

    def test_other_entry_recovery_cannot_release_maintenance_exhaustion(self):
        from unittest.mock import patch
        identity = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base, 'd' * 40, 'ENTRY')
        for kind in ('revoke-integration', 'dequeue'):
            with self.subTest(kind=kind):
                self.journal = {'schema': 1, 'effects': {}}

                def never_sent(effect, key):
                    raise q.NotSent('e' * 64)

                original = q.Effect(kind, identity, 'c' * 64)
                for index in range(q.MAX_NOT_SENT_ATTEMPTS):
                    with patch.object(q.time, 'time', return_value=1000 + index * 1000):
                        q.execute(self.journal, original, never_sent, self.readback, self.save)
                exhausted = copy.deepcopy(self.journal)
                for field, value in (('entry_id', 'ANOTHER_ENTRY'), ('source', 'a' * 40),
                                     ('base', 'e' * 40), ('integration', 'a' * 40)):
                    self.journal = copy.deepcopy(exhausted)
                    unrelated = q.Identity(**{**asdict(identity), field: value})
                    recovery = q.Effect(kind, unrelated, 'f' * 64, generation=1)
                    self.assertEqual(q.execute(self.journal, recovery, self.write,
                                              self.readback, self.save), kind + '-confirmed')
                    self.journal = json.loads(json.dumps(self.saved[-1]))
                    before = copy.deepcopy(self.journal)
                    writes = len(self.writes)
                    changed = q.Effect(kind, identity, 'a' * 64)
                    self.assertEqual(q.execute(self.journal, changed, self.write,
                                              self.readback, self.save),
                                     'not-sent-exhausted-held')
                    self.assertEqual(q.exhausted_selection_result(self.journal, changed),
                                     'not-sent-exhausted-held')
                    self.assertEqual(len(self.writes), writes)
                    self.assertEqual(self.journal, before)

    def test_uncertain_maintenance_cannot_be_replayed_or_bypassed(self):
        self.exhaust_never_sent_budget()
        identity = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base, 'd' * 40, 'ENTRY')
        revoke = q.Effect('revoke-integration', identity, 'c' * 64)
        self.assertEqual(q.execute(self.journal, revoke, self.write,
                                  lambda *_: False, self.save), 'unknown-held')
        self.journal = copy.deepcopy(self.saved[-1])
        for effect in (revoke, q.Effect('dequeue', identity, 'c' * 64, 1)):
            self.assertEqual(q.execute(self.journal, effect, self.write,
                                      self.readback, self.save), 'unknown-held')
        self.assertEqual(len(self.writes), 1)

    def test_corrupt_no_send_proof_cannot_be_retried(self):
        from unittest.mock import patch
        with patch.object(q.time,'time',return_value=1000):
            self.tick(write=lambda *_: (_ for _ in ()).throw(q.NotSent('e'*64)))
        next(iter(self.journal['effects'].values()))['response_sha256']='invalid'
        with self.assertRaisesRegex(q.Held,'not-sent-proof'):self.tick()
        self.assertFalse(self.writes)

    def test_explicit_recovery_generation_creates_a_new_pinned_attempt(self):
        self.tick()
        first = self.writes[-1][0]
        self.snapshot['source_authorization'] = {'effect_key':first.key(),
            'source':self.identity.source,'creator_id':q.ACTOR_ID,'state':'success'}
        self.tick()
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(),'authorize-source-confirmed')
        new = self.writes[-1][0]
        self.assertNotEqual(first.key(),new.key())
        self.assertEqual(new.generation,1)
        self.snapshot['source_authorization']['effect_key'] = new.key()
        self.assertEqual(self.tick(),'enqueue-confirmed')
        self.assertEqual(len(self.journal['effects']),4)

    def follower(self, number=11, position=2):
        return {'position': position, 'state': 'QUEUED', 'base': None,
                'jump': False, 'number': number, 'pull_request_id': f'PR_{number}',
                'source': f'{number:040x}', 'integration': None,
                'entry_id': f'ENTRY_{number}'}

    def test_zero_through_five_entries_and_six_overflow(self):
        for count in range(6):
            snapshot = copy.deepcopy(self.snapshot)
            snapshot['entries'] = [self.follower(11+i, i+1) for i in range(count)]
            snapshot['total_count'] = count
            q.validate_snapshot(snapshot)
        snapshot['entries'].append(self.follower(20, 6))
        snapshot['total_count'] = 6
        with self.assertRaises(q.Held):
            q.validate_snapshot(snapshot)

    def test_waiting_target_gets_no_integration_credit(self):
        self.queued()
        self.snapshot['entries'][0]['integration'] = None
        self.assertEqual(self.tick(), 'idle')
        self.snapshot['entries'][0]['base'] = None
        self.assertEqual(self.tick(), 'idle')
        self.assertFalse(self.writes)

    def test_follower_and_front_state_changes_preserve_front_authorization_key(self):
        self.queued()
        self.integration = lambda *_: 'e' * 64
        self.assertEqual(self.tick(), 'authorize-integration-confirmed')
        original = copy.deepcopy(self.journal)
        self.snapshot['entries'].append(self.follower())
        self.snapshot['total_count'] = 2
        self.assertEqual(self.tick(), 'observed-confirmed')
        self.snapshot['entries'][0]['state'] = 'MERGEABLE'
        self.assertEqual(self.tick(), 'observed-confirmed')
        self.assertEqual(self.journal, original)
        self.assertEqual(len(self.writes), 1)

    def test_front_state_change_between_source_authorization_and_enqueue(self):
        self.snapshot['entries'] = [self.follower(11, 1)]
        self.snapshot['total_count'] = 1
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        effect = self.writes[0][0]
        self.snapshot['source_authorization'] = {'effect_key': effect.key(),
            'source': self.identity.source, 'creator_id': q.ACTOR_ID, 'state': 'success'}
        self.snapshot['entries'][0]['state'] = 'AWAITING_CHECKS'
        self.assertEqual(self.tick(), 'enqueue-confirmed')
        self.assertEqual([effect.kind for effect, _ in self.writes], ['authorize-source', 'enqueue'])

    def test_follower_change_does_not_reset_not_sent_budget(self):
        self.queued()
        self.integration = lambda *_: 'e' * 64
        calls = []
        def never_sent(effect, key):
            calls.append(key)
            raise q.NotSent('f' * 64)
        for index in range(3):
            if self.journal['effects']:
                next(iter(self.journal['effects'].values()))['retry_after'] = 0
            self.snapshot['entries'][0]['state'] = 'MERGEABLE' if index else 'AWAITING_CHECKS'
            if index == 1:
                self.snapshot['entries'].append(self.follower())
                self.snapshot['total_count'] = 2
            self.tick(write=never_sent)
        self.assertEqual(len(set(calls)), 1)
        self.assertEqual(len(calls), 3)
        self.assertEqual(self.tick(write=never_sent), 'not-sent-exhausted-held')
        self.assertEqual(len(calls), 3)

    def test_refusal_is_per_pr_and_changed_proof_needs_new_generation(self):
        self.tick(write=lambda *_: (_ for _ in ()).throw(q.DefiniteRefusal('e' * 64)))
        prior = copy.deepcopy(self.journal)
        self.assertEqual(self.tick(verify_source=lambda *_: 'f' * 64), 'known-refusal-held')
        other = q.Identity(11, 'other', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        blocked = q.Effect('enqueue', self.identity, 'f' * 64)
        self.assertEqual(q.execute(self.journal, blocked, self.write,
                                  self.readback, self.save), 'known-refusal-held')
        self.snapshot['candidate'] = asdict(self.identity)
        self.snapshot['generations'] = {str(self.snapshot['candidate']['number']): 1}
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        for key, record in prior['effects'].items():
            self.assertEqual(self.journal['effects'][key], record)

    def test_waiting_dequeue_identity_has_operation_specific_requirements(self):
        waiting = q.Identity(11, 'other', 'f' * 40, self.identity.base, None, 'ENTRY')
        q.Effect('dequeue', waiting, 'c' * 64).payload()
        for kind in ('authorize-source', 'enqueue', 'authorize-integration', 'revoke-integration'):
            with self.subTest(kind=kind), self.assertRaises(q.Held):
                q.Effect(kind, waiting, 'c' * 64).payload()

    def test_duplicate_reordered_and_partial_inventory_is_held(self):
        self.queued()
        self.snapshot['entries'].append(self.follower())
        self.snapshot['total_count'] = 2
        for change in ('number', 'pull_request_id', 'entry_id', 'position', 'jump', 'partial'):
            bad = copy.deepcopy(self.snapshot)
            if change == 'jump':
                bad['entries'][1]['jump'] = True
            elif change == 'partial':
                bad['entries'][1]['integration'] = 'e' * 40
            else:
                bad['entries'][1][change] = bad['entries'][0][change]
            with self.subTest(change=change), self.assertRaises(q.Held):
                q.validate_snapshot(bad)
        self.snapshot['has_next_page'] = True
        with self.assertRaises(q.Held):
            self.tick()
        self.assertFalse(self.writes)

    def test_follower_is_idle_and_capacity_holds_unqueued_sixth_pr(self):
        self.snapshot['entries'] = [self.follower(11+i, i+1) for i in range(5)]
        self.snapshot['total_count'] = 5
        with self.assertRaisesRegex(q.Held, 'queue-capacity-held'):
            self.tick()
        candidate = q.Identity(12, 'PR_12', f'{12:040x}', self.identity.base)
        self.snapshot['candidate'] = asdict(candidate)
        self.assertEqual(self.tick(candidate=candidate), 'idle')
        self.assertFalse(self.writes)

    def test_original_schema_one_record_and_effect_key_remain_unchanged(self):
        effect = q.Effect('authorize-source', self.identity, 'c' * 64)
        payload = {'schema': 1, 'repo': q.REPO, 'kind': 'authorize-source',
                   'identity': asdict(self.identity), 'proof_sha256': 'c' * 64, 'generation': 0}
        self.assertEqual(effect.payload(), payload)
        self.assertEqual(effect.key(), q.digest(payload))
        self.journal['effects'][effect.key()] = {'payload': payload, 'state': 'not-sent',
            'not_sent_attempts': 2, 'not_sent_budget': 3, 'response_sha256': 'e' * 64,
            'retry_after': 0}
        self.assertEqual(self.tick(write=lambda *_: (_ for _ in ()).throw(q.NotSent('f' * 64))),
                         'not-sent-exhausted-held')
        self.assertEqual(len(self.journal['effects']), 1)
        self.assertEqual(self.journal['effects'][effect.key()]['not_sent_attempts'], 3)

    def test_rules_drift_between_reads_cannot_write_or_change_effect_key(self):
        before = q.plan(self.snapshot, self.identity, self.source, self.integration)
        second = copy.deepcopy(self.snapshot)
        second['rules_sha256'] = 'f' * 64
        after = q.plan(second, self.identity, self.source, self.integration)
        self.assertEqual(before.key(), after.key())
        reads = iter([self.snapshot, second])
        self.assertEqual(self.tick(observe=lambda: next(reads)), 'identity-changed')
        self.assertFalse(self.writes)
        self.assertFalse(self.saved)

    def test_missing_or_invalid_effective_rules_proof_is_held(self):
        for value in (None, '', True, 'a' * 40, 'x' * 64):
            with self.subTest(value=value), self.assertRaises(q.Held):
                self.tick(observe=lambda: {**self.snapshot, 'rules_sha256': value})
        self.assertFalse(self.writes)

if __name__ == '__main__':
    unittest.main()
