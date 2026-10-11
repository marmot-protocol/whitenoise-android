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
            'jump': False, 'number': 10,
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
                           ('integration', None),
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
        self.snapshot['generation'] = 1
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
        self.snapshot['generation'] = 1
        self.assertEqual(self.tick(readback=lambda *_: False), 'unknown-held')
        self.assertEqual(len(self.writes), 1)

    def test_exhaustion_holds_another_candidate_even_with_new_generation(self):
        self.exhaust_never_sent_budget()
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.snapshot['generation'] = 1
        self.assertEqual(self.tick(candidate=other), 'not-sent-exhausted-held')
        effect = q.Effect('authorize-source', other, 'c' * 64, 1)
        self.assertEqual(q.execute(self.journal, effect, self.write,
                                  self.readback, self.save), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_confirmed_explicit_recovery_releases_historical_exhaustion_hold(self):
        self.exhaust_never_sent_budget()
        old_key = next(iter(self.journal['effects']))
        self.snapshot['generation'] = 1
        self.assertEqual(self.tick(), 'authorize-source-confirmed')
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other), 'authorize-source-confirmed')
        self.assertEqual(self.journal['effects'][old_key]['state'], 'not-sent-exhausted')
        self.assertEqual(len(self.writes), 2)

    def test_never_sent_recovery_does_not_release_old_selection_hold(self):
        self.exhaust_never_sent_budget()
        self.snapshot['generation'] = 1
        self.assertEqual(self.tick(write=lambda *_: (_ for _ in ()).throw(q.NotSent('e' * 64))),
                         'not-sent-backoff')
        other = q.Identity(11, 'other_PR', 'f' * 40, self.identity.base)
        self.snapshot['candidate'] = asdict(other)
        self.assertEqual(self.tick(candidate=other), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_changed_proof_cannot_reset_exhausted_candidate_budget(self):
        self.exhaust_never_sent_budget()
        self.assertEqual(self.tick(verify_source=lambda *_: 'f' * 64), 'not-sent-exhausted-held')
        self.assertFalse(self.writes)

    def test_exhausted_selection_does_not_become_idle_when_candidate_disappears(self):
        self.exhaust_never_sent_budget()
        self.snapshot.pop('candidate')
        self.assertEqual(self.tick(candidate=None), 'not-sent-exhausted-held')
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
                    self.journal = copy.deepcopy(self.saved[-1])
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
                    self.journal = copy.deepcopy(self.saved[-1])
                self.assertEqual(len(attempts), q.MAX_NOT_SENT_ATTEMPTS)

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
        self.snapshot['generation'] = 1
        self.assertEqual(self.tick(),'authorize-source-confirmed')
        new = self.writes[-1][0]
        self.assertNotEqual(first.key(),new.key())
        self.assertEqual(new.generation,1)
        self.snapshot['source_authorization']['effect_key'] = new.key()
        self.assertEqual(self.tick(),'enqueue-confirmed')
        self.assertEqual(len(self.journal['effects']),4)

if __name__ == '__main__':
    unittest.main()
