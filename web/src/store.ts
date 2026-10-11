import type { Book, ReadingDay, ReaderSettings, SavedWord } from "./types";
import { normalizeSettings } from "./types";
import {capturePersonal,wordKey} from "./personalJournal";

const DB_NAME = "shelest-web";
const DB_VERSION = 1;

function db(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("books")) database.createObjectStore("books", { keyPath: "id" });
      if (!database.objectStoreNames.contains("words")) database.createObjectStore("words", { keyPath: "id" });
      if (!database.objectStoreNames.contains("days")) database.createObjectStore("days", { keyPath: "date" });
      if (!database.objectStoreNames.contains("settings")) database.createObjectStore("settings", { keyPath: "key" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Не удалось открыть хранилище браузера"));
  });
}

function requestValue<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("Ошибка локального хранилища"));
  });
}

async function all<T>(store: string): Promise<T[]> {
  const database = await db();
  return requestValue(database.transaction(store, "readonly").objectStore(store).getAll());
}

async function put(store: string, value: unknown, stillCurrent: () => boolean = () => true): Promise<void> {
  const database = await db();
  if (!stillCurrent()) { database.close(); return; }
  const transaction = database.transaction(store, "readwrite");
  transaction.objectStore(store).put(value);
  await new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("Не удалось сохранить изменения"));
    transaction.onabort = () => reject(transaction.error ?? new Error("Сохранение отменено"));
  });
}

export const localStore = {
  books: () => all<Book>("books"),
  saveBook: (book: Book, stillCurrent?: () => boolean) => put("books", book, stillCurrent),
  saveBookHash: async (id: string, sha256: string) => {
    const database = await db();
    const tx = database.transaction("books", "readwrite");
    const store = tx.objectStore("books");
    const request = store.get(id);
    request.onsuccess = () => { if (request.result) store.put({ ...request.result, sha256 }); };
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("Не удалось сохранить идентификатор книги"));
      tx.onabort = () => reject(tx.error);
    });
  },
  deleteBook: async (id: string, stillCurrent: () => boolean = () => true) => {
    const database = await db();
    if (!stillCurrent()) { database.close(); return; }
    const tx = database.transaction("books", "readwrite");
    tx.objectStore("books").delete(id);
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("Не удалось удалить книгу"));
    });
  },
  words: () => all<SavedWord>("words"),
  saveWord: async (word: SavedWord,stillCurrent:()=>boolean=()=>true,notify=true) => {
    await put("words",word,stillCurrent);
    if(notify&&stillCurrent()) capturePersonal(wordKey(word.word),{word:word.word.slice(0,200),definition:word.definition.slice(0,12000),bookTitle:word.bookTitle.slice(0,500),addedAt:word.addedAt,learned:word.learned});
  },
  deleteWord: async (id: string,stillCurrent:()=>boolean=()=>true,notify=true) => {
    const database = await db();
    if(!stillCurrent()) {database.close();return;}
    const word=await requestValue<SavedWord|undefined>(database.transaction("words","readonly").objectStore("words").get(id));
    if(!stillCurrent()) {database.close();return;}
    const tx = database.transaction("words", "readwrite");
    tx.objectStore("words").delete(id);
    await new Promise<void>((resolve, reject) => {
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("Не удалось удалить слово"));
    });
    if(notify&&word&&stillCurrent()) capturePersonal(wordKey(word.word),null);
  },
  days: () => all<ReadingDay>("days"),
  saveDay: (day: ReadingDay) => put("days", day),
  settings: async (): Promise<ReaderSettings> => {
    const database = await db();
    const saved = await requestValue<{ key: string; value: ReaderSettings } | undefined>(
      database.transaction("settings", "readonly").objectStore("settings").get("reader"),
    );
    return normalizeSettings(saved?.value ?? {});
  },
  saveSettings: async (value: ReaderSettings,stillCurrent:()=>boolean=()=>true,notify=true) => {
    const previous=await localStore.settings();
    await put("settings", {key:"reader",value:normalizeSettings(value)},stillCurrent);
    if(notify&&stillCurrent()&&previous.dailyGoal!==value.dailyGoal) capturePersonal("goal",value.dailyGoal);
  },
};
