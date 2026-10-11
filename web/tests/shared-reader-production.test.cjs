// Production-bundle smoke check. Files stay in an isolated guest browser's IndexedDB.
// All API requests are mocked: no real accounts, uploads or reading positions are changed.
const assert = require('node:assert/strict');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const page = await browser.newPage({viewport:{width:1100,height:800}});
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', route => route.fulfill({status:401, contentType:'application/json', body:'{"error":"test guest"}'}));
    await page.goto(process.env.READER_TEST_URL || 'https://shelest-book.ru/', {waitUntil:'domcontentloaded'});
    const text = Array.from({length:40}, (_,p) => Array.from({length:50}, (_,w) => `слово${p}номер${w}`).join(' ')).join('\n\n');
    await page.locator('input[type=file][multiple]').setInputFiles({name:'Общий движок smoke.txt', mimeType:'text/plain', buffer:Buffer.from(text)});
    await page.locator('.book-title').filter({hasText:'Общий движок smoke'}).last().click();
    const getFrame = async () => {
      const iframe = page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)');
      await iframe.waitFor(); const frame = await (await iframe.elementHandle()).contentFrame();
      await frame.waitForFunction(() => typeof paged !== 'undefined' && paged);
      return frame;
    };
    let frame = await getFrame();
    assert.equal(await frame.evaluate(() => currentPage), 0);
    await page.waitForFunction(() => document.querySelector('.reader-page-meter')?.textContent?.includes('из'));
    await page.locator('.reader-footer button').last().click(); await page.waitForTimeout(750);
    const place = await frame.evaluate(() => captureReadingPosition());
    assert.equal(place.page, 1);
    await page.locator('.reader-back').click();
    await page.locator('.book-title').filter({hasText:'Общий движок smoke'}).last().click();
    frame = await getFrame();
    assert.equal(await frame.evaluate(() => currentPage), place.page);
    assert.equal(await frame.evaluate(() => currentChar()), place.character);
    assert.equal(await frame.evaluate(()=>getComputedStyle(container).columnCount),'1','single page on desktop');
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.getByRole('button',{name:'Режим чтения: Прокрутка',exact:true}).click();
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    frame=await (await page.locator('.shared-reader-frame > iframe:not(.reader-measure-frame)').elementHandle()).contentFrame();
    await frame.waitForFunction(()=>typeof placed!=='undefined'&&placed);
    const beforeScroll=await frame.evaluate(()=>scrollY);
    await page.locator('.reader-footer button').last().click();await page.waitForTimeout(750);
    assert.ok(await frame.evaluate(()=>scrollY)>beforeScroll,'vertical next scrolls');
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.getByRole('button',{name:'Режим чтения: Страницы',exact:true}).click();
    await page.getByRole('button',{name:'Настройки чтения',exact:true}).click();
    await page.setViewportSize({width:640,height:740}); await page.waitForTimeout(750);
    frame=await getFrame();
    assert.equal(await frame.evaluate(()=>getComputedStyle(container).columnCount),'1','mobile uses one column');
    const geometry = await frame.evaluate(() => ({offset:mask.scrollLeft, expected:currentPage*pageWidth, width:pageWidth,
      actual:mask.getBoundingClientRect().width, anchorPage:pageOfChar(currentChar()), page:currentPage}));
    assert.ok(Math.abs(geometry.offset - geometry.expected) < 1);
    assert.ok(Math.abs(geometry.width - geometry.actual) < .01);
    assert.equal(geometry.anchorPage, geometry.page);
    await page.evaluate(()=>{
      window.shelestTestSpeech='';
      window.speechSynthesis.speak=utterance=>{window.shelestTestSpeech=utterance.text;};
    });
    await frame.evaluate(()=>{
      goToPage(0,false);
      const tm=buildTextMap(),node=tm.map.find(item=>item.node.nodeValue.includes('слово0номер5')).node;
      const at=node.nodeValue.indexOf('слово0номер5'),range=document.createRange();
      range.setStart(node,at);range.setEnd(node,at+'слово0номер5'.length);
      const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
    });
    await page.waitForTimeout(250);
    await page.getByRole('button',{name:'Слушать',exact:true}).click();
    await page.waitForFunction(()=>window.shelestTestSpeech.length>0);
    const spoken=await page.evaluate(()=>window.shelestTestSpeech);
    assert.ok(spoken.startsWith('слово0номер5 '),'voice starts at the selected occurrence');
    assert.ok(spoken.includes('слово0номер6'),'voice continues after the selected word');
    await page.getByRole('button',{name:'Остановить озвучку',exact:true}).click();
    assert.deepEqual(errors, []);
    console.log('Public production build passed: guest-local TXT import, shared-engine turn, exact reopening, total pages and resize. No API writes.');
  } finally {await browser.close();}
})().catch(error => {console.error(error);process.exitCode=1;});
