import { useEffect,useRef,useState,type ReactNode,type PointerEvent } from "react";
import type {Book} from './types';
import {savePdfMarks} from './annotationSync';
import {Dialog} from './Dialog';
import {ConfirmDialog} from './ConfirmDialog';
import { Pencil,Eraser,MousePointer2,StickyNote,Undo2,Trash2,ChevronDown,ChevronUp } from "lucide-react";
type Point={x:number;y:number};
type Stroke={id:string;page:number;color:string;width:number;points:Point[]};
type Note={id:string;page:number;x:number;y:number;text:string};
type Marks={strokes:Stroke[];notes:Note[]};
type Mode="read"|"pen"|"erase"|"delete"|"note";
const colors=["#a8493d","#d89b24","#45804f","#3976ba","#8157a6","#242424"];
function load(key:string):Marks {
  try {const value=JSON.parse(localStorage.getItem(key)||"{}");
    const validPoint=(p:Point)=>p&&Number.isFinite(p.x)&&Number.isFinite(p.y)&&p.x>=0&&p.x<=1&&p.y>=0&&p.y<=1;
    return {strokes:Array.isArray(value.strokes)?value.strokes.filter((s:Stroke)=>s&&typeof s.id==="string"&&Number.isInteger(s.page)&&s.page>0&&typeof s.color==="string"&&Number.isFinite(s.width)&&s.width>0&&s.width<=12&&Array.isArray(s.points)&&s.points.length>0&&s.points.length<10000&&s.points.every(validPoint)):[],
      notes:Array.isArray(value.notes)?value.notes.filter((n:Note)=>n&&typeof n.id==="string"&&Number.isInteger(n.page)&&n.page>0&&validPoint(n)&&typeof n.text==="string"&&n.text.length<=10000):[]};
  } catch {return {strokes:[],notes:[]};}
}
export function usePdfTools() {
  const [mode,setMode]=useState<Mode>('read'),[expanded,setExpanded]=useState(true),[color,setColor]=useState(colors[0]),[size,setSize]=useState(3),[activePage,setActivePage]=useState(1);
  return {mode,setMode,expanded,setExpanded,color,setColor,size,setSize,activePage,setActivePage};
}
export function PdfMarkup({bookId,book,page,width,height,children,onNotice,tools,showToolbar=true}:{bookId:string;book?:Book;page:number;width:number;height:number;children:ReactNode;onNotice:(message:string)=>void;tools?:ReturnType<typeof usePdfTools>;showToolbar?:boolean}) {
  const key=`shelest:pdf-marks:${bookId}`;
  const [marks,setMarks]=useState(()=>load(key));
  const localTools=usePdfTools();
  const {mode,setMode,expanded,setExpanded,color,setColor,size,setSize,activePage,setActivePage}=tools??localTools;
  const [draft,setDraft]=useState<Stroke|null>(null),[note,setNote]=useState<Note|null>(null);
  const [pendingClear,setPendingClear]=useState<number|null>(null);
  const [erasePreview,setErasePreview]=useState<Marks|null>(null);
  const live=useRef<Stroke|null>(null),erased=useRef<Marks|null>(null),pointer=useRef<number|null>(null);
  useEffect(()=>{const refresh=()=>setMarks(load(key));window.addEventListener('shelest-pdf-marks-changed',refresh);return()=>window.removeEventListener('shelest-pdf-marks-changed',refresh);},[key]);
  const save=(next:Marks,targetPage=page)=>{try {
    const latest=load(key);
    const merged={strokes:[...latest.strokes.filter(s=>s.page!==targetPage),...next.strokes.filter(s=>s.page===targetPage)],notes:[...latest.notes.filter(n=>n.page!==targetPage),...next.notes.filter(n=>n.page===targetPage)]};
    if(book) savePdfMarks(book,latest,merged);else localStorage.setItem(key,JSON.stringify(merged));setMarks(merged);window.dispatchEvent(new Event('shelest-pdf-marks-changed'));return true;}
    catch {onNotice("Недостаточно места для сохранения пометок в браузере");return false;}};
  const point=(event:PointerEvent<SVGSVGElement>)=>{
    const rect=event.currentTarget.getBoundingClientRect();
    return {x:Math.min(1,Math.max(0,(event.clientX-rect.left)/rect.width)),y:Math.min(1,Math.max(0,(event.clientY-rect.top)/rect.height))};
  };
  const erase=(current:Marks,p:Point):Marks=>{
    const radius=Math.max(10,size*3),distance=(q:Point)=>Math.hypot((q.x-p.x)*width,(q.y-p.y)*height);
    return {...current,strokes:current.strokes.flatMap(stroke=>{
      if(stroke.page!==page) return [stroke];
      let touched=false,part:Point[]=[],parts:Point[][]=[];
      const samples:Point[]=[];
      stroke.points.forEach((q,index)=>{
        const prev=stroke.points[index-1];
        if(prev) {const n=Math.min(200,Math.ceil(Math.hypot((q.x-prev.x)*width,(q.y-prev.y)*height)/(radius/2)));
          for(let i=1;i<n;i++) samples.push({x:prev.x+(q.x-prev.x)*i/n,y:prev.y+(q.y-prev.y)*i/n});}
        samples.push(q);
      });
      for(const q of samples) {if(distance(q)<radius) {touched=true;if(part.length) parts.push(part);part=[];} else part.push(q);}
      if(part.length) parts.push(part);
      if(!touched) return [stroke];
      if(mode==="delete") return [];
      return parts.filter(piece=>piece.length>1).map(points=>({...stroke,id:crypto.randomUUID(),points}));
    })};
  };
  const end=(event:PointerEvent<SVGSVGElement>,cancel=false)=>{
    if(pointer.current!==event.pointerId) return;
    pointer.current=null;
    if(!cancel&&live.current) save({...marks,strokes:[...marks.strokes,live.current]});
    if(!cancel&&erased.current) save(erased.current);
    live.current=null;erased.current=null;setDraft(null);setErasePreview(null);
  };
  const strokes=marks.strokes.filter(s=>s.page===page);
  const toolPage=tools?activePage:page,toolStrokes=marks.strokes.filter(s=>s.page===toolPage);
  return <div className={`pdf-workspace ${expanded&&(mode==="pen"||mode==="erase")?"pdf-options-open":""}`} onPointerDown={()=>setActivePage(page)}>
    {showToolbar&&<div className="pdf-markup-toolbar" role="toolbar" aria-label="Инструменты PDF" onPointerDown={e=>e.stopPropagation()}>
      <button aria-label={expanded?"Свернуть инструменты PDF":"Развернуть инструменты PDF"} onClick={()=>setExpanded(!expanded)}>{expanded?<ChevronDown size={18}/>:<ChevronUp size={18}/>}</button>
      {!expanded&&<button className={mode!=="read"?"active":""} onClick={()=>setMode("read")} aria-label="Завершить рисование"><MousePointer2 size={18}/></button>}
      {expanded&&<>
        {([["read","Выделение текста",MousePointer2],["pen","Перо",Pencil],["erase","Ластик",Eraser],["delete","Удаление штриха нажатием",Trash2],["note","Заметка на странице",StickyNote]] as const).map(([value,label,Icon])=>
          <button key={value} aria-label={label} aria-pressed={mode===value} className={mode===value?"active":""} onClick={()=>{setMode(value);setDraft(null);}}><Icon size={18}/></button>)}
        <span className="pdf-tool-divider"/>
        <button aria-label="Отменить последний штрих" disabled={!toolStrokes.length} onClick={()=>save({...marks,strokes:marks.strokes.filter(s=>s.id!==toolStrokes.at(-1)?.id)},toolPage)}><Undo2 size={18}/></button>
        <button aria-label="Очистить пометки страницы" disabled={!toolStrokes.length&&!marks.notes.some(n=>n.page===toolPage)} onClick={()=>{
          setPendingClear(toolPage);
        }}><Trash2 size={18}/></button>
        {(mode==="pen"||mode==="erase")&&<div className="pdf-brush-options">
          {mode==="pen"&&colors.map(value=><button key={value} className="ink-color" aria-label={`Цвет пера ${value}`} aria-pressed={color===value} onClick={()=>setColor(value)} style={{background:value}}/>)}
          <label>Толщина<input aria-label="Толщина пера или ластика" type="range" min="1" max="12" value={size} onChange={e=>setSize(Number(e.target.value))}/></label>
        </div>}
      </>}
    </div>}
    <div className="pdf-scroll"><div className="pdf-sheet" style={{width,height}}>
      {children}
      <svg className={`pdf-drawing-layer ${mode!=="read"?"drawing":""}`} viewBox={`0 0 ${width} ${height}`} aria-label="Рукописные пометки" onPointerDown={event=>{
        if(pointer.current!==null||event.button!==0) return;
        pointer.current=event.pointerId;event.currentTarget.setPointerCapture(event.pointerId);const p=point(event);
        if(mode==="pen") {live.current={id:crypto.randomUUID(),page,color,width:size,points:[p]};setDraft(live.current);}
        else if(mode==="erase"||mode==="delete") {erased.current=erase(marks,p);setErasePreview(erased.current);}
        else if(mode==="note") setNote({id:crypto.randomUUID(),page,...p,text:""});
      }} onPointerMove={event=>{
        if(pointer.current!==event.pointerId) return;const p=point(event);
        if(live.current) {const stroke=live.current;if(stroke.points.length>=2500) return;
          const previous=stroke.points.at(-1)!;if(Math.hypot((p.x-previous.x)*width,(p.y-previous.y)*height)<1) return;
          live.current={...stroke,points:[...stroke.points,p]};setDraft(live.current);}
        else if(mode==="erase"&&erased.current) {erased.current=erase(erased.current,p);setErasePreview(erased.current);}
      }} onPointerUp={event=>end(event)} onPointerCancel={event=>end(event,true)}>
        {[...(erasePreview?.strokes.filter(s=>s.page===page)??strokes),...(draft&&draft.page===page?[draft]:[])].map(stroke=>stroke.points.length===1
          ? <circle key={stroke.id} cx={stroke.points[0].x*width} cy={stroke.points[0].y*height} r={stroke.width/2} fill={stroke.color}/>
          : <polyline key={stroke.id} points={stroke.points.map(p=>`${p.x*width},${p.y*height}`).join(" ")} fill="none" stroke={stroke.color} strokeWidth={stroke.width} strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"/>)}
      </svg>
      {marks.notes.filter(n=>n.page===page).map(n=><button className="pdf-note-pin" key={n.id} aria-label={`Заметка: ${n.text}`} title={n.text} style={{left:`${n.x*100}%`,top:`${n.y*100}%`}} onClick={()=>setNote(n)}><StickyNote size={18}/></button>)}
    </div></div>
    {pendingClear!==null&&<ConfirmDialog title="Очистить пометки?" description={`Рисунки и заметки на странице ${pendingClear} будут удалены. Текст книги останется без изменений.`} onClose={()=>setPendingClear(null)} onConfirm={()=>{if(!save({strokes:marks.strokes.filter(s=>s.page!==pendingClear),notes:marks.notes.filter(n=>n.page!==pendingClear)},pendingClear))throw new Error('Не удалось сохранить изменения');}}/>}
    {note&&<Dialog title="Заметка на странице" icon={<StickyNote size={22}/>} onClose={()=>setNote(null)}><form className="auth-form" onSubmit={event=>{event.preventDefault();if(!note.text.trim()) return;
      if(save({...marks,notes:[...marks.notes.filter(n=>n.id!==note.id),{...note,text:note.text.trim()}]})) setNote(null);
    }}><label>Текст заметки PDF<textarea aria-label="Текст заметки PDF" value={note.text} maxLength={10000} onChange={event=>setNote({...note,text:event.target.value})}/></label>
      <div className="common-dialog-actions"><button type="button" onClick={()=>setNote(null)}>Отмена</button>
        {marks.notes.some(n=>n.id===note.id)&&<button type="button" onClick={()=>{if(save({...marks,notes:marks.notes.filter(n=>n.id!==note.id)})) setNote(null);}}>Удалить</button>}
        <button type="submit" disabled={!note.text.trim()}>Сохранить</button></div></form></Dialog>}
  </div>;
}
