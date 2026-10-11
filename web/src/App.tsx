import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft, BookOpen, Bookmark, Check,
  ChevronLeft, ChevronRight, Clock3, Flame, Headphones, Heart,
  LibraryBig, List, MoreHorizontal, Settings2, Sparkles, Download,
  Trash2, X, Languages, FileUp, UserRound, Layers, Pencil, Image as ImageIcon,
} from "lucide-react";
import pdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { localStore } from "./store";
import type { Book, ReadingDay, ReaderSettings, SavedWord } from "./types";
import { DEFAULT_SETTINGS, normalizeSettings } from "./types";
import { SharedReaderViewport, type SharedReaderController, type SharedReaderPlace } from "./SharedReaderViewport";
import { currentAccount, syncBooks, configureSync, markUserImport, queueBookDeletion, retryPendingDeletes, setCloudReading, positionBeforeOpen, queueReadingPosition, type Account as CloudAccount } from "./cloud";
import { AccountScreen, AccountSummary } from "./AccountScreen";
import { CommonSyncSettings, CommonWorkspacePanel } from "./CommonAccountPanel";
import { sharedCore } from "./sharedCore";
import { pagePalettes,readerFonts } from "./readerAppearance";
import { PdfMarkup,usePdfTools } from "./PdfMarkup";
import {textNotes,saveTextNotes} from './annotationSync';
import { ReaderAudio,useReaderAudio } from "./ReaderAudio";
import {renameCloudBook} from "./cloud";
import {Dialog} from './Dialog';
import {ConfirmDialog} from './ConfirmDialog';
import {CollectionPicker,CollectionEditor,useCollections} from './CollectionDialogs';
import {readCollections} from './collections';
import {collectionHash} from './cloud';
import './collections.css';
import "pdfjs-dist/web/pdf_viewer.css";

type Section = "library" | "discover" | "words" | "stats" | "settings" | "account";
type SortOrder = "recent" | "title" | "author" | "added" | "progress" | "collection";

const todayKey = () => new Date().toISOString().slice(0, 10);
const pct = (value: number) => Math.max(0, Math.min(100, Math.round(value * 100)));

