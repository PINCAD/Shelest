// Run with PLAYWRIGHT_MODULE pointing to an installed Playwright package if it is
// not on NODE_PATH. This tests the Android reader's real CSS/JS, not the web reader.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'shared/src/commonMain/kotlin/com/shelest/shared/reader/ReaderDocument.kt'), 'utf8');
const template = (start) => {
  const at = source.indexOf(start);
  assert.ok(at >= 0, `Missing source block: ${start}`);
  const begin = at + start.length;
  return source.slice(begin, source.indexOf('""".trimIndent()', begin));
};
const style = {font:'Georgia, serif', fontLoad:'Georgia', fs:'18px', lh:'1.6', mx:'18px',
  bg:'#e7eef1', fg:'#203137', accent:'#a8493d', sel:'rgba(168,73,61,.21)', align:'justify', anim:'none'};
const layout = template('val layoutCss = if (isHorizontal) """');
const scripts = [template('val positionJs = """'), template('val scriptJs = if (isHorizontal) """'),
  template('val charIndexJs = """'), template('val annotationJs = """')].join('\n');
const content = Array.from({length:50}, (_,chapter) => `<div id="chapter-${chapter}"><h2>Глава ${chapter}</h2>` +
  Array.from({length:50}, (_,i) => `<p>Абзац ${chapter*50+i}. ${'Проверка постраничного чтения: текст должен оставаться внутри полей, а перелистывание показывать ровно одну страницу. '.repeat(4)}</p>`).join('') + '</div>').join('');
const expand = (s, position=null) => s
  .replaceAll('$styleJson', JSON.stringify(style))
  .replaceAll('${style.animation}', 'none')
  .replaceAll('$positionJson', JSON.stringify(position))
  .replaceAll('$segmentIndex', '0').replaceAll('$isHorizontal', 'true')
  .replaceAll('$savedProgress', '0').replaceAll('$hasPrevious', 'false').replaceAll('$hasNext', 'false')
  .replaceAll('$backgroundMeasurement', 'false')
  .replaceAll('$waitForImages', 'false').replaceAll('$fontLoad', 'Georgia');
