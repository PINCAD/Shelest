import { useEffect,useRef,useState } from "react";
import { Play,Pause,Square,X } from "lucide-react";
export function useReaderAudio(onState:(active:boolean)=>void,onNotice:(message:string)=>void) {
  const [voices,setVoices]=useState<SpeechSynthesisVoice[]>([]),[voice,setVoice]=useState("");
  const [paused,setPaused]=useState(false),[deadline,setDeadline]=useState(0),[left,setLeft]=useState(0);
  const serial=useRef(0),active=useRef<SpeechSynthesisUtterance|null>(null);
  const stop=()=>{serial.current++;window.speechSynthesis?.cancel();active.current=null;setPaused(false);onState(false);};
  useEffect(()=>{
    if(!window.speechSynthesis) return;
    const update=()=>setVoices(window.speechSynthesis.getVoices());update();
    window.speechSynthesis.addEventListener("voiceschanged",update);
    return ()=>{serial.current++;window.speechSynthesis.cancel();window.speechSynthesis.removeEventListener("voiceschanged",update);};
  },[]);
  useEffect(()=>{
    if(!deadline) {setLeft(0);return;}
    const tick=()=>{const remaining=Math.ceil((deadline-Date.now())/1000);setLeft(Math.max(0,remaining));if(remaining<=0){stop();setDeadline(0);onNotice("Таймер сна: озвучка остановлена");}};
    tick();const timer=window.setInterval(tick,1000);return ()=>clearInterval(timer);
  },[deadline]);
  const speak=(text:string,rate:number)=>{
    if(!window.speechSynthesis) {onNotice("Озвучка недоступна в этом браузере");return;}
    if(!text.trim()) {onNotice("На странице нет текста для озвучки");return;}
    stop();const run=serial.current;
    const chunks=text.match(/[^.!?\n]+[.!?\n]*/g)?.flatMap(sentence=>sentence.match(/.{1,250}(?:\s|$)|.{1,250}/g)??[])??[text];
    let index=0;
    const next=()=>{
      if(run!==serial.current) return;
      const part=chunks[index++];if(!part){active.current=null;onState(false);return;}
      const utterance=new SpeechSynthesisUtterance(part);active.current=utterance;
      utterance.voice=voices.find(v=>v.voiceURI===voice)??voices.find(v=>v.localService&&v.lang.startsWith("ru"))??voices.find(v=>v.localService)??null;
      utterance.lang=utterance.voice?.lang??"ru-RU";utterance.rate=rate;
      utterance.onend=next;utterance.onerror=event=>{if(run!==serial.current) return;onState(false);active.current=null;if(event.error!=="canceled"&&event.error!=="interrupted") onNotice("Не удалось воспроизвести текст выбранным голосом");};
      onState(true);window.speechSynthesis.speak(utterance);
    };next();
  };
  return {voices,voice,setVoice:(value:string)=>{stop();setVoice(value);},paused,stop,speak,left,
    sleep:(minutes:number)=>setDeadline(minutes?Date.now()+minutes*60000:0),
    pause:()=>{window.speechSynthesis?.pause();setPaused(true);},resume:()=>{window.speechSynthesis?.resume();setPaused(false);}};
}
export function ReaderAudio({audio,rate,active,onRate,onPlay,onClose}:{audio:ReturnType<typeof useReaderAudio>;rate:number;active:boolean;onRate:(value:number)=>void;onPlay:()=>void;onClose:()=>void}) {
  return <section className="reader-audio" aria-label="Озвучка текста">
    <div className="audio-heading"><strong>Озвучка страницы</strong><button onClick={onClose} aria-label="Закрыть озвучку"><X size={18}/></button></div>
    <div className="audio-buttons">
      <button onClick={active?(audio.paused?audio.resume:audio.pause):onPlay} aria-label={active&&!audio.paused?"Пауза озвучки":"Продолжить озвучку"}>{active&&!audio.paused?<Pause size={18}/>:<Play size={18}/>}</button>
      <button onClick={audio.stop} disabled={!active} aria-label="Остановить озвучку"><Square size={18}/></button>
      <label>Темп<select aria-label="Темп озвучки" value={rate} onChange={e=>{audio.stop();onRate(Number(e.target.value));}}>{[.5,.75,1,1.25,1.5,1.75,2].map(value=><option key={value} value={value}>{value}×</option>)}</select></label>
    </div>
    <label>Голос<select aria-label="Голос озвучки" value={audio.voice} onChange={e=>audio.setVoice(e.target.value)}><option value="">Локальный голос по умолчанию</option>{audio.voices.map(v=><option key={v.voiceURI} value={v.voiceURI}>{v.name} · {v.lang}{!v.localService?" · сетевой":""}</option>)}</select></label>
    <label>Таймер сна<select aria-label="Таймер сна" defaultValue="0" onChange={e=>audio.sleep(Number(e.target.value))}>{[0,10,20,30,45,60].map(value=><option key={value} value={value}>{value?`${value} мин`:"Выключен"}</option>)}</select></label>
    {audio.left>0&&<small role="status">Остановка через {Math.ceil(audio.left/60)} мин</small>}
    <small>Доступные голоса зависят от браузера. Сетевой голос передаёт текст своему провайдеру.</small>
  </section>;
}
