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


if __name__ == '__main__':unittest.main()
