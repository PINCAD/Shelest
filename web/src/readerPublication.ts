import { anchorOffset, quoteAtRange } from "./readingAnchor";
import type { Book } from "./types";
import type { EnginePosition } from "./readerEngine";

export type ReaderSegment = { html: string; text: string; assets?: Record<string, Blob> };
const IMAGE_PLACEHOLDER = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
export type ReaderToc = { label: string; href: string };
export type ReaderPublication = {
  count: number; toc: ReaderToc[];
  load: (segment: number) => Promise<ReaderSegment>;
  locate: (book: Book) => Promise<{segment: number; position: EnginePosition | null; progress: number}>;
  pin?: (segment: number) => void;
  dispose: () => void;
};

const escapeHtml = (text: string) => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
const safeTags = new Set("p div span section article h1 h2 h3 h4 h5 h6 blockquote ul ol li em strong b i u s small sup sub br hr pre code table thead tbody tr th td figure figcaption img a".split(" "));

/** Strict allowlist, not only removal of <script>: SVG/events/CSS/iframes also execute or fetch. */
export async function sanitizeBookHtml(body: Element, image: (src: string) => Promise<string | {key: string; blob: Blob} | null>): Promise<ReaderSegment> {
  const doc = document.implementation.createHTMLDocument("");
  const root = doc.createElement("div");
  const assets: Record<string, Blob> = {};
  async function copy(node: Node, target: Node): Promise<void> {
    if (node.nodeType === Node.TEXT_NODE) { target.appendChild(doc.createTextNode(node.textContent ?? "")); return; }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const original = node as Element;
    const tag = original.localName.toLowerCase();
    if (["script", "style", "iframe", "object", "embed", "svg", "math", "link", "meta", "base", "noscript", "form", "input", "button", "textarea"].includes(tag)) return;
    if (!safeTags.has(tag)) { for (const child of Array.from(node.childNodes)) await copy(child, target); return; }
    const el = doc.createElement(tag);
    for (const name of ["id", "title", "lang", "dir", "colspan", "rowspan", "alt"]) {
      const value = original.getAttribute(name); if (value) el.setAttribute(name, value);
    }
    if (tag === "img") {
      const src = original.getAttribute("src");
      const url = src ? await image(src) : null;
      if (!url) return;
      if (typeof url === "string") {
        if (!/^data:image\/(?:png|jpeg|gif|webp);/i.test(url)) return;
        el.setAttribute("src", url);
      } else {
        assets[url.key] = url.blob;
        el.setAttribute("src", IMAGE_PLACEHOLDER); el.setAttribute("data-reader-resource", url.key);
      }
      for (const key of ["width", "height"]) {
        const value = original.getAttribute(key); if (value && /^\d{1,5}$/.test(value)) el.setAttribute(key, value);
      }
    }
    // Keep inline semantic formatting, but do not copy class/style/href/event attributes.
    for (const child of Array.from(node.childNodes)) await copy(child, el);
    target.appendChild(el);
  }
  for (const node of Array.from(body.childNodes)) await copy(node, root);
  return {html: root.innerHTML, text: root.textContent ?? "", assets};
}

function nativePlace(book: Book, character: number): EnginePosition {
  return {page: Math.max(0, book.cloudPlace?.page ?? 0), character,
    layout: book.cloudPlace?.layout, scrollY: book.cloudPlace?.scrollY};
}

async function locateAnchor(book: Book, count: number, load: ReaderPublication["load"]): Promise<{segment: number; position: EnginePosition | null; progress: number}> {
  const place = book.cloudPlace;
  if (place?.platform === "web" && place.layout && place.character != null && place.segment != null && place.segment < count) {
    return {segment: place.segment, position: nativePlace(book, place.character), progress: 0};
  }
  if (place?.anchor) {
    let found: {segment: number; position: EnginePosition; progress: number} | null = null;
    // Try the likely segment first, but require a unique match throughout the publication.
    const order = Array.from({length: count}, (_, i) => i);
    const hint = Math.min(count - 1, Math.max(0, place.segment ?? 0));
    order.splice(hint, 1); order.unshift(hint);
    for (const segment of order) {
      const loaded = await load(segment);
      const character = anchorOffset(loaded.text, place.anchor);
      if (character === null) continue;
      if (found) throw new Error("Сохранённое место неоднозначно. Оно не было заменено началом книги.");
      found = {segment, position: {page: 0, character}, progress: 0};
    }
    if (!found) throw new Error("Сохранённое место не найдено в тексте. Прогресс не изменён.");
    return found;
  }
  if (place?.platform === "android" && (book.position > 0 || book.page > 1)) {
    throw new Error("У сохранённого места нет текстового якоря. Откройте книгу в обновлённом Android-приложении и синхронизируйте её.");
  }
  const fraction = Math.max(0, Math.min(.999999, book.position));
  const segment = Math.min(count - 1, Math.floor(fraction * count));
  return {segment, position: null, progress: (fraction * count - segment) * 100};
}

