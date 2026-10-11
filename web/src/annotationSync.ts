import type {Book} from './types';
import {capturePersonal,journal,seedPersonal} from './personalJournal';
export type Mark={type:'NOTE'|'HIGHLIGHT'|'DRAWING';color:number;createdAt:number;selectedText:string|null;contextBefore:string|null;contextAfter:string|null;noteText:string|null;pageIndex:number|null;normX:number|null;normY:number|null;points:{x:number;y:number}[];width:number};
type TextNote={id?:string;quote:string;note:string;segment?:number;color?:number;kind?:string;createdAt?:number;contextBefore?:string;contextAfter?:string;pageIndex?:number};
type Stroke={id:string;page:number;color:string;width:number;points:{x:number;y:number}[];createdAt?:number};
type Note={id:string;page:number;x:number;y:number;text:string;color?:number;createdAt?:number};
export type PdfMarks={strokes:Stroke[];notes:Note[]};
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const empty=():Mark=>({type:'NOTE',color:0xffc1884b,createdAt:Date.now(),selectedText:null,contextBefore:null,contextAfter:null,noteText:null,pageIndex:null,normX:null,normY:null,points:[],width:2.5});
function textMark(n:TextNote):Mark {return {...empty(),type:n.kind==='highlight'?'HIGHLIGHT':'NOTE',color:(n.color??0xffc1884b)>>>0,createdAt:n.createdAt??1,selectedText:n.quote,contextBefore:n.contextBefore??null,contextAfter:n.contextAfter??null,noteText:n.note,pageIndex:n.pageIndex??null};}
function pdfRecords(m:PdfMarks):[string,Mark][] {return [
  ...m.strokes.map((s):[string,Mark]=>[s.id,{...empty(),type:'DRAWING',color:(parseInt(s.color.slice(1),16)|0xff000000)>>>0,createdAt:s.createdAt??1,pageIndex:s.page-1,points:s.points,width:s.width}]),
  ...m.notes.map((n):[string,Mark]=>[n.id,{...empty(),color:(n.color??0xffc1884b)>>>0,createdAt:n.createdAt??1,pageIndex:n.page-1,normX:n.x,normY:n.y,noteText:n.text}])];}
function read<T>(key:string,fallback:T):T {const saved=localStorage.getItem(key);return saved?JSON.parse(saved):fallback;}
export function textNotes(bookId:string):TextNote[] {return read(`shelest:notes:${bookId}`,[]);}
function shaFor(book:Book) {return book.sha256||localStorage.getItem(`shelest-mark-book:${book.id}`);}
export function saveTextNotes(book:Book,notes:TextNote[]) {
  const before=textNotes(book.id);
  notes.forEach(n=>{n.id??=crypto.randomUUID();n.createdAt??=Date.now();});
  localStorage.setItem(`shelest:notes:${book.id}`,JSON.stringify(notes));
  const sha=shaFor(book);
  if(sha) {
    const old=new Map(before.filter(n=>n.id).map(n=>[n.id!,textMark(n)]));
    for(const n of notes) if(JSON.stringify(old.get(n.id!))!==JSON.stringify(textMark(n))) capturePersonal(`mark:${sha}:${n.id}`,textMark(n));
    for(const id of old.keys()) if(!notes.some(n=>n.id===id)) capturePersonal(`mark:${sha}:${id}`,null);
  }
  window.dispatchEvent(new Event('shelest-notes-changed'));
}
export function savePdfMarks(book:Book,before:PdfMarks,next:PdfMarks) {
  for(const n of [...next.strokes,...next.notes]) n.createdAt??=Date.now();
  localStorage.setItem(`shelest:pdf-marks:${book.id}`,JSON.stringify(next));
  const sha=shaFor(book);
  if(sha) {
    const old=new Map(pdfRecords(before)),updated=new Map(pdfRecords(next));
    for(const [id,value] of updated) if(JSON.stringify(old.get(id))!==JSON.stringify(value)) capturePersonal(`mark:${sha}:${id}`,value);
    for(const id of old.keys()) if(!updated.has(id)) capturePersonal(`mark:${sha}:${id}`,null);
  }
}
export function applyMark(book:Book,key:string,value:Mark|null,valid:()=>boolean) {
  if(!valid()) return;
  const id=key.split(':')[2],notes=textNotes(book.id).filter(n=>n.id!==id);
  const pdf=read<PdfMarks>(`shelest:pdf-marks:${book.id}`,{strokes:[],notes:[]});
  pdf.strokes=pdf.strokes.filter(n=>n.id!==id);pdf.notes=pdf.notes.filter(n=>n.id!==id);
  if(value) {
    if(value.type==='DRAWING') pdf.strokes.push({id,page:value.pageIndex!+1,color:`#${value.color.toString(16).padStart(8,'0').slice(2)}`,width:value.width,points:value.points,createdAt:value.createdAt});
    else if(value.normX!==null&&value.normY!==null&&value.pageIndex!==null) pdf.notes.push({id,page:value.pageIndex+1,x:value.normX,y:value.normY,text:value.noteText??'',color:value.color,createdAt:value.createdAt});
    else notes.push({id,quote:value.selectedText??'',note:value.noteText??'',kind:value.type==='HIGHLIGHT'?'highlight':'note',color:value.color,createdAt:value.createdAt,
      contextBefore:value.contextBefore??undefined,contextAfter:value.contextAfter??undefined,pageIndex:value.pageIndex??undefined});
  }
  localStorage.setItem(`shelest:notes:${book.id}`,JSON.stringify(notes));
  localStorage.setItem(`shelest:pdf-marks:${book.id}`,JSON.stringify(pdf));
  window.dispatchEvent(new Event('shelest-notes-changed'));window.dispatchEvent(new Event('shelest-pdf-marks-changed'));
}
export function seedMarks(owner:string,books:Book[],current:()=>boolean) {
  // Keep cloud marks even when their book has not been imported/downloaded yet.
  for(const e of Object.values(journal(owner))) if(e.key.startsWith('mark:')&&!e.pending) for(const b of books) if(b.sha256===e.key.split(':')[1]) applyMark(b,e.key,e.value as Mark|null,current);
  for(const book of books) {
    if(!current()) return;
    if(!book.sha256) continue;
    localStorage.setItem(`shelest-mark-book:${book.id}`,book.sha256);
    const notes=textNotes(book.id);for(const n of notes) if(!n.id||!uuid.test(n.id)) n.id=crypto.randomUUID();
    localStorage.setItem(`shelest:notes:${book.id}`,JSON.stringify(notes));
    for(const n of notes) seedPersonal(owner,`mark:${book.sha256}:${n.id}`,textMark(n));
    const pdf=read<PdfMarks>(`shelest:pdf-marks:${book.id}`,{strokes:[],notes:[]});
    for(const n of [...pdf.strokes,...pdf.notes]) if(!uuid.test(n.id)) n.id=crypto.randomUUID();
    localStorage.setItem(`shelest:pdf-marks:${book.id}`,JSON.stringify(pdf));
    for(const [id,value] of pdfRecords(pdf)) seedPersonal(owner,`mark:${book.sha256}:${id}`,value);
  }
}
