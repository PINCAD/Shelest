import { sharedCore } from "./sharedCore";
import type { ReaderSettings } from "./types";
import { pagePalettes, readerFontStyle } from "./readerAppearance";

export type EnginePosition = { page: number; character: number; layout?: string; scrollY?: number; anchor?: string };
export type EngineStyle = { font: string; fontLoad: string; fs: string; lh: string; mx: string;
  bg: string; fg: string; accent: string; sel: string; align: string; anim: string; fontFace?:string;horizontal:boolean;spread:boolean };

export function readerStyle(settings: ReaderSettings): EngineStyle {
  const palette=pagePalettes[settings.pageTheme],dark=["dark","midnight","black"].includes(settings.pageTheme);
  return { ...readerFontStyle(settings),
    fs: `${settings.fontSize}px`, lh: String(settings.lineHeight), mx: `${settings.margin}px`,
    bg: palette.bg, fg: palette.fg,
    accent: dark ? "#CB7C6E" : "#A8493D", sel: dark ? "rgba(203,124,110,0.25)" : "rgba(168,73,61,0.21)",
    align: settings.justify?"justify":"left", anim: settings.pageAnimation,horizontal:settings.readingMode!=="scroll",
    spread:false };
}

export function readerDocument(content: string, style: EngineStyle, segment: number,
  previous: boolean, next: boolean, position: EnginePosition | null, progress: number, bridge: string,
  measurement = false): string {
  return sharedCore.readerDocument(content, style.font, style.fontLoad, parseInt(style.fs), style.lh,
    style.mx.replace("px", ""), style.bg, style.fg, style.accent, style.sel, style.align, style.anim,
    JSON.stringify(style), style.fontFace??"", progress, previous, next, JSON.stringify(position), segment, style.horizontal, bridge, measurement);
}

/** Only the adapter differs: Android has a native Shelest bridge; web uses authenticated messages. */
export function browserBridge(token: string, measurement = false, resources = false): string {
  return `(() => {
    const token = ${JSON.stringify(token)}, measurement = ${measurement};
    let navigationIntent = false, touchX = 0;
    let resourcesDone;
    if (${resources}) window.readerResourcesReady = new Promise(resolve => {resourcesDone = resolve;});
    const send = (kind, data) => parent.postMessage({shelestReader: token, kind, ...data}, '*');
    window.Shelest = {
      onTiming() {}, onToggleChrome() {}, onAnnotationClick(id) { send('annotation', {id}); },
      onPageChange(page, total, percent, segment) { send('pages', {page, total, percent, segment}); },
      onPositionChange(segment, page, character, layout, scrollY, anchor) {
        if (measurement || character < 0) return;
        send('position', {segment, page, character, layout, scrollY, anchor, moved: navigationIntent});
        navigationIntent = false;
      },
      onBoundary(direction) { if (!measurement) send('boundary', {direction}); },
      onCurlAbort() {}, onCurlArm() {}, onCurlIdle() {}, onCurlMove() {}, onCurlEnd() {},
      onCurlBegin(_from, to) { window.curlFallback(to); }
    };
    addEventListener('touchstart', e => {touchX = e.touches[0]?.clientX ?? 0;}, {passive: true});
    addEventListener('touchmove', e => {if (Math.abs((e.touches[0]?.clientX ?? touchX) - touchX) > 12) navigationIntent = true;}, {passive: true});
    document.addEventListener('click', e => {
      if (e.clientX < innerWidth * .3 || e.clientX > innerWidth * .7) navigationIntent = true;
    }, true);
    let speechSelectionOffset = null;
    document.addEventListener('selectionchange', () => {
      if (!measurement) {
        const context=typeof getSelectionContext==='function'?getSelectionContext():{};
        const speech=typeof getSpeechContext==='function'?getSpeechContext(true):null;
        if(speech) speechSelectionOffset=speech.at;
        send('selection', {text: (getSelection()?.toString() || '').trim().slice(0, 20000),contextBefore:context.contextBefore||'',contextAfter:context.contextAfter||''});
      }
    });
    addEventListener('message', e => {
      if (e.source !== parent || e.data?.shelestReader !== token) return;
      const m = e.data;
      if (m.command === 'resources') {
        const urls = new Map();
        const waiting = [...document.querySelectorAll('[data-reader-resource]')].map(img => {
          const key = img.dataset.readerResource, blob = m.assets?.[key];
          if (!blob || !/^image\\/(png|jpeg|gif|webp)$/.test(blob.type)) return Promise.resolve();
          let url = urls.get(key); if (!url) {url = URL.createObjectURL(blob); urls.set(key, url);}
          img.src = url; return img.decode().catch(() => {});
        });
        Promise.all(waiting).then(() => {resourcesDone?.(); resourcesDone = null;});
        return;
      }
      if (measurement) return;
      window.runWhenReaderReady(() => {
        if (m.command === 'turn') {
          navigationIntent = true;
          const page=typeof currentPage==='number'?currentPage:Math.max(0,lastReportedPage);
          const count=typeof totalPages==='number'?totalPages:lastTotalPages;
          const next=page+m.direction;
          if(next>=count&&canOpenNext) Shelest.onBoundary(1);
          else if(next<0&&canOpenPrevious) Shelest.onBoundary(-1);
          else window.jumpToPage(next);
        }
        else if (m.command === 'jump') { navigationIntent = true; window.jumpToAnchor(m.anchor); reportPlace(); }
        else if (m.command === 'style') {
          let face=document.getElementById('reader-font-face');
          if(!face) {face=document.createElement('style');face.id='reader-font-face';document.head.append(face);}
          face.textContent=m.style.fontFace || '';
          window.applyStyle(m.style); reportPlace();
        }
        else if (m.command === 'flush') {
          const position = window.captureReadingPosition();
          send('flushed', {request: m.request, position});
        }
        else if (m.command === 'speech') {
          const text=window.buildTextMap().text,start=m.selected&&speechSelectionOffset!==null?speechSelectionOffset:window.currentChar();
          const end=typeof currentPage==='number'&&currentPage+1<totalPages?charAtPlace(currentPage+1,pageOfChar):Math.min(text.length,start+6000);
          send('speech', {text:text.slice(start,Math.max(start,end)).trim()});
        }
        else if (m.command === 'annotations') window.applyAnnotations(m.items);
      });
    });
    document.addEventListener('keydown', e => {
      if (e.key === 'ArrowRight' || e.key === 'PageDown') { navigationIntent = true; window.jumpToPage(currentPage + 1); e.preventDefault(); }
      if (e.key === 'ArrowLeft' || e.key === 'PageUp') { navigationIntent = true; window.jumpToPage(currentPage - 1); e.preventDefault(); }
      if (e.key === 'Escape') send('escape', {});
    });
  })();`;
}

/** Book content cannot run scripts, navigate, or contact remote servers. */
export const READER_CSP = "default-src 'none'; img-src blob: data:; font-src blob: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'";

export function sandboxDocument(html: string): string {
  return html.replace('<meta charset="UTF-8">', `<meta charset="UTF-8"><meta http-equiv="Content-Security-Policy" content="${READER_CSP}">`);
}
