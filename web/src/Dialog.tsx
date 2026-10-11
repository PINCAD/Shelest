import {useEffect,useId,useRef,type ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {X} from 'lucide-react';

export function Dialog({title,description,icon,children,onClose,locked=false,className='',contentKey=''}:{title:string;description?:string;icon?:ReactNode;children:ReactNode;onClose:()=>void;locked?:boolean;className?:string;contentKey?:string}) {
  const id=useId(),surface=useRef<HTMLDivElement>(null),close=useRef(onClose),blocked=useRef(locked);
  close.current=onClose;blocked.current=locked;
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null,root=document.getElementById('root');
    const oldOverflow=document.body.style.overflow,oldInert=root?.inert;
    document.body.style.overflow='hidden';if(root) root.inert=true;
    const keys=(e:KeyboardEvent)=>{
      if(e.key==='Escape') {e.preventDefault();if(!blocked.current) close.current();}
      if(e.key!=='Tab') return;
      const controls=Array.from(surface.current?.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),textarea:not([disabled]),select:not([disabled]),a[href],[tabindex="0"]')??[]).filter(el=>el.getClientRects().length);
      if(!controls.length) {e.preventDefault();surface.current?.focus();return;}
      const first=controls[0],last=controls.at(-1)!;
      if(e.shiftKey&&(document.activeElement===first||!surface.current?.contains(document.activeElement))) {e.preventDefault();last.focus();}
      else if(!e.shiftKey&&(document.activeElement===last||!surface.current?.contains(document.activeElement))) {e.preventDefault();first.focus();}
    };
    document.addEventListener('keydown',keys);
    return()=>{document.removeEventListener('keydown',keys);document.body.style.overflow=oldOverflow;if(root) root.inert=oldInert??false;if(previous?.isConnected)previous.focus();};
  },[]);
  useEffect(()=>{
    const frame=requestAnimationFrame(()=>{
      const element=surface.current;if(!element)return;
      const target=element.querySelector<HTMLElement>('[data-autofocus],input:not([disabled]),textarea:not([disabled])')??element;
      target.focus({preventScroll:true});
      const body=element.querySelector('.dialog-body');if(body)body.scrollTop=0;
    });
    return()=>cancelAnimationFrame(frame);
  },[title,contentKey]);
  return createPortal(<div className="dialog-backdrop" onMouseDown={e=>{if(e.target===e.currentTarget&&!blocked.current)onClose();}}>
    <div ref={surface} className={`dialog-surface ${className}`} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={description?`${id}-description`:undefined} tabIndex={-1}>
      <header className="dialog-heading">{icon&&<span className="dialog-emblem">{icon}</span>}<div><h2 id={`${id}-title`}>{title}</h2>{description&&<p id={`${id}-description`}>{description}</p>}</div><button type="button" className="dialog-close" aria-label="Закрыть окно" disabled={locked} onClick={onClose}><X size={19}/></button></header>
      <div className="dialog-body">{children}</div>
    </div>
  </div>,document.body);
}
