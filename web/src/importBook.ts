import ePub from "epubjs";
import JSZip from "jszip";
import mammoth from "mammoth";
import * as pdfjs from "pdfjs-dist";
import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import type { Book, BookFormat } from "./types";
import { sharedCore } from "./sharedCore";

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;

const SUPPORTED = new Set<BookFormat>(["EPUB", "PDF", "FB2", "TXT", "HTML", "MD", "DOCX", "RTF", "CBZ", "ODT", "MOBI", "AZW"]);

function extension(name: string): string {
  return name.toLowerCase().split(".").pop() ?? "";
}

function toFormat(ext: string): BookFormat | null {
  if (ext === "htm" || ext === "xhtml") return "HTML";
  if (ext === "markdown") return "MD";
  if (ext === "zip") return "FB2";
  return SUPPORTED.has(ext.toUpperCase() as BookFormat) ? (ext.toUpperCase() as BookFormat) : null;
}

function cleanHtml(html: string): string {
  const parsed = new DOMParser().parseFromString(html, "text/html");
  parsed.querySelectorAll("script,style,noscript,svg").forEach((node) => node.remove());
  return parsed.body.innerText || parsed.body.textContent || "";
}

async function mobiInfo(file: File): Promise<{ title: string; author: string; text: string }> {
  // Keep the temporary copies bounded: an unusually large file is better rejected clearly than
  // exhausting memory in a mobile browser while converting bytes for the shared Kotlin decoder.
  if (file.size > 32 * 1024 * 1024) throw new Error("Файл MOBI больше 32 МБ. Для веб-версии попробуйте EPUB или откройте книгу в приложении.");
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = "";
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  const decodeMobi = (sharedCore as typeof sharedCore & { mobiDocumentJson: (source: string) => string }).mobiDocumentJson;
  const document = JSON.parse(decodeMobi(binary)) as { title?: string; author?: string; html: string };
  return { title: document.title ?? "", author: document.author ?? "", text: cleanHtml(document.html) };
}

async function odtInfo(file: File): Promise<{ title: string; author: string; text: string }> {
  const zip = await JSZip.loadAsync(file);
  const contentEntry = zip.file("content.xml");
  if (!contentEntry) throw new Error("В ODT не найден текст документа");
  const contentXml = await contentEntry.async("text");
  const content = new DOMParser().parseFromString(contentXml, "application/xml");
  if (content.querySelector("parsererror")) throw new Error("Файл ODT повреждён");
  // Preserve reading order by extracting every paragraph/heading from the XML document tree.
  const blocks = Array.from(content.getElementsByTagName("*")).filter((node) => {
    const name = node.localName.toLowerCase();
    return name === "p" || name === "h";
  }).map((node) => {
    let value = "";
    const walk = (current: Node) => {
      if (current.nodeType === Node.TEXT_NODE) { value += current.nodeValue ?? ""; return; }
      if (current.nodeType !== Node.ELEMENT_NODE) return;
      const element = current as Element;
      const name = element.localName.toLowerCase();
      if (name === "s") { value += " ".repeat(Math.max(1, Math.min(100, Number(element.getAttribute("text:c") ?? element.getAttribute("c")) || 1))); return; }
      if (name === "tab") { value += "\t"; return; }
      if (name === "line-break") { value += "\n"; return; }
      element.childNodes.forEach(walk);
    };
    walk(node);
    return value.trim();
  }).filter(Boolean);
  const metaEntry = zip.file("meta.xml");
  let title = ""; let author = "";
  if (metaEntry) {
    const meta = new DOMParser().parseFromString(await metaEntry.async("text"), "application/xml");
    title = meta.getElementsByTagNameNS("*", "title")[0]?.textContent?.trim() ?? "";
    author = meta.getElementsByTagNameNS("*", "creator")[0]?.textContent?.trim() ?? "";
  }
  return { title, author, text: blocks.join("\n\n") };
}

