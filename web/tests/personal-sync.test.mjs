import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';
import {ShelestCore} from '../../shared/build/dist/js/productionLibrary/Sh-shared.mjs';
globalThis.__sharedCore=ShelestCore.getInstance();
const compile=source=>ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const url=source=>'data:text/javascript;base64,'+Buffer.from(compile(source)).toString('base64');
const storage=new Map();globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value))};
globalThis.window={dispatchEvent(){}};
let words=[],books=[{id:'book',sha256:'a'.repeat(64),title:'Original',page:25,progressUpdatedAt:100}],settings={dailyGoal:0};
const store=globalThis.__store={words:async()=>structuredClone(words),books:async()=>structuredClone(books),settings:async()=>({...settings}),
  saveWord:async(word,current)=>{if(!current())return;words=words.filter(w=>w.id!==word.id).concat(word);},
  deleteWord:async(id,current)=>{if(current())words=words.filter(w=>w.id!==id);},
  saveSettings:async(value,current)=>{if(current())settings={...value};},saveBook:async(value,current)=>{if(current())books=books.map(b=>b.id===value.id?value:b);}};
const journalUrl=url(readFileSync(new URL('../src/personalJournal.ts',import.meta.url),'utf8').replace('import { sharedCore } from "./sharedCore";','const sharedCore=globalThis.__sharedCore;'));
const journal=await import(journalUrl);
const annotationUrl=url(readFileSync(new URL('../src/annotationSync.ts',import.meta.url),'utf8').replace("'./personalJournal'",JSON.stringify(journalUrl)));
const annotations=await import(annotationUrl);
const {syncPersonal,wordValue}=await import(url(readFileSync(new URL('../src/personalSync.ts',import.meta.url),'utf8')
  .replace('import {localStore} from "./store";','const localStore=globalThis.__store;').replace('"./personalJournal"',JSON.stringify(journalUrl)).replace("'./annotationSync'",JSON.stringify(annotationUrl)).replace('import {sharedCore} from "./sharedCore";','const sharedCore=globalThis.__sharedCore;')));
