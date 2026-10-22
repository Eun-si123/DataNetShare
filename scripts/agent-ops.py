#!/usr/bin/env python3
"""Dependency-free, read-only agent discovery/preflight/review; opt-in review receipts.

No LLM dispatch, exclusive leases, release publication, or autonomous code edits.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / '.github' / 'agent-areas.json'
RECEIPTS = ROOT / '.github' / 'agent-inspections'
SOURCE = {'.py', '.mjs', '.js', '.ts', '.tsx', '.cs', '.kt', '.java', '.go', '.sh'}
TEST_NAME = re.compile(r'(^|/)(tests?/|__tests__/|test_[^/]+\.|[^/]*[._-]test\.|[^/]*Tests?\.)', re.I)
FLAG = re.compile(r'\b(TODO|FIXME|HACK)\b')
API_PAGE_CAP = 3


def git(*args, check=True):
    run = subprocess.run(['git', '-C', str(ROOT), *args], capture_output=True, text=True)
    if check and run.returncode:
        raise RuntimeError(f'git {args[0]} failed: {run.stderr.strip()[:280]}')
    return run.stdout.strip()


def config():
    cfg = json.loads(CONFIG.read_text(encoding='utf-8'))
    if cfg.get('schema') != 1 or not isinstance(cfg.get('areas'), dict) or not cfg['areas']:
        raise ValueError('Invalid area configuration')
    for area, paths in cfg['areas'].items():
        if not re.fullmatch(r'[a-z][a-z0-9-]{1,40}', area) or not isinstance(paths, list) or not paths:
            raise ValueError('Invalid area or path list')
        if any(not isinstance(p, str) or not p or p.startswith('/') or '..' in p.split('/') or '\\' in p for p in paths):
            raise ValueError('Unsafe area path')
    return cfg


def belongs(filename, prefixes):
    return any(filename == p.rstrip('/') or filename.startswith(p.rstrip('/') + '/') for p in prefixes)


def changed(base):
    # Triple-dot uses the merge base; shallow/local clones without it fail closed.
    return [p for p in git('diff', '--name-only', f'{base}...HEAD', '--').splitlines() if p]


def age_and_changes(area, paths, receipt):
    if not receipt:
        return None, None
    sha = receipt.get('commit')
    if not isinstance(sha, str) or not re.fullmatch('[a-f0-9]{40}', sha) or not receipt.get('evidence'):
        return None, 'invalid-receipt'
    if subprocess.run(['git', '-C', str(ROOT), 'merge-base', '--is-ancestor', sha, 'HEAD'], capture_output=True).returncode:
        return None, 'nonancestor-or-missing-commit'
    count = len([p for p in git('diff', '--name-only', sha, 'HEAD', '--', *paths).splitlines() if belongs(p, paths)])
    return count, None


def todo_hints(files, cap=6):
    found = []
    for name in files:
        if len(found) >= cap:
            break
        p = ROOT / name
        if p.suffix not in SOURCE or TEST_NAME.search(name) or not p.is_file() or p.stat().st_size > 128000:
            continue
        try:
            for number, line in enumerate(p.read_text(encoding='utf-8').splitlines(), 1):
                if FLAG.search(line):
                    found.append({'path': name, 'line': number, 'kind': FLAG.search(line).group(1)})
                    break
        except (UnicodeError, OSError):
            continue
    return found


def api(path):
    token = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
    if not token:
        raise RuntimeError('Live GitHub preflight requires GITHUB_TOKEN/GH_TOKEN (fail closed)')
    request = urllib.request.Request('https://api.github.com/' + path.lstrip('/'), headers={
        'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'agent-readiness-2026',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    except (urllib.error.URLError, ValueError) as ex:
        raise RuntimeError('GitHub live read failed; no task may be claimed') from ex


def open_prs(repo):
    pulls = []
    for page in range(1, API_PAGE_CAP + 1):
        batch = api(f'repos/{repo}/pulls?state=open&per_page=100&page={page}')
        if not isinstance(batch, list):
            raise RuntimeError('Unexpected PR response')
        pulls.extend(batch)
        if len(batch) < 100:
            return pulls
    raise RuntimeError('Open PR list truncated; fail closed')


def pr_areas(repo, prs, cfg):
    occupied = {area: [] for area in cfg['areas']}
    for pr in prs:
        # Do not guess from branch names: inspect the actual current file list.
        files = []
        for page in range(1, API_PAGE_CAP + 1):
            batch = api(f'repos/{repo}/pulls/{pr["number"]}/files?per_page=100&page={page}')
            if not isinstance(batch, list):
                raise RuntimeError('Unexpected PR file response')
            files.extend(f.get('filename', '') for f in batch)
            if len(batch) < 100:
                break
        else:
            raise RuntimeError('PR file list truncated; fail closed')
        for area, paths in cfg['areas'].items():
            if any(belongs(name, paths) for name in files):
                occupied[area].append({'number': pr['number'], 'branch': pr['head']['ref']})
    return occupied


def scan(remote=False):
    cfg = config()
    files = git('ls-files', '-z').split('\0')
    if not files or files == ['']:
        raise RuntimeError('No tracked files: refusing empty discovery')
    occupancy = pr_areas(cfg['repository'], open_prs(cfg['repository']), cfg) if remote else {}
    areas = []
    for area, paths in cfg['areas'].items():
        selected = [f for f in files if belongs(f, paths)]
        if not selected:
            raise RuntimeError(f'Configured area {area} has no tracked files')
        receipt_path = RECEIPTS / (area + '.json')
        receipt = json.loads(receipt_path.read_text(encoding='utf-8')) if receipt_path.exists() else None
        changes, error = age_and_changes(area, paths, receipt)
        hints = todo_hints(selected)
        blocked = occupancy.get(area, [])
        status = 'NEEDS_REINSPECTION' if changes is None or changes else 'REVIEWED_UNCHANGED'
        if blocked:
            status = 'ACTIVE_PR_CHECK_BEFORE_WORK'
        score = (100 if changes is None else min(changes, 20) * 3) + min(len(hints), 6) * 2
        areas.append({'area': area, 'status': status, 'score': score, 'files': len(selected),
                      'changes_since_review': changes, 'receipt_error': error,
                      'active_prs': blocked, 'hints': hints})
    areas.sort(key=lambda a: (-a['score'], a['area']))
    return {'repository': cfg['repository'], 'head': git('rev-parse', 'HEAD'),
            'mode': 'READ_ONLY_CANDIDATES_NOT_AI_TASKS', 'remote_verified': remote,
            'areas': areas, 'warning': 'TODOs are hints, not defects. A review receipt is not a test pass.'}


def preflight(area, branch, issue):
    cfg = config()
    if area not in cfg['areas'] or not re.fullmatch(r'agent/[A-Za-z0-9._/-]{5,120}', branch):
        raise ValueError('Unknown area or unsafe agent branch')
    repo = cfg['repository']
    remote_main = api(f'repos/{repo}/branches/main')['commit']['sha']
    local_base = git('rev-parse', 'origin/main')
    if remote_main != local_base:
        raise RuntimeError('Local base is stale; fetch/reconcile before claiming')
    prs = open_prs(repo)
    occupied = pr_areas(repo, prs, cfg)[area]
    foreign = [pr for pr in occupied if pr['branch'] != branch]
    if foreign:
        return {'result': 'CONFLICT_OR_REVIEW_REQUIRED', 'area': area, 'active_prs': foreign}
    if issue:
        info = api(f'repos/{repo}/issues/{issue}')
        if info.get('state') != 'open' or 'pull_request' in info:
            raise RuntimeError('Issue is not an open, independently scoped Issue')
        comments = api(f'repos/{repo}/issues/{issue}/comments?per_page=100')
        if len(comments) == 100:
            raise RuntimeError('Comments possibly truncated; inspect manually')
        if any('agent-claim' in str(c.get('body', '')).lower() for c in comments):
            return {'result': 'CLAIM_HISTORY_CHECK_MANUALLY', 'area': area}
        if any(re.search(r'(?<!\d)#' + str(issue) + r'(?!\d)', str(p.get('body', '')) + ' ' + str(p.get('title', ''))) for p in prs):
            return {'result': 'CONFLICT_OR_REVIEW_REQUIRED', 'area': area, 'reason': 'open-PR-references-Issue'}
    return {'result': 'NO_CONFLICT_OBSERVED_NOT_A_LOCK', 'area': area, 'branch': branch,
            'warning': 'Nonatomic snapshot. Recheck immediately before writing; respect project-specific claim rules.'}


def independent_review(base):
    files = changed(base)
    check = subprocess.run(['git', '-C', str(ROOT), 'diff', '--check', f'{base}...HEAD'], capture_output=True, text=True)
    sources = [f for f in files if Path(f).suffix in SOURCE and not TEST_NAME.search(f)]
    tests = [f for f in files if TEST_NAME.search(f)]
    risk = [f for f in files if re.search(r'(auth|security|identity|vpn|socket|protocol|release|sign|secret|\.github/workflows)', f, re.I)]
    warnings = []
    if sources and not tests:
        warnings.append('Production/tool source changed without an accompanying changed test: review existing test coverage and justify scope.')
    if risk:
        warnings.append('Risk-sensitive paths touched: independently check invariants, failures, rollback, and exact-source CI evidence.')
    return {'base': base, 'head': git('rev-parse', 'HEAD'), 'changed_files': files,
            'source_changes': sources, 'test_changes': tests, 'risk_paths': risk,
            'diff_check_pass': check.returncode == 0, 'warnings': warnings,
            'boundary': 'Static independent checklist only; does not certify runtime, security, device, or release.'}


def receipt(area, evidence):
    cfg = config()
    if area not in cfg['areas'] or not evidence.strip() or len(evidence) > 400:
        raise ValueError('Existing area and bounded actual evidence required')
    if git('status', '--porcelain'):
        raise RuntimeError('Refuse review receipt with dirty worktree; commit reviewed source first')
    # Receipt is written on the current branch; submit it through the same reviewed PR.
    RECEIPTS.mkdir(parents=True, exist_ok=True)
    path = RECEIPTS / (area + '.json')
    data = {'schema': 1, 'area': area, 'commit': git('rev-parse', 'HEAD'),
            'reviewed_at': dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds'),
            'evidence': evidence.strip()}
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
    return {'written': str(path.relative_to(ROOT)), 'receipt': data}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    cmd = parser.add_subparsers(dest='command', required=True)
    s = cmd.add_parser('scan'); s.add_argument('--remote', action='store_true'); s.add_argument('--output')
    p = cmd.add_parser('preflight'); p.add_argument('--area', required=True); p.add_argument('--branch', required=True); p.add_argument('--issue', type=int)
    v = cmd.add_parser('review'); v.add_argument('--base', default='origin/main'); v.add_argument('--output')
    a = cmd.add_parser('record'); a.add_argument('--area', required=True); a.add_argument('--evidence', required=True)
    args = parser.parse_args()
    if args.command == 'scan': result = scan(args.remote)
    elif args.command == 'preflight': result = preflight(args.area, args.branch, args.issue)
    elif args.command == 'review': result = independent_review(args.base)
    else: result = receipt(args.area, args.evidence)
    output = json.dumps(result, indent=2, ensure_ascii=False) + '\n'
    if getattr(args, 'output', None):
        Path(args.output).write_text(output, encoding='utf-8')
    print(output)
    if args.command == 'review' and not result['diff_check_pass']:
        raise SystemExit(1)
    if args.command == 'preflight' and result['result'] != 'NO_CONFLICT_OBSERVED_NOT_A_LOCK':
        raise SystemExit(2)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError) as exc:
        print('agent-ops: ' + str(exc), file=sys.stderr)
        raise SystemExit(2)
