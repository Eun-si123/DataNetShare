#!/usr/bin/env node
// Pre-merge owner-account privacy guard for owner-directed Agent work.
// Run BEFORE requesting a GitHub-generated merge. Never print profile fields.
import assert from 'node:assert/strict';
const normalize = value => String(value || '').trim().toLowerCase();

export function assessMergeActor(actor, repositoryOwner) {
  if (!actor || !actor.login || !repositoryOwner)
    return 'Cannot establish merge actor public identity';
  if (normalize(actor.login) !== normalize(repositoryOwner))
    return 'Merge actor is not the expected repository owner; use a separately approved contributor workflow';
  if (!actor.name || normalize(actor.name) !== normalize(actor.login))
    return 'Merge actor profile display name differs from public handle; GitHub-generated merge may expose unapproved name';
  return null;
}

if (process.argv.includes('--self-test')) {
  assert.equal(assessMergeActor({login:'owner',name:'owner'},'owner'),null);
  assert.ok(assessMergeActor({login:'owner',name:'Sensitive Display'},'owner'));
  assert.ok(assessMergeActor({login:'outsider',name:'outsider'},'owner'));
  assert.ok(assessMergeActor({login:'owner',name:null},'owner'));
  console.log('Merge identity preflight: synthetic cases passed');
} else {
  const repository = process.env.GITHUB_REPOSITORY;
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (!/^[-\w.]+\/[-\w.]+$/.test(repository || '') || !token) {
    console.error('Merge identity preflight: missing trusted repository or API credentials');
    process.exitCode = 2;
  } else {
    try {
      const response=await fetch('https://api.github.com/user',{headers:{
        authorization:'Bearer '+token,
        accept:'application/vnd.github+json',
        'x-github-api-version':'2022-11-28'
      }});
      if (!response.ok) throw new Error('Authenticated account lookup failed');
      const actor=await response.json();
      const problem=assessMergeActor(actor,repository.split('/')[0]);
      if (problem) {
        console.error('BLOCK owner-directed API merge: '+problem);
        console.error('Use an explicitly controlled local Git merge/commit and verify the remote result; do not downgrade identity checks or alter public profiles without approval.');
        process.exitCode=1;
      } else {
        console.log('Merge actor public-profile preflight passed. Still verify the actual merge commit author, committer, trailers and signature.');
      }
    } catch {
      console.error('Merge identity preflight: cannot establish safe actor identity');
      process.exitCode=2;
    }
  }
}
