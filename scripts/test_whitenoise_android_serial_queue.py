"""Failure-boundary tests for the actual serial queue state machine."""
import copy
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
            'jump': False, 'source_mapping_verified': True, 'number': 10,
            'pull_request_id': 'PR_node', 'source': 'a' * 40,
            'integration': head, 'entry_id': 'ENTRY_node'}])

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
                           ('source_mapping_verified', False), ('integration', None),
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


if __name__ == '__main__':
    unittest.main()
