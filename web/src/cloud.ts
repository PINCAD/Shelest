import { localStore } from "./store";
import type { Book } from "./types";
import { progressTime } from "./readingProgress";
import { sharedCore } from "./sharedCore";
import { SharedSyncSession } from "@shelest/shared";
import {syncPersonal} from "./personalSync";
import {capturePersonal,removeCollectionMembership} from "./personalJournal";
export async function renameCloudBook(book:Book) {const owner=localStorage.getItem("shelest-owner")||"";capturePersonal(`title:${await hash(book)}`,book.title,owner);}

export const GOOGLE_CLIENT_ID = sharedCore.googleWebClientId;
export type Account = { id: string; email: string; displayName: string; isPremium: boolean; quotaBytes: number; storageUsedBytes: number; maxBookBytes?: number; login?: string; hasPassword?: boolean; twoFactorEnabled?:boolean };
type RemoteBook = { sha256: string; title: string; author: string; fileType: Book["format"]; size: number;
  position: number; pageIndex: number | null; updatedAt: number | null; hasFile: boolean; readingPlace?: Book["cloudPlace"] };
let csrf = "";
const syncSession = new SharedSyncSession();
let accountId = localStorage.getItem("shelest-owner") ?? "";
let knownAccount: Account | null = null;
let autoSync = sharedCore.defaultAutoSync;
let syncDeletions = sharedCore.defaultSyncDeletions;
export function settingsAutoSync(): boolean { return autoSync; }
export function lastSuccessfulSync(owner = accountId): number {
  const value = Number(localStorage.getItem(`shelest-last-sync:${owner}`));
  return Number.isFinite(value) && value > 0 ? value : 0;
}
function recordSuccessfulSync(owner: string): void {
  if (!owner || owner !== accountId) return;
  localStorage.setItem(`shelest-last-sync:${owner}`, String(Date.now()));
  window.dispatchEvent?.(new Event("shelest-sync-finished"));
}
export function configureSync(options: {autoSync: boolean; syncDeletions: boolean}): void {
  if (!syncDeletions && options.syncDeletions) syncSession.invalidate();
  if (autoSync && !options.autoSync) syncSession.invalidate();
  autoSync = options.autoSync; syncDeletions = options.syncDeletions;
}
function accountValue(value: unknown): Account { return {...JSON.parse(sharedCore.accountJson(JSON.stringify(value))),twoFactorEnabled:(value as Account)?.twoFactorEnabled===true}; }
function responseValue(value: unknown): {books: RemoteBook[]; deletedBooks: string[]; serverTime: number; account?: Account} {
  return JSON.parse(sharedCore.syncResponseJson(JSON.stringify(value)));
}
function record(book: Book, sha: string, restoring = false): unknown {
  return {sha256: sha, title: book.title.slice(0, 500), author: book.author.slice(0, 500), fileType: book.format,
    size: book.size, deviceId, position: book.position, pageIndex: Math.max(0, book.page - 1),
    updatedAt: progressTime(book), readingPlace: book.cloudPlace ?? null, restoreDeleted: restoring};
}
function syncPayload(records: unknown[], since: number): unknown {
  return JSON.parse(sharedCore.syncRequestJson(JSON.stringify(records), since));
}
function marks(kind: string, owner = accountId): Set<string> {
  try { return new Set(JSON.parse(localStorage.getItem(`shelest-${kind}:${owner}`) ?? "[]")); } catch { return new Set(); }
}
function saveMarks(kind: string, values: Set<string>, owner = accountId): void {
  localStorage.setItem(`shelest-${kind}:${owner}`, JSON.stringify([...values]));
}
function blocked(sha: string): boolean { return sharedCore.blocksBook(marks("hidden").has(sha), marks("pending-deletes").has(sha), marks("deleted").has(sha), marks("restores").has(sha)); }
export async function retryPendingDeletes(): Promise<void> {
  const owner = accountId;
  if (!owner) return;
  for (const sha of marks("pending-deletes", owner)) {
    if (owner !== accountId) return;
    await api(`sync/books/${sha}`, "DELETE", {accountId: owner});
    if (owner !== accountId) return;
    const pending = marks("pending-deletes", owner); pending.delete(sha); saveMarks("pending-deletes", pending, owner);
    saveMarks("deleted", marks("deleted", owner).add(sha), owner); remoteFiles.delete(sha);
  }
}
export async function queueBookDeletion(book: Book): Promise<void> {
  if (!accountId) { if(book.sha256) removeCollectionMembership(book.sha256,''); return; }
  const owner = accountId, sha = await hash(book);
  if (owner !== accountId) throw new Error("Аккаунт изменился. Повторите удаление");
  syncSession.invalidate();
  const kind = sharedCore.deletionKind(syncDeletions);
  const restores = marks("restores", owner); restores.delete(sha); saveMarks("restores", restores, owner);
  saveMarks(kind, marks(kind, owner).add(sha), owner);
  if(syncDeletions) removeCollectionMembership(sha,owner);
}
export async function markUserImport(book: Book): Promise<void> {
  if (!accountId) return;
  const owner = accountId, sha = await hash(book);
  if (owner !== accountId) return;
  syncSession.invalidate();
  for (const kind of ["hidden", "deleted", "pending-deletes"]) { const values = marks(kind, owner); values.delete(sha); saveMarks(kind, values, owner); }
  saveMarks("restores", marks("restores", owner).add(sha), owner); sent.delete(sha);
}
export function setCloudReading(value: boolean): void { syncSession.setReading(value); }
const sent = new Map<string, number>();
const remoteFiles = new Set<string>();
const deviceId = localStorage.getItem("shelest-device") ?? crypto.randomUUID();
localStorage.setItem("shelest-device", deviceId);

