// Real compiled application, disposable browser context, mock API; never touches a reader's account.
const assert=require('node:assert/strict');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:900},reducedMotion:'reduce'});
    const page=await context.newPage(),errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/api/**',route=>route.fulfill({status:401,json:{error:'guest_test'}}));
    await page.goto(process.env.UI_TEST_URL||'http://127.0.0.1:5181/',{waitUntil:'networkidle'});
    const shelf=page.locator('[data-common-ui=library]');await shelf.waitFor();
    await page.locator('input[type=file][multiple]').setInputFiles([
      {name:'Том первый.txt',mimeType:'text/plain',buffer:Buffer.from('Том первый. '+ 'Синтетическая книга. '.repeat(100))},
      {name:'Том второй.txt',mimeType:'text/plain',buffer:Buffer.from('Том второй. '+ 'Вторая синтетическая книга. '.repeat(100))},
    ]);
    await shelf.getByRole('button',{name:'Открыть Том первый',exact:true}).click({button:'right'});
    await shelf.getByRole('button',{name:'Выбрать Том второй',exact:true}).click();
    const toolbar=page.getByRole('toolbar',{name:'Выбранные книги'});
    assert.equal(await shelf.getByText('□',{exact:true}).count(),0,'unselected books have no checkbox');
    assert.equal(await shelf.locator('.common-cover-wrap .common-cover-status').count(),2,'status belongs on each cover');
    await toolbar.getByText('Выбрано: 2',{exact:true}).waitFor();
    assert.equal(await toolbar.getByRole('button',{name:'Переименовать',exact:true}).count(),0);
    await toolbar.getByRole('button',{name:'В коллекцию',exact:true}).click();
    await page.getByLabel('Новая коллекция',{exact:true}).fill('Песнь льда и пламени');
    await page.getByRole('button',{name:'Создать',exact:true}).click();
    const card=page.locator('.collection-card');await card.waitFor();
    assert.match(await card.innerText(),/Книг: 2/);
    assert.equal(await shelf.locator('.common-book-card').count(),2,'groups do not remove books from All books');
    for(const width of [1440,768,320]) {
      await page.setViewportSize({width,height:900});
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'no collection overflow at '+width);
      assert.ok((await card.boundingBox()).width<=225,'collection card never expands to the whole shelf');
      await page.screenshot({path:path.resolve(__dirname,'../build/collections-'+width+'.png'),fullPage:true});
    }
    await page.getByRole('button',{name:'Настройки',exact:true}).last().click();
    await page.getByRole('button',{name:'Тема приложения: Тёмная',exact:true}).click();
    await page.getByRole('button',{name:'Библиотека',exact:true}).last().click();await card.waitFor();
    await page.screenshot({path:path.resolve(__dirname,'../build/collections-dark-320.png'),fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),'dark');
    await page.getByRole('button',{name:'Настройки',exact:true}).last().click();
    await page.getByRole('button',{name:'Тема приложения: Светлая',exact:true}).click();
    await page.getByRole('button',{name:'Библиотека',exact:true}).last().click();await card.waitFor();
    await card.click();
    await page.getByRole('heading',{name:'Песнь льда и пламени',exact:true}).waitFor();
    assert.equal(await shelf.getByRole('combobox',{name:'Сортировка книг'}).inputValue(),'COLLECTION');
    assert.equal(await shelf.getByRole('heading',{name:'Недавние',exact:true}).count(),0);
    const titles=()=>shelf.locator('.common-book-card .book-title').allTextContents();
    assert.deepEqual(await titles(),['Том первый','Том второй'],'initial volume order follows the selection order');
    await page.getByRole('button',{name:'Изменить коллекцию',exact:true}).click();
    await page.getByLabel('Название',{exact:true}).fill('Моя серия');
    await page.getByRole('button',{name:'Выше: Том второй',exact:true}).click();
    await page.getByRole('button',{name:'Сохранить',exact:true}).click();
    await page.getByRole('heading',{name:'Моя серия',exact:true}).waitFor();
    assert.deepEqual(await titles(),['Том второй','Том первый'],'manual volume order is applied to shelf');
    // Existing page remains editable without a server connection.
    await context.setOffline(true);
    await page.getByRole('button',{name:'Изменить коллекцию',exact:true}).click();
    await page.getByRole('button',{name:'Убрать из коллекции: Том первый',exact:true}).click();
    await page.screenshot({path:path.resolve(__dirname,'../build/collection-editor-320.png'),fullPage:true});
    await page.getByRole('button',{name:'Сохранить',exact:true}).click();
    assert.deepEqual(await titles(),['Том второй']);
    await context.setOffline(false);
    await page.reload({waitUntil:'networkidle'});await card.waitFor();
    assert.match(await card.innerText(),/Моя серия/);assert.match(await card.innerText(),/Книг: 1/);
    await card.click();await page.getByRole('button',{name:'Изменить коллекцию',exact:true}).click();
    await page.getByRole('button',{name:'Удалить коллекцию',exact:true}).click();
    await page.getByRole('dialog').getByRole('button',{name:'Удалить',exact:true}).click();
    await page.getByRole('button',{name:'Все книги',exact:true}).click();
    await page.waitForFunction(()=>document.querySelectorAll('.collection-card').length===0);
    assert.equal(await shelf.locator('.common-book-card').count(),2,'deleting group retains both local books');
    assert.deepEqual(errors,[]);
    console.log('Collections UI passed: desktop/tablet/320px, create, selection, saved order, offline edits, reload and safe deletion.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
