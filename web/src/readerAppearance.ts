import type { ReaderSettings } from "./types";
export const pagePalettes = {
  light:{label:"Белый",bg:"#ffffff",fg:"#1a1a1a"},
  sepia:{label:"Сепия",bg:"#f8f2e3",fg:"#3b2f20"},
  gray:{label:"Серый",bg:"#e9e7e2",fg:"#2c2a26"},
  mint:{label:"Мятный",bg:"#e1ebe2",fg:"#22301f"},
  fog:{label:"Голубой туман",bg:"#e8eef0",fg:"#23343b"},
  dark:{label:"Тёмный",bg:"#121212",fg:"#f2f2f2"},
  midnight:{label:"Ночной синий",bg:"#17232c",fg:"#d8e3e6"},
  black:{label:"Чёрный",bg:"#000000",fg:"#c7c7c7"}
};
export const readerFonts = {
  serif:{label:"PT Serif",file:"PTSerif-Regular.ttf"},
  sans:{label:"PT Sans",file:"PTSans-Regular.ttf"},
  literata:{label:"Literata",file:"Literata.ttf"},
  lora:{label:"Lora",file:"Lora.ttf"},
  alegreya:{label:"Alegreya",file:"Alegreya.ttf"},
  golos:{label:"Golos",file:"GolosText.ttf"},
  inter:{label:"Inter",file:"Inter.ttf"},
  mono:{label:"Моно",file:""}
};
const fonts=new Map<string,Promise<string>>();
const fontUrls=new Map<string,string>();
/** Fetch trusted bundled fonts in the parent, not from book HTML. CSP stays unchanged. */
export async function prepareReaderFont(settings:ReaderSettings) {
  const key=settings.fontFamily,file=readerFonts[key].file;
  if(!file) return;
  if(!fonts.has(key)) fonts.set(key,fetch(`/assets/fonts/${file}`).then(async response=>{
    if(!response.ok) throw new Error("Шрифт недоступен");
    // Sandboxed book frames have opaque origins: parent blob URLs cannot be read there.
    const blob=await response.blob();
    const url=await new Promise<string>((resolve,reject)=>{
      const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=()=>reject(reader.error);reader.readAsDataURL(blob);
    });
    fontUrls.set(key,url);return url;
  }).catch(()=>{fonts.delete(key);return "";}));
  await fonts.get(key);
}
export function readerFontStyle(settings:ReaderSettings) {
  const key=settings.fontFamily, url=fontUrls.get(key);
  const family=key==="mono" ? "monospace" : url ? `Shelest-${key}` : key==="sans"||key==="inter"||key==="golos" ? "Arial" : "Georgia";
  return {font:family==="monospace"?family:`'${family}', ${key==="sans"?"sans-serif":"serif"}`,
    fontLoad:family,fontFace:url?`@font-face{font-family:'${family}';src:url('${url}');font-weight:100 900;font-display:swap;}`:""};
}
