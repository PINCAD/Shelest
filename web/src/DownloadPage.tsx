import { useEffect, useState } from "react";
import { ArrowDownToLine, ArrowLeft, BookOpen, Check, ShieldAlert } from "lucide-react";
import "./download.css";

type AndroidRelease = {
  versionName: string;
  versionCode: number;
  builtAtUtc: string;
  variant: "debug" | "release";
  file: string;
  sizeBytes: number;
  sha256: string;
};

const formatSize = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
const formatDate = (value: string) => {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : new Intl.DateTimeFormat("ru-RU", {
    day: "numeric", month: "long", year: "numeric", timeZone: "Europe/Moscow",
  }).format(parsed);
};

export default function DownloadPage() {
  const [release, setRelease] = useState<AndroidRelease | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    document.title = "Скачать Шелест для Android";
    const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    description?.setAttribute("content", "Скачайте читалку Шелест для Android с официального сайта.");
    let active = true;
    fetch("/downloads/latest.json", { cache: "no-store" })
      .then((response) => {
        if (!response.ok) throw new Error("Release information is unavailable");
        return response.json() as Promise<AndroidRelease>;
      })
      .then((data) => { if (active) setRelease(data); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, []);

  return (
    <div className="download-page">
      <header className="download-topbar">
        <a href="/" className="download-brand" aria-label="Шелест, на главную">
          <span className="download-brand-mark"><img src="/logo.jpg" alt="" /></span>
          <span><strong>Шелест</strong><small>читалка книг</small></span>
        </a>
        <a className="download-back" href="/"><ArrowLeft size={16} />В библиотеку</a>
      </header>

      <main className="download-content">
        <section className="download-hero">
          <div className="download-orbit download-orbit-one" />
          <div className="download-orbit download-orbit-two" />
          <div className="download-hero-copy">
            <span className="download-eyebrow"><span />ЧИТАЙТЕ В СВОЁМ РИТМЕ</span>
            <h1>Все ваши книги.<br /><em>В одном тихом месте.</em></h1>
            <p>Шелест помогает сосредоточиться на чтении: удобные настройки страницы, заметки, словарик и синхронизация между устройствами.</p>
            <div className="download-release-line">
              <span className="download-version">Android {release ? `v${release.versionName}` : ""}</span>
              {release && <><span className="download-dot">·</span><span>{formatSize(release.sizeBytes)}</span><span className="download-dot">·</span><span>{formatDate(release.builtAtUtc)}</span></>}
            </div>
            <a
              className="download-primary"
              href="/downloads/shelest-latest.apk"
              download={release ? `shelest-${release.versionName}.apk` : "shelest-android.apk"}
            >
              <ArrowDownToLine size={19} />
              <span>Скачать для Android</span>
            </a>
            <span className="download-requirement">Android 8.0 и новее · APK с официального сайта</span>
            {release?.variant === "debug" && (
              <div className="download-build-note"><ShieldAlert size={17} /><span>Предварительная сборка для установки вручную. Это не версия из Google Play.</span></div>
            )}
            {failed && <p className="download-meta-error" role="status">Не удалось загрузить сведения о версии. Ссылка на актуальный APK всё ещё доступна.</p>}
          </div>
          <div className="download-book-art" aria-hidden="true">
            <div className="download-book-shadow" />
            <div className="download-book">
              <div className="download-book-glow" />
              <BookOpen size={42} strokeWidth={1.15} />
              <span>Читайте<br />с удовольствием</span>
              <small>ШЕЛЕСТ</small>
            </div>
            <div className="download-spark download-spark-a" />
            <div className="download-spark download-spark-b" />
          </div>
        </section>

        <section className="download-details" aria-label="О приложении">
          <article className="download-detail-card">
            <span className="download-detail-icon"><BookOpen size={19} /></span>
            <div><strong>Поддерживаемые книги</strong><p>EPUB, FB2, PDF, TXT и другие популярные форматы.</p></div>
          </article>
          <article className="download-detail-card">
            <span className="download-detail-icon"><Check size={19} /></span>
            <div><strong>Ваш прогресс остаётся с вами</strong><p>Войдите в аккаунт, чтобы синхронизировать библиотеку и место чтения.</p></div>
          </article>
          <article className="download-detail-card download-checksum-card">
            <span className="download-detail-icon"><ShieldAlert size={19} /></span>
            <div><strong>Проверка файла</strong><p>{release ? `SHA-256: ${release.sha256}` : "Контрольная сумма появится вместе со сведениями о сборке."}</p></div>
          </article>
        </section>

        <section className="download-install">
          <div><span className="download-eyebrow">УСТАНОВКА</span><h2>Пара простых шагов</h2></div>
          <ol>
            <li><span>1</span><div><strong>Скачайте APK</strong><p>Нажмите кнопку и дождитесь окончания загрузки файла.</p></div></li>
            <li><span>2</span><div><strong>Откройте загруженный файл</strong><p>Android попросит разрешить установку приложений из браузера. Подтвердите её для этого источника.</p></div></li>
            <li><span>3</span><div><strong>Установите обновление поверх текущего приложения</strong><p>Не удаляйте старую версию: так сохранятся локальные книги, заметки и настройки.</p></div></li>
          </ol>
        </section>

        <footer className="download-footer">
          <span>Шелест · пространство для чтения</span>
          <a href="/">Открыть веб-версию</a>
        </footer>
      </main>
    </div>
  );
}
