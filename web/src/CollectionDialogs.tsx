import {useEffect,useState} from 'react';
import {ArrowUp,ArrowDown,Layers,Plus,X} from 'lucide-react';
import {Dialog} from './Dialog';
import {ConfirmDialog} from './ConfirmDialog';
import {addToCollection,editCollection,readCollections,type BookCollection} from './collections';
import type {Book} from './types';

export function CollectionPicker({books,collections,onClose,onSaved}:{books:Book[];collections:BookCollection[];onClose:()=>void;onSaved:()=>void}) {
  const [name,setName]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const add=async(id:string|null)=>{setBusy(true);setError('');try{await addToCollection(id,name,books);onSaved();onClose();}catch(e){setError(e instanceof Error?e.message:'Не удалось сохранить');}finally{setBusy(false);}};
  return <Dialog title="В коллекцию" description={'Выбрано книг: '+books.length} icon={<Layers size={22}/>} onClose={()=>{if(!busy)onClose();}}>
    {collections.length>0&&<div className="collection-picker-list">{collections.map(c=><button className="collection-picker-item" key={c.id} disabled={busy} onClick={()=>void add(c.id)}><Layers size={18}/><span>{c.name}</span><small>{c.members.length}</small></button>)}</div>}
    <form className="auth-form" onSubmit={e=>{e.preventDefault();void add(null);}}>
      <label>Новая коллекция<input value={name} maxLength={120} onChange={e=>setName(e.target.value)} placeholder="Например, Песнь льда и пламени"/></label>
      {error&&<p role="alert" className="auth-error">{error}</p>}
      <div className="dialog-footer"><button type="button" className="secondary-button" disabled={busy} onClick={onClose}>Отмена</button><button className="primary-button" disabled={busy||!name.trim()}><Plus size={17}/>Создать</button></div>
    </form>
  </Dialog>;
}
export function CollectionEditor({collection,books,onClose,onSaved}:{collection:BookCollection;books:Book[];onClose:()=>void;onSaved:()=>void}) {
  const [name,setName]=useState(collection.name),[members,setMembers]=useState(collection.members),[error,setError]=useState(''),[deleting,setDeleting]=useState(false);
  const move=(index:number,delta:number)=>setMembers(old=>{const next=[...old];const [sha]=next.splice(index,1);next.splice(index+delta,0,sha);return next;});
  const [originalMembers]=useState(collection.members);
  const save=(remove=false)=>{try{editCollection(collection.id,name,members,remove,originalMembers);onSaved();onClose();}catch(e){setDeleting(false);setError(e instanceof Error?e.message:'Не удалось сохранить');}};
  if(deleting) return <ConfirmDialog title="Удалить коллекцию?" description="Удалится только группа на устройствах аккаунта. Книги и место чтения сохранятся." onClose={()=>setDeleting(false)} onConfirm={()=>save(true)}/>;
  return <Dialog title="Коллекция" icon={<Layers size={22}/>} onClose={onClose}>
    <form className="auth-form" onSubmit={e=>{e.preventDefault();save();}}>
      <label>Название<input value={name} maxLength={120} onChange={e=>setName(e.target.value)}/></label>
      <h3 className="collection-order-heading">Порядок томов</h3>
      <ol className="collection-member-list">{members.map((sha,index)=>{const title=books.find(b=>b.sha256===sha)?.title??'Книга на другом устройстве';return <li key={sha}><span><small>{index+1}</small>{title}</span><div><button type="button" aria-label={'Выше: '+title} disabled={index===0} onClick={()=>move(index,-1)}><ArrowUp size={17}/></button><button type="button" aria-label={'Ниже: '+title} disabled={index===members.length-1} onClick={()=>move(index,1)}><ArrowDown size={17}/></button><button type="button" aria-label={'Убрать из коллекции: '+title} onClick={()=>setMembers(old=>old.filter(s=>s!==sha))}><X size={17}/></button></div></li>;})}</ol>
      {error&&<p role="alert" className="auth-error">{error}</p>}
      <div className="dialog-footer"><button type="button" className="text-danger" onClick={()=>setDeleting(true)}>Удалить коллекцию</button><button className="primary-button" disabled={!name.trim()}>Сохранить</button></div>
    </form>
  </Dialog>;
}
export function useCollections(owner:string) {
  const [collections,setCollections]=useState<BookCollection[]>(()=>readCollections(owner));
  const refresh=()=>setCollections(readCollections(owner));
  useEffect(()=>{refresh();const update=()=>refresh();window.addEventListener('shelest-personal-changed',update);window.addEventListener('shelest-sync-finished',update);window.addEventListener('storage',update);return()=>{window.removeEventListener('shelest-personal-changed',update);window.removeEventListener('shelest-sync-finished',update);window.removeEventListener('storage',update);};},[owner]);
  return {collections,refresh};
}
