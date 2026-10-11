import { useEffect, useRef, useState } from "react";
import { ArrowRight, Check, Cloud, Eye, EyeOff, LoaderCircle, LogOut, RefreshCw, Sparkles, UserRound } from "lucide-react";
import { authConfig, currentAccount, googleLogin, logout, passwordLogin, settingsAutoSync, syncBooks, lastSuccessfulSync, type Account } from "./cloud";
import { sharedCore } from "./sharedCore";
import { CommonAccountPanel } from "./CommonAccountPanel";
import {AccountSecurity} from './AccountSecurity';
import {MfaRequired,finishMfa} from './cloud';
import {Dialog} from './Dialog';
import {ShieldCheck} from 'lucide-react';

function LegacyAccountSummary({account, onOpen, onSync, onLogout, busy = false,status=""}: {
  account: Account | null; onOpen?: () => void; onSync?: () => void; onLogout?: () => void; busy?: boolean;status?:string;
}) {
  const name = sharedCore.accountDisplayName(JSON.stringify(account));
  const fraction = account ? sharedCore.storageFraction(account.storageUsedBytes, account.quotaBytes) : 0;
  return <section className="cloud-summary">
    <div className="cloud-profile"><span className="cloud-avatar" aria-hidden="true">{account ? sharedCore.initials(name) : <UserRound size={27} />}</span>
      <div className="cloud-identity"><h2>{name}</h2><p>{account?.email || account?.login || "Продолжайте чтение на любом устройстве"}</p></div>
      {account && <span className={`cloud-plan ${account.isPremium ? "premium" : ""}`}>{account.isPremium && <Sparkles size={13} />}{account.isPremium ? "Premium" : "Бесплатный"}</span>}
    </div>
    {account?.isPremium ? <div className="cloud-storage"><div><span>Облачное хранилище</span><strong>{sharedCore.formatBytes(account.storageUsedBytes)} из {sharedCore.formatBytes(account.quotaBytes)}</strong></div>
      <div className="cloud-meter" role="progressbar" aria-label="Занято в облаке" aria-valuenow={Math.round(fraction * 100)} aria-valuemin={0} aria-valuemax={100}><span style={{width:`${fraction * 100}%`}} /></div>
    </div> : account && <p className="cloud-description">Место чтения синхронизируется бесплатно. Хранение файлов книг доступно с Premium.</p>}
    {onOpen && <button className="cloud-manage" onClick={onOpen}>{account ? "Управление аккаунтом" : "Войти в аккаунт"}<ArrowRight size={16} /></button>}
    {(onSync || onLogout) && <div className="cloud-actions">{onSync && <button className="cloud-primary" disabled={busy} onClick={onSync}>{busy ? <LoaderCircle size={17} className="spin" /> : <RefreshCw size={17} />}Обновить</button>}
      {onLogout && <button className="cloud-secondary" disabled={busy} onClick={onLogout}><LogOut size={17} />Выйти</button>}</div>}
    {account&&status&&<p className="cloud-status" role="status">{status}</p>}
  </section>;
}

export function AccountSummary(props: Parameters<typeof LegacyAccountSummary>[0]) {
  const [now,setNow]=useState(Date.now());
  useEffect(()=>{
    const update=()=>setNow(Date.now());
    const timer=window.setInterval(update,30000);
    window.addEventListener("shelest-sync-finished",update);
    window.addEventListener("storage",update);
    return ()=>{clearInterval(timer);window.removeEventListener("shelest-sync-finished",update);window.removeEventListener("storage",update);};
  },[]);
  const last=props.account ? lastSuccessfulSync(props.account.id) : 0;
  const age=Math.max(0,Math.floor((now-last)/60000));
  const status=!props.account ? "" : !last ? "Ещё не синхронизировано" : age<1 ? "Синхронизация: только что" : age<60 ? `Синхронизация: ${age} мин назад` : age<1440 ? `Синхронизация: ${Math.floor(age/60)} ч назад` : `Синхронизация: ${Math.floor(age/1440)} дн назад`;
  return <CommonAccountPanel state={{account:props.account, busy:props.busy ?? false,
    manage:Boolean(props.onOpen), sync:Boolean(props.onSync), logout:Boolean(props.onLogout),status}}
    onAction={action => { if (action === "manage") props.onOpen?.(); else if (action === "sync") props.onSync?.(); else if (action === "logout") props.onLogout?.(); }}
    fallback={<LegacyAccountSummary {...props} status={status} />} />;
}