export async function authConfig(signal?: AbortSignal): Promise<{ clientId: string; nonce: string; csrfToken: string }> {
  const response = await fetch("/api/v1/auth/config", { credentials: "same-origin", signal });
  if (!response.ok) throw new Error("Сервер входа временно недоступен");
  const config = await response.json(); csrf = config.csrfToken; return config;
}
async function api(path: string, method = "GET", body?: unknown, retryCsrf = true, signal?: AbortSignal): Promise<any> {
  const generation = syncSession.generation;
  const guarded = path.startsWith("sync/") || path.startsWith("account/");
  const checkSession = () => { if (guarded && generation !== syncSession.generation) throw new Error("Состояние синхронизации изменилось"); };
  if (!csrf) await authConfig(signal);
  checkSession();
  const response = await fetch(`/api/v1/${path}`, { method, credentials: "same-origin", signal,
    headers: { "X-CSRFToken": csrf, ...(body instanceof Blob ? { "Content-Type": "application/octet-stream" } : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body) });
  checkSession();
  if (retryCsrf && response.status === 403 && response.headers.get("Content-Type")?.includes("text/html")) {
    await authConfig(signal); checkSession(); return api(path, method, body, false, signal);
  }
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    const error = new Error(sharedCore.serverError(JSON.stringify(detail), response.status));
    Object.assign(error, {status: response.status}); throw error;
  }
  if (path.endsWith("/download")) return response.blob();
  return response.json();
}
function reset(account: Account | null) {
  if (syncSession.updateAccount(JSON.stringify(account))) { sent.clear(); remoteFiles.clear(); }
  accountId = account?.id ?? "";
  knownAccount = account;
  localStorage.setItem("shelest-owner", accountId);
}
export async function currentAccount(): Promise<Account | null> {
  const generation = syncSession.generation;
  try {
    const result = await api("account");
    if (generation !== syncSession.generation) return knownAccount;
    const account = accountValue(result.account); reset(account); return account;
  }
  catch (error) { if (generation === syncSession.generation && (error as {status?: number})?.status === 401) reset(null); return null; }
}
export async function googleLogin(idToken: string, nonce: string): Promise<Account> {
  const result = await api("auth/web/google", "POST", { idToken, nonce }); checkMfa(result); csrf = result.csrfToken;
  const account = accountValue(result.account); reset(account); return account;
}
export async function logout(): Promise<void> { await api("auth/web/logout", "POST", {}); reset(null); }
export async function passwordLogin(login: string, password: string, register = false): Promise<Account> {
  const result = await api(sharedCore.passwordPath(register), "POST", JSON.parse(sharedCore.passwordPayload(login, password, register)));
  checkMfa(result);
  const account = accountValue(result.account); csrf = result.csrfToken; reset(account); return account;
}
export class MfaRequired extends Error { constructor(public challenge:string) {super("Введите код из приложения-аутентификатора");} }
function checkMfa(result:any) {if(result.mfaRequired) throw new MfaRequired(result.challenge);}
export async function finishMfa(challenge:string,code:string):Promise<Account> {
  const result=await api("auth/mfa","POST",{challenge,code});csrf=result.csrfToken;
  const account=accountValue(result.account);reset(account);return account;
}
export async function accountSecurity(payload:Record<string,string>):Promise<any> {
  const result=await api("account/security","POST",payload);
  if(result.account) {result.account=accountValue(result.account);reset(result.account);}
  return result;
}
export async function setAccountPassword(login: string, password: string, currentPassword: string): Promise<Account> {
  const result = await api("account/password", "POST", {login, password, currentPassword});
  csrf = result.csrfToken; const account = accountValue(result.account); reset(account); return account;
}
export async function collectionHash(book: Book): Promise<string> { return hash(book); }
async function hash(book: Book): Promise<string> {
  if (book.sha256) return book.sha256;
  const digest = await crypto.subtle.digest("SHA-256", await book.blob.arrayBuffer());
  book.sha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
  await localStore.saveBookHash(book.id, book.sha256); return book.sha256;
}