function App() {
  const [section, setSection] = useState<Section>("library");
  const [books, setBooks] = useState<Book[]>([]);
  const [words, setWords] = useState<SavedWord[]>([]);
  const [days, setDays] = useState<ReadingDay[]>([]);
  const [settings, setSettings] = useState<ReaderSettings>(DEFAULT_SETTINGS);
  const [activeBookId, setActiveBookId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<SortOrder>("recent");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [pendingDelete,setPendingDelete]=useState<Book|null>(null);
  const [signedAccount, setSignedAccount] = useState<CloudAccount | null>(null);
  const [dropActive, setDropActive] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const activeBook = books.find((book) => book.id === activeBookId) ?? null;

  const refresh = useCallback(async () => {
    const [savedBooks, savedWords, savedDays, savedSettings] = await Promise.all([
      localStore.books(), localStore.words(), localStore.days(), localStore.settings(),
    ]);
    if (readCollections().some(collection=>collection.members.length>0))
      await Promise.all(savedBooks.filter(book=>!book.sha256).map(book=>collectionHash(book).catch(()=>null)));
    setBooks(savedBooks.sort((a, b) => b.lastOpenedAt - a.lastOpenedAt));
    setWords(savedWords.sort((a, b) => b.addedAt - a.addedAt));
    setDays(savedDays.sort((a, b) => b.date.localeCompare(a.date)));
    setSettings(savedSettings);
    configureSync(savedSettings);
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    setCloudReading(Boolean(activeBookId));
    if(activeBookId) setNotice("");
    if (activeBookId) return;
    let stopped = false;
    const synchronize = async () => {
      if (document.visibilityState !== "visible" || stopped) return;
      const user = await currentAccount();
      if (!stopped) setSignedAccount(user);
      if (!user || stopped) return;
      try { await retryPendingDeletes(); if (settings.autoSync) await syncBooks(user); } catch { /* Offline reading stays available. */ }
      finally { if (!stopped) await refresh(); }
    };
    const initial = window.setTimeout(() => void synchronize(), 2500);
    const timer = window.setInterval(() => void synchronize(), sharedCore.syncIntervalMs(settings.autoSync));
    const focus = () => void synchronize();
    window.addEventListener("focus", focus);
    window.addEventListener("shelest-personal-changed",focus);
    return () => { stopped = true; window.clearTimeout(initial); window.clearInterval(timer); window.removeEventListener("focus", focus);window.removeEventListener("shelest-personal-changed",focus); };
  }, [activeBookId, refresh, settings.autoSync]);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 3000);
    return () => window.clearTimeout(timer);
  }, [notice]);
  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const dark = settings.themeMode === "SYSTEM" ? media.matches : settings.themeMode === "DARK";
      const root = document.documentElement;
      root.dataset.theme = dark ? "dark" : "light";
      const colors = {DEFAULT:["#A8493D","#CB7C6E"],BLUE:["#3169A8","#8AB8EA"],GREEN:["#3A7652","#8BCAA0"],VIOLET:["#7756A6","#BA9DE2"],AMBER:["#9C621E","#E1B36C"],TEAL:["#257B80","#86C8C9"]};
      const accent=colors[settings.accentChoice][dark ? 1 : 0];
      root.style.setProperty("--accent", accent);
      const channels=[1,3,5].map(index=>parseInt(accent.slice(index,index+2),16)/255).map(v=>v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));
      const luminance=channels[0]*.2126+channels[1]*.7152+channels[2]*.0722;
      root.style.setProperty('--on-accent',1.05/(luminance+.05)>=(luminance+.05)/.0586?'#ffffff':'#171717');
      root.style.setProperty("--accent-soft", `color-mix(in srgb, var(--accent) ${dark ? 16 : 9}%, var(--bg))`);
    };
    apply(); media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [settings.themeMode, settings.accentChoice]);

  const addFiles = async (files: FileList | File[]) => {
    const incoming = Array.from(files);
    if (!incoming.length) return;
    setBusy(true);
    try {
      for (const file of incoming) {
        const { importBook } = await import("./importBook");
        const book = await importBook(file);
        await localStore.saveBook(book);
        await markUserImport(book);
      }
      await refresh();
      if (settings.autoSync) { const user = await currentAccount(); if (user) { await syncBooks(user); await refresh(); } }
      setNotice(incoming.length === 1 ? "Книга добавлена в библиотеку" : `Добавлено книг: ${incoming.length}`);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Не удалось добавить книгу");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  };

  const openBook = async (book: Book) => {
    setCloudReading(true);
    try {
    const synchronized = await positionBeforeOpen(book);
    const updated = { ...synchronized, lastOpenedAt: Date.now() };
    await localStore.saveBook(updated);
    setBooks((items) => items.map((item) => item.id === book.id ? updated : item));
    setActiveBookId(book.id);
    } catch (reason) {
      setCloudReading(false);
      setNotice(reason instanceof Error ? reason.message : "Не удалось открыть книгу");
    }
  };

  const updateBook = async (updated: Book) => {
    await localStore.saveBook(updated);
    queueReadingPosition(updated);
    setBooks((items) => items.map((item) => item.id === updated.id ? updated : item).sort((a, b) => b.lastOpenedAt - a.lastOpenedAt));
  };

  const changeSettings = async (change: Partial<ReaderSettings>) => {
    const next = normalizeSettings({ ...settings, ...change });
    setSettings(next);
    configureSync(next);
    await localStore.saveSettings(next);
  };

  const removeBook = async (book: Book) => {
    setPendingDelete(book);
  };
  const confirmRemoveBook = async (book: Book) => {
    await queueBookDeletion(book);
    await localStore.deleteBook(book.id);
    void retryPendingDeletes().catch(() => setNotice("Удалено здесь. Удаление из облака повторится при подключении"));
    await refresh();
    if (activeBookId === book.id) setActiveBookId(null);
    setNotice("Книга удалена из библиотеки");
  };

  const saveWord = async (value: string, bookTitle: string) => {
    const word = value.trim().split(/\s+/)[0]?.replace(/[^\p{L}'’-]/gu, "");
    if (!word || word.length < 2) return;
    const existing = words.find((entry) => entry.word.toLowerCase() === word.toLowerCase());
    if (existing) { setNotice("Это слово уже есть в словарике"); return; }
    const saved: SavedWord = {
      id: crypto.randomUUID(), word, definition: "Определение можно добавить позже.", bookTitle,
      addedAt: Date.now(), learned: false,
    };
    await localStore.saveWord(saved);
    await refresh();
    setNotice(`«${word}» сохранено в словарик`);
  };

  const markReadSeconds = async (bookId: string, seconds: number, chars: number) => {
    const date = todayKey();
    const current = (await localStore.days()).find((day) => day.date === date) ?? { date, seconds: 0, chars: 0 };
    await localStore.saveDay({ ...current, seconds: current.seconds + seconds, chars: current.chars + chars });
    const book = books.find((item) => item.id === bookId);
    if (book) await updateBook({ ...book, readingSeconds: book.readingSeconds + seconds });
    setDays(await localStore.days());
  };

  const currentPageBook = activeBook;

  return (
    <div className={`app-shell ${activeBook ? "reader-open" : ""}`}>
      {!activeBook && <Sidebar section={section} onSelect={setSection} wordCount={words.length} account={signedAccount} />}
      <main className="main-panel">
        {activeBook ? (
          <Reader
            key={activeBook.id}
            book={currentPageBook!}
            settings={settings}
            onBack={() => setActiveBookId(null)}
            onSave={updateBook}
            onSaveWord={(value) => void saveWord(value, activeBook.title)}
            onReadSeconds={markReadSeconds}
            onSettingsChange={(change) => void changeSettings(change)}
          />
        ) : (
          <>
            <Topbar section={section} account={signedAccount} onOpenAccount={() => setSection("account")} />
            <div
              className={`page-content ${dropActive ? "drop-active" : ""}`}
              onDragOver={(event) => { event.preventDefault(); setDropActive(true); }}
              onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setDropActive(false); }}
              onDrop={(event) => { event.preventDefault(); setDropActive(false); void addFiles(event.dataTransfer.files); }}
            >
              {dropActive && <div className="drop-overlay"><FileUp size={30} /><span>Отпустите файл, чтобы добавить книгу</span></div>}
              {section === "library" && <Library
                owner={signedAccount?.id??localStorage.getItem("shelest-owner")??""} onCollectionSaved={()=>void refresh()}
                books={books} query={query} setQuery={setQuery} sort={sort} setSort={setSort} busy={busy}
                onAdd={() => inputRef.current?.click()} onImport={addFiles}
                onOpen={openBook} onDelete={removeBook} onLike={async (book) => { await updateBook({ ...book, liked: !book.liked }); }}
                onEdit={async (book,renamed=false) => {
                  const fresh=(await localStore.books()).find(item=>item.id===book.id);if(!fresh) return;
                  const updated={...fresh,...(renamed?{title:book.title}:{cover:book.cover})};
                  await localStore.saveBook(updated);if(renamed&&fresh.title!==book.title) await renameCloudBook(updated);await refresh();
                }}
                onContinue={() => books[0] && void openBook(books[0])} days={days} settings={settings}
                onStats={()=>setSection("stats")}
              />}
              {section === "discover" && <Discover books={books} onOpen={openBook} onAdd={() => inputRef.current?.click()} />}
              {section === "words" && <Vocabulary words={words} onToggle={async (word) => { await localStore.saveWord({ ...word, learned: !word.learned }); await refresh(); }} onDelete={async (word) => { await localStore.deleteWord(word.id); await refresh(); }} />}
              {section === "stats" && <Statistics books={books} days={days} settings={settings} onGoalChange={(dailyGoal) => void changeSettings({ dailyGoal })} />}
              {section === "settings" && <Settings settings={settings} onChange={changeSettings} account={signedAccount} onAccount={() => setSection("account")} onStats={() => setSection("stats")} />}
              {section === "account" && <AccountScreen onSynced={refresh} onAccountChanged={setSignedAccount} />}
            </div>
          </>
        )}
      </main>
      {!activeBook && <nav className="mobile-nav">
        {([ ["library", "Библиотека", LibraryBig], ["discover", "Почитать", Sparkles], ["words", "Словарик", Languages], ["settings", "Настройки", Settings2] ] as const).map(([id, label, Icon]) => <button key={id} className={section === id ? "selected" : ""} onClick={() => setSection(id)}><Icon size={19} /><span>{label}</span></button>)}
      </nav>}
      <input
        ref={inputRef} className="visually-hidden" type="file" multiple
        accept=".epub,.pdf,.fb2,.fb2.zip,.txt,.html,.htm,.md,.markdown,.docx,.rtf,.cbz,.odt,.mobi,.azw"
        onChange={(event) => void addFiles(event.target.files ?? [])}
      />
      {notice && <div className="toast" role="status">{notice}</div>}
      {pendingDelete&&<ConfirmDialog title="Удалить книгу?" description={`«${pendingDelete.title}» будет удалена ${signedAccount&&settings.syncDeletions?'из облака и со всех устройств аккаунта':'только с этого устройства'}.`} onClose={()=>setPendingDelete(null)} onConfirm={()=>confirmRemoveBook(pendingDelete)}/>}
    </div>
  );
}

function Sidebar({ section, onSelect, wordCount, account }: { section: Section; onSelect: (section: Section) => void; wordCount: number; account: CloudAccount | null }) {
  const nav: { id: Section; label: string; icon: typeof LibraryBig; badge?: number }[] = [
    { id: "library", label: "Моя библиотека", icon: LibraryBig },
    { id: "discover", label: "Что почитать", icon: Sparkles },
    { id: "words", label: "Словарик", icon: Languages, badge: wordCount || undefined },
  ];
  return (
    <aside className="sidebar">
      <button className="brand" onClick={() => onSelect("library")}>
        <span className="brand-mark"><img src="/logo.jpg" alt="" /></span>
        <span><strong>Шелест.</strong><small>читалка книг</small></span>
      </button>
      <div className="nav-caption">БИБЛИОТЕКА</div>
      <nav className="side-nav">
        {nav.map(({ id, label, icon: Icon, badge }) => (
          <button key={id} className={`nav-item ${section === id ? "selected" : ""}`} onClick={() => onSelect(id)}>
            <Icon size={18} strokeWidth={1.8} /><span>{label}</span>{badge && <span className="nav-badge">{badge}</span>}
          </button>
        ))}
      </nav>
      <div className="nav-caption nav-caption-spaced">ВАШ ПРОГРЕСС</div>
      <nav className="side-nav">
        <button className={`nav-item ${section === "stats" ? "selected" : ""}`} onClick={() => onSelect("stats")}><Flame size={18} strokeWidth={1.8} /><span>Статистика чтения</span></button>
      </nav>
      <div className="sidebar-spacer" />
      <button className={`nav-item ${section === "settings" ? "selected" : ""}`} onClick={() => onSelect("settings")}><Settings2 size={18} strokeWidth={1.8} /><span>Настройки</span></button>
      <button className={`profile-row ${section === "account" ? "selected" : ""}`} onClick={() => onSelect("account")}>
        <span className="profile-avatar">{(account?.displayName || account?.login || "Г").charAt(0).toUpperCase()}</span><span className="profile-copy"><strong>{account?.displayName || account?.login || "Гость"}</strong><small>{account ? account.isPremium ? "Premium" : "Аккаунт Шелест" : "Локальная библиотека"}</small></span><MoreHorizontal size={18} />
      </button>
      <div className="sidebar-footer">Шелест · веб-версия</div>
    </aside>
  );
}

const sectionTitles: Record<Section, string> = {
  library: "Моя библиотека", discover: "Что почитать дальше", words: "Словарик",
  stats: "Чтение", settings: "Настройки", account: "Аккаунт",
};

function Topbar({ section, onOpenAccount, account }: { section: Section; onOpenAccount: () => void; account: CloudAccount | null }) {
  return (
    <header className="topbar">
      <div className="breadcrumb"><span>Шелест.</span><ChevronRight size={14} /><strong>{sectionTitles[section]}</strong></div>
      <div className="topbar-actions">
        <a className="download-app-link" href="/download/" aria-label="Скачать приложение для Android"><Download size={16} /><span>Приложение для Android</span></a>
        <button className="mobile-account" onClick={onOpenAccount} aria-label="Аккаунт"><span className="top-avatar">{account ? sharedCore.initials(account.displayName || account.login || "Ш") : <UserRound size={17} />}</span><span>{account ? "Аккаунт" : "Войти"}</span></button>
      </div>
    </header>
  );
}

function Library(props: {
  owner:string;onCollectionSaved:()=>void;
  books: Book[]; query: string; setQuery: (value: string) => void; sort: SortOrder; setSort: (value: SortOrder) => void; busy: boolean;
  onAdd: () => void; onImport: (files: FileList | File[]) => Promise<void>;
  onOpen: (book: Book) => void; onDelete: (book: Book) => void; onLike: (book: Book) => void;
  onEdit: (book: Book,renamed?:boolean) => Promise<void>;
  onContinue: () => void; days: ReadingDay[]; settings: ReaderSettings;
  onStats: () => void;
}) {
  const [rename,setRename] = useState<Book|null>(null),[name,setName] = useState(""),[editError,setEditError] = useState("");
  const {collections,refresh:refreshCollections}=useCollections(props.owner);
  const [activeCollectionId,setActiveCollectionId]=useState<string|null>(null);
  const [collectionSort,setCollectionSort]=useState<SortOrder>("collection");
  const sort=activeCollectionId?collectionSort:props.sort;
  const setSort=(value:SortOrder)=>activeCollectionId?setCollectionSort(value):props.setSort(value);
  const leaveCollection=()=>{setActiveCollectionId(null);props.setQuery("");setSelected([]);};
  const [selected,setSelected]=useState<string[]>([]),[collectionPicker,setCollectionPicker]=useState(false),[collectionEditor,setCollectionEditor]=useState(false),[deleteSelection,setDeleteSelection]=useState(false);
  const activeCollection=collections.find(c=>c.id===activeCollectionId);
  useEffect(()=>{leaveCollection();setCollectionPicker(false);setCollectionEditor(false);setDeleteSelection(false);},[props.owner]);
  useEffect(()=>{setSelected(old=>old.filter(id=>props.books.some(b=>b.id===id)));},[props.books]);
  const selectedBooks=selected.map(id=>props.books.find(b=>b.id===id)).filter((book):book is Book=>Boolean(book));
  const collectionSaved=()=>{refreshCollections();props.onCollectionSaved();setSelected([]);};
  const scopedBooks=activeCollectionId?props.books.filter(b=>activeCollection?.members.includes(b.sha256??"")):props.books;
  const coverInput = useRef<HTMLInputElement>(null), coverBook = useRef<Book|null>(null);
  const filtered = useMemo(() => {
    const clean = props.query.trim().toLocaleLowerCase("ru");
    const result = scopedBooks.filter((book) => !clean || `${book.title} ${book.author}`.toLocaleLowerCase("ru").includes(clean));
    if (sort === "recent") result.sort((a,b)=>b.lastOpenedAt-a.lastOpenedAt || b.addedAt-a.addedAt);
    if (sort === "title") result.sort((a, b) => a.title.localeCompare(b.title, "ru"));
    if (sort === "author") result.sort((a, b) => a.author.localeCompare(b.author, "ru"));
    if (sort === "added") result.sort((a,b)=>b.addedAt-a.addedAt);
    if (sort === "progress") result.sort((a,b)=>b.position-a.position);
    if (sort === "collection") {
      const ranks=new Map(activeCollection?.members.map((sha,index)=>[sha,index])??[]);
      result.sort((a,b)=>(ranks.get(a.sha256??'')??0)-(ranks.get(b.sha256??'')??0));
    }
    return result;
  }, [props.books, props.query, sort, activeCollection, activeCollectionId]);
  const fallback = <section className="common-library">
    <div className="common-library-controls">
      <div className="common-search-sort">
      <input type="search" aria-label="Найти книгу" placeholder="Найти книгу" value={props.query} onChange={event=>props.setQuery(event.target.value)}/>
      <select aria-label="Сортировка книг" value={sort} onChange={event=>setSort(event.target.value as SortOrder)}>
        {activeCollectionId&&<option value="collection">Сортировка: Порядок томов</option>}
        {[["recent","Недавние"],["title","Название"],["author","Автор"],["added","Добавлены"],["progress","Прогресс"]].map(([id,label])=><option key={id} value={id}>Сортировка: {label}</option>)}
      </select>
      </div>
      <span className="common-book-count">Книги: {props.books.length}</span>
      <button className="primary-button" onClick={props.onAdd} disabled={props.busy}>{props.busy?"Добавляем…":"Добавить книги"}</button>
    </div>
    {filtered.length ? <><h2 className="common-shelf-heading">Все книги</h2><div className="book-grid">{filtered.map(book=><BookCard key={book.id} book={book} selected={selected.includes(book.id)} selectionMode={selected.length>0} onOpen={()=>props.onOpen(book)} onSelect={()=>setSelected(old=>old.includes(book.id)?old.filter(id=>id!==book.id):[...old,book.id])}/>)}</div></>
      : <><h2 className="common-shelf-heading">{props.books.length?"Ничего не найдено":"Здесь будет ваша библиотека"}</h2><p>{props.books.length?"Попробуйте другое название или автора":"Выберите файлы или загрузите папку с книгами"}</p></>}
  </section>;
  return <div className="content-width">
    <div className="page-heading"><div><h1>{activeCollectionId?activeCollection?.name??"Коллекция удалена":"Моя библиотека"}</h1></div>
      {activeCollectionId&&<div className="collection-heading-actions"><button className="secondary-button" onClick={leaveCollection}><ArrowLeft size={18}/>Все книги</button>{activeCollection&&<button className="secondary-button" aria-label="Изменить коллекцию" onClick={()=>setCollectionEditor(true)}><Pencil size={18}/></button>}</div>}
    </div>
    {selectedBooks.length>0&&<div className="collection-selection-bar" role="toolbar" aria-label="Выбранные книги">
      <button aria-label="Отменить выделение" onClick={()=>setSelected([])}><X size={20}/></button><strong>Выбрано: {selectedBooks.length}</strong>
      <button aria-label="В коллекцию" onClick={()=>setCollectionPicker(true)}><Layers size={20}/><span>В коллекцию</span></button>
      {selectedBooks.length===1&&<><button aria-label="В избранное" onClick={()=>void props.onLike(selectedBooks[0])}><Heart size={20}/></button><button aria-label="Переименовать" onClick={()=>{setRename(selectedBooks[0]);setName(selectedBooks[0].title);}}><Pencil size={20}/></button><button aria-label="Обложка" onClick={()=>{coverBook.current=selectedBooks[0];coverInput.current?.click();}}><ImageIcon size={20}/></button></>}
      <button className="text-danger" aria-label="Удалить выбранные книги" onClick={()=>setDeleteSelection(true)}><Trash2 size={20}/></button>
    </div>}
    {activeCollection&&activeCollection.members.length>scopedBooks.length&&<p className="collection-missing">На этом устройстве доступны {scopedBooks.length} из {activeCollection.members.length} книг. Остальные появятся после импорта или облачной загрузки.</p>}
    {props.settings.showStats && props.days.some(day=>day.date===todayKey()&&day.seconds>0) &&
      <button className="common-stats-strip" onClick={props.onStats} aria-label="Статистика чтения">
        <span>Чтение сегодня</span><strong>{Math.floor((props.days.find(day=>day.date===todayKey())?.seconds??0)/60)} мин</strong><span>→</span>
      </button>}
    <CommonWorkspacePanel kind="library" state={{
      books: filtered.map(book=>({id:book.id,title:book.title,author:book.author,format:book.format,
        progress:book.position,liked:book.liked,opened:book.lastOpenedAt>0,cover:book.cover??""})),
      query:props.query,sort:sort.toUpperCase(),total:activeCollection?.members.length??props.books.length,busy:props.busy
      ,selectedIds:selected,allowRecent:!activeCollectionId,
      collections:activeCollectionId?[]:collections.map(c=>({id:c.id,title:c.name,total:c.members.length,books:c.members.map(sha=>props.books.find(b=>b.sha256===sha)).filter((b):b is Book=>Boolean(b)).slice(0,3).map(b=>({id:b.id,title:b.title,cover:b.cover??""}))}))
    }} onAction={(action,id)=>{
      if(action==="query") props.setQuery(id);
      else if(action==="sort" && ["recent","title","author","added","progress",...(activeCollectionId?["collection"]:[])].includes(id.toLowerCase())) setSort(id.toLowerCase() as SortOrder);
      else if(action==="add") props.onAdd();
      else if(action==="collection-open") {setActiveCollectionId(id);props.setQuery("");setSelected([]);setCollectionSort("collection");}
      else if(action==="select") setSelected(old=>old.includes(id)?old.filter(value=>value!==id):[...old,id]);
      else { const book=props.books.find(item=>item.id===id); if(!book) return;
        if(action==="open") props.onOpen(book);
        if(action==="delete") props.onDelete(book);
        if(action==="like") props.onLike(book);
        if(action==="rename") { setRename(book); setName(book.title); }
        if(action==="cover") { coverBook.current=book; coverInput.current?.click(); }
      }
    }} fallback={fallback}/>
    {collectionPicker&&<CollectionPicker books={selectedBooks} collections={collections} onClose={()=>setCollectionPicker(false)} onSaved={collectionSaved}/>}
    {collectionEditor&&activeCollection&&<CollectionEditor collection={activeCollection} books={props.books} onClose={()=>setCollectionEditor(false)} onSaved={collectionSaved}/>}
    {deleteSelection&&<ConfirmDialog title="Удалить выбранные книги?" description={'Выбрано: '+selectedBooks.length+'. Книги будут удалены '+(props.owner&&props.settings.syncDeletions?'из облака и со всех устройств':'только с этого устройства')+'.'} onClose={()=>setDeleteSelection(false)} onConfirm={async()=>{
      for(const book of selectedBooks){await queueBookDeletion(book);await localStore.deleteBook(book.id);}
      setDeleteSelection(false);setSelected([]);props.onCollectionSaved();void retryPendingDeletes().catch(()=>setEditError("Книги удалены локально. Облачное удаление повторится при подключении."));
    }}/>}
    <input ref={coverInput} type="file" accept="image/*" hidden onChange={event=>{
      const file=event.target.files?.[0],book=coverBook.current; event.target.value=""; coverBook.current=null;
      if(!file||!book) return;
      void saveCover(file).then(cover=>props.onEdit({...book,cover})).catch(error=>setEditError(error instanceof Error?error.message:"Не удалось установить обложку"));
    }}/>
    {rename && <Dialog title="Переименовать" description="Название изменится в библиотеке. Место чтения сохранится." icon={<BookOpen size={22}/>} onClose={()=>setRename(null)}><form className="auth-form" onSubmit={event=>{
      event.preventDefault(); if(!name.trim()) return;
      void props.onEdit({...rename,title:name.trim().slice(0,500)},true).then(()=>setRename(null)).catch(()=>setEditError("Не удалось сохранить название"));
    }}><label>Название книги<input value={name} maxLength={500} onChange={event=>setName(event.target.value)}/></label>
      <div className="common-dialog-actions"><button type="button" onClick={()=>setRename(null)}>Отмена</button><button type="submit" disabled={!name.trim()}>Сохранить</button></div></form></Dialog>}
    {editError && <div className="auth-error" role="alert">{editError}<button onClick={()=>setEditError("")}>Закрыть</button></div>}
  </div>;
}

async function saveCover(file: File): Promise<string> {
  if (!file.type.startsWith("image/") || file.size > 8 * 1024 * 1024) throw new Error("Выберите изображение размером до 8 МБ");
  const bitmap = await createImageBitmap(file);
  try {
    const ratio = Math.min(1, 600 / Math.max(bitmap.width,bitmap.height));
    const canvas = document.createElement("canvas"); canvas.width=Math.max(1,Math.round(bitmap.width*ratio)); canvas.height=Math.max(1,Math.round(bitmap.height*ratio));
    const context=canvas.getContext("2d"); if(!context) throw new Error("Не удалось прочитать изображение");
    context.drawImage(bitmap,0,0,canvas.width,canvas.height); return canvas.toDataURL("image/jpeg",.85);
  } finally { bitmap.close(); }
}


function Cover({ book, large = false }: { book: Book; large?: boolean }) {
  if (book.cover) return <div className={`book-cover ${large ? "cover-large" : ""}`}><img src={book.cover} alt={`Обложка: ${book.title}`} /></div>;
  const palette: Record<Book["format"], string> = {
    EPUB: "cover-clay", PDF: "cover-slate", FB2: "cover-olive", TXT: "cover-ink", HTML: "cover-sky", MD: "cover-plum", DOCX: "cover-sand", RTF: "cover-rust", CBZ: "cover-night", ODT: "cover-sand", MOBI: "cover-ink", AZW: "cover-night",
  };
  return <div className={`book-cover ${palette[book.format]} ${large ? "cover-large" : ""}`}>
    <span className="cover-frame" /><span className="cover-format">{book.format}</span><span className="cover-title">{book.title}</span><span className="cover-author">{book.author || "Шелест"}</span>
    <BookOpen className="cover-mark" size={large ? 28 : 20} strokeWidth={1.1} />
  </div>;
}

function BookCard({book,onOpen,onSelect,selected,selectionMode}:{book:Book;onOpen:()=>void;onSelect:()=>void;selected:boolean;selectionMode:boolean}) {
  const press=useRef({timer:0,x:0,y:0,fired:false});
  const cancel=()=>window.clearTimeout(press.current.timer);
  useEffect(()=>cancel,[]);
  const activate=()=>{if(press.current.fired){press.current.fired=false;return;}if(selectionMode)onSelect();else onOpen();};
  return <article className="book-card common-book-card">
    <div className="common-cover-wrap">
      <button className="cover-button" aria-label={`${selectionMode?'Выбрать':'Открыть'} ${book.title}`} aria-pressed={selected} onClick={activate}
        onContextMenu={event=>{event.preventDefault();cancel();if(!press.current.fired)onSelect();}}
        onKeyDown={event=>{if(event.key==='ContextMenu'||event.key==='F10'&&event.shiftKey){event.preventDefault();onSelect();}}}
        onPointerDown={event=>{cancel();if(!event.isPrimary||event.button!==0)return;press.current={timer:window.setTimeout(()=>{press.current.fired=true;onSelect();},500),x:event.clientX,y:event.clientY,fired:false};}}
        onPointerMove={event=>{if(Math.hypot(event.clientX-press.current.x,event.clientY-press.current.y)>10)cancel();}}
        onPointerUp={cancel} onPointerCancel={cancel} onPointerLeave={cancel}><Cover book={book}/></button>
      {selected&&<span className="collection-selected-cover"/>}
      {book.liked&&<span className="common-favorite" aria-label="В избранном">♥</span>}
      <span className={`common-cover-status ${book.lastOpenedAt?'':'common-new-book'}`}>{book.lastOpenedAt?`${pct(book.position)}%`:'Новая'}</span>
    </div>
    <div className="book-card-copy"><button className="book-title" onClick={activate} title={book.title}>{book.title}</button><span className="book-author">{book.author||'Автор не указан'}</span><div className="book-card-bottom"><span>{book.format}</span></div></div>
  </article>;
}

function Discover({ books, onOpen, onAdd }: { books: Book[]; onOpen: (book: Book) => void; onAdd: () => void }) {
  const liked = books.filter((book) => book.liked);
  return <div className="content-width subpage"><div className="page-heading"><div><div className="eyebrow">ПО ВАШЕМУ ВКУСУ</div><h1>Что почитать дальше</h1><p>Рекомендации появятся на основе книг, которые тебе нравятся.</p></div><span className="heading-emblem"><Sparkles size={22} /></span></div>
    <div className="discover-intro"><div className="discover-icon"><Heart size={20} /></div><div><strong>{liked.length ? `Ты отметил ${liked.length} ${liked.length === 1 ? "книгу" : "книги"}` : "Отметь книги, которые тебе нравятся"}</strong><span>{liked.length ? "Подборка будет строиться на этих книгах. Всё можно изменить в любой момент." : "Открой меню книги в библиотеке и выбери «Мне понравилось». Рекомендации будут личными и не повлияют на библиотеку."}</span></div>{books.length === 0 && <button className="secondary-button" onClick={onAdd}>Добавить книгу</button>}</div>
    {liked.length > 0 && <><div className="section-title-row"><h2>Твоя отправная точка</h2><span>{liked.length} в подборке</span></div><div className="liked-row">{liked.map((book) => <button key={book.id} className="liked-chip" onClick={() => onOpen(book)}><Cover book={book} /><span>{book.title}</span></button>)}</div><div className="recommend-empty"><Sparkles size={22} /><strong>Подключаем каталог рекомендаций</strong><p>Веб-подборка пока не обращается к внешним каталогам. Когда API будет развёрнут, здесь появятся книги с обложками и описаниями, как в приложении.</p></div></>}
    {liked.length === 0 && books.length > 0 && <div className="recommend-empty"><Heart size={22} /><strong>Пока нет отметок</strong><p>Поставь сердечко у нескольких книг, чтобы мы поняли, какие истории тебе близки.</p></div>}
  </div>;
}

function Vocabulary({ words, onToggle, onDelete }: { words: SavedWord[]; onToggle: (word: SavedWord) => void; onDelete: (word: SavedWord) => void }) {
  const [filter, setFilter] = useState<"all" | "new" | "learned">("all");
  const visible = words.filter((word) => filter === "all" || (filter === "learned" ? word.learned : !word.learned));
  return <div className="content-width subpage"><div className="page-heading"><div><div className="eyebrow">СЛОВА, К КОТОРЫМ ХОЧЕТСЯ ВЕРНУТЬСЯ</div><h1>Словарик</h1><p>{words.length ? `Выучено ${words.filter((word) => word.learned).length} из ${words.length}` : "Сохраняй новые слова прямо во время чтения."}</p></div><span className="heading-emblem"><Languages size={22} /></span></div>
    {words.length > 0 ? <><div className="filter-chips">{([["all", `Все · ${words.length}`], ["new", "Новые"], ["learned", "Выученные"]] as const).map(([key, label]) => <button key={key} className={`filter-chip ${filter === key ? "active" : ""}`} onClick={() => setFilter(key)}>{label}</button>)}</div><div className="word-list">{visible.map((word) => <article className={`word-card ${word.learned ? "learned" : ""}`} key={word.id}><button className="word-main" onClick={() => onToggle(word)}><span className="word-check">{word.learned && <Check size={14} />}</span><span><strong>{word.word}</strong><small>{word.bookTitle}</small></span></button><p>{word.definition}</p><button className="word-delete" onClick={() => onDelete(word)} title="Удалить"><Trash2 size={16} /></button></article>)}</div></> : <div className="recommend-empty"><Languages size={22} /><strong>Здесь будут сохранённые слова</strong><p>Выдели слово в тексте книги и выбери «Сохранить в словарик».</p></div>}
  </div>;
}

function Statistics({ books, days, settings, onGoalChange }: { books: Book[]; days: ReadingDay[]; settings: ReaderSettings; onGoalChange: (goal: number) => void }) {
  const today = days.find((day) => day.date === todayKey())?.seconds ?? 0;
  const total = days.reduce((sum, day) => sum + day.seconds, 0);
  const readBooks = books.filter((book) => book.position >= 0.95).length;
  let streak = 0;
  const byDate = new Map(days.map((day) => [day.date, day]));
  for (let offset = 0; offset < 370; offset++) {
    const date = new Date(); date.setDate(date.getDate() - offset);
    const key = date.toISOString().slice(0, 10);
    const day = byDate.get(key);
    if (day && day.seconds >= 60) streak++;
    else if (offset === 0) continue;
    else break;
  }
  const recentDays = Array.from({ length: 28 }, (_, index) => { const date = new Date(); date.setDate(date.getDate() - (27 - index)); const key = date.toISOString().slice(0, 10); return byDate.get(key) ?? { date: key, seconds: 0, chars: 0 }; });
  const max = Math.max(3600, ...recentDays.map((day) => day.seconds));
  const goal = settings.dailyGoal;
  const progress = goal ? Math.min(1, today / (goal * 60)) : 0;
  return <div className="content-width subpage"><div className="page-heading"><div><div className="eyebrow">ТВОЙ РИТМ, БЕЗ ГОНКИ</div><h1>Чтение</h1><p>История привычки, а не соревнование.</p></div><span className="heading-emblem"><Flame size={22} /></span></div>
    <div className="stats-overview"><div className="today-ring-wrap"><svg viewBox="0 0 120 120" className="today-ring"><circle cx="60" cy="60" r="49" className="ring-track" /><circle cx="60" cy="60" r="49" className="ring-progress" style={{ strokeDasharray: 308, strokeDashoffset: 308 * (1 - progress) }} /></svg><div className="ring-label"><strong>{Math.floor(today / 60)}</strong><span>минут сегодня</span></div></div><div className="today-copy"><span className="eyebrow">{goal ? "ЦЕЛЬ НА СЕГОДНЯ" : "СЕГОДНЯ"}</span><h2>{goal ? `${Math.floor(today / 60)} из ${goal} минут` : "Читай в своём темпе"}</h2><p>{goal ? (progress >= 1 ? "Цель на сегодня выполнена. Отличная работа." : "Каждая прочитанная страница укрепляет привычку.") : "Поставь себе комфортную цель или оставь чтение свободным."}</p><div className="goal-control"><label htmlFor="goal-select">Цель на день</label><select id="goal-select" value={goal} onChange={(event) => onGoalChange(Number(event.target.value))}><option value={0}>Без цели</option><option value={10}>10 минут</option><option value={20}>20 минут</option><option value={30}>30 минут</option><option value={45}>45 минут</option><option value={60}>1 час</option></select></div></div></div>
    <div className="stat-cards"><div className="stat-card"><span className="stat-icon"><Flame size={17} /></span><strong>{streak}</strong><span>дней подряд</span></div><div className="stat-card"><span className="stat-icon"><Clock3 size={17} /></span><strong>{Math.floor(total / 3600)}<small> ч </small>{Math.floor((total % 3600) / 60)}<small> мин</small></strong><span>всего за всё время</span></div><div className="stat-card"><span className="stat-icon"><BookOpen size={17} /></span><strong>{readBooks}</strong><span>книг дочитано</span></div></div>
    <section className="chart-card"><div className="chart-heading"><div><h2>Последние 4 недели</h2><p>Минуты чтения по дням</p></div><span className="chart-legend"><i /> Чтение</span></div><div className="heatmap">{recentDays.map((day) => <div className="heat-cell" key={day.date} title={`${day.date}: ${Math.round(day.seconds / 60)} мин`} style={{ opacity: day.seconds ? 0.25 + Math.min(0.75, day.seconds / max) : 0.1 }} />)}</div><div className="heat-labels"><span>4 недели назад</span><span>Сегодня</span></div></section>
  </div>;
}

function Settings({ settings, onChange, account, onAccount, onStats }: { settings: ReaderSettings; onChange: (change: Partial<ReaderSettings>) => void; account: CloudAccount | null; onAccount: () => void; onStats: () => void }) {
  const [systemDark,setSystemDark]=useState(()=>matchMedia("(prefers-color-scheme: dark)").matches);
  useEffect(()=>{const media=matchMedia("(prefers-color-scheme: dark)"); const update=()=>setSystemDark(media.matches); media.addEventListener("change",update); return ()=>media.removeEventListener("change",update);},[]);
  const controls = <div className="settings-section"><h2>Синхронизация</h2>{(["autoSync", "syncDeletions"] as const).map(key => <div className="setting-row" key={key}><span className="setting-copy"><strong>{key === "autoSync" ? "Автоматическая синхронизация" : "Удалять книги на всех устройствах"}</strong><small>{key === "autoSync" ? "Обновлять книги и место чтения при подключении" : "Если отключено, книга удаляется только на этом устройстве"}</small></span><button role="switch" aria-checked={settings[key]} aria-label={key === "autoSync" ? "Автоматическая синхронизация" : "Удалять книги на всех устройствах"} className={`switch ${settings[key] ? "on" : ""}`} onClick={() => onChange({[key]: !settings[key]})}><span /></button></div>)}</div>;
  const appearanceFallback=<div className="settings-section"><h2>Интерфейс</h2>
    <label>Тема приложения<select value={settings.themeMode} onChange={event=>onChange({themeMode:event.target.value as ReaderSettings["themeMode"]})}><option value="SYSTEM">Как в системе</option><option value="LIGHT">Светлая</option><option value="DARK">Тёмная</option></select></label>
    <label>Акцентный цвет<select value={settings.accentChoice} onChange={event=>onChange({accentChoice:event.target.value as ReaderSettings["accentChoice"]})}>{[["DEFAULT","Фирменный"],["BLUE","Синий"],["GREEN","Зелёный"],["VIOLET","Фиолетовый"],["AMBER","Янтарный"],["TEAL","Бирюзовый"]].map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
    <button onClick={onStats}>Статистика и цель дня</button>
    <button role="switch" aria-checked={settings.showStats} onClick={()=>onChange({showStats:!settings.showStats})}>Статистика чтения</button></div>;
  return <div className="content-width settings-page"><div className="page-heading"><div><h1>Настройки</h1></div></div>
    <AccountSummary account={account} onOpen={onAccount} onSync={onAccount} />
    <CommonSyncSettings settings={settings} onChange={(key,value)=>onChange({[key]:value})} fallback={controls}/>
    <CommonWorkspacePanel kind="appearance" state={{themeMode:settings.themeMode,accentChoice:settings.accentChoice,
      dark:settings.themeMode==="SYSTEM"?systemDark:settings.themeMode==="DARK",showStats:settings.showStats,goal:settings.dailyGoal,version:__APP_VERSION__}}
      onAction={(key,value)=>{
        if(key==="stats") onStats();
        if(key==="themeMode"&&["SYSTEM","LIGHT","DARK"].includes(value)) onChange({themeMode:value as ReaderSettings["themeMode"],darkApp:value==="DARK"});
        if(key==="accentChoice"&&["DEFAULT","BLUE","GREEN","VIOLET","AMBER","TEAL"].includes(value)) onChange({accentChoice:value as ReaderSettings["accentChoice"]});
        if(key==="showStats") onChange({showStats:value==="true"});
      }} fallback={appearanceFallback}/>
  </div>;
}

function readingAppearanceState(settings: ReaderSettings, pdf: boolean) {
  return {
    selections: [
      ...pdf?[]:[{key:"readingMode",title:"Режим чтения",value:settings.readingMode,choices:[{id:"pages",label:"Страницы"},{id:"scroll",label:"Прокрутка"}]}],
      {key:"pageTheme",title:"Цвет страницы",value:settings.pageTheme,choices:[
        ...Object.entries(pagePalettes).map(([id,value])=>({id,label:value.label,color:value.bg}))]},
      ...pdf ? [] : [{key:"fontFamily",title:"Шрифт",value:settings.fontFamily,choices:[
        ...Object.entries(readerFonts).map(([id,value])=>({id,label:value.label}))]}],
      ...pdf ? [] : [{key:"pageAnimation",title:"Перелистывание",value:settings.pageAnimation,choices:[
        {id:"slide",label:"Сдвиг"},{id:"fade",label:"Затухание"},{id:"flip",label:"Поворот"},{id:"none",label:"Без анимации"}]}]
    ],
    numbers: pdf ? [{key:"pdfZoom",title:"Масштаб PDF",value:settings.pdfZoom,min:1,max:4,step:.25}] : [
      {key:"fontSize",title:"Размер",value:settings.fontSize,min:12,max:32,step:1},
      {key:"lineHeight",title:"Интервал",value:settings.lineHeight,min:1.2,max:2.5,step:.1},
      {key:"margin",title:"Поля",value:settings.margin,min:8,max:64,step:4}
    ], toggles: [...pdf?[]:[{key:"justify",title:"По ширине страницы",value:settings.justify}]]
  };
}


function Reader({ book, settings, onBack, onSave, onSaveWord, onReadSeconds, onSettingsChange }: {
  book: Book; settings: ReaderSettings; onBack: () => void; onSave: (book: Book) => void | Promise<void>;
  onSaveWord: (word: string) => void; onReadSeconds: (id: string, seconds: number, chars: number) => void;
  onSettingsChange: (change: Partial<ReaderSettings>) => void;
}) {
  const [page, setPage] = useState(Math.max(1, book.page || 1));
  const [pages, setPages] = useState(book.pages || 0);
  const [showToc, setShowToc] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [showAudio,setShowAudio]=useState(false);
  const [readerNotice, setNoticeFromReader] = useState("");
  const audio=useReaderAudio(setIsSpeaking,setNoticeFromReader);
  const [selectedText, setSelectedText] = useState("");
  const selectedContext=useRef({contextBefore:'',contextAfter:''});
  const selectedPdfPage=useRef<number|null>(null);
  const [pdfText,setPdfText]=useState("");
  const [toc, setToc] = useState<{ label: string; href: string }[]>([]);
  const bookRef = useRef(book); bookRef.current = book;
  const readerController = useRef<SharedReaderController | null>(null);
  const [noteText, setNoteText] = useState("");
  const startRef = useRef(Date.now());
  const readSecondsRef = useRef(onReadSeconds);
  readSecondsRef.current = onReadSeconds;

  useEffect(() => {
    setPage(Math.max(1, book.page || 1)); setPages(book.pages || 0);
    startRef.current = Date.now();
    return () => { window.speechSynthesis?.cancel(); };
  }, [book.id]);

  const leaveReader = async () => {
    await readerController.current?.flush();
    onBack();
  };
  // Reader popovers share one slot: no overlapping settings/audio/contents.
  const toggleSettings = () => { setShowSettings(value => !value); setShowToc(false); setShowAudio(false); };
  const toggleToc = () => { setShowToc(value => !value); setShowSettings(false); setShowAudio(false); };
  const dismissReaderPanel = () => {
    if (document.querySelector('[role="dialog"]')) return;
    if (showSettings || showAudio || showToc || selectedText) {
      const trigger = showSettings ? 'Настройки чтения' : showAudio ? 'Слушать' : showToc ? 'Оглавление' : '';
      setShowSettings(false); setShowAudio(false); setShowToc(false); setSelectedText("");
      if (trigger) requestAnimationFrame(() => document.querySelector<HTMLButtonElement>(`button[aria-label="${trigger}"]`)?.focus({preventScroll:true}));
    } else void leaveReader();
  };
  const saveSharedPosition = async (place: SharedReaderPlace) => {
    const latest = bookRef.current;
    const updated: Book = {...latest, page: place.globalPage, pages: place.pages,
      position: place.moved ? place.fraction : latest.position,
      progressUpdatedAt: place.moved ? Date.now() : latest.progressUpdatedAt,
      cloudPlace: {platform: "web", segment: place.segment, page: place.page, character: place.character,
        layout: place.layout, scrollY: place.scrollY, anchor: place.anchor}};
    // Consecutive bridge events must not rebuild a record from an older React render.
    bookRef.current = updated;
    await onSave(updated);
  };

  useEffect(() => {
    const timer = window.setInterval(() => {
      const elapsed = Math.floor((Date.now() - startRef.current) / 1000);
      if (elapsed >= 60) {
        readSecondsRef.current(book.id, 60, 1000);
        startRef.current = Date.now();
      }
    }, 15_000);
    return () => window.clearInterval(timer);
  }, [book.id]);

  useEffect(() => {
    const font = (event: Event) => {
      const delta = (event as CustomEvent<number>).detail;
      onSettingsChange({ fontSize: Math.max(14, Math.min(36, settings.fontSize + delta)) });
    };
    window.addEventListener("shelest-font", font);
    return () => window.removeEventListener("shelest-font", font);
  }, [onSettingsChange, settings.fontSize]);

  const saveProgress = (nextPage: number, totalPages: number, cfi?: string, anchor?: string, epubPercent?: number) => {
    const latest = bookRef.current;
    const position = totalPages ? Math.min(0.999, Math.max(0, (nextPage - 1) / totalPages)) : book.position;
    const moved = nextPage !== latest.page || (cfi !== undefined && cfi !== latest.cloudPlace?.cfi);
    onSave({ ...latest, page: nextPage, pages: totalPages, position: epubPercent ?? position, lastOpenedAt: Date.now(),
      progressUpdatedAt: moved ? Date.now() : latest.progressUpdatedAt,
      cloudPlace: { platform: "web", segment: 0, page: nextPage - 1, cfi, anchor: anchor ?? latest.cloudPlace?.anchor } });
  };
  const goPage = (direction: -1 | 1) => {
    if (book.format !== "PDF") { readerController.current?.turn(direction); return; }
    const step=1;
    setPage(current => { const next = Math.max(1, Math.min(pages || book.pages, current + direction*step));
      saveProgress(next, pages || book.pages); return next; });
  };
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (document.querySelector('[role="dialog"]')) return;
      if (event.key === "Escape") { event.preventDefault(); dismissReaderPanel(); return; }
      if ((event.target as HTMLElement)?.closest("input,textarea,select,[contenteditable=true]")) return;
      if (showSettings || showAudio || showToc) return;
      if (event.key === "ArrowRight" || event.key === "PageDown") goPage(1);
      if (event.key === "ArrowLeft" || event.key === "PageUp") goPage(-1);
    };
    window.addEventListener("keydown", listener);
    return () => window.removeEventListener("keydown", listener);
  });
  useEffect(() => {
    const select = (event: Event) => setSelectedText((event as CustomEvent<string>).detail);
    window.addEventListener("shelest-selection", select);
    return () => window.removeEventListener("shelest-selection", select);
  }, []);

  const speakText = (speech: string) => {
    if (!("speechSynthesis" in window) || !speech) return;
    audio.speak(speech,settings.speechRate);
  };
  const speak = () => {
    setShowAudio(true);
    setShowSettings(false); setShowToc(false);
    if (isSpeaking) { audio.stop(); return; }
    if (selectedText && book.format !== "PDF") readerController.current?.speech(true);
    else if (selectedText) speakText(selectedText);
    else if (book.format !== "PDF") readerController.current?.speech();
    else if(pdfText) speakText(pdfText);
    else setNoticeFromReader("В PDF нет текстового слоя. Для сканов требуется распознавание текста.");
  };

  const saveNote = (color?:number) => {
    if (!selectedText) return;
    try {
      const notes=textNotes(book.id);
      notes.push({ quote: selectedText, note: noteText.trim(), segment: bookRef.current.cloudPlace?.segment,color,kind:color===undefined?"note":"highlight",pageIndex:book.format==='PDF'?(selectedPdfPage.current??page)-1:undefined,...(book.format==='PDF'?{}:selectedContext.current) });
      saveTextNotes(bookRef.current,notes);
    } catch {setNoticeFromReader("Не удалось сохранить пометку: проверьте свободное место в браузере");return;}
    window.dispatchEvent(new Event("shelest-notes-changed"));
    setSelectedText(""); setNoteText(""); setNoticeFromReader(color===undefined?"Заметка сохранена":"Выделение сохранено");
  };
  useEffect(() => { if (!readerNotice) return; const timer = window.setTimeout(() => setNoticeFromReader(""), 2500); return () => window.clearTimeout(timer); }, [readerNotice]);

  return <div className={`reader-shell page-${settings.pageTheme}`} style={{"--reader-bg":pagePalettes[settings.pageTheme].bg,"--reader-text":pagePalettes[settings.pageTheme].fg} as React.CSSProperties}>
    <CommonWorkspacePanel kind="reader-header" state={{title:book.title,author:book.author||book.format,actions:[
      {id:"toc",label:"Оглавление",active:showToc,enabled:book.format!=="PDF"&&toc.length>0},
      {id:"tts",label:"Слушать",active:isSpeaking,enabled:true},
      {id:"settings",label:"Настройки чтения",active:showSettings}
    ]}} onAction={action=>{
      if(action==="back") void leaveReader();
      if(action==="toc") toggleToc();
      if(action==="note") void saveNote();
      if(action==="dictionary") { if(selectedText) onSaveWord(selectedText); else setSelectedText(window.getSelection()?.toString().trim()??""); }
      if(action==="tts") speak();
      if(action==="settings") toggleSettings();
    }} fallback={<header className="reader-toolbar common-reader-header">
      <button className="reader-tool reader-back" aria-label="Назад" onClick={() => void leaveReader()}><ArrowLeft size={19} /></button>
      <div className="reader-book-label"><b>{book.title}</b><small>{book.author || book.format}</small></div>
      <div className="reader-actions">
        <button className={`reader-tool ${showToc ? "active" : ""}`} disabled={book.format==="PDF"||!toc.length} onClick={toggleToc} aria-label="Оглавление"><List size={18} /></button>
        <button className={`reader-tool ${isSpeaking ? "active" : ""}`} onClick={speak} aria-label="Слушать"><Headphones size={18} /></button>
        <button className={`reader-tool ${showSettings ? "active" : ""}`} onClick={toggleSettings} aria-label="Настройки чтения"><Settings2 size={18} /></button>
      </div>
    </header>}/>
    <div className="reader-layout">
      {showToc && <aside className="reader-toc" aria-label="Содержание книги"><div className="toc-title"><strong>Содержание</strong><button aria-label="Закрыть содержание" onClick={() => setShowToc(false)}><X size={16} /></button></div>{toc.length ? toc.map((entry, index) => <button key={index} className="toc-entry" onClick={() => { readerController.current?.jump(entry.href); setShowToc(false); }}>{entry.label}</button>) : <div className="toc-empty">В этой книге нет встроенного оглавления.</div>}</aside>}
      <div className="reader-center">
        {showAudio&&<ReaderAudio audio={audio} rate={settings.speechRate} active={isSpeaking} onRate={value=>onSettingsChange({speechRate:value})} onPlay={speak} onClose={()=>{audio.stop();setShowAudio(false);}}/>}
        {book.format === "PDF" ? <PdfViewport book={book} settings={settings} page={page} onSelection={(text,selectedPage)=>{selectedPdfPage.current=selectedPage??page;setSelectedText(text);}} onText={setPdfText} onNotice={setNoticeFromReader} onPage={(next, total) => { setPage(next); setPages(total); saveProgress(next, total); }} />
          : <SharedReaderViewport book={book} settings={settings} controller={readerController}
              onToc={setToc} onCount={(next, total) => {setPage(next); setPages(total);}}
              onPlace={saveSharedPosition} onSelection={(text,context)=>{selectedContext.current=context??{contextBefore:'',contextAfter:''};setSelectedText(text);}} onSpeech={speakText}
              onEscape={dismissReaderPanel} onNotice={setNoticeFromReader} />}
        {selectedText && <div className="selection-popover"><span>«{selectedText.slice(0, 72)}{selectedText.length > 72 ? "…" : ""}»</span><button onClick={() => onSaveWord(selectedText)}><Languages size={14} />Слово</button><input aria-label="Заметка" placeholder="Заметка к отрывку…" value={noteText} onChange={(event) => setNoteText(event.target.value)} /><button onClick={()=>saveNote()}><Bookmark size={14} />Сохранить</button>{book.format!=="PDF"&&<div className="text-highlight-colors" role="group" aria-label="Цвет выделения">{[["Жёлтый",0xFFF6D365],["Зелёный",0xFFA6D8A8],["Голубой",0xFF9ECBF0],["Розовый",0xFFEEADC5]].map(([name,color])=><button key={name} aria-label={`Выделить: ${name}`} style={{background:`#${Number(color).toString(16).slice(2)}`}} onClick={()=>saveNote(Number(color))}/>)}</div>}<button className="selection-close" onClick={() => setSelectedText("")} aria-label="Закрыть"><X size={14} /></button></div>}
        {readerNotice && <div className="reader-toast">{readerNotice}</div>}
        {showSettings && <div className="reader-settings-popover" role="region" aria-label="Настройки чтения"><div className="popover-head"><strong>Настройки чтения</strong><button onClick={()=>setShowSettings(false)} aria-label="Закрыть настройки чтения"><X size={15}/></button></div>
          <CommonWorkspacePanel kind="reading" state={readingAppearanceState(settings,book.format==="PDF")} onAction={(key,value)=>{
            if(["fontSize","lineHeight","margin","pdfZoom"].includes(key)) onSettingsChange({[key]:Number(value)});
            if(key==="justify") onSettingsChange({justify:value==="true"});
            if(key==="pageTheme"&&value in pagePalettes) onSettingsChange({pageTheme:value as ReaderSettings["pageTheme"]});
            if(key==="fontFamily"&&value in readerFonts) onSettingsChange({fontFamily:value as ReaderSettings["fontFamily"]});
            if(key==="pageAnimation"&&["slide","fade","flip","none"].includes(value)) onSettingsChange({pageAnimation:value as ReaderSettings["pageAnimation"]});
            if(key==="readingMode"&&(value==="pages"||value==="scroll")) onSettingsChange({readingMode:value});
            if(key==="twoPage") onSettingsChange({twoPage:value==="true"});
          }} fallback={<div className="common-controls"><label>Размер текста<input type="range" min="12" max="32" value={settings.fontSize} onChange={event=>onSettingsChange({fontSize:Number(event.target.value)})}/></label><label>Межстрочный интервал<input type="range" min="1.2" max="2.5" step=".1" value={settings.lineHeight} onChange={event=>onSettingsChange({lineHeight:Number(event.target.value)})}/></label>
            <label>Цвет страницы<select value={settings.pageTheme} onChange={event=>onSettingsChange({pageTheme:event.target.value as ReaderSettings["pageTheme"]})}><option value="light">Белый</option><option value="sepia">Сепия</option><option value="dark">Тёмный</option></select></label>
            <label>Шрифт<select value={settings.fontFamily} onChange={event=>onSettingsChange({fontFamily:event.target.value as ReaderSettings["fontFamily"]})}><option value="serif">С засечками</option><option value="sans">Без засечек</option><option value="literata">Литературный</option></select></label></div>}/>
        </div>}
      </div>
    </div>
    <CommonWorkspacePanel kind="reader-pages" state={{page,total:pages,label:"Страница"}} onAction={action=>goPage(action==="previous"?-1:1)} fallback={<footer className="reader-footer"><button onClick={() => goPage(-1)} disabled={book.format !== "EPUB" && page <= 1}><ChevronLeft size={18} /><span>Назад</span></button><div className="reader-page-meter"><span>{"Страница"}</span><strong>{page}</strong>{pages > 0 && <><span>из</span><strong>{pages}</strong></>}</div><button onClick={() => goPage(1)}><span>Дальше</span><ChevronRight size={18} /></button></footer>}/>
  </div>;
}