localStorage.setItem('shelest-owner','one');
let revision=0;const remote=new Map();const requests=[];
const send=async body=>{
  requests.push(body);
  for(const e of body.entries){const old=remote.get(e.key);if(!old||e.changedAt>old.changedAt)remote.set(e.key,{...e,revision:++revision});}
  const row=({revision,...e})=>e;
  const delta=[...remote.values()].filter(e=>e.revision>body.since).sort((a,b)=>a.revision-b.revision);
  return {accountId:'one',entries:delta.slice(0,100).map(row),ack:body.entries.map(e=>row(remote.get(e.key))),cursor:delta.length>100?delta[99].revision:revision,hasMore:delta.length>100};
};
const run=()=>syncPersonal('one',send,()=>true);
const word={id:'native-id-differs',word:'Шелест',definition:'Тихий шорох',bookTitle:'Книга',addedAt:100,learned:false};
words=[word];journal.capturePersonal('word:шелест',wordValue(word));journal.capturePersonal('goal',45);journal.capturePersonal('title:'+'a'.repeat(64),'Новое название');
await run();assert.equal(settings.dailyGoal,45);assert.equal(books[0].title,'Новое название');assert.equal(books[0].page,25);assert.equal(books[0].progressUpdatedAt,100);
await run();assert.deepEqual(requests.at(-1).entries,[],'unchanged records are not uploaded again');
remote.set('word:шелест',{key:'word:шелест',value:{...wordValue(word),learned:true},changedAt:Date.now()+1000,revision:++revision});await run();assert.equal(words[0].learned,true);
remote.set('word:шелест',{key:'word:шелест',value:null,changedAt:Date.now()+2000,revision:++revision});await run();assert.equal(words.length,0);
await run();assert.equal(words.length,0,'deleted word is not resurrected by an unchanged snapshot');
// A losing upload must receive the winner even if its delta cursor is already current.
journal.capturePersonal('goal',15);const ledger=journal.journal('one');ledger.goal.changedAt=1;journal.persistJournal('one',ledger);await run();assert.equal(settings.dailyGoal,45);
// Mutation during a request must survive an older response and get a second upload.
let injected=false;
await syncPersonal('one',async body=>{const reply=await send(body);if(!injected){injected=true;journal.capturePersonal('goal',90);}return reply;},()=>true);
assert.equal(settings.dailyGoal,90);
// Offline requests never acknowledge the outbox.
journal.capturePersonal('goal',60);await assert.rejects(syncPersonal('one',async()=>{throw Error('offline');},()=>true));assert.equal(journal.journal('one').goal.pending,true);
await run();assert.equal(settings.dailyGoal,60);
// Reject the entire response before applying a malicious/invalid deletion.
words=[word];await assert.rejects(syncPersonal('one',async()=>({accountId:'one',entries:[{key:'word:шелест',value:null,changedAt:100},{key:'goal',value:999,changedAt:100}],cursor:1,hasMore:false}),()=>true));assert.equal(words.length,1);
// Data bound to another account is not implicitly uploaded at login.
localStorage.setItem('shelest-personal-binding:word:шелест','other');localStorage.setItem('shelest-personal-binding:goal','other');
localStorage.setItem('shelest-owner','new');let captured;
await syncPersonal('new',async body=>{captured=body;return {accountId:'new',entries:[],cursor:0,hasMore:false};},()=>true);
assert.deepEqual(captured.entries,[]);
// Rename in guest mode survives the first login; foreign logged-out data stays bound.
localStorage.setItem('shelest-owner','');journal.capturePersonal('title:'+'c'.repeat(64),'Guest rename');
localStorage.setItem('shelest-owner','guest-login');
await syncPersonal('guest-login',async body=>{captured=body;return {accountId:'guest-login',entries:body.entries,cursor:1,hasMore:false};},()=>true);
assert.ok(captured.entries.some(e=>e.key==='title:'+'c'.repeat(64)&&e.value==='Guest rename'));
localStorage.setItem('shelest-owner','');journal.capturePersonal('goal',75);
assert.equal(journal.journal('other').goal.value,75,'logged-out edits of previously owned data do not leak into a different account');
assert.equal(journal.wordKey('  ЁЖ  '),'word:ёж');
assert.notEqual(journal.wordKey('все'),journal.wordKey('всё'));
assert.notEqual(journal.wordKey('осел'),journal.wordKey('осёл'));
localStorage.setItem('shelest-personal:legacy',JSON.stringify({'word:еж':{key:'word:еж',value:{...wordValue(word),word:'ёж'},changedAt:123,pending:false}}));
localStorage.setItem('shelest-personal-binding:word:еж','legacy');
assert.equal(journal.journal('legacy')['word:ёж'].pending,true);
assert.equal(journal.journal('legacy')['word:еж'],undefined);
assert.equal(localStorage.getItem('shelest-personal-binding:word:ёж'),'legacy');
localStorage.setItem('shelest-owner','one');
const fixture=books[0];
annotations.saveTextNotes(fixture,[{quote:'Selected text',note:'My note',kind:'note',contextBefore:'before',contextAfter:'after'}]);
annotations.savePdfMarks(fixture,{strokes:[],notes:[]},{strokes:[{id:crypto.randomUUID(),page:3,color:'#3976ba',width:4,points:[{x:.2,y:.3},{x:.4,y:.5}]}],notes:[]});
await run();
const marked=[...remote.values()].filter(e=>e.key.startsWith('mark:'));
assert.equal(marked.length,2);assert.equal(marked.find(e=>e.value.type==='DRAWING').value.pageIndex,2);
assert.equal(marked.find(e=>e.value.type==='NOTE').value.contextBefore,'before');
// New device, same book but different local id: restore all marks from the cloud.
books=[{...fixture,id:'second-local-id'}];localStorage.setItem('shelest-personal-collections-cursor:one','0');await run();
assert.equal(annotations.textNotes('second-local-id')[0].quote,'Selected text');
assert.equal(JSON.parse(localStorage.getItem('shelest:pdf-marks:second-local-id')).strokes[0].page,3);
annotations.saveTextNotes(books[0],[]);await run();
assert.equal(remote.get(marked.find(e=>e.value.type==='NOTE').key).value,null);
assert.equal(JSON.parse(localStorage.getItem('shelest:pdf-marks:second-local-id')).strokes.length,1,'deleting a note preserves unrelated strokes');
const bad=structuredClone(marked.find(e=>e.value?.type==='DRAWING'));bad.value.points[0].x=2;
assert.throws(()=>__sharedCore.personalResponse(JSON.stringify({accountId:'one',cursor:1,hasMore:false,entries:[bad]})));
console.log('Personal sync passed: dictionary/learned/delete, goals, rename without moving position, deltas, stale ACK, in-flight edits, offline retry, account isolation and validation');
