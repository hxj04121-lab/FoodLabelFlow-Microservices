#!/usr/bin/env python3
"""Reuse complete repository CI evidence instead of uploading partial Sonar coverage."""
import json
import os
import re
import subprocess
import time
from pathlib import Path
from urllib.parse import urlencode


def event_context(event, payload, sha, ref):
    if event == 'pull_request':
        try:
            context = {'event': event, 'sha': payload['pull_request']['head']['sha'],
                       'base': payload['pull_request']['base']['sha'], 'pr': payload['number']}
            if not isinstance(context['pr'], int) or context['pr'] <= 0:
                raise ValueError('Invalid PR number')
        except (KeyError, TypeError, ValueError) as error:
            raise RuntimeError('Missing PR head/base identity') from error
    elif event == 'push' and ref == 'refs/heads/main':
        context = {'event': event, 'sha': sha}
    else:
        raise RuntimeError('Repository quality gate requires a pull request or main push')
    if any(not isinstance(context[key], str) or not re.fullmatch(r'[0-9a-f]{40}', context[key])
           for key in ('sha', 'base') if key in context):
        raise RuntimeError('Invalid commit identity')
    return context


def select_run(runs, context):
    candidates = []
    for run in runs:
        if (run.get('path') != '.github/workflows/ci.yml' or run.get('event') != context['event']
                or run.get('head_sha') != context['sha'] or not isinstance(run.get('id'), int)):
            continue
        if context['event'] == 'pull_request':
            if not any(pr.get('number') == context['pr'] and pr.get('head', {}).get('sha') == context['sha']
                       and pr.get('base', {}).get('sha') == context['base'] for pr in run.get('pull_requests', [])):
                continue
        elif run.get('head_branch') != 'main':
            continue
        candidates.append(run)
    return max(candidates, key=lambda run: run['id'], default=None)


def verify_success(run, jobs, context):
    if select_run([run], context) is None or run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise RuntimeError('Matching repository CI did not pass')
    sonar = [job for job in jobs if job.get('name') == 'SonarQube analysis']
    if (len(sonar) != 1 or sonar[0].get('conclusion') != 'success'
            or not any(step.get('name') == 'SonarQube scan' and step.get('conclusion') == 'success'
                       for step in sonar[0].get('steps', []))):
        raise RuntimeError('The complete Sonar scan must succeed; missing or skipped analysis is not approval')


def api(path):
    result = subprocess.run(['gh', 'api', path], check=True, capture_output=True, text=True, timeout=30)
    return json.loads(result.stdout)


def wait_for_gate(repository, context, timeout=1800):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise RuntimeError('Invalid GitHub repository')
    root = f'repos/{repository}/actions'
    query = urlencode({'event': context['event'], 'head_sha': context['sha'], 'per_page': 100})
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        run = select_run(api(f'{root}/workflows/ci.yml/runs?{query}')['workflow_runs'], context)
        if run and run.get('status') == 'completed':
            break
        state = (run['id'], run.get('status')) if run else ('not scheduled',)
        if state != previous:
            print(f'Waiting for repository CI at the exact head/base revision: {state}', flush=True)
            previous = state
        time.sleep(20)
    else:
        raise RuntimeError('Timed out waiting for matching repository CI')
    # Failed-job reruns omit already-successful jobs. Find the newest Sonar execution
    # within this exact run; never fall back past a newer failed or skipped scan.
    final = api(f'{root}/runs/{run["id"]}')
    jobs = []
    for attempt in range(final['run_attempt'], 0, -1):
        if time.monotonic() >= deadline:
            raise RuntimeError('Timed out reading repository CI evidence')
        jobs = api(f'{root}/runs/{run["id"]}/attempts/{attempt}/jobs?per_page=100')['jobs']
        if any(job.get('name') == 'SonarQube analysis' for job in jobs):
            break
    verify_success(final, jobs, context)
    print(f'Complete repository quality gate passed: https://github.com/{repository}/actions/runs/{run["id"]}')


if __name__ == '__main__':
    try:
        payload = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        context = event_context(os.environ['GITHUB_EVENT_NAME'], payload,
                                os.environ['GITHUB_SHA'], os.environ['GITHUB_REF'])
        wait_for_gate(os.environ['GITHUB_REPOSITORY'], context)
    except (RuntimeError, KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        # GitHub authentication is supplied only through GH_TOKEN, never arguments or output.
        print(f'Quality gate failed: {error if isinstance(error, RuntimeError) else type(error).__name__}')
        raise SystemExit(1)
