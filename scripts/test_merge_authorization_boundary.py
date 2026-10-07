import unittest
from scripts.check_merge_authorization_boundary import validate


class AuthorizationBoundaryTest(unittest.TestCase):
    def test_readonly_normal_matrix_and_empty_permissions_allowed(self):
        for permissions in [{'contents':'read'},{}]:
            validate('normal.yml',{'permissions':permissions,'jobs':{
                'test':{'name':'Unit tests (${{ matrix.flavor }})'}}})

    def test_status_write_all_or_job_override_cannot_authorize(self):
        for permissions in ['write-all','read-all',None,{'statuses':'write'},{'checks':'write'},
                            {'contents':'write'},{'contents':'unknown'}]:
            for job in [False,True]:
                workflow={'permissions':{'contents':'read'},'jobs':{'test':{}}}
                (workflow['jobs']['test'] if job else workflow)['permissions']=permissions
                with self.assertRaises(ValueError):validate('normal.yml',workflow)

    def test_publish_allowlist_does_not_grant_status_or_other_job_write(self):
        for name,job,permissions in [('android-pr-preview-publish.yml','publish',{'contents':'write'}),
                                    ('codeql.yml','analyze',{'security-events':'write'})]:
            validate(name,{'permissions':{},'jobs':{job:{'permissions':permissions}}})
            permissions['statuses']='write'
            with self.assertRaises(ValueError):validate(name,{'permissions':{},'jobs':{job:{'permissions':permissions}}})
        with self.assertRaises(ValueError):validate('android-pr-preview-publish.yml',{
            'permissions':{},'jobs':{'other':{'permissions':{'contents':'write'}}}})

    def test_reserved_or_unbounded_dynamic_job_names_are_rejected(self):
        for name in ['Android merge authorization','Android merge authorization (${{ matrix.x }})',
                     '${{ inputs.name }}','Android merge ${{ matrix.name }}']:
            with self.assertRaises(ValueError):validate('normal.yml',{'permissions':{},'jobs':{'test':{'name':name}}})


    def test_queue_workflows_cannot_use_privileged_secrets(self):
        for event in [{'merge_group':{}},['pull_request','merge_group'],'merge_group',{'push':{}},'pull_request_target']:
            for job in [{'steps':[{'env':{'TOKEN':'${{ secrets.MERGE_TOKEN }}'}}]},
                        {'uses':'organization/repo/.github/workflows/build.yml@main','secrets':'inherit'}]:
                with self.assertRaisesRegex(ValueError,'secrets'):
                    validate('normal.yml',{'on':event,'permissions':{'contents':'read'},'jobs':{'test':job}})
        # Release/signing workflows that never run queue candidates retain their
        # existing secrets and reviewed non-status publishing permissions.
        validate('normal.yml',{'on':{'workflow_dispatch':{}},'permissions':{},'jobs':{'test':{
            'steps':[{'env':{'KEY':'${{ secrets.SIGNING_KEY }}'}}]}}})

    def test_candidate_jobs_cannot_escape_through_reusable_jobs_or_environments(self):
        for job in [{'uses':'organization/repo/.github/workflows/build.yml@main'},
                    {'environment':'production','steps':[]}]:
            with self.assertRaisesRegex(ValueError,'delegate'):
                validate('normal.yml',{'on':{'merge_group':{}},'permissions':{},'jobs':{'test':job}})
        validate('normal.yml',{'on':{'push':{'branches':['master']}},'permissions':{},'jobs':{'test':{
            'steps':[{'env':{'KEY':'${{ secrets.SIGNING_KEY }}'}}]}}})

if __name__ == '__main__':unittest.main()