function html(position=null) {
  let out = template('return """<!DOCTYPE html>');
  out = '<!DOCTYPE html>' + out
    .replaceAll('$fontFaceCss', '').replaceAll('$fontFamilyCss', style.font)
    .replaceAll('${fs}', '18').replaceAll('$lh', style.lh).replaceAll('${mx}', '18')
    .replaceAll('$bg', style.bg).replaceAll('$fg', style.fg).replaceAll('$accent', style.accent)
    .replaceAll('$selColor', style.sel).replaceAll('$justify', style.align)
    .replaceAll('$layoutCss', layout.replaceAll('${if (spread) 2 else 1}','1')).replaceAll('$content', content)
    .replaceAll('$bridgeJs', '')
    .replaceAll('$fullScriptJs', `window.Shelest = new Proxy({}, {get: () => () => {}});\n${expand(scripts,position)}`);
  return out;
}
async function geometry(frame) {
  return frame.evaluate(() => {
    const c=document.getElementById('container'), m=document.getElementById('mask');
    const lefts=new Set();
    for (const p of c.querySelectorAll('p')) for (const r of p.getClientRects()) {
      if (r.right > 1 && r.left < m.getBoundingClientRect().width-1) lefts.add(Math.round(r.left*100)/100);
    }
    return {page:currentPage, pages:totalPages, step:pageWidth, viewport:m.getBoundingClientRect().width,
      offset:m.scrollLeft, container:c.getBoundingClientRect().width, lefts:[...lefts], margin:parseFloat(getComputedStyle(c).paddingLeft)};
  });
}
async function aligned(frame, label) {
  const g=await geometry(frame);
  assert.ok(g.lefts.length>0 && g.lefts.every(x=>Math.abs(x-g.margin)<2), `${label}: ${JSON.stringify(g)}`);
  assert.ok(Math.abs(g.offset-g.page*g.step)<1, `${label}: exact scroll offset ${JSON.stringify(g)}`);
  assert.ok(Math.abs(g.container-g.viewport)<1, `${label}: one-page column box`);
  return g;
}
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const page=await browser.newPage({viewport:{width:1000,height:1000}});
    const errors=[];
    page.on('pageerror', error => errors.push(error.message));
    if (process.env.TRACE_PAGING) page.on('console',message=>console.log(message.text()));
    await page.setContent('<iframe id="reader" style="border:0;width:392.727px;height:850px"></iframe>');
    const frame=page.frames()[1];
    await frame.setContent(html());
    await frame.waitForFunction(() => typeof paged !== 'undefined' && paged);
    await frame.evaluate(() => goToPage(18,false));
    const before=await aligned(frame,'opening'); console.log('Initial geometry:', before);
    // An Android WebView can have a fractional CSS viewport even though an iframe
    // rounds its own viewport. Exercise the actual scrollport, not only iframe width.
    for (const width of [392.727,411.429,360.333]) {
      await frame.evaluate(w=>document.getElementById('page-frame').style.width=w+'px',width);
      await frame.waitForTimeout(100);
      for(const index of [1,99,500,await frame.evaluate(()=>totalPages-1)]) {
        await frame.evaluate(n=>goToPage(n,false),index);
        await aligned(frame,`fractional scrollport ${width}, page ${index+1}`);
      }
    }
    await frame.evaluate(()=>document.getElementById('page-frame').style.width='100vw');
    await frame.waitForTimeout(100);
    await frame.evaluate(()=>goToPage(18,false));
    const anchor=await frame.evaluate(() => currentChar());
    await page.locator('#reader').evaluate(el => el.style.width='500px');
    await frame.waitForTimeout(150);
    const resized=await aligned(frame,'width change'); console.log('Resized geometry:',resized);
    assert.equal(await frame.evaluate(c=>pageOfChar(c),anchor),resized.page,'resize preserves reading anchor');
    await frame.evaluate(() => goToPage(currentPage+1,false));
    await aligned(frame,'page turn');
    const saved=await frame.evaluate(() => captureReadingPosition());
    await frame.setContent(html(saved));
    await frame.waitForFunction(() => typeof paged !== 'undefined' && paged);
    const reopened=await aligned(frame,'reopening');
    assert.equal(reopened.page,saved.page,'same layout restores the exact page');
    await frame.setContent(html({...saved,page:9999,layout:'other-device-layout'}));
    await frame.waitForFunction(() => typeof paged !== 'undefined' && paged);
    const cloud=await aligned(frame,'synced position from a different layout');
    assert.equal(await frame.evaluate(c=>pageOfChar(c),saved.character),cloud.page,'synced character overrides foreign page number');
    for (const index of [0,1,18,99,500,cloud.pages-2,cloud.pages-1]) {
      await frame.evaluate(n=>goToPage(n,false),index);
      await aligned(frame,`page ${index+1}, including final page`);
    }
    await frame.evaluate(()=>goToPage(18,false));
    for (const size of [[280,600],[872.727,320],[392.727,850],[500,300],[500,850],
      [1200,800],[1371.429,914.286],[1600,1000],[800,1200]]) {
      const c=await frame.evaluate(()=>currentChar());
      await page.locator('#reader').evaluate((el,[w,h])=>{el.style.width=w+'px';el.style.height=h+'px'},size);
      await frame.waitForTimeout(100);
      const g=await aligned(frame,`viewport ${size}`);
      assert.equal(await frame.evaluate(c=>pageOfChar(c),c),g.page,'width/height/orientation keeps anchor');
      for (const index of [1,99,await frame.evaluate(()=>totalPages-1)]) {
        await frame.evaluate(n=>goToPage(n,false),index);
        await aligned(frame,`viewport ${size}, page ${index+1}`);
      }
      const tabletSaved=await frame.evaluate(()=>captureReadingPosition());
      await frame.setContent(html(tabletSaved));
      await frame.waitForFunction(()=>typeof paged !== 'undefined' && paged);
      const tabletReopened=await aligned(frame,`viewport ${size}, reopen`);
      assert.equal(tabletReopened.page,tabletSaved.page,'tablet restores exact page');
    }
    for (const change of [{mx:'36px'},{fs:'24px',lh:'2'},{font:'monospace',fontLoad:'monospace'},{mx:'8px',fs:'15px',lh:'1.4'}]) {
      Object.assign(style,change);
      const c=await frame.evaluate(()=>currentChar());
      await frame.evaluate(s=>applyStyle(s),style);
      await frame.waitForTimeout(100);
      const g=await aligned(frame,'font/margins/line spacing');
      assert.equal(await frame.evaluate(c=>pageOfChar(c),c),g.page,'restyle keeps anchor');
    }
    for (const animation of ['slide','fade','flip','none']) {
      if (process.env.TRACE_PAGING && animation==='flip') await frame.evaluate(()=>{
        const offset=setOffset; window.setOffset=function(n){offset(n);console.log('setOffset',n,mask.scrollLeft)};
        const restore=restoreProgress;window.restoreProgress=function(c){console.log('restoreProgress',c);return restore(c)};
      });
      await frame.evaluate(mode=>{setPageAnimation(mode);goToPage(currentPage+1,true)},animation);
      await frame.waitForTimeout(420);
      await aligned(frame,`animation ${animation}`);
    }
    await frame.evaluate(()=>goToPage(18,false));
    const autoPage=await frame.evaluate(()=>currentPage);
    await frame.evaluate(()=>autoAdvance());
    await frame.waitForTimeout(420);
    assert.equal((await aligned(frame,'horizontal hands-free advance')).page,autoPage+1);
    await frame.evaluate(()=>{
      const tm=buildTextMap(),entry=tm.map.find(item=>item.node.nodeValue.includes('Абзац 25.'));
      const at=entry.node.nodeValue.indexOf('текст должен');
      const range=document.createRange();range.setStart(entry.node,at);range.setEnd(entry.node,at+5);
      const selection=getSelection();selection.removeAllRanges();selection.addRange(range);
      const speech=getSpeechContext(true);
      if(speech.at!==entry.start+at || !speech.text.startsWith('текст должен')) throw Error('Speech selection lost exact occurrence');
      selection.removeAllRanges();
    });
    await frame.evaluate(() => {
      let toggles = 0;
      window.Shelest = new Proxy({}, {get: (_,key) => key === 'onToggleChrome' ? () => toggles++ : () => {}});
      const touch = (type,x,y) => {
        const event = new Event(type,{bubbles:true});
        Object.defineProperties(event,{
          touches:{value:type === 'touchend' ? [] : [{clientX:x,clientY:y}]},
          changedTouches:{value:[{clientX:x,clientY:y}]}
        });
        document.dispatchEvent(event);
      };
      const tap = () => {
        touch('touchstart',pageWidth / 2,300);
        touch('touchend',pageWidth / 2,300);
      };
      setPageAnimation('none');
      touch('touchstart',pageWidth * .8,300);
      touch('touchend',pageWidth * .2,300);
      // WebView may omit the compatibility click after a page turn. The next
      // independent touch must toggle immediately, not wait for a second tap.
      tap();
      if (toggles !== 1) throw new Error('First touch after page turn must toggle chrome');
      document.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:pageWidth / 2,clientY:300}));
      if (toggles !== 1) throw new Error('Compatibility click must not toggle chrome twice');
      tap();
      document.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:pageWidth / 2,clientY:300}));
      if (toggles !== 2) throw new Error('Next single touch must toggle chrome again');
      setPageAnimation('curl');
      touch('touchstart',pageWidth * .8,300);
      touch('touchmove',pageWidth * .2,300);
      touch('touchend',pageWidth * .2,300);
      curlLand(currentPage + 1); // native fold has finished
      tap();
      document.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:pageWidth / 2,clientY:300}));
      if (toggles !== 3) throw new Error('First tap after native curl must toggle exactly once');
      const mark = document.createElement('span');
      mark.className = 'shelest-ann';
      document.body.appendChild(mark);
      touch('touchstart',pageWidth / 2,300);
      const annotationEnd = new Event('touchend',{bubbles:true});
      Object.defineProperties(annotationEnd,{
        touches:{value:[]}, changedTouches:{value:[{clientX:pageWidth / 2,clientY:300}]}
      });
      mark.dispatchEvent(annotationEnd);
      if (toggles !== 3) throw new Error('Annotation touch must not toggle chrome');
      mark.remove();
    });
    await frame.evaluate(()=>{
      const text=buildTextMap().text.slice(0,300);
      applyAnnotations([{id:'old',selectedText:text,type:'HIGHLIGHT',color:0xffffd54f},{id:'new',selectedText:text.slice(20,250),type:'HIGHLIGHT',color:0xff4fc3f7}]);
      if(!document.querySelector('[data-id="new"]')) throw Error('Overlapping multi-node highlight failed');
      applyAnnotations([{id:'new',selectedText:text,type:'HIGHLIGHT',color:0xff4fc3f7}]);
      if(document.querySelector('[data-id="old"]')) throw Error('Old highlight was not cleared');
      if(buildTextMap().text.slice(0,300)!==text) throw Error('Highlight altered book text');
    });
    const output=path.join(root,'app/build/pagination'); fs.mkdirSync(output,{recursive:true});
    fs.writeFileSync(path.join(output,'shelest-pagination-fixture.txt'),content.replace(/<[^>]+>/g,'\n'));
    await page.locator('#reader').screenshot({path:path.join(output,'virtual-fixed.png')});
    assert.deepEqual(errors,[],'reader has no JavaScript errors');
    console.log('Android reader pagination: opening, sync, exact reopening, final page, resize, fonts, 4 animations and single-tap chrome after swipe/curl passed');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
