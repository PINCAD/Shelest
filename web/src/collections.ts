import {sharedCore} from './sharedCore';
import {journal,persistJournal} from './personalJournal';
import {collectionHash} from './cloud';
import type {Book} from './types';

export type BookCollection={id:string;name:string;members:string[]};
export const collectionOwner=()=>localStorage.getItem('shelest-owner')||'';
export function readCollections(owner=collectionOwner()):BookCollection[] {
  const entries=journal(owner);
  if(!owner) for(const key of Object.keys(entries)) {
    if(/^(collection|member):/.test(key)&&localStorage.getItem('shelest-personal-binding:'+key)!=='guest') delete entries[key];
  }
  return JSON.parse(sharedCore.collectionsJson(JSON.stringify(entries)));
}
export function captureCollections(values:Record<string,unknown>,owner=collectionOwner()) {
  if(!Object.keys(values).length) return;
  const entries=journal(owner);
  for(const [key,value] of Object.entries(values)) entries[key]={key,value,changedAt:Math.max(Date.now(),(entries[key]?.changedAt||0)+1),pending:true};
  persistJournal(owner,entries);
  for(const key of Object.keys(values)) localStorage.setItem('shelest-personal-binding:'+key,owner||'guest');
  window.dispatchEvent(new Event('shelest-personal-changed'));
}
export async function addToCollection(id:string|null,name:string,books:Book[]) {
  const owner=collectionOwner(),existing=readCollections(owner),target=id??crypto.randomUUID();
  const title=id?existing.find(c=>c.id===id)?.name:name.trim();
  if(!title||title.length>120) throw new Error('Название должно содержать от 1 до 120 символов');
  const entries=journal(owner),prefix='member:'+target+':';
  let next=Math.max(-1,...Object.values(entries).filter(e=>e.key.startsWith(prefix)&&typeof e.value==='number').map(e=>Number(e.value)))+1;
  const changes:Record<string,unknown>=id?{}:{['collection:'+target]:title};
  const members=new Set(existing.find(c=>c.id===target)?.members??[]);
  for(const book of books) {
    const sha=await collectionHash(book);
    if(members.has(sha)) continue;
    members.add(sha);changes[prefix+sha]=next++;
  }
  if(owner!==collectionOwner()) throw new Error('Аккаунт изменился');
  if(next>1000000) throw new Error('Слишком много книг в коллекции');
  captureCollections(changes,owner);return target;
}
export function editCollection(id:string,name:string,members:string[],removeCollection=false,expectedMembers?:string[]) {
  const previous=readCollections().find(c=>c.id===id);
  if(!previous) throw new Error('Коллекция удалена');
  if(!removeCollection&&expectedMembers&&JSON.stringify(previous.members)!==JSON.stringify(expectedMembers))
    throw new Error('Коллекция изменилась на другом устройстве. Откройте редактор заново.');
  if(!removeCollection&&(!name.trim()||name.trim().length>120||new Set(members).size!==members.length||members.some(s=>!previous.members.includes(s)))) throw new Error('Некорректная коллекция');
  const changes:Record<string,unknown>={['collection:'+id]:removeCollection?null:name.trim()};
  for(const sha of previous.members) if(removeCollection||!members.includes(sha)) changes['member:'+id+':'+sha]=null;
  if(!removeCollection) members.forEach((sha,index)=>changes['member:'+id+':'+sha]=index);
  captureCollections(changes);
}