export async function positionBeforeOpen(book: Book): Promise<Book> {
  if (!autoSync) return (await localStore.books()).find(item => item.id === book.id) ?? book;
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 4000);
  try {
    const initialGeneration = syncSession.generation;
    const account = await api("account", "GET", undefined, true, controller.signal);
    if (initialGeneration !== syncSession.generation) return book;
    reset(accountValue(account.account));
    const owner = accountId, generation = syncSession.generation;
    const sha = await hash(book);
    const result = responseValue(await api("sync/books", "PUT", syncPayload([], 0), true, controller.signal));
    if (owner !== accountId || generation !== syncSession.generation) return book;
    const remote = (result.books as RemoteBook[]).find(item => item.sha256 === sha);
    const local = (await localStore.books()).find(item => item.id === book.id) ?? book;
    if (owner !== accountId || generation !== syncSession.generation) return local;
    if (!remote) return local;
    const action = sharedCore.positionAction(progressTime(local), JSON.stringify(remote), "web", false);
    if (action === "KEEP_LOCAL" || action === "INCOMPATIBLE") return local;
    const updated = {...local, position: remote.position, page: (remote.pageIndex ?? 0) + 1,
      progressUpdatedAt: remote.updatedAt!, cloudPlace: remote.readingPlace ?? undefined};
    await localStore.saveBook(updated, () => owner === accountId && generation === syncSession.generation);
    return owner === accountId && generation === syncSession.generation ? updated : local;
  } catch { return (await localStore.books()).find(item => item.id === book.id) ?? book; }
  finally { window.clearTimeout(timeout); }
}

const positionTimers = new Map<string, number>();
export function queueReadingPosition(book: Book): void {
  if (!sharedCore.canPublishPosition(autoSync, Boolean(accountId))) return;
  window.clearTimeout(positionTimers.get(book.id));
  const owner = accountId;
  positionTimers.set(book.id, window.setTimeout(() => {
    positionTimers.delete(book.id);
    void (async () => {
      const current = (await localStore.books()).find(item => item.id === book.id);
      if (!current || owner !== accountId || !autoSync) return;
      const sha = await hash(current);
      if (owner !== accountId || !autoSync || blocked(sha) || sent.get(sha) === progressTime(current)) return;
      await api("sync/books", "PUT", syncPayload([record(current, sha)], 0));
      if (owner === accountId) { sent.set(sha, progressTime(current)); recordSuccessfulSync(owner); }
    })().catch(() => { /* Keep the local place; the library sync retries it. */ });
  }, sharedCore.syncDebounceMs));
}