export async function prepareReaderPublication(book: Book, signal: AbortSignal): Promise<ReaderPublication> {
  const check = () => { if (signal.aborted) throw new DOMException("Закрыто", "AbortError"); };
  if (book.format !== "EPUB" && book.format !== "CBZ") {
    const text = book.text ?? "";
    if (!text.trim()) throw new Error("В книге не найден текст");
    // Preserve existing chunk boundaries while moving the renderer, not the storage schema.
    const chunkSize = 22_000;
    const count = Math.max(1, Math.ceil(text.length / chunkSize));
    const load = async (segment: number): Promise<ReaderSegment> => {
      check();
      const html = text.slice(segment * chunkSize, (segment + 1) * chunkSize).split(/\n\s*\n/)
        .filter(part => part.trim()).map(part => `<p>${escapeHtml(part.trim())}</p>`).join("");
      const root = new DOMParser().parseFromString(html, "text/html");
      return {html, text: root.body.textContent ?? ""};
    };
    return {count, toc: [], load, locate: b => locateAnchor(b, count, load), dispose() {}};
  }
  if (book.format === "CBZ") {
    const {default: JSZip} = await import("jszip");
    const zip = await JSZip.loadAsync(book.blob); check();
    const entries = Object.values(zip.files).filter(item => !item.dir && /\.(jpe?g|png|webp|gif)$/i.test(item.name))
      .sort((a, b) => a.name.localeCompare(b.name, undefined, {numeric: true}));
    if (!entries.length) throw new Error("В архиве не найдены страницы");
    const load = async (segment: number) => {
      check();
      const entry = entries[segment];
      const ext = entry.name.split(".").pop()!.toLowerCase();
      const type = `image/${ext === "jpg" ? "jpeg" : ext}`;
      const blob = new Blob([await entry.async("uint8array")], {type}); check();
      return {html: `<img class="cbz-page" src="${IMAGE_PLACEHOLDER}" data-reader-resource="image" alt="">`, text: "", assets: {image: blob}};
    };
    return {count: entries.length, toc: [], load,
      locate: async b => ({segment: Math.min(entries.length - 1, Math.max(0, b.cloudPlace?.segment ?? b.page - 1)), position: null, progress: 0}),
      dispose() {}};
  }
  const {default: ePub} = await import("epubjs");
  const publication: any = ePub(await book.blob.arrayBuffer());
  const resourceUrls = new Map<string, {blob: Blob; owners: Set<Set<string>>}>();
  const release = () => { resourceUrls.clear(); publication.destroy(); };
  try {
    await publication.ready; check();
    const sections: any[] = publication.spine.spineItems.filter((section: any) => section.linear !== "no");
    if (!sections.length) throw new Error("В EPUB не найдено содержимое");
    const cache = new Map<number, {promise: Promise<ReaderSegment>; owned: Set<string>}>();
    let pinned = -1;
    const releaseOwned = (owned: Set<string>) => {
      for (const key of owned) {
        const resource = resourceUrls.get(key); if (!resource) continue;
        resource.owners.delete(owned);
        if (!resource.owners.size) resourceUrls.delete(key);
      }
      owned.clear();
    };
    const load = (segment: number): Promise<ReaderSegment> => {
      check();
      let entry = cache.get(segment);
      if (!entry) {
        const section = sections[segment];
        const owned = new Set<string>();
        const promise = (async () => {
          const content = await section.load(publication.load.bind(publication)); check();
          try {
            const body = content.ownerDocument.querySelector("body") ?? content;
            return await sanitizeBookHtml(body, async src => {
              if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(src)) return null;
              const resolved = new URL(src, new URL(section.url, "https://epub.invalid")).pathname;
              const existing = resourceUrls.get(resolved);
              if (existing) { existing.owners.add(owned); owned.add(resolved); return {key: resolved, blob: existing.blob}; }
              const blob: Blob | undefined = await publication.archive.getBlob(decodeURIComponent(resolved));
              check();
              if (!blob || !/^image\/(png|jpeg|gif|webp)$/.test(blob.type)) return null;
              resourceUrls.set(resolved, {blob, owners: new Set([owned])}); owned.add(resolved); return {key: resolved, blob};
            });
          } finally { section.unload(); }
        })();
        entry = {promise, owned}; cache.set(segment, entry);
        promise.catch(() => { if (cache.get(segment)?.promise === promise) cache.delete(segment); releaseOwned(owned); });
        // A few HTML chapters, not a second in-memory copy of the complete book.
        if (cache.size > 3) {
          const oldest = [...cache.keys()].find(index => index !== pinned && index !== segment);
          if (oldest !== undefined) {
            const removed = cache.get(oldest)!; cache.delete(oldest);
            void removed.promise.then(() => releaseOwned(removed.owned), () => releaseOwned(removed.owned));
          }
        }
      }
      return entry.promise;
    };
    const navigation = await publication.loaded.navigation; check();
    const toc: ReaderToc[] = [];
    const visit = (entries: any[]) => { for (const entry of entries) {
      const section = publication.spine.get(entry.href);
      const segment = sections.indexOf(section);
      if (segment >= 0) toc.push({label: String(entry.label).trim(), href: `${segment}#${entry.href.split("#")[1] ?? ""}`});
      if (entry.subitems) visit(entry.subitems);
    } };
    visit(navigation.toc);
    return {count: sections.length, toc, load, pin(segment) { pinned = segment; },
      locate: async b => {
        if (b.cloudPlace?.platform === "web" && b.cloudPlace.cfi && !b.cloudPlace.layout) {
          // Migrate older web EPUB positions before discarding the old rendition engine.
          const range = await publication.getRange(b.cloudPlace.cfi); check();
          const body = range.startContainer.ownerDocument.querySelector("body");
          if (!body) throw new Error("Не удалось восстановить сохранённое место EPUB");
          return locateAnchor({...b, cloudPlace: {...b.cloudPlace, anchor: quoteAtRange(body, range)}}, sections.length, load);
        }
        return locateAnchor(b, sections.length, load);
      },
      dispose() { cache.clear(); release(); }
    };
  } catch (error) { release(); throw error; }
}
