// Isolated guest fixtures only; never reads or changes a real account.
const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors=[];
    page.on('pageerror', e=>errors.push(e.message));
    await page.route('**/api/**',route=>route.fulfill({status:401,json:{error:'test_guest'}}));
    await page.goto(process.env.UI_TEST_URL || 'http://127.0.0.1:5181/',{waitUntil:'networkidle'});
    await page.locator('[data-common-ui=library]').waitFor();
    const text=Array.from({length:70},(_,i)=>`Абзац ${i}. `+'Спокойное чтение и точное место в книге. '.repeat(35)).join('\n\n');
    await page.locator('input[type=file][multiple]').setInputFiles([
      {name:'Тихий вечер.txt',mimeType:'text/plain',buffer:Buffer.from(text)},
      {name:'Истории у моря.txt',mimeType:'text/plain',buffer:Buffer.from(text+'\nИстория моря.')},
      {name:'Сад воспоминаний.txt',mimeType:'text/plain',buffer:Buffer.from(text+'\nИстория сада.')},
    ]);
    await page.getByRole('button',{name:'Открыть Тихий вечер',exact:true}).first().waitFor();
    const cover=page.locator('[data-common-ui=library] .common-book-card').filter({has:page.locator('.book-title',{hasText:'Тихий вечер'})}).first().locator('.cover-button');
    assert.equal(await page.locator('.common-book-more').count(),0,'covers have no dots or checkmark buttons');
    const box=await cover.boundingBox();
    await cover.hover();
    await page.waitForTimeout(300);
    assert.equal(await cover.locator('.book-cover').evaluate(el=>getComputedStyle(el).transform),'none','hover never moves or crops covers');
    await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
    await page.mouse.down();await page.waitForTimeout(650);await page.mouse.up();
    const selected=page.getByRole('toolbar',{name:'Выбранные книги'});
    await selected.waitFor();
    assert.equal(await page.locator('.reader-shell').count(),0,'release after long press does not open the book');
    assert.equal(await page.locator('.common-book-more').count(),0,'selected books have no checkmark button');
    assert.equal(await page.locator('.collection-selected-cover').first().evaluate(el=>getComputedStyle(el).borderTopLeftRadius),'14px','selection frame follows rounded cover');
    await page.screenshot({path:path.resolve(__dirname,'../build/cover-selection-desktop.png')});
    await page.getByRole('button',{name:'Отменить выделение',exact:true}).click();
    await cover.focus();await page.keyboard.press('Shift+F10');await selected.waitFor();
    await page.getByRole('button',{name:'Отменить выделение',exact:true}).click();
    await cover.dispatchEvent('pointerdown',{pointerId:9,pointerType:'touch',isPrimary:true,button:0,clientX:100,clientY:100});
    await cover.dispatchEvent('pointermove',{pointerId:9,pointerType:'touch',isPrimary:true,clientX:100,clientY:140});
    await page.waitForTimeout(600);
    await cover.dispatchEvent('pointercancel',{pointerId:9,pointerType:'touch',isPrimary:true});
    assert.equal(await selected.count(),0,'scrolling cancels long press');
    await cover.dispatchEvent('pointerdown',{pointerId:10,pointerType:'touch',isPrimary:true,button:0,clientX:100,clientY:100});
    await page.waitForTimeout(600);
    await cover.dispatchEvent('pointerup',{pointerId:10,pointerType:'touch',isPrimary:true});
    await cover.dispatchEvent('click');await selected.waitFor();
    assert.equal(await page.locator('.reader-shell').count(),0,'touch selection does not open the book');
    await page.getByRole('button',{name:'Отменить выделение',exact:true}).click();
    for (const width of [1440,768,390,320]) {
      await page.setViewportSize({width,height:900});
      const navigate=async section=>{
        if (width>680) await page.locator('.sidebar').getByRole('button',{name:section,exact:true}).click();
        else if (section==='Статистика чтения') {
          await page.locator('.mobile-nav').getByRole('button',{name:'Настройки',exact:true}).click();
          await page.getByRole('button',{name:'Статистика и цель дня',exact:true}).click();
        } else await page.locator('.mobile-nav').getByRole('button',{name:section==='Моя библиотека'?'Библиотека':section,exact:true}).click();
      };
      for (const section of ['Моя библиотека','Настройки','Словарик','Статистика чтения']) {
        await navigate(section);
        await page.waitForTimeout(250);
        assert.doesNotMatch(await page.locator('main').innerText(),/[—–]/,`${section} interface copy has no long dashes`);
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${section} fits ${width}px`);
      }
      await navigate('Моя библиотека');
      await page.waitForTimeout(300);
      await page.screenshot({path:path.resolve(__dirname,`../build/polish-library-${width}.png`),fullPage:true});
    }
    await page.setViewportSize({width:1440,height:1000});
    await page.getByRole('button',{name:'Открыть Тихий вечер',exact:true}).first().click();
    await page.locator('[data-common-ui=reader-pages]').waitFor();
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    const settings=page.getByRole('region',{name:'Настройки чтения',exact:true});
    await settings.waitFor();
    await page.waitForTimeout(250);
    await page.screenshot({path:path.resolve(__dirname,'../build/polish-reader-desktop.png')});
    await page.getByRole('button',{name:'Слушать',exact:true}).click();
    await page.getByRole('region',{name:'Озвучка текста',exact:true}).waitFor();
    assert.equal(await settings.count(),0,'audio replaces settings');
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await settings.waitFor();
    assert.equal(await page.locator('.reader-audio').count(),0,'settings replace audio');
    const meter=page.locator('.reader-page-meter');
    const before=await meter.innerText();
    await settings.getByRole('button').first().focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await meter.innerText(),before,'keyboard does not turn behind a panel');
    await page.keyboard.press('Escape');
    assert.equal(await settings.count(),0,'Escape closes panel');
    assert.equal(await page.locator('[data-common-ui=reader-pages]').count(),1,'Escape keeps the book open');
    await page.waitForFunction(()=>document.activeElement?.getAttribute('aria-label')==='Настройки чтения');
    assert.ok(await page.getByRole('button',{name:'Настройки чтения',exact:true}).evaluate(el=>el===document.activeElement),'Escape returns focus to its trigger');
    await page.setViewportSize({width:390,height:844});
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await settings.waitFor();
    await page.waitForTimeout(250);
    await page.screenshot({path:path.resolve(__dirname,'../build/polish-reader-mobile.png')});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'mobile reader fits');
    await page.emulateMedia({reducedMotion:'reduce'});
    assert.equal(await settings.evaluate(el=>getComputedStyle(el).animationName),'none','reduced motion disables panel animation');
    await page.keyboard.press('Escape');
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).focus();
    assert.ok(await page.getByRole('button',{name:'Настройки чтения',exact:true}).evaluate(el=>getComputedStyle(el).outlineWidth!=='0px'),'visible keyboard focus');
    assert.deepEqual(errors,[]);
    console.log('Visual interactions passed: 320/390/768/1440px, reader exclusive panels, Escape, arrow keys, keyboard focus, reduced motion, no JS errors');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
