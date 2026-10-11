import {useEffect,useState} from 'react';
import {ShieldCheck,KeyRound,UserRound,ChevronRight,Copy,Download,Check,LoaderCircle,ShieldOff} from 'lucide-react';
import {accountSecurity,type Account} from './cloud';
import {Dialog} from './Dialog';

export function AccountSecurity({account,onChanged}:{account:Account;onChanged:(account:Account)=>void}) {
  const [modal,setModal]=useState<'credentials'|'mfa'|'disable'|null>(null);
  const [login,setLogin]=useState(account.login||''),[password,setPassword]=useState(''),[confirmation,setConfirmation]=useState('');
  const [currentPassword,setCurrentPassword]=useState(''),[code,setCode]=useState('');
  const [pending,setPending]=useState<{secret:string;uri:string}|null>(null),[recovery,setRecovery]=useState<string[]>([]),[qr,setQr]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[copied,setCopied]=useState('');
  useEffect(()=>{let live=true;setQr('');if(pending) void import('qrcode').then(q=>q.toDataURL(pending.uri,{width:216,margin:2,errorCorrectionLevel:'M'})).then(image=>{if(live)setQr(image);}).catch(()=>{});return()=>{live=false;};},[pending]);
  const open=(next:typeof modal)=>{setModal(next);setLogin(account.login||'');setPassword('');setConfirmation('');setCurrentPassword('');setCode('');setPending(null);setRecovery([]);setError('');setMessage('');setCopied('');};
  const close=()=>{setModal(null);setPassword('');setConfirmation('');setCurrentPassword('');setCode('');setPending(null);setQr('');setRecovery([]);setError('');setCopied('');};
  const copy=async(text:string,label:string)=>{try{await navigator.clipboard.writeText(text);setCopied(label);}catch{setCopied('Выделите текст и скопируйте его вручную');}};
  const download=()=>{const url=URL.createObjectURL(new Blob([`Шелест: одноразовые резервные коды\n\n${recovery.join('\n')}\n\nХраните отдельно от устройства с аутентификатором.\n`],{type:'text/plain;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='shelest-recovery-codes.txt';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);setCopied('Файл с кодами подготовлен');};
  const run=async(action:string)=>{
    if(action==='credentials'&&password!==confirmation) {setError('Пароли не совпадают');return;}
    setBusy(true);setError('');setMessage('');setCopied('');
    try {
      const result=await accountSecurity({action,login:login.trim().toLowerCase(),password,currentPassword,code});
      if(result.secret) {setPending(result);setCode('');}
      if(result.account) {onChanged(result.account);setPassword('');setConfirmation('');setCurrentPassword('');setCode('');}
      if(result.recoveryCodes) {setRecovery(result.recoveryCodes);setPending(null);setQr('');}
      if(action==='credentials'||action==='disable') close();
      setMessage(action==='credentials'?'Данные аккаунта сохранены':action==='disable'?'Двухфакторная защита отключена':action==='confirm'?'Двухфакторная защита включена':'');
    } catch(e) {setError(e instanceof Error?e.message:'Не удалось сохранить настройки');}
    finally {setBusy(false);}
  };
  const proof=<>{account.hasPassword?<label>Текущий пароль<input required disabled={busy} type="password" autoComplete="current-password" maxLength={128} value={currentPassword} onChange={e=>setCurrentPassword(e.target.value)}/></label>:<p className="security-hint">Для изменения настроек войдите через Google заново. Подтверждение действует 5 минут.</p>}{account.twoFactorEnabled&&<label>Код аутентификатора или резервный код<input required disabled={busy} autoComplete="one-time-code" maxLength={32} value={code} onChange={e=>setCode(e.target.value)}/></label>}</>;
  const submit=(text:string)=><button className="auth-submit" type="submit" disabled={busy}>{busy?<><LoaderCircle size={17} className="spin"/>Сохраняем…</>:text}</button>;
  return <section className="account-security" aria-label="Настройки аккаунта">
    <header><span className="security-icon"><ShieldCheck size={22}/></span><div><h2>Вход и безопасность</h2><p>Управляйте своим аккаунтом и его защитой</p></div></header>
    <div className="security-rows">
      <button className="security-row" onClick={()=>open('credentials')}><span className="security-row-icon"><UserRound size={20}/></span><span className="security-row-copy"><strong>Логин и пароль</strong><small>{account.login?`Логин: ${account.login}`:'Вход через Google'} · {account.hasPassword?'Пароль установлен':'Без пароля'}</small></span><span className="security-row-action">Изменить<ChevronRight size={17}/></span></button>
      <button className="security-row" onClick={()=>open(account.twoFactorEnabled?'disable':'mfa')}><span className="security-row-icon"><KeyRound size={20}/></span><span className="security-row-copy"><strong>Двухфакторная защита <span className={`security-badge ${account.twoFactorEnabled?'enabled':''}`}>{account.twoFactorEnabled?'Включена':'Выключена'}</span></strong><small>Код из приложения-аутентификатора при каждом входе</small></span><span className="security-row-action">{account.twoFactorEnabled?'Управлять':'Настроить'}<ChevronRight size={17}/></span></button>
    </div>
    {message&&<p className="security-success" role="status"><Check size={16}/>{message}</p>}
    {modal&&<Dialog title={recovery.length?'Сохраните резервные коды':modal==='credentials'?'Логин и пароль':modal==='disable'?'Отключить защиту?':'Двухфакторная защита'} description={modal==='credentials'?'Новый логин не меняет библиотеку и прогресс.':modal==='disable'?'После отключения код при входе больше не потребуется.':recovery.length?'Сохраните их сейчас. Повторно посмотреть коды нельзя.':'Google Authenticator, Aegis и другие приложения TOTP'} icon={modal==='credentials'?<UserRound size={22}/>:modal==='disable'?<ShieldOff size={22}/>:<ShieldCheck size={22}/>} onClose={close} locked={busy||recovery.length>0} className="security-dialog" contentKey={recovery.length?'recovery':pending?'verify':modal}>
      {modal==='credentials'?<form className="auth-form" onSubmit={e=>{e.preventDefault();void run('credentials');}}>
        <label>Логин<input aria-label="Логин" required disabled={busy} autoComplete="username" autoCapitalize="none" maxLength={32} value={login} onChange={e=>setLogin(e.target.value)}/><small>от 3 до 32 символов: латиница, цифры, точка, дефис</small></label>
        <div className="security-divider"/>
        <label>Новый пароль<input aria-label="Новый пароль" disabled={busy} type="password" autoComplete="new-password" maxLength={128} value={password} onChange={e=>setPassword(e.target.value)}/><small>{account.hasPassword?'Оставьте пустым, чтобы изменить только логин':'Не меньше 8 символов'}</small></label>
        <label>Повторите новый пароль<input disabled={busy} type="password" autoComplete="new-password" maxLength={128} value={confirmation} onChange={e=>setConfirmation(e.target.value)}/></label>
        <div className="security-divider"/>{proof}{error&&<p className="auth-error" role="alert">{error}</p>}{submit('Сохранить изменения')}
      </form>:recovery.length>0?<div className="recovery-content"><p>Каждый код используется один раз вместо кода аутентификатора. Они больше не будут показаны.</p><div className="recovery-grid">{recovery.map(value=><code key={value}>{value}</code>)}</div><div className="recovery-actions"><button className="secondary-button" onClick={()=>void copy(recovery.join('\n'),'Коды скопированы')}><Copy size={17}/>Скопировать</button><button className="secondary-button" onClick={download}><Download size={17}/>Скачать .txt</button></div>{copied&&<p className="security-hint" role="status">{copied}</p>}<p className="security-callout">Храните коды отдельно от телефона с аутентификатором.</p><button className="auth-submit" onClick={close}>Я сохранил коды</button></div>:pending?<form className="auth-form" onSubmit={e=>{e.preventDefault();void run('confirm');}}>
        <ol className="mfa-steps" aria-label="Шаги настройки"><li className="done"><Check size={14}/>Подтверждение</li><li className="current">2 · Аутентификатор</li><li>3 · Резервные коды</li></ol>
        <p className="security-hint">Отсканируйте QR-код в приложении-аутентификаторе или введите ключ вручную, выбрав тип «По времени».</p>
        <div className="totp-qr">{qr?<img src={qr} width={216} height={216} alt="QR-код для подключения аутентификатора"/>:<span>Ключ доступен ниже</span>}</div>
        <div className="totp-key"><span>Ключ настройки</span><code className="totp-secret">{pending.secret}</code><button type="button" className="secondary-button" onClick={()=>void copy(pending.secret,'Ключ скопирован')}><Copy size={16}/>Скопировать ключ</button></div>
        {copied&&<p className="security-hint" role="status">{copied}</p>}
        <label>Код из аутентификатора<input aria-label="Код из аутентификатора" required disabled={busy} className="otp-input" inputMode="numeric" autoComplete="one-time-code" maxLength={6} pattern="[0-9]{6}" placeholder="000000" value={code} onChange={e=>setCode(e.target.value.replace(/\D/g,''))}/><small>Шесть цифр из приложения</small></label>{error&&<p className="auth-error" role="alert">{error}</p>}{submit('Подтвердить и включить')}
      </form>:<form className="auth-form" onSubmit={e=>{e.preventDefault();void run(modal==='disable'?'disable':'start');}}>
        {modal==='mfa'&&<><ol className="mfa-steps" aria-label="Шаги настройки"><li className="current">1 · Подтверждение</li><li>2 · Аутентификатор</li><li>3 · Резервные коды</li></ol><p className="security-callout">После включения дополнительный код потребуется и при входе через Google. Другие устройства попросят войти заново.</p></>}
        {proof}{error&&<p className="auth-error" role="alert">{error}</p>}{submit(modal==='disable'?'Отключить двухфакторную защиту':'Продолжить')}
      </form>}
    </Dialog>}
  </section>;
}
