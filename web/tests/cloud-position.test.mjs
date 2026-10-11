import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';
import { ShelestCore, SharedSyncSession } from '../../shared/build/dist/js/productionLibrary/Sh-shared.mjs';
globalThis.__sharedCore = ShelestCore.getInstance();
globalThis.__SharedSyncSession = SharedSyncSession;
const compile = source => ts.transpileModule(source, {compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;
const moduleUrl = source => 'data:text/javascript;base64,' + Buffer.from(compile(source)).toString('base64');
const progressUrl = moduleUrl(readFileSync(new URL('../src/readingProgress.ts', import.meta.url), 'utf8')
  .replace('import { sharedCore } from "./sharedCore";', 'const sharedCore = globalThis.__sharedCore;'));
let book = {id:'one', title:'Fixture', author:'', format:'EPUB', size:4, page:1, position:0, addedAt:Date.now(), lastOpenedAt:Date.now(), progressUpdatedAt:0, sha256:'a'.repeat(64), blob:new Blob(['test'])};
const requests = [];
let offline = false;
let remote = {sha256:book.sha256, title:book.title, author:'', fileType:'EPUB', size:4, hasFile:false, position:0.4, pageIndex:13, updatedAt:Date.now()-1000, readingPlace:{platform:'android',segment:2,page:13,anchor:'Уникальныйотрывоктестовойкнигидостаточнойдлины.'}};
let tombstones = [];
let deletedLocally = false;
const account = {id:'1',isPremium:false,quotaBytes:0};
globalThis.__testStore = {books:async()=>deletedLocally ? [] : [{...book}], deleteBook:async()=>{deletedLocally=true;}, saveBook:async value=>{book={...value};}, saveBookHash:async(id,sha)=>{book.sha256=sha;}};
const storage = new Map();
globalThis.localStorage = {getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value)};
globalThis.window = {setTimeout:(fn,ms)=>setTimeout(fn, ms===1500?0:ms),clearTimeout};
globalThis.fetch = async (url, options={}) => {
  if (offline) throw new Error('offline');
  requests.push({url,body:options.body instanceof Blob ? options.body : options.body ? JSON.parse(options.body) : null});
  if (url.endsWith('/auth/config')) return Response.json({csrfToken:'test',nonce:'test'});
  if (url.endsWith('/account')) return Response.json({account});
  if (options.method === 'DELETE') { tombstones = [book.sha256]; return Response.json({status:'deleted'}); }
  if (url.endsWith('/file') || url.endsWith('/auth/web/logout')) return Response.json({});
  if (url.endsWith('/sync/books')) return Response.json({account,books:tombstones.length?[]:[remote],deletedBooks:tombstones,serverTime:Date.now()});
  throw new Error('Unexpected request: '+url);
};
const source = readFileSync(new URL('../src/cloud.ts',import.meta.url),'utf8')
  .replace('import {syncPersonal} from "./personalSync";', 'const syncPersonal=async()=>{}; // Personal sync has a separate protocol test.')
  .replace('import {capturePersonal,removeCollectionMembership} from "./personalJournal";', 'const capturePersonal=()=>{},removeCollectionMembership=()=>{};')
  .replace('import { localStore } from "./store";', 'const localStore = globalThis.__testStore;')
  .replace('import { sharedCore } from "./sharedCore";', 'const sharedCore = globalThis.__sharedCore;')
  .replace('import { SharedSyncSession } from "@shelest/shared";', 'const SharedSyncSession = globalThis.__SharedSyncSession;')
  .replace('"./readingProgress"', JSON.stringify(progressUrl));
const cloud = await import(moduleUrl(source));
assert.equal(cloud.settingsAutoSync(), true);
cloud.configureSync({autoSync:false,syncDeletions:true});
const untouched = requests.length;
assert.equal((await cloud.positionBeforeOpen(book)).page, 1);
cloud.queueReadingPosition(book);
await new Promise(resolve => setTimeout(resolve, 10));
assert.equal(requests.length, untouched);
cloud.configureSync({autoSync:true,syncDeletions:true});
const opened = await cloud.positionBeforeOpen(book);
assert.equal(opened.page,14);
assert.equal(opened.cloudPlace.anchor,remote.readingPlace.anchor);
assert.equal(requests.at(-1).body.books.length,0);
console.log('remote position pulled before opening; no file upload');

const before = requests.length;
cloud.setCloudReading(true);
book = {...book,page:5,progressUpdatedAt:Date.now(),cloudPlace:{platform:'web',cfi:'epubcfi(test)',anchor:'Последняястрочка.'}};
cloud.queueReadingPosition(book);
for(let i=0;i<30 && requests.length===before;i++) await new Promise(resolve=>setTimeout(resolve,5));
assert.equal(requests.at(-1).body.books.length,1);
assert.equal(requests.at(-1).body.books[0].readingPlace.cfi,'epubcfi(test)');
assert.equal(requests.at(-1).body.books[0].pageIndex,4);
console.log('open reader publishes its latest position without syncing files');

const localTime = book.progressUpdatedAt;
await cloud.positionBeforeOpen(book);
assert.equal(book.progressUpdatedAt,localTime);
assert.equal(book.page,5);
offline = true;
assert.equal((await cloud.positionBeforeOpen(book)).page,5);
console.log('older remote position cannot overwrite local reading; offline opening works');

offline = false;
const originalFetch = globalThis.fetch;
let configSignal;
globalThis.fetch = async (url, options = {}) => {
  if (url.endsWith('/auth/config')) configSignal = options.signal;
  return originalFetch(url, options);
};
const controller = new AbortController();
await cloud.authConfig(controller.signal);
assert.equal(configSignal, controller.signal);
console.log('authentication config honors the opening timeout signal');
cloud.setCloudReading(false);
cloud.configureSync({autoSync:true,syncDeletions:false});
await cloud.queueBookDeletion(book);
assert.ok(storage.get('shelest-hidden:1')?.includes(book.sha256), JSON.stringify([...storage]));
await cloud.syncBooks(account);
assert.equal(deletedLocally, false);
assert.ok(cloud.lastSuccessfulSync(account.id)>0);
assert.equal(cloud.lastSuccessfulSync('other-account'),0,'sync timestamp is scoped to its account');
assert.equal(requests.at(-1).body.books.length, 0);
console.log('local-only removal is not republished');
await cloud.markUserImport(book);
cloud.configureSync({autoSync:true,syncDeletions:true});
offline = true;
await cloud.queueBookDeletion(book);
await assert.rejects(cloud.retryPendingDeletes());
assert.ok(storage.get('shelest-pending-deletes:1').includes(book.sha256));
offline = false;
await cloud.retryPendingDeletes();
assert.equal(storage.get('shelest-pending-deletes:1'), '[]');
await cloud.syncBooks(account);
assert.equal(deletedLocally, true);
assert.equal(requests.at(-1).body.books.length, 0);
console.log('offline deletion retries and removes the incoming tombstoned book');

// Import while offline cancels queued deletion rather than deleting the newly restored copy.
deletedLocally = false; tombstones = [];
offline = true;
await cloud.queueBookDeletion(book);
await cloud.markUserImport(book);
assert.equal(storage.get('shelest-pending-deletes:1'), '[]');
assert.equal(storage.get('shelest-deleted:1'), '[]');
offline = false;
await cloud.syncBooks(account);
assert.equal(deletedLocally,false);
console.log('explicit reimport cancels pending offline deletion');

// Premium changes invalidate the cursor and recheck file availability.
account.isPremium = true; account.quotaBytes = 4096;
await cloud.syncBooks(account);
assert.ok(requests.some(request => request.url.endsWith('/file')));
account.isPremium = false; account.quotaBytes = 0;
console.log('Premium promotion rechecks and uploads the missing book file');

// Response validation occurs before applying any tombstones.
globalThis.fetch = async (url, options={}) => url.endsWith('/sync/books')
  ? Response.json({account, books:[{...remote,position:2}], deletedBooks:[book.sha256], serverTime:Date.now()})
  : originalFetch(url,options);
await assert.rejects(cloud.syncBooks(account));
assert.equal(deletedLocally,false);
console.log('malformed reply cannot delete a local book');

// Opening the reader during a slow library request must invalidate the response.
let releaseSync, syncStarted;
const syncReached = new Promise(resolve => {syncStarted=resolve;});
globalThis.fetch = async (url,options={}) => {
  if (url.endsWith('/sync/books')) {syncStarted(); return new Promise(resolve=>{releaseSync=()=>resolve(Response.json({
    account,books:[],deletedBooks:[book.sha256],serverTime:Date.now()}));});}
  return originalFetch(url,options);
};
const beforeInvalidated=cloud.lastSuccessfulSync(account.id);
const pendingSync = cloud.syncBooks(account);
await syncReached; cloud.setCloudReading(true); releaseSync(); await pendingSync;
assert.equal(deletedLocally,false);
cloud.setCloudReading(false);
assert.equal(cloud.lastSuccessfulSync(account.id),beforeInvalidated,'an invalidated sync does not claim success');
console.log('reader opening rejects an in-flight library deletion');

let releaseAccount, accountStarted;
const accountReached = new Promise(resolve => {accountStarted=resolve;});
globalThis.fetch = async (url,options={}) => {
  if (url.endsWith('/account')) {accountStarted(); return new Promise(resolve=>{releaseAccount=()=>resolve(Response.json({account}));});}
  return originalFetch(url,options);
};
const staleAccount = cloud.currentAccount();
await accountReached; await cloud.logout(); releaseAccount();
assert.equal(await staleAccount,null);
assert.equal(storage.get('shelest-owner'),'');
console.log('late account reply cannot resurrect a logged-out session');