function PdfViewport({ book, settings, page, onPage,onSelection,onNotice,onText,secondary=false,sharedLoading,sharedTools }: { book: Book; settings: ReaderSettings; page: number; onPage: (page: number, total: number) => void;onSelection:(text:string,page?:number)=>void;onNotice:(message:string)=>void;onText:(text:string)=>void;secondary?:boolean;sharedLoading?:{current:Promise<any>|null};sharedTools?:ReturnType<typeof usePdfTools> }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const host=useRef<HTMLDivElement>(null),textLayer=useRef<HTMLDivElement>(null);
  const [hostWidth,setHostWidth]=useState(0),[hostHeight,setHostHeight]=useState(0),[sheetSize,setSheetSize]=useState({width:600,height:800});
  const pdfRef = useRef<any>(null);
  const ownLoading=useRef<Promise<any>|null>(null),pdfLoading=sharedLoading??ownLoading;
  const ownTools=usePdfTools(),tools=sharedTools??ownTools;
  const spread=false;
  useEffect(()=>{if(!secondary) tools.setActivePage(page);},[page,secondary]);
  const activeRender = useRef<any>(null);
  const onPageRef = useRef(onPage);
  onPageRef.current = onPage;
  const [error, setError] = useState("");
  useEffect(()=>{
    if(!host.current) return;
    const observer=new ResizeObserver(()=>{setHostWidth(host.current?.clientWidth??0);setHostHeight(host.current?.clientHeight??0);});
    observer.observe(host.current);return ()=>observer.disconnect();
  },[]);
  useEffect(()=>{
    const select=()=>{const selection=window.getSelection();if(selection?.anchorNode&&textLayer.current?.contains(selection.anchorNode)) {tools.setActivePage(page);onSelection(selection.toString().trim().slice(0,10000),page);}};
    document.addEventListener("selectionchange",select);return ()=>document.removeEventListener("selectionchange",select);
  },[onSelection]);
  useEffect(() => () => {
    if(secondary) return;
    const loading = pdfLoading.current;
    pdfLoading.current = null;
    pdfRef.current = null;
    void loading?.then(pdf => pdf.destroy()).catch(() => undefined);
  }, [book.id]);
  useEffect(() => {
    let cancelled = false;
    let renderTask: any;
    let layerTask: any;
    const run = async () => {
      const pdfjs = await import("pdfjs-dist");
      pdfjs.GlobalWorkerOptions.workerSrc = pdfWorker;
      if (cancelled) return;
      pdfLoading.current ??= book.blob.arrayBuffer().then(data => pdfjs.getDocument({ data }).promise);
      const pdf = await pdfLoading.current;
      if (cancelled) return;
      pdfRef.current = pdf;
      if (cancelled) return;
      const actualPage = Math.max(1, Math.min(page, pdf.numPages));
      const pdfPage = await pdf.getPage(actualPage);
      if (cancelled) return;
      const canvas = canvasRef.current;
      const context = canvas?.getContext("2d");
      if (!canvas || !context) return;
      const parentWidth = hostWidth || 760;
      const baseViewport = pdfPage.getViewport({ scale: 1 });
      const sidebar=!secondary&&window.matchMedia('(min-width:1000px) and (pointer:fine)').matches?74:0;
      const desktop=window.matchMedia('(min-width:1000px) and (pointer:fine)').matches;
      const options=desktop?144:0;
      const available=parentWidth-sidebar-options;
      const availableHeight=hostHeight-(desktop?24:130);
      const scale = Math.max(.1,Math.min(1.35, (available - 24) / baseViewport.width,Math.max(100,availableHeight)/baseViewport.height)) * settings.pdfZoom;
      const viewport = pdfPage.getViewport({ scale });
      const previous = activeRender.current;
      if (previous) { previous.cancel(); await previous.promise.catch(() => undefined); }
      if (cancelled) return;
      canvas.width = viewport.width; canvas.height = viewport.height;
      setSheetSize({width:viewport.width,height:viewport.height});
      renderTask = pdfPage.render({ canvasContext: context, viewport });
      activeRender.current = renderTask;
      await renderTask.promise;
      if(cancelled) return;
      const text=await pdfPage.getTextContent();
      if(cancelled) return;
      onText(text.items.map((item:{str?:string;hasEOL?:boolean})=>item.str?(item.str+(item.hasEOL?"\n":" ")):"").join(""));
      if(textLayer.current) {
        textLayer.current.replaceChildren();
        textLayer.current.style.setProperty("--scale-factor",String(viewport.scale));
        layerTask=new pdfjs.TextLayer({textContentSource:text,container:textLayer.current,viewport});
        await layerTask.render();
      }
      if (!cancelled) { setError(""); onPageRef.current(actualPage, pdf.numPages); }
    };
    void run().catch(reason => { if (!cancelled) setError(reason instanceof Error ? reason.message : "Не удалось открыть PDF"); });
    return () => { cancelled = true; renderTask?.cancel();layerTask?.cancel(); };
  }, [book.id, page,hostWidth,hostHeight,settings.pdfZoom,spread]);
  return <div className={`pdf-reader-frame ${spread?'pdf-spread':''}`} ref={host}><PdfMarkup bookId={book.id} book={book} page={page} width={sheetSize.width} height={sheetSize.height} onNotice={onNotice} tools={tools} showToolbar={!secondary}>
    <canvas ref={canvasRef} style={{ filter: settings.pageTheme === "sepia" ? "sepia(.2)" : ["dark","midnight","black"].includes(settings.pageTheme) ? "invert(.88) hue-rotate(180deg)" : undefined }}/>
    <div className="textLayer" ref={textLayer}/>
    </PdfMarkup>{spread&&<PdfViewport book={book} settings={settings} page={page+1} onPage={()=>{}} onSelection={onSelection} onText={()=>{}} onNotice={onNotice} secondary sharedLoading={pdfLoading} sharedTools={tools}/>}{error && <div className="reader-toast" role="alert">{error}</div>}</div>;
}


export default App;
