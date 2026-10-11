// Isolated synthetic library, mock API only. Can run against a production build.
const assert=require('node:assert/strict');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const address=process.env.UI_TEST_URL||'http://127.0.0.1:5180/';
function fixturePdf(count=1) {
  const stream='BT /F1 22 Tf 40 740 Td (Shelest PDF appearance test) Tj ET';
  const objects=['<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [3 0 R ${Array.from({length:count-1},(_,i)=>`${6+i*2} 0 R`).join(' ')}] /Count ${count} >>`,
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
  for(let p=1;p<count;p++) {
    const contents=objects.length+2,extra=stream.replace('test','test page '+(p+1));
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 5 0 R >> >> /Contents ${contents} 0 R >>`,`<< /Length ${extra.length} >>\nstream\n${extra}\nendstream`);
  }
  let text='%PDF-1.4\n'; const offsets=[0];
  objects.forEach((object,index)=>{offsets.push(text.length);text+=`${index+1} 0 obj\n${object}\nendobj\n`;});
  const xref=text.length;
  text+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n${offsets.slice(1).map(offset=>String(offset).padStart(10,'0')+' 00000 n \n').join('')}trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return Buffer.from(text);
}
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const page=await browser.newPage({viewport:{width:1100,height:850},reducedMotion:'reduce'}),errors=[];
    await page.addInitScript(()=>{
      window.__spoken=[];
      class Utterance {constructor(text){this.text=text;}}
      Object.defineProperty(window,'SpeechSynthesisUtterance',{value:Utterance});
      Object.defineProperty(window,'speechSynthesis',{value:{getVoices:()=>[],addEventListener(){},removeEventListener(){},cancel(){},pause(){},resume(){},speak(utterance){window.__spoken.push(utterance.text);setTimeout(()=>utterance.onend?.(),0);}}});
    });
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/api/**',route=>route.fulfill({status:401,json:{error:'test_guest'}}));
    await page.goto(address,{waitUntil:'networkidle'});
    await page.locator('[data-common-ui=library]').waitFor();
    const text=Array.from({length:80},(_,i)=>`Абзац ${i}. `+'Длинный текст для проверки чтения. '.repeat(50)).join('\n\n');
    await page.locator('input[type=file][multiple]').setInputFiles({name:'Общая библиотека.txt',mimeType:'text/plain',buffer:Buffer.from(text)});
    const shelf=page.locator('[data-common-ui=library]');
    await shelf.locator('.common-new-book').first().waitFor();
    assert.equal(await shelf.locator('.mini-progress').count(),0,'unopened books do not show reading progress');
    await shelf.getByRole('button',{name:'Открыть Общая библиотека',exact:true}).first().click({button:'right'});
    await page.getByRole('toolbar',{name:'Выбранные книги'}).getByRole('button',{name:'Переименовать',exact:true}).click();
    await page.getByLabel('Название книги',{exact:true}).fill('Переименованная книга');
    await page.getByRole('button',{name:'Сохранить',exact:true}).click();
    await page.getByRole('toolbar',{name:'Выбранные книги'}).getByRole('button',{name:'Удалить выбранные книги',exact:true}).click();
    await page.getByRole('dialog').waitFor();
    await page.screenshot({path:path.resolve(__dirname,'../build/design-delete-book-desktop.png')});
    await page.getByRole('dialog').getByRole('button',{name:'Отмена',exact:true}).click();
    await page.getByRole('toolbar',{name:'Выбранные книги'}).getByRole('button',{name:'В избранное',exact:true}).click();
    await page.getByRole('button',{name:'Отменить выделение',exact:true}).click();
    await shelf.locator('[aria-label="В избранном"]').first().waitFor();
    await shelf.getByRole('searchbox',{name:'Найти книгу'}).fill('Нет такой книги');
    await shelf.getByText('Ничего не найдено',{exact:true}).waitFor();
    await shelf.getByRole('searchbox',{name:'Найти книгу'}).fill('');
    await shelf.getByRole('combobox',{name:'Сортировка книг'}).selectOption('PROGRESS');
    await shelf.getByRole('button',{name:'Открыть Переименованная книга',exact:true}).first().click();
    await page.locator('[data-common-ui=reader-header]').waitFor();
    assert.equal(await page.locator('.reader-titlebar').count(),0,'book title is not duplicated in another toolbar');
    assert.equal(await page.locator('.reader-page-meter').count(),1,'only one page counter');
    await page.setViewportSize({width:320,height:850});
    assert.ok(await page.locator('.reader-back .common-tool-icon').isVisible(),'mobile back icon remains visible');
    const iframe=page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)'); await iframe.waitFor();
    const frame=await (await iframe.elementHandle()).contentFrame();
    await frame.waitForFunction(()=>typeof paged!=='undefined'&&paged&&document.querySelector('#container')?.textContent?.length>0);
    assert.ok(await frame.evaluate(()=>document.fonts.check('16px "Shelest-serif"')),'bundled font loads inside the isolated reader');
    assert.ok(await page.locator('[data-common-ui=reader-header]').evaluate(header=> {
      const bounds=header.getBoundingClientRect();
      return [...header.querySelectorAll('button')].every(button=> {
        const rect=button.getBoundingClientRect(); return rect.top>=bounds.top-1&&rect.bottom<=bounds.bottom+1&&rect.left>=0&&rect.right<=innerWidth+1;
      });
    }),'mobile reader controls stay inside the header');
    await page.screenshot({path:path.resolve(__dirname,'../build/common-reader-320.png')});
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.locator('[data-common-ui=reading-appearance]').waitFor();
    await page.getByRole('heading',{name:'Страница',exact:true}).waitFor();
    await page.getByRole('heading',{name:'Текст',exact:true}).waitFor();
    await page.getByRole('button',{name:'Увеличить: Размер',exact:true}).click();
    await page.getByRole('button',{name:'Цвет страницы: Сепия',exact:true}).click();
    await page.getByRole('button',{name:'Шрифт: Lora',exact:true}).click();
    await frame.waitForFunction(()=>document.fonts.check('16px "Shelest-lora"')&&getComputedStyle(document.querySelector('#container')).fontFamily.includes('Shelest-lora')).catch(async error=>{
      console.error('Font diagnostic',await frame.evaluate(()=>({family:getComputedStyle(document.querySelector('#container')).fontFamily,faces:[...document.fonts].map(f=>({family:f.family,status:f.status})),faceLength:document.querySelector('#reader-font-face')?.textContent?.length})));
      throw error;
    });
    await page.getByRole('button',{name:'Закрыть настройки чтения',exact:true}).click();
    await page.getByRole('button',{name:'Слушать',exact:true}).click();
    await page.getByRole('region',{name:'Озвучка текста',exact:true}).waitFor();
    await page.waitForFunction(()=>window.__spoken.join('').length>160);
    assert.ok(await page.evaluate(()=>window.__spoken.every(text=>text.length<=251)),'speech is queued in short chunks, without truncating the page');
    await page.getByRole('button',{name:'Закрыть озвучку',exact:true}).click();
    await page.locator('[data-common-ui=reader-pages]').waitFor();
    await page.getByRole('button',{name:'Дальше',exact:true}).click();
    await page.getByRole('button',{name:'Назад',exact:true}).first().click();
    await shelf.waitFor();
    await page.getByRole('button',{name:'Настройки',exact:true}).first().click();
    const appearance=page.locator('[data-common-ui=appearance]'); await appearance.waitFor();
    await page.locator('.common-version-footer').waitFor();
    assert.equal(await appearance.locator('.common-version-footer').count(),0,'version is outside the settings card');
    await appearance.getByRole('button',{name:'Тема приложения: Как в системе',exact:true}).click();
    await page.emulateMedia({colorScheme:'dark'});
    await page.waitForFunction(()=>document.documentElement.dataset.theme==='dark');
    await appearance.getByRole('button',{name:'Акцентный цвет: Зелёный',exact:true}).click();
    await page.waitForFunction(()=>getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()==='#8BCAA0');
    assert.equal(await appearance.getByRole('button',{name:'Сбросить акцентный цвет',exact:true}).count(),0);
    await appearance.getByRole('button',{name:'Акцентный цвет: Фирменный',exact:true}).click();
    await appearance.getByRole('button',{name:'Тема приложения: Тёмная',exact:true}).click();
    await page.reload({waitUntil:'networkidle'});
    assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'dark');
    await page.getByRole('button',{name:'Настройки',exact:true}).first().click();
    await appearance.waitFor();
    assert.equal(await appearance.getByRole('button',{name:'Тема приложения: Тёмная',exact:true}).getAttribute('aria-pressed'),'true');
    for(const width of [320,768,1440]) {
      await page.setViewportSize({width,height:850});
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`appearance overflow at ${width}`);
    }
    await page.setViewportSize({width:320,height:850});
    await page.screenshot({path:path.resolve(__dirname,'../build/common-appearance-320.png'),fullPage:true});
    await page.getByRole('button',{name:'Библиотека',exact:true}).last().click(); await shelf.waitFor();
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'library overflow at 320');
    await shelf.getByRole('button',{name:'Открыть Переименованная книга',exact:true}).first().click({button:'right'});
    assert.ok(await page.getByRole('toolbar',{name:'Выбранные книги'}).evaluate(menu=>{const r=menu.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth+1;}),'selection actions stay inside a narrow screen');
    await page.getByRole('button',{name:'Отменить выделение',exact:true}).click();
    await page.screenshot({path:path.resolve(__dirname,'../build/common-library-320.png'),fullPage:true});
    await shelf.getByRole('combobox',{name:'Сортировка книг'}).selectOption('RECENT');
    await page.locator('input[type=file][multiple]').setInputFiles([1,2,3].map(i=>({
      name:`Непрочитанная ${i}.txt`,mimeType:'text/plain',buffer:Buffer.from(`Книга ${i}. ${text}`)
    })));
    await shelf.getByRole('button',{name:'Открыть Непрочитанная 3',exact:true}).first().waitFor();
    const recentHeading=shelf.getByRole('heading',{name:'Недавние',exact:true}); await recentHeading.waitFor();
    const allHeading=shelf.getByRole('heading',{name:'Все книги',exact:true}); await allHeading.waitFor();
    const allGrid=allHeading.locator('xpath=following-sibling::*[1]');
    const recentGrid=recentHeading.locator('xpath=following-sibling::*[1]');
    const recentIds=await recentGrid.locator('.common-book-card').evaluateAll(cards=>cards.map(card=>card.dataset.bookId));
    const remainingIds=await allGrid.locator('.common-book-card').evaluateAll(cards=>cards.map(card=>card.dataset.bookId));
    assert.ok(recentIds.length>0 && recentIds.length<=3);
    assert.equal(remainingIds.length,4,'all books contains the entire library');
    assert.ok(recentIds.every(id=>remainingIds.includes(id)),'recent books remain in all books too');
    assert.ok(await recentHeading.evaluate(node=>{
      const panel=node.closest('.common-recent-panel');
      return panel && panel.contains(node.nextElementSibling) && getComputedStyle(panel).backgroundColor!=='rgba(0, 0, 0, 0)';
    }),'accent panel contains both heading and recent books');
    assert.equal(await shelf.getByRole('combobox',{name:'Сортировка книг'}).locator('option:checked').textContent(),'Сортировка: Недавние');
    await shelf.locator('.cover-button').first().click({button:'right'});
    await page.getByRole('toolbar',{name:'Выбранные книги'}).getByRole('button',{name:'В коллекцию',exact:true}).click();
    await page.getByLabel('Новая коллекция',{exact:true}).fill('Проверка порядка блоков');
    await page.getByRole('button',{name:'Создать',exact:true}).click();
    await shelf.locator('.collection-section').waitFor();
    assert.ok(await shelf.evaluate(root=>{
      const recent=root.querySelector('.common-recent-panel'),collection=root.querySelector('.collection-section'),all=root.querySelector('.common-all-panel');
      return !!(recent.compareDocumentPosition(collection)&Node.DOCUMENT_POSITION_FOLLOWING) &&
        !!(collection.compareDocumentPosition(all)&Node.DOCUMENT_POSITION_FOLLOWING);
    }),'recent books precede collections, then All books');
    assert.ok(await shelf.locator('.common-cover-status').evaluateAll(nodes=>nodes.every(n=>n.closest('.common-cover-wrap'))),'reading statuses sit on covers');
    await page.evaluate(()=>new Promise((resolve,reject)=>{
      const request=indexedDB.open('shelest-web');
      request.onsuccess=()=>{ const db=request.result,tx=db.transaction('days','readwrite');
        tx.objectStore('days').put({date:new Date().toISOString().slice(0,10),seconds:120,chars:100});
        tx.oncomplete=()=>{db.close();resolve();};tx.onerror=()=>reject(tx.error); };
      request.onerror=()=>reject(request.error);
    }));
    await page.reload({waitUntil:'networkidle'});
    await page.getByRole('button',{name:'Статистика чтения',exact:true}).waitFor();
    for(const width of [320,768,1440]) {
      await page.setViewportSize({width,height:850});
      const statsBounds=await page.locator('.common-stats-strip').boundingBox();
      const searchBounds=await page.getByRole('searchbox',{name:'Найти книгу',exact:true}).boundingBox();
      assert.ok(statsBounds && searchBounds && statsBounds.y+statsBounds.height<searchBounds.y,
        `statistics must be above search, without overlap at ${width}`);
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`statistics overflow at ${width}`);
    }
    await page.setViewportSize({width:320,height:850});
    await page.screenshot({path:path.resolve(__dirname,'../build/common-library-stats-320.png'),fullPage:true});
    await page.getByRole('button',{name:'Настройки',exact:true}).last().click();await appearance.waitFor();
    await appearance.getByRole('switch',{name:'Статистика чтения',exact:true}).click();
    await page.getByRole('button',{name:'Библиотека',exact:true}).last().click();await shelf.waitFor();
    assert.equal(await page.getByRole('button',{name:'Статистика чтения',exact:true}).count(),0,'home statistics preference actually hides the strip');
    assert.equal(await page.locator('.sync-pill,.sync-card').count(),0,'no duplicate or misleading cloud indicators');
    await page.setViewportSize({width:1440,height:900});
    await page.screenshot({path:path.resolve(__dirname,'../build/design-library-desktop.png')});
    for(const [name,slug] of [['Что почитать','discover'],['Словарик','words'],['Статистика чтения','stats'],['Настройки','settings']]) {
      const navigation=page.locator('.sidebar').getByRole('button',{name,exact:true});
      await navigation.click();
      await page.waitForFunction(label=>[...document.querySelectorAll('.sidebar .nav-item.selected')].some(el=>el.textContent===label),name);
      await page.locator('.page-heading h1').waitFor();
      await page.screenshot({path:path.resolve(__dirname,`../build/design-${slug}-desktop.png`)});
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${slug} fits desktop`);
    }
    await page.locator('.sidebar').getByRole('button',{name:'Моя библиотека',exact:true}).click();
    await shelf.waitFor();
    assert.ok(await recentGrid.locator('.book-card').evaluateAll(cards=>cards.every(card=>card.getBoundingClientRect().width<=181)),
      'recent covers do not stretch across desktop');
    assert.ok(await page.locator('.common-recent-panel').evaluate(panel=>panel.getBoundingClientRect().width<650),'recent panel fits three books, not the full desktop');
    await page.evaluate(()=>scrollTo(0,document.body.scrollHeight));
    assert.ok(await page.locator('.sidebar').evaluate(sidebar=>Math.abs(sidebar.getBoundingClientRect().top)<1&&sidebar.getBoundingClientRect().height<=innerHeight+1),'sidebar stays anchored to the viewport');
    await page.evaluate(()=>scrollTo(0,0));
    await page.screenshot({path:path.resolve(__dirname,'../build/refined-library-desktop.png'),fullPage:true});
    for (const [label,name] of [['Что почитать','discover'],['Словарик','words'],['Статистика чтения','stats'],['Настройки','settings']]) {
      await page.setViewportSize({width:1440,height:900});
      await page.locator('.sidebar').getByRole('button',{name:label,exact:true}).click();
      await page.waitForFunction(label=>[...document.querySelectorAll('.sidebar .nav-item.selected')].some(el=>el.textContent===label),label);
      await page.locator('.content-width h1').waitFor();
      if(name==='settings') await appearance.waitFor();
      for (const width of [1440,320]) {
        await page.setViewportSize({width,height:900});
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${name} overflow at ${width}`);
        await page.screenshot({path:path.resolve(__dirname,`../build/refined-${name}-${width}.png`),fullPage:true});
      }
    }
    await appearance.getByRole('button',{name:'Тема приложения: Светлая',exact:true}).click();
    await page.waitForFunction(()=>document.documentElement.dataset.theme==='light');
    await page.screenshot({path:path.resolve(__dirname,'../build/refined-settings-light-320.png'),fullPage:true});
    await page.setViewportSize({width:1440,height:900});
    await page.locator('.sidebar').getByRole('button',{name:'Моя библиотека',exact:true}).click();
    await shelf.waitFor();
    await page.locator('input[type=file][multiple]').setInputFiles({name:'Проверка PDF.pdf',mimeType:'application/pdf',buffer:fixturePdf()});
    await shelf.getByRole('button',{name:'Открыть Проверка PDF',exact:true}).first().click();
    await page.locator('.pdf-reader-frame canvas').waitFor();
    await page.locator('.textLayer span').first().waitFor();
    await page.waitForFunction(()=>document.querySelector('.reader-page-meter')?.textContent?.includes('из'));
    assert.equal(await page.locator('.reader-page-meter').count(),1,'PDF has one page counter');
    assert.equal(await page.locator('.pdf-page-count').count(),0,'no second floating PDF counter');
    await page.getByRole('button',{name:'Перо',exact:true}).click();
    const drawing=page.locator('.pdf-drawing-layer');
    const bounds=await drawing.boundingBox();
    const drawY=bounds.y+Math.min(160,bounds.height*.4),drawX=bounds.x+40;
    await page.mouse.move(drawX,drawY);await page.mouse.down();await page.mouse.move(drawX+160,drawY,{steps:20});await page.mouse.up();
    assert.equal(await drawing.locator('polyline').count(),1,'pen produces a persistent stroke');
    assert.ok(await page.evaluate(()=>Object.keys(localStorage).some(key=>key.startsWith('shelest:pdf-marks:')&&JSON.parse(localStorage.getItem(key)).strokes.length===1)));
    await page.getByRole('button',{name:'Ластик',exact:true}).click();
    await page.mouse.move(drawX+80,drawY);await page.mouse.down();await page.mouse.up();
    assert.equal(await drawing.locator('polyline').count(),2,'eraser splits a stroke instead of deleting it entirely');
    await page.getByRole('button',{name:'Свернуть инструменты PDF',exact:true}).click();
    await page.getByRole('button',{name:'Завершить рисование',exact:true}).click();
    assert.equal(await drawing.evaluate(node=>getComputedStyle(node).pointerEvents),'none','collapsed tools do not block reading or text selection');
    await page.getByRole('button',{name:'Развернуть инструменты PDF',exact:true}).click();
    await page.getByRole('button',{name:'Заметка на странице',exact:true}).click();
    await page.mouse.click(drawX+20,drawY+40);
    await page.getByLabel('Текст заметки PDF',{exact:true}).fill('Проверочная заметка');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-pdf-note-desktop.png')});
    await page.getByRole('button',{name:'Сохранить',exact:true}).click();
    await page.getByRole('button',{name:'Заметка: Проверочная заметка',exact:true}).waitFor();
    await page.getByRole('button',{name:'Выделение текста',exact:true}).click();
    await page.locator('.textLayer').evaluate(layer=>{const range=document.createRange();range.selectNodeContents(layer);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);});
    await page.locator('.selection-popover').waitFor();
    await page.locator('.selection-popover').getByRole('button',{name:'Закрыть',exact:true}).click();
    await page.getByRole('button',{name:'Слушать',exact:true}).click();
    await page.waitForFunction(()=>window.__spoken.some(text=>text.includes('Shelest PDF appearance test')));
    await page.getByRole('button',{name:'Закрыть озвучку',exact:true}).click();
    await page.screenshot({path:path.resolve(__dirname,'../build/pdf-markup-desktop.png')});
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.locator('[data-common-ui=reading-appearance]').waitFor();
    await page.getByRole('heading',{name:'Страница',exact:true}).waitFor();
    assert.equal(await page.getByRole('heading',{name:'Текст',exact:true}).count(),0,'PDF does not offer ineffective font controls');
    await page.getByRole('button',{name:'Цвет страницы: Сепия',exact:true}).click();
    await page.setViewportSize({width:320,height:900});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'PDF settings fit mobile');
    await page.screenshot({path:path.resolve(__dirname,'../build/refined-pdf-settings-320.png')});
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.locator('.reader-back').click();
    await page.setViewportSize({width:1440,height:900});
    await page.locator('input[type=file][multiple]').setInputFiles({name:'Разворот PDF.pdf',mimeType:'application/pdf',buffer:fixturePdf(3)});
    await shelf.getByRole('button',{name:'Открыть Разворот PDF',exact:true}).first().click();
    await page.waitForFunction(()=>document.querySelectorAll('.pdf-sheet .textLayer span').length>=1);
    assert.equal(await page.locator('.pdf-sheet canvas').count(),1,'one PDF page on desktop');
    assert.equal(await page.locator('.pdf-markup-toolbar').count(),1,'one shared toolbar for the spread');
    const sheet=await page.locator('.pdf-sheet').boundingBox(),toolbar=await page.locator('.pdf-markup-toolbar').boundingBox();
    assert.ok(toolbar.x>=sheet.x+sheet.width,'PDF toolbar on the right');
    assert.ok(await page.locator('.pdf-scroll').evaluate(el=>el.scrollHeight<=el.clientHeight+2),'PDF fits vertically at default zoom');
    await page.getByRole('button',{name:'Перо',exact:true}).click();
    const options=await page.locator('.pdf-brush-options').boundingBox(),expandedToolbar=await page.locator('.pdf-markup-toolbar').boundingBox();
    assert.ok(options.x>=expandedToolbar.x+expandedToolbar.width,'brush options expand right');
    for(let i=0;i<1;i++) {
      const rect=await page.locator('.pdf-drawing-layer').nth(i).boundingBox();
      await page.mouse.move(rect.x+30,rect.y+70);await page.mouse.down();await page.mouse.move(rect.x+80,rect.y+100,{steps:8});await page.mouse.up();
    }
    assert.equal(await page.locator('.pdf-drawing-layer polyline').count(),1,'drawing on the current page');
    await page.getByRole('button',{name:'Отменить последний штрих',exact:true}).click();
    assert.equal(await page.locator('.pdf-drawing-layer').first().locator('polyline').count(),0,'undo removes last stroke');
    assert.equal(await page.locator('.pdf-drawing-layer').last().locator('polyline').count(),0);
    await page.screenshot({path:path.resolve(__dirname,'../build/pdf-spread-desktop.png')});
    await page.setViewportSize({width:390,height:844});
    await page.waitForFunction(()=>document.querySelectorAll('.pdf-sheet canvas').length===1);
    await page.screenshot({path:path.resolve(__dirname,'../build/pdf-tools-mobile.png')});
    assert.deepEqual(errors,[]);
    console.log('Shared workspace UI passed: compact library/sidebar, reader fonts/settings, PDF text/pen/eraser/notes, theme/accent without reset, persistence, 320/768/1440px');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
