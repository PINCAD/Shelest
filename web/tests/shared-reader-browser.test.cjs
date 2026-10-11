// A separate headless browser profile and synthetic books only; no phone/server/account access.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const JSZip = require('../node_modules/jszip');
const root = path.resolve(__dirname, '../..');
const address = process.env.READER_TEST_URL || 'http://127.0.0.1:5180';

async function geometry(frame) {
  return frame.evaluate(() => {
    const mask = document.getElementById('mask'), box = document.getElementById('container');
    const lefts = new Set();
    for (const p of box.querySelectorAll('p')) for (const r of p.getClientRects()) {
      if (r.right > 1 && r.left < mask.getBoundingClientRect().width - 1) lefts.add(Math.round(r.left * 100) / 100);
    }
    return {page: currentPage, pages: totalPages, character: currentChar(), step: pageWidth,
      offset: mask.scrollLeft, width: mask.getBoundingClientRect().width,
      lefts: [...lefts], margin: parseFloat(getComputedStyle(box).paddingLeft)};
  });
}
async function aligned(page, label, allowIllustration = false) {
  const iframe = page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)');
  await iframe.waitFor(); const frame = await (await iframe.elementHandle()).contentFrame();
  await frame.waitForFunction(() => typeof paged !== 'undefined' && paged);
  const g = await geometry(frame);
  assert.ok((allowIllustration || g.lefts.length) && g.lefts.every(left => Math.abs(left - g.margin) < 2), `${label}: one column ${JSON.stringify(g)}`);
  assert.ok(Math.abs(g.offset - g.page * g.step) < 1, `${label}: exact offset`);
  assert.ok(Math.abs(g.width - g.step) < .01, `${label}: exact viewport step`);
  return {frame, geometry: g};
}
async function seed(page, fileName, bytes, place) {
  return page.evaluate(async ({fileName, encoded, place}) => {
    const {importBook} = await import('/src/importBook.ts');
    const {localStore} = await import('/src/store.ts');
    const bytes = Uint8Array.from(atob(encoded), char => char.charCodeAt(0));
    const book = await importBook(new File([bytes], fileName));
    if (place) { book.cloudPlace = place; book.page = 99; book.position = .4; book.progressUpdatedAt = 1770000000000; }
    await localStore.saveBook(book); return {id: book.id, title: book.title};
  }, {fileName, encoded: Buffer.from(bytes).toString('base64'), place});
}
async function saved(page, id) {
  return page.evaluate(async id => {
    const {localStore} = await import('/src/store.ts');
    return (await localStore.books()).find(book => book.id === id);
  }, id);
}
async function open(page, title, allowIllustration = false) {
  await page.reload();
  await page.locator('.book-title').filter({hasText: title}).first().click();
  return aligned(page, 'opening', allowIllustration);
}

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.BROWSER_EXECUTABLE});
  try {
    const page = await browser.newPage({viewport: {width: 1200, height: 900}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    if (process.env.TRACE_READER) page.on('console', message => console.log('browser:', message.text()));
    await page.route('**/api/**', route => route.fulfill({status: 401, contentType: 'application/json', body: '{"error":"test guest"}'}));
    await page.goto(address);
    const sanitized = await page.evaluate(async () => {
      const {sanitizeBookHtml} = await import('/src/readerPublication.ts');
      const body = new DOMParser().parseFromString(`<p onclick="parent.localStorage.clear()" style="background:url(https://evil.invalid/x)">Безопасный текст</p><script>parent.localStorage.clear()</script><svg onload="alert(1)"></svg><iframe src="https://evil.invalid"></iframe><img src="data:image/svg+xml;base64,abc"><a href="javascript:alert(1)">Ссылка</a>`, 'text/html').body;
      return sanitizeBookHtml(body, async src => src);
    });
    assert.equal(sanitized.html, '<p>Безопасный текст</p><a>Ссылка</a>', 'active book markup removed before entering the sandbox');
    const text = Array.from({length: 100}, (_, p) => Array.from({length: 65}, (_, w) => `слово${p}номер${w}`).join(' ')).join('\n\n');
    const txt = await seed(page, 'Общий движок TXT.txt', Buffer.from(text));
    let current = await open(page, txt.title);
    await page.waitForFunction(() => document.querySelector('.reader-page-meter')?.textContent?.includes('из'));
    const initialPage = current.geometry.page;
    await page.locator('.reader-footer button').last().click();
    await page.waitForTimeout(750);
    current = await aligned(page, 'TXT page turn');
    assert.equal(current.geometry.page, initialPage + 1);
    const before = await saved(page, txt.id);
    assert.ok(before.cloudPlace.anchor.length >= 12);
    assert.ok(before.progressUpdatedAt > 1770000000000, 'a turn is a reading event');
    await page.locator('.reader-back').click();
    await page.locator('.book-title').filter({hasText: txt.title}).first().click();
    current = await aligned(page, 'TXT exact reopening');
    assert.equal(current.geometry.page, before.cloudPlace.page);
    assert.equal(current.geometry.character, before.cloudPlace.character);
    await page.setViewportSize({width: 633, height: 780});
    await page.waitForTimeout(200);
    await aligned(page, 'fractional viewport resize');
    await page.getByTitle('Настройки чтения', {exact: true}).click();
    await page.getByRole('button',{name:'Увеличить: Размер',exact:true}).click();
    await page.waitForTimeout(250); await aligned(page, 'live font change');
    await page.locator('.popover-head button').click();
    await page.locator('.reader-back').click();
    const foreignAnchor = text.split('\n\n')[65].replace(/\s/g, '').slice(0, 160);
    const foreign = await seed(page, 'Облачное место TXT.txt', Buffer.from(text), {platform:'android', segment: 999, page: 999,
      character: 9999999, anchor: foreignAnchor, layout: 'different-platform'});
    current = await open(page, foreign.title);
    await page.waitForTimeout(400);
    const restored = await saved(page, foreign.id);
    assert.equal(restored.progressUpdatedAt, 1770000000000, 'opening a synced place is not newer reading');
    const targetInPage = await current.frame.evaluate(anchor => {
      const t = container.textContent, normalized = t.replace(/\s/g, '');
      const found = normalized.indexOf(anchor); let count = 0, raw = 0;
      for (; raw < t.length; raw++) { if (!/\s/.test(t[raw]) && count++ === found) break; }
      return pageOfChar(raw) === currentPage;
    }, foreignAnchor);
    assert.equal(targetInPage, true, 'Android anchor found and visible, foreign page ignored');
    await page.locator('.reader-back').click();
    const invalid = await seed(page, 'Отсутствующий якорь.txt', Buffer.from(text), {platform:'android', segment: 1, page: 22,
      character: 99, anchor: 'Этогоотрывканетвданнойтестовойкниге', layout: 'foreign'});
    await page.reload();
    await page.locator('.book-title').filter({hasText: invalid.title}).first().click();
    await page.getByRole('alert').filter({hasText: 'не найдено'}).waitFor();
    assert.equal((await saved(page, invalid.id)).progressUpdatedAt, 1770000000000, 'failed restore preserves cloud progress');
    await page.locator('.reader-back').click();
    await page.setViewportSize({width: 1200, height: 900});

    const epub = await seed(page, 'synthetic.epub', fs.readFileSync(path.join(root, 'web/tests/reader-fixture.epub')));
    current = await open(page, epub.title);
    await page.waitForFunction(() => document.querySelector('.reader-page-meter')?.textContent?.includes('из'));
    await page.getByTitle('Оглавление', {exact: true}).click();
    await page.locator('.toc-entry').filter({hasText: 'Вторая глава'}).click();
    await page.waitForTimeout(750); current = await aligned(page, 'EPUB second chapter');
    assert.equal(await current.frame.evaluate(() => positionSegment), 1);
    await page.locator('.reader-footer button').first().click();
    await page.waitForTimeout(850); current = await aligned(page, 'EPUB preceding chapter boundary');
    assert.equal(await current.frame.evaluate(() => positionSegment), 0);
    assert.equal(current.geometry.page, current.geometry.pages - 1, 'preceding chapter opens on its last page');
    await page.locator('.reader-footer button').last().click();
    await page.waitForTimeout(850); current = await aligned(page, 'EPUB next chapter boundary');
    assert.equal(await current.frame.evaluate(() => positionSegment), 1);
    assert.equal(current.geometry.page, 0);
    await page.locator('.reader-footer button').last().click();
    await page.waitForTimeout(750); current = await aligned(page, 'EPUB turn');
    const epubBefore = await saved(page, epub.id);
    await page.locator('.reader-back').click();
    await page.locator('.book-title').filter({hasText: epub.title}).first().click();
    current = await aligned(page, 'EPUB exact reopening');
    assert.equal(await current.frame.evaluate(() => positionSegment), epubBefore.cloudPlace.segment);
    assert.equal(current.geometry.page, epubBefore.cloudPlace.page);
    await current.frame.evaluate(() => {
      const p = [...container.querySelectorAll('p')].find(el => {
        const r = el.getBoundingClientRect(); return r.left > 0 && r.left < innerWidth;
      });
      const range = document.createRange(); range.selectNodeContents(p);
      getSelection().removeAllRanges(); getSelection().addRange(range);
    });
    await page.locator('.selection-popover input').fill('Общая заметка');
    await page.locator('.selection-popover button').filter({hasText:'Сохранить'}).click();
    await current.frame.waitForFunction(() => document.querySelectorAll('.shelest-ann').length > 0);

    const legacyCfi = await page.evaluate(async () => {
      const {localStore} = await import('/src/store.ts');
      const {default:ePub} = await import('/node_modules/.vite/deps/epubjs.js');
      const book = (await localStore.books()).find(book => book.format === 'EPUB');
      const publication = ePub(await book.blob.arrayBuffer());
      try {
        await publication.ready;
        const section = publication.spine.spineItems[1];
        const content = await section.load(publication.load.bind(publication));
        const paragraph = content.ownerDocument.querySelectorAll('p')[12];
        const range = content.ownerDocument.createRange(); range.selectNodeContents(paragraph); range.collapse(true);
        const cfi = section.cfiFromRange(range);
        await localStore.saveBook({...book, cloudPlace:{platform:'web',cfi}, progressUpdatedAt:1770000000000});
        return cfi;
      } finally {publication.destroy();}
    });
    assert.ok(legacyCfi.startsWith('epubcfi('));
    current = await open(page, epub.title);
    assert.equal(await current.frame.evaluate(() => positionSegment), 1, 'legacy EPUB CFI migrated to shared-engine chapter');
    assert.ok(current.geometry.page > 0, 'legacy EPUB place not reset to chapter start');
    assert.equal((await saved(page, epub.id)).progressUpdatedAt, 1770000000000);

    await page.locator('.reader-back').click();
    const zip = await JSZip.loadAsync(fs.readFileSync(path.join(root, 'web/tests/reader-fixture.epub')));
    zip.file('book.opf', (await zip.file('book.opf').async('text')).replace('Проверка EPUB', 'Иллюстрированный EPUB'));
    const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jC1sAAAAASUVORK5CYII=', 'base64');
    zip.file('picture.png', image);
    zip.file('one.xhtml', (await zip.file('one.xhtml').async('text')).replace('<body>', '<body><img src="picture.png" width="64" height="64"/><script>parent.document.body.remove()</script><p onclick="parent.localStorage.clear()">Проверка защиты.</p>'));
    const illustrated = await seed(page, 'illustrated.epub', await zip.generateAsync({type:'nodebuffer'}));
    current = await open(page, illustrated.title, true);
    await current.frame.waitForFunction(() => document.querySelector('#container img')?.naturalWidth > 0, {timeout: 5000}).catch(async error => {
      console.log('Image diagnostics:', await current.frame.evaluate(() => ({html: container.innerHTML.slice(0, 500), img: container.querySelector('img')?.outerHTML,
        ready: container.querySelector('img')?.complete, width: container.querySelector('img')?.naturalWidth})));
      throw error;
    });
    assert.equal(await current.frame.evaluate(() => container.querySelectorAll('script,[onclick]').length), 0);
    assert.equal(await current.frame.evaluate(() => getSelection().rangeCount), 0);
    await page.locator('.reader-back').click();

    const comic = new JSZip(); comic.file('001.png', image); comic.file('002.png', image);
    const cbz = await seed(page, 'Тест комикса.cbz', await comic.generateAsync({type:'nodebuffer'}));
    await page.reload(); await page.locator('.book-title').filter({hasText: cbz.title}).first().click();
    const comicFrame = await (await page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)').elementHandle()).contentFrame();
    await comicFrame.waitForFunction(() => typeof paged !== 'undefined' && paged && document.querySelector('img')?.naturalWidth > 0);
    await page.locator('.reader-footer button').last().click();
    await page.waitForTimeout(500);
    const secondComicFrame = await (await page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)').elementHandle()).contentFrame();
    await secondComicFrame.waitForFunction(() => typeof paged !== 'undefined' && paged);
    assert.equal(await secondComicFrame.evaluate(() => positionSegment), 1, 'CBZ image pages use the shared engine too');

    const output = path.join(root, 'web/build/shared-reader'); fs.mkdirSync(output, {recursive: true});
    await page.screenshot({path: path.join(output, 'shared-engine-browser.png')});
    assert.deepEqual(errors, [], 'no JavaScript errors');
    console.log('Shared engine browser checks passed: TXT/EPUB/CBZ, exact reopening, foreign anchor, legacy CFI, resize/fonts, annotations, images, chapter boundaries, full totals, sandbox');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