export async function syncBooks(account: Account): Promise<void> {
  if (syncSession.readerOpen) return;
  const fresh = await currentAccount();
  if (!fresh) throw new Error("Войдите в аккаунт");
  account = fresh;
  const run = syncSession.begin();
  if (run < 0) return;
  let incompatiblePlace = false;
  try {
    await retryPendingDeletes();
    if (!syncSession.isCurrent(run)) return;
    const local = await localStore.books();
    const byHash = new Map<string, Book>();
    for (const book of local) { if (!syncSession.isCurrent(run)) return; byHash.set(await hash(book), book); }
    const changed = Array.from(byHash).filter(([sha, book]) => sharedCore.needsPublish(progressTime(book), sent.get(sha) ?? -1,
      marks("hidden").has(sha), marks("pending-deletes").has(sha), marks("deleted").has(sha), marks("restores").has(sha)));
    for (let offset = 0; offset < Math.max(changed.length, 1); offset += sharedCore.syncBatchSize) {
      if (!syncSession.isCurrent(run)) return;
      const batch = changed.slice(offset, offset + sharedCore.syncBatchSize);
      const result = responseValue(await api("sync/books", "PUT", syncPayload(
        batch.map(([sha, book]) => record(book, sha, marks("restores").has(sha))), syncSession.cursor)));
      if (!syncSession.isCurrent(run)) return;
      if (result.account && result.account.id !== account.id) throw new Error("Аккаунт изменился");
      if (result.account) reset(result.account);
      if (!syncSession.isCurrent(run)) return;
      for (const sha of result.deletedBooks ?? []) {
        if (!syncSession.isCurrent(run)) return;
        saveMarks("deleted", marks("deleted").add(sha)); remoteFiles.delete(sha);
        if (syncDeletions) { const book = byHash.get(sha); if (book) await localStore.deleteBook(book.id, () => syncSession.isCurrent(run)); byHash.delete(sha); }
      }
      for (const remote of result.books as RemoteBook[]) {
        if (!syncSession.isCurrent(run)) return;
        for (const kind of ["deleted", "restores"]) { const values = marks(kind); values.delete(remote.sha256); saveMarks(kind, values); }
        if (blocked(remote.sha256)) continue;
        if (remote.hasFile) remoteFiles.add(remote.sha256);
        let book = byHash.get(remote.sha256);
        if (sharedCore.shouldDownload(account.isPremium, Boolean(book), remote.hasFile, blocked(remote.sha256))) {
          if (!sharedCore.validFileSize(remote.size, account.maxBookBytes!)) throw new Error("Книга превышает допустимый размер файла");
          const blob = await api(`sync/books/${remote.sha256}/download`);
          if (!syncSession.isCurrent(run)) return;
          if (!sharedCore.validFileSize(blob.size, account.maxBookBytes!)) throw new Error("Книга превышает допустимый размер файла");
          const actual = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
          const actualHash = Array.from(new Uint8Array(actual), byte => byte.toString(16).padStart(2, "0")).join("");
          if (actualHash !== remote.sha256) throw new Error("Книга повреждена при загрузке");
          const { importBook } = await import("./importBook");
          if (!syncSession.isCurrent(run)) return;
          book = await importBook(new File([blob], `${remote.title}.${remote.fileType.toLowerCase()}`));
          if (!syncSession.isCurrent(run)) return;
          book.sha256 = remote.sha256; book.progressUpdatedAt = 0; byHash.set(remote.sha256, book);
          book.title=remote.title;
          await localStore.saveBook(book, () => syncSession.isCurrent(run));
        }
        const action = book ? sharedCore.positionAction(progressTime(book), JSON.stringify(remote), "web", syncSession.readerOpen) : "KEEP_LOCAL";
        if (book && action !== "KEEP_LOCAL") {
          if (action === "INCOMPATIBLE") { incompatiblePlace = true; continue; }
          book = { ...book, position: remote.position, page: (remote.pageIndex ?? 0) + 1,
            progressUpdatedAt: remote.updatedAt!, cloudPlace: remote.readingPlace ?? undefined };
          await localStore.saveBook(book, () => syncSession.isCurrent(run)); byHash.set(remote.sha256, book);
        } else if (book && !local.some(item => item.id === book!.id)) await localStore.saveBook(book, () => syncSession.isCurrent(run));
        if (book) sent.set(remote.sha256, progressTime(book));
      }
      if (!syncSession.isCurrent(run)) return;
      for (const [sha, book] of batch) sent.set(sha, progressTime(book));
      if (!syncSession.acceptCursor(run, result.serverTime)) return;
    }
    if (account.isPremium) for (const [sha, book] of byHash) {
      if (!syncSession.isCurrent(run)) return;
      if (sharedCore.shouldUpload(account.isPremium, remoteFiles.has(sha), blocked(sha))) {
        if (!sharedCore.validFileSize(book.size, account.maxBookBytes!)) throw new Error(`Книга «${book.title}» превышает допустимый размер файла`);
        await api(`sync/books/${sha}/file`, "PUT", book.blob);
        if (!syncSession.isCurrent(run)) return;
        remoteFiles.add(sha);
      }
    }
    if(syncSession.isCurrent(run)) await syncPersonal(account.id,body=>api("sync/personal","PUT",body),()=>syncSession.isCurrent(run));
    if (incompatiblePlace) throw new Error("Книги загружены. Для переноса места чтения обновите Android-приложение и перелистните страницу в книге.");
    if (syncSession.isCurrent(run)) recordSuccessfulSync(account.id);
  } catch (error) {
    if (syncSession.isCurrent(run)) throw error;
  } finally { syncSession.finish(run); }
}
