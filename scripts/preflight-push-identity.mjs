#!/usr/bin/env node
// Local, read-only pre-push gate for owner-directed Agent commits.
// Inspect the exact unpublished Git objects, including metadata and trailers.
// This intentionally does NOT impersonate or override independent contributors.
import {execFileSync} from 'node:child_process';

const owner = 'Eun-si123';
const ownerMail = '267835237+Eun-si123@users.noreply.github.com';
const aiMail = 'ai-assisted@users.noreply.github.com';
const norm = x => String(x || '').trim().toLowerCase();
const runGit = (...args) => execFileSync('git',args,{encoding:'utf8',stdio:['ignore','pipe','pipe'],maxBuffer:4*1024*1024});

export function inspectLocalCommit(item) {
  const problems=[];
  for(const field of ['author','committer']) {
    if (item[field]?.name !== owner || norm(item[field]?.email) !== norm(ownerMail))
      problems.push(field+': owner-directed Agent commit must use public owner handle and GitHub no-reply email');
  }
  const message=String(item.message || '');
  const coauthors=[];
  for (const line of message.split(/\r?\n/)) {
    const m=/^(Co-authored-by|Signed-off-by|Reviewed-by|Acked-by|Tested-by):\s*(.*?)\s*<([^<>\s]+@[^<>\s]+)>\s*$/i.exec(line.trim());
    if (/^(Co-authored-by|Signed-off-by|Reviewed-by|Acked-by|Tested-by):/i.test(line.trim()) && !m) {
      problems.push('malformed identity trailer');
      continue;
    }
    if (!m) continue;
    const display=m[2];const email=norm(m[3]);
    if (!email.endsWith('@users.noreply.github.com') && !['noreply@github.com','support@github.com'].includes(email))
      problems.push('identity trailer contains non-no-reply address (requires separate contributor-safe handling)');
    if (email === aiMail && !/(?:^|\s)Agent$/.test(display))
      problems.push('agent name does not end with Agent');
    if (email === norm(ownerMail) && display !== owner)
      problems.push('owner identity trailer does not use public handle');
    if (m[1].toLowerCase()==='co-authored-by' && email===aiMail && /(?:^|\s)Agent$/.test(display)) coauthors.push(display);
  }
  if (!coauthors.length) problems.push('missing distinct Agent co-author trailer');
  return [...new Set(problems)];
}

export function parseCommit(raw) {
  const [an,ae,cn,ce,...message]=raw.split('\0');
  if (!an || !ae || !cn || !ce || !message.length) throw new Error('incomplete commit metadata');
  return {author:{name:an,email:ae},committer:{name:cn,email:ce},message:message.join('\0')};
}

if (process.argv[1] && import.meta.url === new URL('file://' + process.argv[1]).href) {
  const baseFlag=process.argv.indexOf('--base');
  const base=baseFlag<0 ? '' : process.argv[baseFlag+1];
  if (!base || !/^[A-Za-z0-9_./^@{}~+-]+$/.test(base) || base.startsWith('-')) {
    console.error('Pre-push owner-Agent identity: specify a trusted --base ref, typically origin/main');
    process.exitCode=2;
  } else {
    try {
      runGit('rev-parse','--verify',base+'^{commit}');
      runGit('merge-base','--is-ancestor',base,'HEAD');
      const hashes=runGit('rev-list','--reverse',base+'..HEAD').trim().split(/\s+/).filter(Boolean);
      if (!hashes.length) throw new Error('no new commits in range');
      if (hashes.length>250) throw new Error('too many commits for a bounded audit');
      let failures=0;
      for (const hash of hashes) {
        const raw=runGit('show','--no-patch','--format=%an%x00%ae%x00%cn%x00%ce%x00%B',hash);
        const issues=inspectLocalCommit(parseCommit(raw));
        for (const reason of issues) {
          console.error('Commit '+hash.slice(0,12)+': '+reason);
          failures++;
        }
      }
      if (failures) {
        console.error('Pre-push identity check blocked owner-directed Agent publication; sanitize unpublished commits before pushing.');
        process.exitCode=1;
      } else console.log('Pre-push identity check passed for '+hashes.length+' local owner-directed Agent commit(s).');
    } catch {
      console.error('Pre-push identity check could not establish a complete safe commit range');
      process.exitCode=2;
    }
  }
}
