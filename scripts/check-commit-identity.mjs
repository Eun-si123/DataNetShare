#!/usr/bin/env node
// Read-only GitHub PR metadata audit. Never echo potentially private identity values.
import assert from 'node:assert/strict';

const noReply = email => /@users\.noreply\.github\.com$/i.test(email) || /^(?:noreply|support)@github\.com$/i.test(email);
const normalized = value => String(value || '').trim().toLowerCase();

export function inspectCommit(item, owner, ownerId = null, publicName = null) {
  const approvedOwnerNames = new Set([normalized(owner), normalized(publicName)].filter(Boolean));
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
  assert.deepEqual(inspectCommit(item('public@company.example','other'),'owner'),[]);
  assert.ok(inspectCommit(item('private@example.com',null),'owner').length);
  assert.ok(inspectCommit(item('private@example.com','owner'),'owner').length);
  assert.ok(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Person <private@example.com>'),'owner').length);
  assert.deepEqual(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Person <456+other@users.noreply.github.com>'),'owner',123,'Owner Public'),[]);
  assert.ok(inspectCommit(item(masked,'owner','fix: test\n\nCo-authored-by: Private Name <123+owner@users.noreply.github.com>'),'owner',123,'Owner Public').length);
  console.log('Commit identity privacy: synthetic cases passed');
}

if (process.argv.includes('--self-test')) {
  selfTest();
} else {
  const repository = process.env.GITHUB_REPOSITORY;
  const prNumber = Number(process.env.PR_NUMBER);
  const token = process.env.GITHUB_TOKEN;
  if (!/^[-\w.]+\/[-\w.]+$/.test(repository || '') || !Number.isSafeInteger(prNumber) || prNumber < 1 || !token) {
    console.error('Commit identity privacy: missing trusted GitHub context');
    process.exitCode = 2;
  } else {
    const base = 'https://api.github.com/repos/' + repository;
    async function read(url) {
      const response=await fetch(url,{headers:{
        authorization:'Bearer '+token,
        accept:'application/vnd.github+json',
        'x-github-api-version':'2022-11-28'
      }});
      if(!response.ok)throw new Error('GitHub API read failed ('+response.status+')');
      return response.json();
    }
    try {
      const pr=await read(base+'/pulls/'+prNumber);
      if(pr.base?.repo?.full_name?.toLowerCase() !== repository.toLowerCase())throw new Error('PR repository mismatch');
      if(pr.commits>250)throw new Error('PR has more commits than the GitHub API permits auditing');
      const profile=await read('https://api.github.com/users/'+encodeURIComponent(repository.split('/')[0]));
      if(!profile || !profile.login || !profile.id)throw new Error('Cannot resolve owner public identity');
      const entries=[];
      for(let page=1;page<=3;page++){
        const batch=await read(base+'/pulls/'+prNumber+'/commits?per_page=100&page='+page);
        if(!Array.isArray(batch))throw new Error('Invalid GitHub API response');
        entries.push(...batch);
        if(batch.length<100)break;
      }
      if(entries.length!==pr.commits)throw new Error('Incomplete PR commit identity metadata');
      let problems=0;
      for(const item of entries){
        const found=inspectCommit(item,repository.split('/')[0],profile.id,profile.name);
        for(const reason of found){
          // Do not expose the original name, email, or commit message in CI output.
          console.error('Commit '+String(item.sha).slice(0,12)+': '+reason);
          problems++;
        }
      }
      if(problems){console.error('Private attribution detected. Re-create affected unmerged commits with approved public or no-reply identities.');process.exitCode=1;}
      else console.log('Commit identity privacy: '+entries.length+' PR commits passed');
    }catch(error){console.error('Commit identity privacy: '+error.message);process.exitCode=2;}
  }
}
