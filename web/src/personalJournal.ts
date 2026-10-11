import { sharedCore } from "./sharedCore";
export type PersonalEntry={key:string;value:unknown;changedAt:number;pending?:boolean};
export const wordKey=(word:string)=>sharedCore.personalWordKey(word);
export function journal(owner:string):Record<string,PersonalEntry> {
  const saved=localStorage.getItem(`shelest-personal:${owner||"guest"}`);
  if(!saved) return {};
  const value=JSON.parse(saved);
  if(!value||typeof value!=="object"||Array.isArray(value)) throw new Error("Повреждён локальный журнал синхронизации");
  let migrated=false;
  for(const [key,entry] of Object.entries(value) as [string,PersonalEntry][]) {
    const word=(entry.value as {word?:string}|null)?.word;
    if(!key.startsWith("word:")||typeof word!=="string") continue;
    const canonical=wordKey(word);
    if(canonical===key) continue;
    const existing=value[canonical] as PersonalEntry|undefined;
    if(!existing||entry.changedAt>existing.changedAt) value[canonical]={...entry,key:canonical,pending:true};
    delete value[key];migrated=true;
    const binding=localStorage.getItem(`shelest-personal-binding:${key}`);
    if(binding&&!localStorage.getItem(`shelest-personal-binding:${canonical}`)) localStorage.setItem(`shelest-personal-binding:${canonical}`,binding);
  }
  if(migrated) persistJournal(owner,value);
  return value;
}
export function persistJournal(owner:string,value:Record<string,PersonalEntry>) {localStorage.setItem(`shelest-personal:${owner||"guest"}`,JSON.stringify(value));}
export function capturePersonal(key:string,value:unknown,owner=localStorage.getItem("shelest-owner")||"") {
  const binding=localStorage.getItem(`shelest-personal-binding:${key}`);
  if(!owner&&binding&&binding!=="guest") owner=binding;
  const entries=journal(owner);
  entries[key]={key,value,changedAt:Math.max(Date.now(),(entries[key]?.changedAt||0)+1),pending:true};
  persistJournal(owner,entries);localStorage.setItem(`shelest-personal-binding:${key}`,owner||"guest");
  window.dispatchEvent(new Event("shelest-personal-changed"));
}
export function seedPersonal(owner:string,key:string,value:unknown) {
  const binding=localStorage.getItem(`shelest-personal-binding:${key}`);
  if(binding&&binding!==owner&&binding!=="guest") return;
  const entries=journal(owner);if(entries[key]) return;
  const guest=journal("")[key];
  entries[key]=guest&&JSON.stringify(guest.value)===JSON.stringify(value)?{...guest,pending:true}:{key,value,changedAt:1,pending:true};
  persistJournal(owner,entries);localStorage.setItem(`shelest-personal-binding:${key}`,owner);
}
export function adoptGuest(owner:string) {
  for(const entry of Object.values(journal(""))) if(entry.pending&&localStorage.getItem(`shelest-personal-binding:${entry.key}`)==="guest") seedPersonal(owner,entry.key,entry.value);
}
export function removeCollectionMembership(sha:string,owner:string) {
  for(const entry of Object.values(journal(owner))) if(entry.key.startsWith('member:')&&entry.key.endsWith(':'+sha)&&entry.value!==null&&
    (owner||localStorage.getItem('shelest-personal-binding:'+entry.key)==='guest'))
    capturePersonal(entry.key,null,owner);
}
