import {test} from 'node:test';
import assert from 'node:assert/strict';
import {inspectLocalCommit,parseCommit} from './preflight-push-identity.mjs';

const email='267835237+Eun-si123@users.noreply.github.com';
const make=(message='feat: synthetic\n\nCo-authored-by: Network Reliability Agent <ai-assisted@users.noreply.github.com>')=>({author:{name:'Eun-si123',email},committer:{name:'Eun-si123',email},message});
test('valid owner directed Agent commit',()=>assert.deepEqual(inspectLocalCommit(make()),[]));
test('leaked profile name blocked even with no-reply mail',()=>assert.match(inspectLocalCommit({...make(),author:{name:'Private Example',email}}).join(','),/author:/));
test('unlinked agent requires Agent suffix',()=>assert.match(inspectLocalCommit(make('test\n\nCo-authored-by: Person <ai-assisted@users.noreply.github.com>')).join(','),/Agent/));
test('private trailer mail blocked and never emitted',()=>{const issues=inspectLocalCommit(make('test\n\nCo-authored-by: Test Agent <private@example.com>')); assert.ok(issues.length);assert.ok(!issues.join('').includes('private@example.com'));});
test('missing co-author blocked for owner-directed Agent work',()=>assert.ok(inspectLocalCommit(make('test')).length));
test('independent contributor must not be rewritten as owner by this mode',()=>assert.ok(inspectLocalCommit({...make(),author:{name:'Independent',email:'public@example.com'}}).length));
test('parse exact Git metadata records',()=>assert.deepEqual(parseCommit('Eun-si123\0'+email+'\0Eun-si123\0'+email+'\0feat: test\n'),{author:{name:'Eun-si123',email},committer:{name:'Eun-si123',email},message:'feat: test\n'}));
