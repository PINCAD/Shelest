import {localStore} from "./store";
import {journal,persistJournal,seedPersonal,adoptGuest,wordKey,type PersonalEntry} from "./personalJournal";
import type {SavedWord} from "./types";
import {sharedCore} from "./sharedCore";
import {seedMarks,applyMark,type Mark} from './annotationSync';
export const wordValue=(word:SavedWord)=>({word:word.word.slice(0,200),definition:word.definition.slice(0,12000),bookTitle:word.bookTitle.slice(0,500),addedAt:word.addedAt,learned:word.learned});
export async function syncPersonal(owner:string,send:(body:unknown)=>Promise<any>,current:()=>boolean) {
  if(!current()) return;adoptGuest(owner);
  for(const word of await localStore.words()) {if(!current()) return;seedPersonal(owner,wordKey(word.word),wordValue(word));}
  const settings=await localStore.settings();if(!current()) return;seedPersonal(owner,"goal",settings.dailyGoal);
  const books=await localStore.books();if(!current()) return;seedMarks(owner,books,current);
  let cursor=Number(localStorage.getItem(`shelest-personal-collections-cursor:${owner}`))||0;
  let more=true;
  while(current()&&(more||Object.values(journal(owner)).some(e=>e.pending))) {
    const outgoing:PersonalEntry[]=[];let bytes=0;
    for(const e of Object.values(journal(owner)).filter(e=>e.pending)) {
      const size=new TextEncoder().encode(JSON.stringify(e)).length;
      if(outgoing.length&&(bytes+size>1500000||outgoing.length>=25)) break;
      outgoing.push(e);bytes+=size;
    }
    const raw=await send({collections:true,since:cursor,entries:outgoing.map(({key,value,changedAt})=>({key,value,changedAt}))});
    if(!current()) return;
    const reply=JSON.parse(sharedCore.personalResponse(JSON.stringify(raw)));
    if(reply.accountId!==owner||!Array.isArray(reply.entries)||!Number.isSafeInteger(reply.cursor)||typeof reply.hasMore!=="boolean") throw new Error("Некорректный ответ синхронизации личных данных");
    const received=new Map<string,PersonalEntry>(([...(reply.ack??[]),...reply.entries] as PersonalEntry[]).map(entry=>[entry.key,entry]));
    for(const remote of received.values()) {
      if(!current()) return;
      const before=journal(owner)[remote.key],sent=outgoing.find(e=>e.key===remote.key);
      const valid=()=>current()&&JSON.stringify(journal(owner)[remote.key])===JSON.stringify(before);
      if(before&&(before.changedAt>remote.changedAt)&&(!sent||JSON.stringify(before)!==JSON.stringify(sent))) continue;
      if(/^(collection|member):/.test(remote.key)) {
        // The persisted journal itself is the collection store.
      } else if(remote.key.startsWith('mark:')) {
        for(const book of books) if(book.sha256===remote.key.split(':')[1]) applyMark(book,remote.key,remote.value as Mark|null,valid);
      } else if(remote.key.startsWith("word:")) {
        const matches=(await localStore.words()).filter(word=>wordKey(word.word)===remote.key);
        if(!current()) return;
        if(!valid()) continue;
        localStorage.setItem(`shelest-personal-binding:${remote.key}`,owner);
        if(remote.value===null) {for(const word of matches) await localStore.deleteWord(word.id,valid,false);}
        else {
          const value=remote.value as Omit<SavedWord,"id">;
          await localStore.saveWord({...value,id:matches[0]?.id??crypto.randomUUID()},valid,false);
          for(const duplicate of matches.slice(1)) await localStore.deleteWord(duplicate.id,valid,false);
        }
      } else if(remote.key==="goal") {
        const settings=await localStore.settings();
        await localStore.saveSettings({...settings,dailyGoal:Number(remote.value)},valid,false);
      } else if(remote.key.startsWith("title:")) {
        for(const book of await localStore.books()) if(book.sha256===remote.key.slice(6)) await localStore.saveBook({...book,title:String(remote.value)},valid);
      } else throw new Error("Неизвестные личные данные");
      if(!current()) return;
      const after=journal(owner);
      if(JSON.stringify(after[remote.key])===JSON.stringify(before)) {after[remote.key]={...remote,pending:false};persistJournal(owner,after);localStorage.setItem(`shelest-personal-binding:${remote.key}`,owner);}
    }
    if(!current()) return;
    const entries=journal(owner);
    for(const sent of outgoing) if(JSON.stringify(entries[sent.key])===JSON.stringify(sent)) entries[sent.key]={...sent,pending:false};
    persistJournal(owner,entries);cursor=reply.cursor;localStorage.setItem(`shelest-personal-collections-cursor:${owner}`,String(cursor));more=reply.hasMore;
  }
}