export function AccountScreen({ onSynced, onAccountChanged }: { onSynced: () => Promise<void>; onAccountChanged: (account: Account | null) => void }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [checking, setChecking] = useState(true);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [register, setRegister] = useState(false);
  const [challenge,setChallenge]=useState(''),[otp,setOtp]=useState('');
  const [googleStatus,setGoogleStatus]=useState<'loading'|'ready'|'unavailable'>('loading');
  const googleButton = useRef<HTMLDivElement>(null);
  useEffect(() => { if (account) onAccountChanged(account); }, [account, onAccountChanged]);
  const synchronize = async (user: Account) => {
    try { await syncBooks(user); await onSynced(); setAccount(await currentAccount()); setMessage("Синхронизировано"); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "Нет связи с сервером"); }
  };
  useEffect(() => {
    let live = true;
    void (async () => {
      const user = await currentAccount(); if (!live) return;
      setAccount(user); setChecking(false);
      if (user) return;
      const config = await authConfig();
      await new Promise<void>((resolve, reject) => {
        if ((window as any).google?.accounts?.id) { resolve(); return; }
        const existing = document.querySelector<HTMLScriptElement>('script[data-google-login]');
        const script = existing ?? document.createElement("script");
        script.addEventListener("load", () => resolve(), {once:true});
        script.addEventListener("error", () => reject(new Error("Google недоступен. Войдите по логину и паролю.")), {once:true});
        if (!existing) { script.src = "https://accounts.google.com/gsi/client?hl=ru"; script.dataset.googleLogin = "true"; document.head.append(script); }
      });
      if (!live || !googleButton.current) return;
      const google = (window as any).google.accounts.id;
      google.initialize({client_id:config.clientId, nonce:config.nonce, callback:async (response:{credential:string}) => {
        setBusy(true); setError("");
        try { const signed = await googleLogin(response.credential, config.nonce); setAccount(signed); if (settingsAutoSync()) await synchronize(signed); }
        catch (failure) {if(failure instanceof MfaRequired) setChallenge(failure.challenge);setError(failure instanceof Error ? failure.message : "Не удалось войти"); }
        finally { setBusy(false); }
      }});
      google.renderButton(googleButton.current, {theme:"outline", size:"large", text:"continue_with", shape:"pill", width:Math.min(320, googleButton.current.clientWidth), locale:"ru"});
      setGoogleStatus('ready');
    })().catch(() => { if (live) { setChecking(false); setGoogleStatus('unavailable'); } });
    return () => { live = false; };
  }, []);
  return <div className="content-width subpage account-page"><div className="page-heading"><div><div className="eyebrow">ВАШЕ ПРОСТРАНСТВО</div><h1>Аккаунт</h1><p>Ваша библиотека всегда под рукой.</p></div><span className="heading-emblem"><Cloud size={22} /></span></div>
    {checking ? <div className="auth-loading" role="status"><LoaderCircle className="spin" size={22} />Проверяем аккаунт…</div> : account ?
      <AccountSummary account={account} busy={busy} onSync={() => { setBusy(true); setError(""); void synchronize(account).finally(() => setBusy(false)); }} onLogout={() => {
        setBusy(true); void logout().then(() => { onAccountChanged(null); window.location.reload(); }).catch(failure => {setError(failure.message); setBusy(false);});
      }} /> : <section className="auth-card">
        <span className="auth-emblem"><UserRound size={27} /></span><h2>{register ? "Начните свою историю" : "С возвращением"}</h2><p>Книги и место чтения на всех ваших устройствах</p>
        <div className="auth-tabs" role="group" aria-label="Вход или регистрация">{([false,true] as const).map(value => <button key={String(value)} type="button" aria-pressed={register === value} disabled={busy} onClick={() => {setRegister(value); setPassword(""); setConfirmation(""); setError("");}}>{value ? "Регистрация" : "Вход"}</button>)}</div>
        <div className={`google-signin ${googleStatus==='ready'?'ready':''} ${busy ? "busy" : ""}`} ref={googleButton} />
        {googleStatus!=='ready'&&<p className="google-status" role="status">{googleStatus==='loading'?<><LoaderCircle size={16} className="spin"/>Подключаем Google…</>:'Google сейчас недоступен. Используйте логин и пароль.'}</p>}
        <div className="auth-divider"><span>{googleStatus==='ready'?'или по логину':'Вход по логину'}</span></div>
        <form className="auth-form" onSubmit={async event => {
          event.preventDefault(); setError(""); setMessage("");
          const invalid = sharedCore.validateLogin(login, password, confirmation, register);
          if (invalid) { setError(invalid); return; }
          setBusy(true);
          try { const user = await passwordLogin(login.trim(), password, register); setAccount(user); setPassword(""); setConfirmation(""); if (settingsAutoSync()) await synchronize(user); }
          catch (failure) {if(failure instanceof MfaRequired) setChallenge(failure.challenge);setError(failure instanceof Error ? failure.message : "Не удалось войти"); }
          finally { setBusy(false); }
        }}>
          <label>Логин<input required disabled={busy} autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={32} value={login} placeholder="Ваш логин" onChange={event => setLogin(event.target.value)} /></label>
          <label>Пароль<span className="auth-password"><input aria-label="Пароль" required disabled={busy} type={showPassword ? "text" : "password"} autoComplete={register ? "new-password" : "current-password"} maxLength={128} value={password} placeholder="Введите пароль" onChange={event => setPassword(event.target.value)} /><button type="button" aria-label={showPassword ? "Скрыть пароль" : "Показать пароль"} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeOff size={18} /> : <Eye size={18} />}</button></span></label>
          {register && <label>Повторите пароль<input required disabled={busy} type="password" autoComplete="new-password" maxLength={128} value={confirmation} onChange={event => setConfirmation(event.target.value)} /><small>Не меньше 8 символов</small></label>}
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="auth-submit" type="submit" disabled={busy}>{busy && <LoaderCircle size={18} className="spin" />}{busy ? "Подождите…" : register ? "Создать аккаунт" : "Войти"}{!busy && <ArrowRight size={18} />}</button>
        </form>
      </section>}
    {challenge&&!account&&<Dialog title="Подтвердите вход" description="Откройте приложение-аутентификатор и введите код. Если телефон недоступен, используйте резервный код." icon={<ShieldCheck size={22}/>} locked={busy} onClose={()=>{setChallenge('');setOtp('');setError('');}}><form className="auth-form" onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try {const user=await finishMfa(challenge,otp);setAccount(user);setChallenge('');setOtp('');setPassword('');if(settingsAutoSync()) await synchronize(user);}catch(e){setError(e instanceof Error?e.message:'Неверный код');}finally{setBusy(false);}}}><label>Код аутентификатора или резервный код<input className="otp-input" required disabled={busy} autoComplete="one-time-code" maxLength={32} placeholder="Код подтверждения" value={otp} onChange={e=>setOtp(e.target.value)}/></label>{error&&<p className="auth-error" role="alert">{error}</p>}<button className="auth-submit" type="submit" disabled={busy}>{busy?<><LoaderCircle size={17} className="spin"/>Проверяем…</>:"Войти"}</button></form></Dialog>}
    {account&&<AccountSecurity key={account.id} account={account} onChanged={setAccount}/>}
    {account && (error || message) && <p className={error ? "auth-error" : "cloud-status"} role={error ? "alert" : "status"}>{!error && <Check size={16} />}{error || message}</p>}
  </div>;
}
