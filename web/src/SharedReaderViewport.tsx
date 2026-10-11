import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { browserBridge, readerDocument, readerStyle, sandboxDocument, type EnginePosition } from "./readerEngine";
import { prepareReaderPublication, type ReaderPublication, type ReaderToc } from "./readerPublication";
import type { Book, ReaderSettings } from "./types";
import { prepareReaderFont } from "./readerAppearance";

export type SharedReaderPlace = EnginePosition & {segment: number; globalPage: number; pages: number; fraction: number; moved: boolean};
export type SharedReaderController = { turn: (direction: number) => void; jump: (href: string) => void;
  flush: () => Promise<void>; speech: (selected?: boolean) => void };
type DocumentState = {html: string; token: string; segment: number; assets?: Record<string, Blob>};

export function SharedReaderViewport({book, settings, controller, onPlace, onCount, onToc, onSelection, onSpeech, onEscape, onNotice}: {
  book: Book; settings: ReaderSettings; controller: MutableRefObject<SharedReaderController | null>;
  onPlace: (place: SharedReaderPlace) => void | Promise<void>;
  onCount: (page: number, total: number) => void; onToc: (entries: ReaderToc[]) => void;
  onSelection: (text: string, context?: {contextBefore:string;contextAfter:string}) => void; onSpeech: (text: string) => void; onEscape: () => void; onNotice: (text: string) => void;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  const host = useRef<HTMLDivElement>(null);
  const measurement = useRef<HTMLIFrameElement>(null);
  const [document, setDocument] = useState<DocumentState | null>(null);
  const [measureDocument, setMeasureDocument] = useState<DocumentState | null>(null);
  const [publication, setPublication] = useState<ReaderPublication | null>(null);
  const [error, setError] = useState("");
  const [size, setSize] = useState({width: 0, height: 0});
  const counts = useRef<number[]>([]);
  const localPage = useRef(0);
  const localTotal = useRef(0);
  const currentSegment = useRef(0);
  const activeToken = useRef("");
  const activeDocument = useRef<DocumentState | null>(null);
  const measurementToken = useRef("");
  const measurementDone = useRef<((total: number) => void) | null>(null);
  const currentPlace = useRef<SharedReaderPlace | null>(null);
  const transitioning = useRef(false);
  const pendingIntent = useRef(false);
  const pendingJump = useRef<string | null>(null);
  const alive = useRef(true);
  const sequence = useRef(0);
  const pendingFlush = useRef(new Map<string, () => void>());
  const callbacks = useRef({onPlace, onCount, onToc, onSelection, onSpeech, onEscape, onNotice});
  callbacks.current = {onPlace, onCount, onToc, onSelection, onSpeech, onEscape, onNotice};
  const settingsRef = useRef(settings); settingsRef.current = settings;
  const bookRef = useRef(book); bookRef.current = book;
  const publicationRef = useRef(publication); publicationRef.current = publication;

  function send(command: string, data: Record<string, unknown> = {}) {
    frame.current?.contentWindow?.postMessage({shelestReader: activeToken.current, command, ...data}, "*");
  }
  function notes(): {quote: string; note: string; segment?: number;color?:number;kind?:string;contextBefore?:string;contextAfter?:string}[] {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem(`shelest:notes:${bookRef.current.id}`) || "[]");
      return Array.isArray(stored) ? stored.filter(item => item && typeof item.quote === "string" && typeof item.note === "string") : [];
    } catch { return []; }
  }
  function applyNotes() {
    send("annotations", {items: notes().map((item, index) => ({...item, id: String(index)}))
      .filter(item => item.segment === undefined || item.segment === currentSegment.current)
      .map(item => ({id: item.id, selectedText: item.quote,contextBefore:item.contextBefore??'',contextAfter:item.contextAfter??'', type: item.kind==="highlight"?"HIGHLIGHT":"NOTE", color: Number.isInteger(item.color)?item.color:0xFFC1884B}))});
  }
  function pageNumbers() {
    const count = publicationRef.current?.count ?? 0;
    const all = count > 0 && Array.from({length: count}, (_, i) => counts.current[i]).every(n => n > 0);
    const before = counts.current.slice(0, currentSegment.current);
    const prefixKnown = Array.from({length: currentSegment.current}, (_, i) => counts.current[i]).every(n => n > 0);
    const globalPage = prefixKnown ? before.reduce((a, b) => a + b, 0) + localPage.current + 1 : Math.max(1, bookRef.current.page);
    return {globalPage, pages: all ? counts.current.reduce((a, b) => a + b, 0) : 0};
  }
  function updateMeter() {
    const numbers = pageNumbers(); callbacks.current.onCount(numbers.globalPage, numbers.pages);
  }
  async function publishPosition(position: EnginePosition & {segment: number}, moved: boolean) {
    const numbers = pageNumbers();
    const count = publicationRef.current?.count ?? 1;
    const fraction = Math.min(.999999, Math.max(0, (position.segment + position.page / Math.max(1, localTotal.current)) / count));
    const next = {...position, ...numbers, fraction, moved: moved || pendingIntent.current};
    pendingIntent.current = false;
    currentPlace.current = next;
    await callbacks.current.onPlace(next);
  }
  async function openSegment(pub: ReaderPublication, segment: number, position: EnginePosition | null, progress = 0) {
    const serial = ++sequence.current;
    transitioning.current = true;
    try {
      const loaded = await pub.load(segment);
      await prepareReaderFont(settingsRef.current);
      if (!alive.current || serial !== sequence.current) return;
      pub.pin?.(segment);
      const token = crypto.randomUUID();
      activeToken.current = token; currentSegment.current = segment; localPage.current = 0;
      const next = {segment, token, assets: loaded.assets, html: sandboxDocument(readerDocument(loaded.html,
        readerStyle(settingsRef.current), segment, segment > 0, segment < pub.count - 1,
        position, progress, browserBridge(token, false, Boolean(Object.keys(loaded.assets ?? {}).length))))};
      activeDocument.current = next;
      setDocument(next); setError("");
    } catch (reason) {
      if (alive.current && serial === sequence.current) { transitioning.current = false; setError(reason instanceof Error ? reason.message : "Не удалось открыть книгу"); }
    }
  }
  async function boundary(direction: number) {
    const pub = publicationRef.current;
    const segment = currentSegment.current + direction;
    if (!pub || transitioning.current || segment < 0 || segment >= pub.count) return;
    pendingIntent.current = true;
    // A preceding chapter opens on its final page, including a page containing only an image.
    const position = direction < 0 ? {page: 2147483647, character: -1, layout: ""} : null;
    await openSegment(pub, segment, position, direction < 0 ? 100 : 0);
  }

  useEffect(() => {
    alive.current = true;
    const abort = new AbortController();
    let prepared: ReaderPublication | null = null;
    void prepareReaderPublication(bookRef.current, abort.signal).then(async pub => {
      if (abort.signal.aborted) { pub.dispose(); return; }
      prepared = pub;
      publicationRef.current = pub; setPublication(pub); callbacks.current.onToc(pub.toc);
      const location = await pub.locate(bookRef.current);
      if (abort.signal.aborted) return;
      await openSegment(pub, location.segment, location.position, location.progress);
    }).catch(reason => { if (!abort.signal.aborted) setError(reason instanceof Error ? reason.message : "Не удалось открыть книгу"); });
    return () => {
      alive.current = false; abort.abort(); sequence.current++; prepared?.dispose();
      for (const resolve of pendingFlush.current.values()) resolve(); pendingFlush.current.clear();
    };
  }, [book.id]);

  useEffect(() => {
    const receive = (event: MessageEvent) => {
      const message = event.data;
      if (!message || typeof message !== "object") return;
      if (event.source === measurement.current?.contentWindow && message.shelestReader === measurementToken.current) {
        if (message.kind === "pages" && message.percent >= 0 && Number.isInteger(message.total) && message.total > 0) {
          measurementDone.current?.(message.total); measurementDone.current = null;
        }
        return;
      }
      if (event.source !== frame.current?.contentWindow || message.shelestReader !== activeToken.current) return;
      if (message.kind === "pages") {
        if (!Number.isInteger(message.page) || !Number.isInteger(message.total) || message.total < 1) return;
        localPage.current = message.page; localTotal.current = message.total;
        counts.current[message.segment] = message.total;
        transitioning.current = false; updateMeter();
        const place = currentPlace.current;
        if (place && place.segment === message.segment && place.page === message.page) {
          const numbers = pageNumbers();
          const fraction = (place.segment + place.page / message.total) / (publicationRef.current?.count ?? 1);
          if (place.pages !== numbers.pages || place.globalPage !== numbers.globalPage || place.fraction !== fraction) {
            void publishPosition(place, false);
          }
        }
      } else if (message.kind === "position") {
        localPage.current = message.page;
        void publishPosition({segment: message.segment, page: message.page, character: message.character,
          layout: message.layout, scrollY: message.scrollY, anchor: message.anchor}, Boolean(message.moved));
      } else if (message.kind === "boundary") { void boundary(message.direction); }
      else if (message.kind === "selection") callbacks.current.onSelection(String(message.text),{contextBefore:String(message.contextBefore??''),contextAfter:String(message.contextAfter??'')});
      else if (message.kind === "speech") callbacks.current.onSpeech(String(message.text));
      else if (message.kind === "escape") callbacks.current.onEscape();
      else if (message.kind === "annotation") {
        const item = notes()[Number(message.id)]; if (item) callbacks.current.onNotice(item.note || item.quote);
      }
      else if (message.kind === "flushed") {
        const done = pendingFlush.current.get(message.request); pendingFlush.current.delete(message.request);
        if (message.position) void publishPosition(message.position, false).finally(done);
        else done?.();
      }
    };
    window.addEventListener("message", receive);
    window.addEventListener("shelest-notes-changed", applyNotes);
    controller.current = {
      turn(direction) { if (!transitioning.current) send("turn", {direction}); },
      jump(href) {
        const [index, anchor = ""] = href.split("#"); const pub = publicationRef.current;
        const segment = Number(index);
        if (!pub || !Number.isInteger(segment) || segment < 0 || segment >= pub.count) return;
        if (segment === currentSegment.current) send("jump", {anchor});
        else { pendingIntent.current = true; pendingJump.current = anchor; void openSegment(pub, segment, null); }
      },
      flush() { return new Promise<void>(resolve => {
        if (!activeDocument.current) { resolve(); return; }
        const request = crypto.randomUUID();
        const timer = window.setTimeout(() => { pendingFlush.current.delete(request); resolve(); }, 1200);
        pendingFlush.current.set(request, () => { clearTimeout(timer); resolve(); });
        send("flush", {request});
      }); },
      speech(selected = false) { send("speech", {selected}); }
    };
    return () => { window.removeEventListener("message", receive); window.removeEventListener("shelest-notes-changed", applyNotes); controller.current = null; };
  }, []);

  useEffect(() => {
    let active=true;
    void prepareReaderFont(settings).then(()=>{
      if(!active||!document) return;
      send("style",{style:readerStyle(settings)});
    });
    return ()=>{active=false;};
  }, [settings.fontSize, settings.lineHeight, settings.fontFamily, settings.pageTheme, settings.margin,settings.justify,settings.pageAnimation]);

  const desktopSpread=false;
  const modeRef=useRef(`${settings.readingMode}:${desktopSpread}`);
  useEffect(()=>{
    const mode=`${settings.readingMode}:${desktopSpread}`;
    if(modeRef.current===mode) return;modeRef.current=mode;
    const pub=publicationRef.current,place=currentPlace.current;
    if(pub) void openSegment(pub,currentSegment.current,place,bookRef.current.position*100);
  },[settings.readingMode,desktopSpread]);

  useEffect(() => {
    const node = host.current; if (!node) return;
    const observer = new ResizeObserver(() => {
      const rect = node.getBoundingClientRect();
      setSize(previous => Math.abs(previous.width - rect.width) < .01 && Math.abs(previous.height - rect.height) < .01
        ? previous : {width: rect.width, height: rect.height});
    });
    observer.observe(node); return () => observer.disconnect();
  }, []);

  // One reusable offscreen iframe, one chapter at a time. No full-book layout on startup.
  useEffect(() => {
    if (!publication || !size.width || !size.height) return;
    const abort = new AbortController();
    counts.current = []; updateMeter();
    let timer = 0;
    const wait = (ms: number) => new Promise<void>(resolve => {
      timer = window.setTimeout(resolve, ms); abort.signal.addEventListener("abort", () => { clearTimeout(timer); resolve(); }, {once: true});
    });
    void (async () => {
      await wait(250);
      await prepareReaderFont(settingsRef.current);
      for (let index = 0; index < publication.count && !abort.signal.aborted; index++) {
        if (counts.current[index]) continue;
        const loaded = await publication.load(index);
        if (abort.signal.aborted) break;
        const total = await new Promise<number>(resolve => {
          const token = crypto.randomUUID(); measurementToken.current = token;
          const timeout = window.setTimeout(() => resolve(0), 8000);
          measurementDone.current = pages => { clearTimeout(timeout); resolve(pages); };
          abort.signal.addEventListener("abort", () => { clearTimeout(timeout); resolve(0); }, {once: true});
          setMeasureDocument({token, segment: index, assets: loaded.assets, html: sandboxDocument(readerDocument(loaded.html,
            readerStyle(settingsRef.current), index, false, false, null, 0,
            browserBridge(token, true, Boolean(Object.keys(loaded.assets ?? {}).length)), true))});
        });
        if (abort.signal.aborted) break;
        if (!total) break; // Keep the total hidden rather than publish a guessed count.
        counts.current[index] = total; updateMeter();
        await wait(window.document.hidden ? 1000 : 25);
      }
      if (!abort.signal.aborted) {
        setMeasureDocument(null);
        if (currentPlace.current) await publishPosition(currentPlace.current, false);
      }
    })().catch(() => { if (!abort.signal.aborted) setMeasureDocument(null); });
    return () => { abort.abort(); clearTimeout(timer); measurementDone.current = null; };
  }, [publication, size.width, size.height, settings.fontSize, settings.lineHeight, settings.fontFamily,settings.margin,settings.justify,settings.readingMode,settings.twoPage]);

  return <div className="shared-reader-frame" ref={host}>
    {document && <iframe ref={frame} title={book.title} sandbox="allow-scripts" srcDoc={document.html}
      onLoad={() => {
        send("resources", {assets: document.assets});
        const token=document.token;
        void prepareReaderFont(settingsRef.current).then(()=>{
          if(activeToken.current===token) send('style',{style:readerStyle(settingsRef.current)});
        }).catch(()=>callbacks.current.onNotice('Не удалось загрузить шрифт'));
        applyNotes();
        if (pendingJump.current !== null) { send("jump", {anchor: pendingJump.current}); pendingJump.current = null; }
      }} />}
    {measureDocument && <iframe ref={measurement} title="Расчёт страниц" aria-hidden="true" tabIndex={-1}
      sandbox="allow-scripts" srcDoc={measureDocument.html} className="reader-measure-frame"
      onLoad={() => measurement.current?.contentWindow?.postMessage({shelestReader: measureDocument.token, command: "resources", assets: measureDocument.assets}, "*")}
      style={{width: size.width, height: size.height}} />}
    {!document && !error && <div className="reader-loading" role="status">Открываем книгу…</div>}
    {error && <div className="reader-toast" role="alert">{error}</div>}
  </div>;
}
