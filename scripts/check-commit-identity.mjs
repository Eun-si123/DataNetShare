#!/usr/bin/env node
// Read-only GitHub PR metadata audit. Never echo potentially private identity values.
import assert from 'node:assert/strict';

const noReply = email => /@users\.noreply\.github\.com$/i.test(email) || /^(?:noreply|support)@github\.com$/i.test(email);
const normalized = value => String(value || '').trim().toLowerCase();

export function inspectCommit(item, owner, ownerId = null, publicName = null) {
  // Profile display names can be personal data; only the public account handle is approved.
  const approvedOwnerNames = new Set([normalized(owner)].filter(Boolean));
  const isOwnerEmail = email => normalized(email) === normalized(`${ownerId}+${owner}@users.noreply.github.com`) || normalized(email) === normalized(`${owner}@users.noreply.github.com`);
  const problems = [];
  const commit = item?.commit || {};
  for (const role of ['author', 'committer']) {
    const email = normalized(commit[role]?.email);
    const linked = normalized(item?.[role]?.login);
    if (!email) {
      problems.push(role + ': missing email');
    } else if (!noReply(email) && (!linked || linked === normalized(owner))) {
      // A different, linked GitHub contributor may intentionally publish their own email.
      // An unlinked agent or the repository owner's account must use GitHub no-reply.
      problems.push(role + ': unverified or owner personal email');
    }
    if ((linked === normalized(owner) || isOwnerEmail(email)) && !approvedOwnerNames.has(normalized(commit[role]?.name))) {
      problems.push(role + ': owner display name is not the public GitHub identity');
    }
  }
  const message = String(commit.message || '');
  // Co-author and other identity trailers are not rewritten by GitHub's email privacy settings.
  for (const line of message.split(/\r?\n/)) {
    if (!/^(?:co-authored-by|signed-off-by|reviewed-by|acked-by|tested-by):/i.test(line.trim())) continue;
    const match = /<([^<>\s]+@[^<>\s]+)>\s*$/.exec(line);
    if (!match || !noReply(normalized(match[1]))) {
      problems.push('identity trailer contains a non-no-reply address');
    } else if (normalized(match[1]) === 'ai-assisted@users.noreply.github.com') {
      const agentName = line.trim().replace(/^(?:co-authored-by|signed-off-by|reviewed-by|acked-by|tested-by):\s*/i, '').replace(/\s*<[^<>]+>\s*$/, '').trim();
      if (!/(?:^|\s)Agent$/.test(agentName)) {
        problems.push('unlinked AI attribution name must end with Agent');
      }
    } else if (isOwnerEmail(match[1])) {
      const displayed = line.trim().replace(/^(?:co-authored-by|signed-off-by|reviewed-by|acked-by|tested-by):\s*/i, '').replace(/\s*<[^<>]+>\s*$/, '');
      if (!approvedOwnerNames.has(normalized(displayed))) {
        problems.push('identity trailer uses a non-public owner display name');
      }
    }
  }
  return [...new Set(problems)];
}

