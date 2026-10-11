import { useEffect, useId, useRef, useState } from "react";
import type { Account } from "./cloud";

type State = {account: Account | null; busy: boolean; manage: boolean; sync: boolean; logout: boolean; status?: string; error?: string};
type Mount = {update(state: string): void; dispose(): void};

/** React owns lifecycle/navigation/storage; Kotlin exclusively owns the mounted subtree. */
function ComposeIsland({kind, state, onAction, fallback}: {kind:"account"|"sync"|WorkspaceKind; state:unknown;
  onAction:(action:string,value?:boolean|string)=>void; fallback:React.ReactNode}) {
  const id = "shelest-common-" + useId().replace(/:/g, "");
  const stateRef = useRef(state), actionRef = useRef(onAction);
  stateRef.current = state; actionRef.current = onAction;
  const mount = useRef<Mount | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    if (kind !== 'library' || !ready) return;
    const root=document.getElementById(id);
    if (!root) return;
    let timer=0,origin={x:0,y:0},pressed:HTMLElement|null=null,suppressed:HTMLElement|null=null;
    const cover=(target:EventTarget|null)=>target instanceof Element
      ? target.closest('.common-book-card')?.querySelector<HTMLElement>('.cover-button')??null : null;
    const clear=()=>{window.clearTimeout(timer);timer=0;pressed=null;};
    const select=(element:HTMLElement)=>element.dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true}));
    const down=(event:PointerEvent)=>{
      clear();suppressed=null;
      if (!event.isPrimary || event.button!==0 || !(event.target instanceof Element) || !event.target.closest('.cover-button,.book-title')) return;
      pressed=cover(event.target);origin={x:event.clientX,y:event.clientY};
      timer=window.setTimeout(()=>{if(pressed){suppressed=pressed;select(pressed);}},500);
    };
    const move=(event:PointerEvent)=>{if(Math.hypot(event.clientX-origin.x,event.clientY-origin.y)>10)clear();};
    const click=(event:MouseEvent)=>{if(suppressed&&cover(event.target)===suppressed){event.preventDefault();event.stopImmediatePropagation();suppressed=null;}};
    const context=(event:MouseEvent)=>{
      if(!event.isTrusted) return;
      if(suppressed&&cover(event.target)===suppressed){event.preventDefault();event.stopImmediatePropagation();}
      else if(pressed&&cover(event.target)===pressed) clear(); // Native touch menu won the timer race.
    };
    const keys=(event:KeyboardEvent)=>{if((event.key==='F10'&&event.shiftKey)||event.key==='ContextMenu'){const element=cover(event.target);if(element){event.preventDefault();select(element);}}};
    root.addEventListener('pointerdown',down);
    root.addEventListener('click',click,true);
    root.addEventListener('contextmenu',context,true);
    root.addEventListener('keydown',keys);
    window.addEventListener('pointermove',move,{passive:true});
    window.addEventListener('pointerup',clear);
    window.addEventListener('pointercancel',clear);
    window.addEventListener('scroll',clear,true);
    return()=>{clear();root.removeEventListener('pointerdown',down);root.removeEventListener('click',click,true);root.removeEventListener('contextmenu',context,true);root.removeEventListener('keydown',keys);window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',clear);window.removeEventListener('pointercancel',clear);window.removeEventListener('scroll',clear,true);};
  },[id,kind,ready]);
  useEffect(() => {
    let live = true;
    void import("@shelest/shared-ui").then(({AccountPanelMount,SyncSettingsMount,WorkspaceMount}) => {
      if (!live) return;
      const instance = kind === "account"
        ? new AccountPanelMount(id, JSON.stringify(stateRef.current), action => actionRef.current(action))
        : kind === "sync" ? new SyncSettingsMount(id, JSON.stringify(stateRef.current), (key,value) => actionRef.current(key,value))
        : new WorkspaceMount(id, kind, JSON.stringify(stateRef.current), (key,value) => actionRef.current(key,value));
      mount.current = instance; setReady(true);
    }).catch(() => { /* Keep the original accessible controls when the UI chunk is unavailable. */ });
    return () => { live = false; mount.current?.dispose(); mount.current = null; };
  }, [id,kind]);
  useEffect(() => { mount.current?.update(JSON.stringify(state)); }, [state]);
  return <><div id={id} className={`compose-island compose-${kind}`} hidden={!ready} />{!ready && fallback}</>;
}
type WorkspaceKind = "appearance"|"reading"|"library"|"reader-header"|"reader-pages";
export function CommonWorkspacePanel({kind,state,onAction,fallback}:{kind:WorkspaceKind;state:unknown;
  onAction:(action:string,value:string)=>void;fallback:React.ReactNode}) {
  return <ComposeIsland kind={kind} state={state} onAction={(key,value)=>onAction(key,typeof value === "string" ? value : "")} fallback={fallback}/>;
}
export function CommonAccountPanel({state,onAction,fallback}:{state:State;onAction:(action:string)=>void;fallback:React.ReactNode}) {
  return <ComposeIsland kind="account" state={state} onAction={onAction} fallback={fallback}/>;
}
export function CommonSyncSettings({settings,onChange,fallback}:{settings:{autoSync:boolean;syncDeletions:boolean};
  onChange:(key:"autoSync"|"syncDeletions",value:boolean)=>void;fallback:React.ReactNode}) {
  return <ComposeIsland kind="sync" state={{autoSync:settings.autoSync,syncDeletions:settings.syncDeletions}}
    onAction={(key,value)=>{if ((key === "autoSync" || key === "syncDeletions") && typeof value === "boolean") onChange(key,value);}}
    fallback={fallback}/>;
}