function cleanRtf(rtf: string): string {
  return rtf
    .replace(/\\'[0-9a-f]{2}/gi, " ")
    .replace(/\\par[d]?/g, "\n\n")
    .replace(/\\[a-z]+-?\d* ?/gi, "")
    .replace(/[{}]/g, "")
    .replace(/\\~/g, " ")
    .replace(/\\-/g, "")
    .replace(/\\_/g, "—")
    .trim();
}

function cleanMarkdown(markdown: string): string {
  return markdown
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "• ")
    .replace(/^\s*>\s?/gm, "")
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/[`*_~]/g, "")
    .replace(/^\s*[-*_]{3,}\s*$/gm, "")
    .trim();
}

async function fb2Text(file: File): Promise<string> {
  if (extension(file.name) === "zip") {
    const zip = await JSZip.loadAsync(file);
    const entry = Object.values(zip.files).find((item) => !item.dir && item.name.toLowerCase().endsWith(".fb2"));
    if (!entry) throw new Error("В ZIP-архиве не найден файл FB2");
    return entry.async("text");
  }
  return file.text();
}

function fb2PlainText(xml: string): string {
  const document = new DOMParser().parseFromString(xml, "application/xml");
  if (document.querySelector("parsererror")) return xml.replace(/<[^>]+>/g, " ");
  const title = document.querySelector("book-title")?.textContent?.trim();
  return [title, ...Array.from(document.querySelectorAll("body p, body subtitle, body title"))
    .map((node) => node.textContent?.trim()).filter((value): value is string => Boolean(value))]
    .filter(Boolean).join("\n\n");
}

function dataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("Не удалось прочитать обложку"));
    reader.readAsDataURL(blob);
  });
}

async function pdfInfo(file: File): Promise<{ title?: string; pages: number; cover?: string }> {
  const task = pdfjs.getDocument({ data: await file.arrayBuffer() });
  const pdf = await task.promise;
  let title: string | undefined;
  try {
    const metadata = await pdf.getMetadata();
    const info = metadata.info as Record<string, unknown>;
    if (typeof info.Title === "string" && info.Title.trim()) title = info.Title.trim();
  } catch { /* Metadata is optional and frequently absent. */ }
  let cover: string | undefined;
  try {
    const page = await pdf.getPage(1);
    const viewport = page.getViewport({ scale: 0.42 });
    const canvas = document.createElement("canvas");
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    const context = canvas.getContext("2d");
    if (context) {
      await page.render({ canvasContext: context, viewport }).promise;
      cover = canvas.toDataURL("image/jpeg", 0.72);
    }
  } catch { /* A corrupt/unsupported preview should not prevent import. */ }
  return { title, pages: pdf.numPages, cover };
}

async function epubInfo(file: File): Promise<{ title?: string; author?: string; cover?: string }> {
  const book = ePub(await file.arrayBuffer());
  try {
    await book.ready;
    const metadata = await book.loaded.metadata;
    let cover: string | undefined;
    try {
      const url = await book.coverUrl();
      if (url) {
        const response = await fetch(url);
        if (response.ok) cover = await dataUrl(await response.blob());
      }
    } catch { /* Some EPUBs have no cover or a broken cover reference. */ }
    return { title: metadata.title?.trim(), author: metadata.creator?.trim(), cover };
  } finally {
    book.destroy();
  }
}

export async function importBook(file: File): Promise<Book> {
  const format = toFormat(extension(file.name));
  if (!format) throw new Error("Формат не поддерживается. Доступны EPUB, PDF, FB2, TXT, HTML, MD, DOCX, RTF, CBZ, ODT, MOBI и AZW.");

  const fallbackTitle = file.name.replace(/\.(fb2\.zip|[^.]+)$/i, "").replace(/[._]+/g, " ").trim();
  let title = fallbackTitle;
  let author = "";
  let text: string | undefined;
  let pages = 0;
  let cover: string | undefined;

  if (format === "TXT") text = await file.text();
  else if (format === "HTML") text = cleanHtml(await file.text());
  else if (format === "MD") text = cleanMarkdown(await file.text());
  else if (format === "RTF") text = cleanRtf(await file.text());
  else if (format === "MOBI" || format === "AZW") {
    const info = await mobiInfo(file);
    title = info.title || title;
    author = info.author;
    text = info.text;
  } else if (format === "ODT") {
    const info = await odtInfo(file);
    title = info.title || title;
    author = info.author;
    text = info.text;
  }
  else if (format === "FB2") {
    const xml = await fb2Text(file);
    const document = new DOMParser().parseFromString(xml, "application/xml");
    title = document.querySelector("book-title")?.textContent?.trim() || title;
    author = ["first-name", "middle-name", "last-name"].map((tag) => document.querySelector(`author ${tag}`)?.textContent?.trim()).filter(Boolean).join(" ");
    text = fb2PlainText(xml);
  } else if (format === "DOCX") {
    const result = await mammoth.extractRawText({ arrayBuffer: await file.arrayBuffer() });
    text = result.value;
  } else if (format === "PDF") {
    const info = await pdfInfo(file);
    title = info.title || title;
    pages = info.pages;
    cover = info.cover;
  } else if (format === "EPUB") {
    const info = await epubInfo(file);
    title = info.title || title;
    author = info.author || author;
    cover = info.cover;
  } else if (format === "CBZ") {
    const zip = await JSZip.loadAsync(file);
    const image = Object.values(zip.files).filter((item) => !item.dir && /\.(jpe?g|png|webp)$/i.test(item.name)).sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))[0];
    if (image) {
      const ext = extension(image.name);
      cover = await dataUrl(await image.async("blob"));
      if (!["jpg", "jpeg", "png", "webp"].includes(ext)) cover = undefined;
    }
  }

  const now = Date.now();
  return {
    id: crypto.randomUUID(), title: title || fallbackTitle || "Без названия", author,
    fileName: file.name, format, size: file.size, addedAt: now, lastOpenedAt: 0, progressUpdatedAt: 0,
    position: 0, page: 1, pages, readingSeconds: 0, liked: false, cover, text,
    blob: file,
  };
}