function selfTest() {
  const item = (email, user, message='fix: test') => ({
    commit: { author:{email,name:'owner'}, committer:{email,name:'owner'}, message },
    author:user ? {login:user} : null, committer:user ? {login:user} : null
  });
  const masked='123+owner@users.noreply.github.com';
  assert.deepEqual(inspectCommit(item(masked,'owner'),'owner',123,'Owner Public'),[]);
  assert.ok(inspectCommit({...item(masked,'owner'),commit:{...item(masked,'owner').commit,author:{email:masked,name:'Private Name'}}},'owner',123,'Owner Public').length);
  assert.equal(inspectCommit({...item(masked,'owner'),commit:{author:{email:masked,name:'Private Profile Name'},committer:{email:masked,name:'Private Profile Name'},message:'fix: test'}},'owner',123,'Private Profile Name').length,2);
  assert.deepEqual(inspectCommit(item('public@company.example','other'),'owner'),[]);
  assert.ok(inspectCommit(item('private@example.com',null),'owner').length);
  assert.ok(inspectCommit(item('private@example.com','owner'),'owner').length);
  assert.ok(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Person <private@example.com>'),'owner').length);
  assert.deepEqual(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Person <456+other@users.noreply.github.com>'),'owner',123,'Owner Public'),[]);
  assert.ok(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Private Name <123+owner@users.noreply.github.com>'),'owner',123,'Owner Public').length);
  const agentTrailer = (label) => item(masked,'owner', 'fix: test\n\nCo-authored-by: '+label+' <ai-assisted@users.noreply.github.com>');
  assert.deepEqual(inspectCommit(agentTrailer('Privacy Guard Agent'),'owner',123),[]);
  assert.ok(inspectCommit(agentTrailer('Privacy Guard'),'owner',123).some(reason => reason.includes('Agent')));
  assert.deepEqual(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Other Human <456+other@users.noreply.github.com>'),'owner',123),[]);
  // A GitHub-signed merge commit can still leak an owner's profile name.
  assert.ok(inspectCommit({commit:{author:{name:'Private Display',email:masked},committer:{name:'owner',email:masked},message:'Merge PR'},author:{login:'owner'},committer:{login:'owner'}},'owner',123).length);
  // An independent linked human may deliberately use a public non-GitHub email.
  const human={commit:{author:{name:'Contributor',email:'public@company.example'},committer:{name:'Contributor',email:'public@company.example'},message:'fix: human'},author:{login:'contributor'},committer:{login:'contributor'}};
  assert.deepEqual(inspectCommit(human,'owner',123),[]);
  // A bot/unlinked Agent cannot use arbitrary private email.
  assert.ok(inspectCommit({...human,author:null,committer:null},'owner',123).length);
  console.log('Commit identity privacy: synthetic cases passed');
}

if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  // Two modes share the same trusted policy. The push mode runs AFTER publication;
  // it detects merge-generated metadata, but cannot retroactively prevent disclosure.
  const pushMode = process.argv.includes('--audit-push');
  const repository = process.env.GITHUB_REPOSITORY;
  const prNumber = Number(process.env.PR_NUMBER);
  const baseSha = String(process.env.PUSH_BASE_SHA || '');
  const headSha = String(process.env.PUSH_HEAD_SHA || '');
  const token = process.env.GITHUB_TOKEN;
  const shaPattern = /^[0-9a-f]{40}$/i;
  const validPush = shaPattern.test(baseSha) && shaPattern.test(headSha) &&
    !/^0{40}$/.test(baseSha) && baseSha !== headSha;
  if (!/^[-\w.]+\/[-\w.]+$/.test(repository || '') || !token ||
      (pushMode ? !validPush : !Number.isSafeInteger(prNumber) || prNumber < 1)) {
    console.error('Commit identity privacy: missing or invalid trusted GitHub context');
    process.exitCode = 2;
  } else {
    const base = 'https://api.github.com/repos/' + repository;
    async function read(url) {
      const response = await fetch(url, {headers: {
        authorization:'Bearer '+token,
        accept:'application/vnd.github+json',
        'x-github-api-version':'2022-11-28'
      }});
      if(!response.ok) throw new Error('GitHub API read failed ('+response.status+')');
      return response.json();
    }
    try {
      const profile = await read('https://api.github.com/users/'+encodeURIComponent(repository.split('/')[0]));
      if (!profile || !profile.login || !profile.id) throw new Error('Cannot resolve owner public identity');
      const entries = [];
      if (pushMode) {
        // GitHub's compare API caps exposed commits; never silently audit a partial set.
        for (let page=1; page<=3; page++) {
          const result = await read(base+'/compare/'+baseSha+'...'+headSha+'?per_page=100&page='+page);
          if (result.status !== 'ahead') throw new Error('Push comparison is not a fast-forward');
          if (result.head_commit && result.head_commit.sha !== headSha) throw new Error('Push HEAD mismatch');
          if (!Number.isSafeInteger(result.total_commits) || result.total_commits < 1 || result.total_commits > 250)
            throw new Error('Push commit count is missing or exceeds complete-audit limit');
          if (!Array.isArray(result.commits)) throw new Error('Invalid push comparison response');
          entries.push(...result.commits);
          if (entries.length >= result.total_commits) {
            if (entries.length !== result.total_commits) throw new Error('Duplicate or incomplete push commit metadata');
            break;
          }
          if (page===3 || result.commits.length<100) throw new Error('Incomplete push commit metadata');
        }
        if (entries.at(-1)?.sha !== headSha) throw new Error('Head commit missing from push audit');
      } else {
        const pr = await read(base+'/pulls/'+prNumber);
        if (pr.base?.repo?.full_name?.toLowerCase() !== repository.toLowerCase()) throw new Error('PR repository mismatch');
        if (pr.commits>250) throw new Error('PR has more commits than the GitHub API permits auditing');
        for (let page=1; page<=3; page++) {
          const batch=await read(base+'/pulls/'+prNumber+'/commits?per_page=100&page='+page);
          if(!Array.isArray(batch)) throw new Error('Invalid GitHub API response');
          entries.push(...batch);
          if(batch.length<100) break;
        }
        if(entries.length!==pr.commits) throw new Error('Incomplete PR commit identity metadata');
      }
      let problems=0;
      for (const item of entries) {
        const found=inspectCommit(item,repository.split('/')[0],profile.id,profile.name);
        for (const reason of found) {
          // Do not expose identity details; SHA and fixed failure categories only.
          console.error('Commit '+String(item.sha).slice(0,12)+': '+reason);
          problems++;
        }
      }
      if (problems) {
        console.error(pushMode
          ? 'Published mainline commit attribution requires privacy remediation; do not rewrite shared history automatically.'
          : 'Private attribution detected. Correct affected unmerged commits before publication.');
        process.exitCode=1;
      } else {
        console.log('Commit identity privacy: '+entries.length+(pushMode?' pushed':' PR')+' commits passed');
      }
    } catch (error) {
      // Only our own fixed errors are normally emitted; network failures are redacted.
      console.error('Commit identity privacy: '+String(error.message).slice(0,160));
      process.exitCode=2;
    }
  }
}
