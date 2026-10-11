import { sharedCore } from "./sharedCore";

export type BookFormat = "EPUB" | "PDF" | "FB2" | "TXT" | "HTML" | "MD" | "DOCX" | "RTF" | "CBZ" | "ODT" | "MOBI" | "AZW";

export type Book = {
  id: string;
  title: string;
  author: string;
  fileName: string;
  format: BookFormat;
  size: number;
  addedAt: number;
  lastOpenedAt: number;
  position: number;
  page: number;
  pages: number;
  readingSeconds: number;
  liked: boolean;
  cover?: string;
  text?: string;
  blob: Blob;
  sha256?: string;
  progressUpdatedAt?: number;
  cloudPlace?: { platform: "web" | "android"; segment?: number; page?: number; character?: number | null; layout?: string; scrollY?: number; cfi?: string; anchor?: string };
};

export type SavedWord = {
  id: string;
  word: string;
  definition: string;
  bookTitle: string;
  addedAt: number;
  learned: boolean;
};

export type ReadingDay = { date: string; seconds: number; chars: number };

export type ReaderSettings = {
  fontSize: number;
  lineHeight: number;
  fontFamily: "serif" | "sans" | "literata" | "lora" | "alegreya" | "golos" | "inter" | "mono";
  pageTheme: "light" | "sepia" | "gray" | "mint" | "fog" | "dark" | "midnight" | "black";
  margin: number;
  justify: boolean;
  pageAnimation: "slide" | "fade" | "flip" | "none";
  readingMode: "pages" | "scroll";
  twoPage: boolean;
  speechRate: number;
  pdfZoom: number;
  darkApp: boolean;
  themeMode: "SYSTEM" | "LIGHT" | "DARK";
  accentChoice: "DEFAULT" | "BLUE" | "GREEN" | "VIOLET" | "AMBER" | "TEAL";
  showStats: boolean;
  dailyGoal: number;
  autoSync: boolean;
  syncDeletions: boolean;
};

export function normalizeSettings(settings: Partial<ReaderSettings>): ReaderSettings {
  return JSON.parse(sharedCore.webSettings(JSON.stringify(settings))) as ReaderSettings;
}
export const DEFAULT_SETTINGS = normalizeSettings({});
