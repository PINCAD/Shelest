import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';
import {ShelestCore} from '../../shared/build/dist/js/productionLibrary/Sh-shared.mjs';
globalThis.__sharedCore=ShelestCore.getInstance();
const storage=new Map();globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value))};
let events=0;globalThis.window={dispatchEvent(){events++;}};
const url=source=>'data:text/javascript;base64,'+Buffer.from(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText).toString('base64');
const journalUrl=url(readFileSync(new URL('../src/personalJournal.ts',import.meta.url),'utf8').replace('import { sharedCore } from "./sharedCore";','const sharedCore=globalThis.__sharedCore;'));
const journal=await import(journalUrl);
globalThis.__hash=async book=>book.sha256;
const collections=await import(url(readFileSync(new URL('../src/collections.ts',import.meta.url),'utf8')
  .replace("import {sharedCore} from './sharedCore';",'const sharedCore=globalThis.__sharedCore;')
  .replace("'./personalJournal'",JSON.stringify(journalUrl))
  .replace("import {collectionHash} from './cloud';",'const collectionHash=book=>globalThis.__hash(book);')));
const a={id:'a',sha256:'a'.repeat(64),title:'Том I',page:254},b={id:'b',sha256:'b'.repeat(64),title:'Том II',page:17};
const id=await collections.addToCollection(null,'  Песнь льда и пламени  ',[a,b,a]);
assert.equal(events,1,'batch commits notify once, not once per book');
assert.deepEqual(collections.readCollections(),[{id,name:'Песнь льда и пламени',members:[a.sha256,b.sha256]}]);
await collections.addToCollection(id,'',[a]);assert.equal(events,1,'duplicate add is a no-op');
collections.editCollection(id,'Переименована',[b.sha256,a.sha256]);
assert.deepEqual(collections.readCollections()[0].members,[b.sha256,a.sha256]);
assert.throws(()=>collections.editCollection(id,'Outdated',[a.sha256],false,[a.sha256,b.sha256]),/другом устройстве/);
collections.editCollection(id,'Переименована',[a.sha256]);
assert.equal(journal.journal('')['member:'+id+':'+b.sha256].value,null);
const second=await collections.addToCollection(null,'Другая',[a]);
assert.equal(collections.readCollections().length,2,'same book may belong to multiple groups');
collections.editCollection(id,'',[a.sha256],true);
assert.equal(collections.readCollections().length,1);
assert.equal(a.page,254,'removing a group never changes files/progress');
// Login adopts guest groups once; logout does not expose them as new guest data.
journal.adoptGuest('one');localStorage.setItem('shelest-owner','one');
assert.equal(collections.readCollections()[0].id,second);
localStorage.setItem('shelest-owner','two');assert.deepEqual(collections.readCollections(),[]);
localStorage.setItem('shelest-owner','');assert.deepEqual(collections.readCollections(),[]);
const accountBefore=JSON.stringify(journal.journal('one'));
journal.removeCollectionMembership(a.sha256,'');
assert.equal(JSON.stringify(journal.journal('one')),accountBefore,'guest deletion never mutates a previous account');
// Owner changed while hashing a large local file: no write to either namespace.
globalThis.__hash=async book=>{localStorage.setItem('shelest-owner','two');return book.sha256;};
await assert.rejects(collections.addToCollection(null,'Interrupted',[b]),/Аккаунт изменился/);
assert.deepEqual(collections.readCollections('two'),[]);
// Collection parent tombstone hides a late membership upload.
collections.captureCollections({['member:'+id+':'+b.sha256]:7},'');
assert.ok(!collections.readCollections('').some(c=>c.id===id));
console.log('Collection journal tests passed: offline, order, duplicates, tombstones, account isolation.');
