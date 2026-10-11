import {useState} from 'react';
import {Trash2,LoaderCircle} from 'lucide-react';
import {Dialog} from './Dialog';

export function ConfirmDialog({title,description,onClose,onConfirm}:{title:string;description:string;onClose:()=>void;onConfirm:()=>void|Promise<void>}) {
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  return <Dialog title={title} description={description} icon={<Trash2 size={22}/>} locked={busy} onClose={onClose}>
    {error&&<p className="auth-error" role="alert">{error}</p>}
    <div className="dialog-actions"><button className="secondary-button" disabled={busy} onClick={onClose}>Отмена</button><button className="danger-button" disabled={busy} onClick={async()=>{setBusy(true);setError('');try{await onConfirm();onClose();}catch(e){setError(e instanceof Error?e.message:'Не удалось удалить');}finally{setBusy(false);}}}>{busy?<><LoaderCircle size={17} className="spin"/>Удаляем…</>:'Удалить'}</button></div>
  </Dialog>;
}
