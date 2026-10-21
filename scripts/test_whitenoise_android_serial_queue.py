"""Failure-boundary coverage for five queued sources and one active integration."""
import copy
import unittest
from scripts import whitenoise_android_serial_queue as policy

def identity(n=1):return policy.Identity(n,'PR_'+str(n),hex(n)[2:]*40 if n<16 else 'a'*40,'b'*40)
def entry(n=1,position=1,mapped=False):
 return dict(identity(n).__dict__,entry_id='E'+str(n),position=position,jump=False,state='QUEUED',
             base='b'*40 if mapped else None,integration='c'*40 if mapped else None)
def snapshot(count=0,target=1):
 return {'repo':policy.REPO,'repository_id':policy.REPOSITORY_ID,'actor_id':policy.ACTOR_ID,
 'protection_verified':True,'prerequisites_verified':True,'configuration':{'maximumEntriesToBuild':1,
 'maximumEntriesToMerge':1,'minimumEntriesToMerge':1,'minimumEntriesToMergeWaitTime':0,
 'mergeMethod':'SQUASH','mergingStrategy':'ALLGREEN','checkResponseTimeout':120},
 'master':'b'*40,'has_next_page':False,'total_count':count,'entries':[entry(i,i) for i in range(1,count+1)],
 'candidate':identity(target).__dict__,'rules_sha256':'d'*64}

class CapacityTests(unittest.TestCase):
 def test_zero_through_five(self):
  for n in range(6):self.assertEqual(policy.validate_snapshot(snapshot(n))['total_count'],n)
 def test_six_rejected(self):
  with self.assertRaisesRegex(policy.Held,'queue-proof-held'):policy.validate_snapshot(snapshot(6))
 def test_follower_has_no_integration_proof(self):
  s=snapshot(5,5);calls=[]
  self.assertIsNone(policy.plan(s,identity(5),lambda *a:calls.append('source'),lambda *a:calls.append('G')))
  self.assertEqual(calls,[])
 def test_front_missing_mapping_waits(self):
  s=snapshot(5);self.assertIsNone(policy.plan(s,identity(),None,lambda *a:self.fail('G called')))
 def test_front_mapped_behind_four(self):
  s=snapshot(5);s['entries'][0]=entry(1,1,True)
  e=policy.plan(s,identity(),None,lambda *a:'a'*64)
  self.assertEqual(e.kind,'authorize-integration');self.assertEqual(e.identity.entry_id,'E1')
 def test_second_source_uses_candidate(self):
  s=snapshot(1,2);called=[]
  e=policy.plan(s,identity(2),lambda i,s:(called.append(i.number)or 'a'*64),lambda *a:self.fail('other front proof'))
  self.assertEqual(called,[2]);self.assertEqual(e.identity.number,2)
 def test_full_guard_drift(self):
  s=snapshot(4,5);original=policy.queue_fingerprint(s)
  for field,value in [('master','e'*40),('rules_sha256','f'*64)]:
   changed=copy.deepcopy(s);changed[field]=value;self.assertNotEqual(original,policy.queue_fingerprint(changed))
  changed=copy.deepcopy(s);changed['entries'][2]['source']='f'*40
  self.assertNotEqual(original,policy.queue_fingerprint(changed))
 def test_invalid_inventory(self):
  for modify in [lambda s:s.update(has_next_page=True),lambda s:s['entries'][1].update(position=1),lambda s:s['entries'][1].update(entry_id='E1'),lambda s:s['entries'][1].update(integration='e'*40),lambda s:s['entries'][0].update(jump=True)]:
   s=snapshot(2);modify(s)
   with self.assertRaises(policy.Held):policy.validate_snapshot(s)
 def test_actual_tick_persists_before_write_and_lost_response_holds(self):
  s=snapshot(1,2);state={'schema':2,'effects':{}};calls=[];saved=[]
  def write(effect,key):
   self.assertEqual(saved[-1]['effects'][key]['state'],'attempted');calls.append(key);raise RuntimeError('lost')
  arguments=(state,lambda:copy.deepcopy(s),identity(2),lambda *a:'a'*64,None,write,lambda *a:False,lambda:saved.append(copy.deepcopy(state)),lambda:True)
  self.assertEqual(policy.tick(*arguments),'unknown-held');self.assertEqual(len(calls),1)
  self.assertEqual(policy.tick(*arguments),'unknown-held');self.assertEqual(len(calls),1)
 def test_actual_tick_changed_queue_prevents_mutation(self):
  s=snapshot(1,2);changed=copy.deepcopy(s);changed['rules_sha256']='e'*64;observations=iter([s,changed]);writes=[]
  self.assertEqual(policy.tick({'schema':2,'effects':{}},lambda:next(observations),identity(2),lambda *a:'a'*64,None,lambda *a:writes.append(a),lambda *a:True,lambda:None,lambda:True),'identity-changed')
  self.assertEqual(writes,[])
 def test_schema1_unknown_preserved_global(self):
  from scripts import v1_policy
  e=v1_policy.Effect('enqueue',v1_policy.Identity(**identity().__dict__),'e'*64)
  record={'payload':e.payload(),'state':'unknown'};state={'schema':2,'effects':{e.key():copy.deepcopy(record)}}
  self.assertEqual(policy.tick(state,lambda:self.fail('observe'),None,None,None,lambda:self.fail('write'),lambda *a:False,lambda:None,lambda:True),'unknown-held')
  self.assertEqual(state['effects'][e.key()],record)


def load_tests(loader, tests, pattern):
    # CI names this module explicitly. Discovery loads the retained module itself.
    if pattern is None:
        from scripts import test_whitenoise_android_serial_queue_v1
        tests.addTests(loader.loadTestsFromModule(test_whitenoise_android_serial_queue_v1))
    return tests


if __name__=="__main__":unittest.main()
