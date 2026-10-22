"""A partial service report must never overwrite the repository's Sonar analysis."""
import copy
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SHA, BASE = 'a' * 40, 'b' * 40


def gate_module():
    spec = importlib.util.spec_from_file_location('sonar_gate', ROOT / 'scripts/ci/wait_for_sonar.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def matching_run():
    return {'id': 42, 'path': '.github/workflows/ci.yml', 'event': 'pull_request',
            'head_sha': SHA, 'head_branch': 'XFY', 'run_attempt': 1,
            'status': 'completed', 'conclusion': 'success',
            'pull_requests': [{'number': 145, 'head': {'sha': SHA}, 'base': {'sha': BASE}}]}


def matching_job():
    return {'name': 'SonarQube analysis', 'conclusion': 'success',
            'steps': [{'name': 'SonarQube scan', 'conclusion': 'success'}]}


class WorkflowContract(unittest.TestCase):
    def test_only_complete_repository_ci_uploads_sonar(self):
        scanners = [p.name for p in (ROOT / '.github/workflows').glob('*.yml')
                    if 'uses: SonarSource/sonarqube-scan-action' in p.read_text()]
        self.assertEqual(scanners, ['ci.yml'])
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('Generate frontend coverage from browser tests', ci)
        self.assertIn('npx nyc report --reporter=lcov', ci)
        self.assertIn('-Dsonar.qualitygate.wait=true', ci)

    def test_service_gate_is_read_only_and_still_blocks_downstream(self):
        workflow = (ROOT / '.github/workflows/service-cicd-reusable.yml').read_text()
        self.assertIn('python3 scripts/ci/wait_for_sonar.py', workflow)
        self.assertIn('actions: read', workflow)
        self.assertEqual(workflow.count('needs: [checks, security]'), 2)
        caller = (ROOT / '.github/workflows/compliance-kind-ci.yml').read_text()
        self.assertIn('actions: read', caller)

    def test_repository_ci_covers_every_service_pull_request_target(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        trigger = ci.split('  pull_request:', 1)[1].split('\n\n', 1)[0]
        self.assertEqual(trigger.strip(), '', 'Unfiltered service PRs require unfiltered repository CI')


class ExactRevisionGate(unittest.TestCase):
    def setUp(self):
        self.gate = gate_module()
        self.context = {'event': 'pull_request', 'sha': SHA, 'base': BASE, 'pr': 145}

    def test_selects_latest_matching_run_only(self):
        correct = matching_run()
        self.assertEqual(self.gate.select_run([correct], self.context)['id'], 42)
        mutations = [dict(head_sha='c' * 40), dict(path='.github/workflows/other.yml'),
                     dict(event='push'), dict(pull_requests=[])]
        for change in mutations:
            wrong = {**correct, **change, 'id': 99}
            self.assertIsNone(self.gate.select_run([wrong], self.context))
        for field, value in [('number', 146), ('base', {'sha': 'd' * 40}), ('head', {'sha': 'c' * 40})]:
            wrong = copy.deepcopy(correct)
            wrong['pull_requests'][0][field] = value
            self.assertIsNone(self.gate.select_run([wrong], self.context))
        self.assertEqual(self.gate.select_run([correct, {**correct, 'id': 43}], self.context)['id'], 43)

    def test_main_push_requires_main_and_exact_sha(self):
        context = {'event': 'push', 'sha': SHA}
        run = {**matching_run(), 'event': 'push', 'head_branch': 'main', 'pull_requests': []}
        self.assertIsNotNone(self.gate.select_run([run], context))
        self.assertIsNone(self.gate.select_run([{**run, 'head_branch': 'XFY'}], context))

    def test_requires_actual_successful_scan_not_skipped_or_named_job_only(self):
        run, job = matching_run(), matching_job()
        self.gate.verify_success(run, [job], self.context)
        for conclusion in ['failure', 'skipped', 'cancelled', None]:
            with self.subTest(conclusion=conclusion):
                with self.assertRaises(RuntimeError):
                    self.gate.verify_success({**run, 'conclusion': conclusion}, [job], self.context)
                with self.assertRaises(RuntimeError):
                    self.gate.verify_success(run, [{**job, 'conclusion': conclusion}], self.context)
                with self.assertRaises(RuntimeError):
                    self.gate.verify_success(run, [{**job, 'steps': [{'name': 'SonarQube scan', 'conclusion': conclusion}]}], self.context)
        for jobs in [[], [job, job], [{**job, 'steps': []}]]:
            with self.assertRaises(RuntimeError):
                self.gate.verify_success(run, jobs, self.context)
        with self.assertRaises(RuntimeError):
            self.gate.verify_success({**run, 'head_sha': 'e' * 40}, [job], self.context)

    def test_wait_uses_exact_workflow_and_latest_attempt(self):
        run = {**matching_run(), 'run_attempt': 2}
        pending = {**run, 'status': 'in_progress', 'conclusion': None}
        with patch.object(self.gate, 'api', side_effect=[{'workflow_runs': []}, {'workflow_runs': [pending]}, {'workflow_runs': [run]}, run, {'jobs': [matching_job()]}]) as api, \
             patch.object(self.gate.time, 'sleep'):
            self.gate.wait_for_gate('owner/repo', self.context, timeout=60)
        self.assertIn('/actions/workflows/ci.yml/runs?', api.call_args_list[0].args[0])
        self.assertIn('head_sha=' + SHA, api.call_args_list[0].args[0])
        self.assertIn('/attempts/2/jobs', api.call_args_list[-1].args[0])

    def test_wait_fails_when_workflow_fails_or_never_appears(self):
        with patch.object(self.gate, 'api', return_value={'workflow_runs': []}), \
             patch.object(self.gate.time, 'monotonic', side_effect=[0, 0, 61]), patch.object(self.gate.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'Timed out'):
                self.gate.wait_for_gate('owner/repo', self.context, timeout=60)
        failed = {**matching_run(), 'conclusion': 'failure'}
        with patch.object(self.gate, 'api', side_effect=[{'workflow_runs': [failed]}, failed, {'jobs': [matching_job()]}]):
            with self.assertRaisesRegex(RuntimeError, 'did not pass'):
                self.gate.wait_for_gate('owner/repo', self.context)

    def test_failed_job_rerun_can_reuse_latest_same_revision_sonar_execution(self):
        run = {**matching_run(), 'run_attempt': 2}
        with patch.object(self.gate, 'api', side_effect=[{'workflow_runs': [run]}, run,
                {'jobs': [{'name': 'containers', 'conclusion': 'success'}]}, {'jobs': [matching_job()]}]) as api:
            self.gate.wait_for_gate('owner/repo', self.context)
        self.assertIn('/attempts/2/jobs', api.call_args_list[-2].args[0])
        self.assertIn('/attempts/1/jobs', api.call_args_list[-1].args[0])
        for conclusion in ['failure', 'skipped']:
            with patch.object(self.gate, 'api', side_effect=[{'workflow_runs': [run]}, run,
                    {'jobs': [{**matching_job(), 'conclusion': conclusion}]}]) as api:
                with self.assertRaises(RuntimeError):
                    self.gate.wait_for_gate('owner/repo', self.context)
                self.assertEqual(api.call_count, 3, 'Never look past a newer failed/skipped Sonar execution')

    def test_api_uses_only_actions_rest_read_without_printing_credentials(self):
        with patch.object(self.gate.subprocess, 'run') as command:
            command.return_value.stdout = '{"jobs": []}'
            self.assertEqual(self.gate.api('repos/owner/repo/actions/runs/42/jobs'), {'jobs': []})
        self.assertEqual(command.call_args.args[0], ['gh', 'api', 'repos/owner/repo/actions/runs/42/jobs'])
        self.assertTrue(command.call_args.kwargs['capture_output'])

    def test_unsupported_events_do_not_bypass_gate(self):
        with self.assertRaises(RuntimeError):
            self.gate.event_context('workflow_dispatch', {}, SHA, 'refs/heads/XFY')
        with self.assertRaises(RuntimeError):
            self.gate.event_context('push', {}, SHA, 'refs/heads/XFY')
        self.assertEqual(self.gate.event_context('pull_request', {'number': 145, 'pull_request': {'head': {'sha': SHA}, 'base': {'sha': BASE}}}, 'f' * 40, ''), self.context)


if __name__ == '__main__':
    unittest.main()
