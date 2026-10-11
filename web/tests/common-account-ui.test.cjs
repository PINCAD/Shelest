const assert = require('node:assert/strict');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const address = process.env.UI_TEST_URL || 'http://127.0.0.1:5180/';
const user = {id:'ui-test', displayName:'Тестовый читатель', login:'reader', email:'reader@example.invalid',
  isPremium:true,hasPassword:true,twoFactorEnabled:false, quotaBytes:4*1024*1024, storageUsedBytes:3*1024*1024, maxBookBytes:4*1024*1024};
(async()=>{
  const browser = await chromium.launch({headless:true, executablePath:process.env.BROWSER_EXECUTABLE});
  try {
    const page = await browser.newPage({viewport:{width:1100,height:800},reducedMotion:'reduce'});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    const uiRequests=[]; page.on('request',r=>{if(r.url().includes('shared-ui'))uiRequests.push(r.url());});
    let signed=true;
    await page.route('**/api/**',route=>{
      const url=route.request().url();
      if(url.endsWith('/auth/config')) return route.fulfill({json:{csrfToken:'ui-test',nonce:'ui-test'}});
      if(url.endsWith('/auth/web/logout')) {signed=false; return route.fulfill({json:{}});}
      if(!signed) return route.fulfill({status:401,json:{error:'invalid_credentials'}});
      if(url.endsWith('/account/security')) {
        const payload=route.request().postDataJSON();
        if(payload.action==='start') return route.fulfill({json:{secret:'JBSWY3DPEHPK3PXP',uri:'otpauth://totp/Test?secret=JBSWY3DPEHPK3PXP&issuer=Test'}});
        if(payload.action==='confirm') {user.twoFactorEnabled=true;return route.fulfill({json:{account:user,recoveryCodes:Array.from({length:8},(_,i)=>'TESTONLY0000000'+i)}});}
        if(payload.action==='disable') user.twoFactorEnabled=false;
        if(payload.action==='credentials') user.login=payload.login;
        return route.fulfill({json:{account:user}});
      }
      if(url.endsWith('/sync/personal')) return route.fulfill({json:{accountId:user.id,entries:route.request().postDataJSON().entries,cursor:1,hasMore:false}});
      if(url.endsWith('/sync/books')) return route.fulfill({json:{account:user,books:[],deletedBooks:[],serverTime:Date.now()}});
      return route.fulfill({json:{account:user}});
    });
    await page.goto(address,{waitUntil:'networkidle'});
    await page.locator('[data-common-ui=library]').waitFor();
    assert.ok(uiRequests.length>0,'library mounts its shared UI after the initial fallback');
    await page.getByText('Тестовый читатель',{exact:true}).first().waitFor();
    await page.getByRole('button',{name:'Настройки',exact:true}).first().click();
    const panel=page.locator('[data-common-ui=account]');
    await panel.waitFor();
    const syncPanel=page.locator('[data-common-ui=sync-settings]');
    await syncPanel.waitFor();
    const automatic=syncPanel.getByRole('switch',{name:'Автосинхронизация',exact:true});
    await automatic.click();
    await page.waitForFunction(()=>document.querySelector('[aria-label="Автосинхронизация"]')?.getAttribute('aria-checked')==='false');
    const deletions=syncPanel.getByRole('switch',{name:'Удаление на всех устройствах',exact:true});
    await deletions.click();
    await page.waitForFunction(()=>document.querySelector('[aria-label="Удаление на всех устройствах"]')?.getAttribute('aria-checked')==='false');
    await page.reload({waitUntil:'networkidle'});
    await page.getByText('Тестовый читатель',{exact:true}).first().waitFor();
    await page.getByRole('button',{name:'Настройки',exact:true}).first().click();
    await panel.waitFor(); await syncPanel.waitFor();
    assert.equal(await automatic.getAttribute('aria-checked'),'false','explicit preference survives reload');
    assert.equal(await deletions.getAttribute('aria-checked'),'false');
    await assert.equal(await panel.getByText('Тестовый читатель',{exact:true}).count(),1);
    assert.equal(await panel.getByRole('progressbar').getAttribute('aria-valuenow'),'75');
    await panel.getByRole('button',{name:'Управление аккаунтом'}).click();
    await page.locator('.account-page [data-common-ui=account]').waitFor();
    const signedPanel=page.locator('.account-page [data-common-ui=account]');
    const refresh=signedPanel.getByRole('button',{name:'Обновить',exact:true});
    await refresh.focus(); await page.keyboard.press('Enter');
    await page.getByText('Синхронизировано',{exact:true}).waitFor();
    await signedPanel.getByText('Синхронизация: только что',{exact:true}).waitFor();
    assert.ok(await page.evaluate(()=>Number(localStorage.getItem('shelest-last-sync:ui-test'))>0));
    assert.equal(await page.locator('.auth-card').count(),0,'no sign-in form when signed in');
    await page.getByRole('region',{name:'Настройки аккаунта'}).waitFor();
    const security=page.getByRole('region',{name:'Настройки аккаунта'});
    await security.getByRole('button',{name:/Логин и пароль/}).click();
    const modal=page.getByRole('dialog');
    await modal.getByLabel('Логин',{exact:true}).fill('reader.new');
    await modal.getByLabel('Текущий пароль',{exact:true}).fill('test-current-password');
    assert.doesNotMatch(await modal.innerText(),/[—–]/,'credential dialog copy has no long dashes');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-credentials-desktop.png')});
    await modal.getByRole('button',{name:'Сохранить изменения',exact:true}).click();
    await security.getByText('Данные аккаунта сохранены',{exact:true}).waitFor();
    await security.getByRole('button',{name:/Двухфакторная защита/}).click();
    await modal.getByLabel('Текущий пароль',{exact:true}).fill('test-current-password');
    await modal.getByRole('button',{name:'Продолжить',exact:true}).click();
    await modal.locator('.totp-qr img').waitFor();
    await page.screenshot({path:path.resolve(__dirname,'../build/design-mfa-qr-desktop.png')});
    await page.setViewportSize({width:320,height:760});
    assert.ok(await modal.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'MFA dialog fits 320px');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-mfa-qr-mobile.png')});
    await page.keyboard.press('Tab');
    assert.ok(await modal.evaluate(el=>el.contains(document.activeElement)),'Tab stays inside MFA');
    await modal.getByLabel('Код из аутентификатора',{exact:true}).fill('123456');
    await modal.getByRole('button',{name:'Подтвердить и включить',exact:true}).click();
    await modal.getByText('TESTONLY00000000',{exact:true}).waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await modal.count(),1,'recovery codes cannot be accidentally dismissed');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-mfa-recovery-mobile.png')});
    await modal.getByRole('button',{name:'Я сохранил коды',exact:true}).click();
    await page.setViewportSize({width:1100,height:800});
    await page.screenshot({path:path.resolve(__dirname,'../build/account-security-desktop.png'),fullPage:true});
    await security.getByRole('button',{name:/Двухфакторная защита/}).click();
    await modal.getByRole('heading',{name:'Отключить защиту?',exact:true}).waitFor();
    await modal.getByLabel('Текущий пароль',{exact:true}).fill('test-current-password');
    await modal.getByLabel('Код аутентификатора или резервный код',{exact:true}).fill('123456');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-mfa-disable-desktop.png')});
    await modal.getByRole('button',{name:'Отключить двухфакторную защиту',exact:true}).click();
    await security.getByText('Двухфакторная защита отключена',{exact:true}).waitFor();
    await page.setViewportSize({width:320,height:760});
    assert.ok(await signedPanel.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'panel has no horizontal overflow');
    await page.screenshot({path:path.resolve(__dirname,'../build/common-account-mobile.png'),fullPage:true});
    await page.evaluate(()=>document.documentElement.dataset.theme='dark');
    await page.screenshot({path:path.resolve(__dirname,'../build/common-account-dark.png'),fullPage:true});
    await security.getByRole('button',{name:/Логин и пароль/}).click();
    await modal.getByLabel('Логин',{exact:true}).waitFor();
    assert.ok(await modal.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'credentials fit 320px in dark theme');
    await page.screenshot({path:path.resolve(__dirname,'../build/design-credentials-dark-mobile.png')});
    await page.keyboard.press('Escape');
    await security.getByRole('button',{name:/Двухфакторная защита/}).click();
    await modal.getByLabel('Текущий пароль',{exact:true}).fill('test-current-password');
    await modal.getByRole('button',{name:'Продолжить',exact:true}).click();
    await modal.locator('.totp-qr img').waitFor();
    await page.screenshot({path:path.resolve(__dirname,'../build/design-mfa-dark-mobile.png')});
    await page.keyboard.press('Escape');
    // Navigation unmounts Compose; repeated mounting must produce exactly one card.
    for(let i=0;i<3;i++){
      await page.getByRole('button',{name:'Библиотека',exact:true}).last().click();
      await page.getByRole('button',{name:'Настройки',exact:true}).last().click();
      await panel.waitFor(); assert.equal(await panel.count(),1);
    }
    assert.ok(await syncPanel.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'sync panel has no horizontal overflow');
    await page.screenshot({path:path.resolve(__dirname,'../build/common-sync-mobile.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    const fallbackPage=await browser.newPage();
    await fallbackPage.route('**/api/**',route=>route.fulfill({json:route.request().url().endsWith('/sync/personal')
      ? {accountId:user.id,entries:route.request().postDataJSON().entries,cursor:1,hasMore:false} : route.request().url().endsWith('/auth/config')
      ? {csrfToken:'ui-test',nonce:'ui-test'} : route.request().url().endsWith('/sync/books')
      ? {account:user,books:[],deletedBooks:[],serverTime:Date.now()} : {account:user}}));
    await fallbackPage.route('**/*shared-ui*',route=>route.abort());
    await fallbackPage.goto(address,{waitUntil:'networkidle'});
    await fallbackPage.getByRole('button',{name:'Настройки',exact:true}).first().click();
    const fallbackButton=fallbackPage.getByRole('button',{name:'Войти в аккаунт',exact:true});
    await fallbackButton.waitFor();
    await fallbackButton.click();
    await fallbackPage.locator('.account-page').waitFor();
    assert.equal(await fallbackPage.locator('[data-common-ui=account]').count(),0);
    await fallbackPage.getByRole('button',{name:'Обновить',exact:true}).waitFor();
    await fallbackPage.close();
    // Guest login/registration and MFA are isolated from real production accounts.
    const guest=await browser.newPage({viewport:{width:390,height:844},reducedMotion:'reduce'});
    guest.on('pageerror',e=>errors.push(e.message));
    await guest.route('https://accounts.google.com/**',route=>route.abort());
    await guest.route('**/api/**',route=>{
      const url=route.request().url();
      if(url.endsWith('/auth/config'))return route.fulfill({json:{csrfToken:'ui-test',nonce:'ui-test'}});
      if(url.endsWith('/auth/web/password'))return route.fulfill({status:202,json:{mfaRequired:true,challenge:'test-only-challenge'}});
      if(url.endsWith('/auth/mfa'))return route.fulfill({status:401,json:{error:'invalid_mfa_code'}});
      return route.fulfill({status:401,json:{error:'invalid_credentials'}});
    });
    await guest.goto(address,{waitUntil:'networkidle'});
    await guest.getByRole('button',{name:'Аккаунт',exact:true}).click();
    await guest.locator('.auth-card').waitFor();
    await guest.getByLabel('Логин',{exact:true}).fill('testreader');
    await guest.getByLabel('Пароль',{exact:true}).fill('test-password');
    await guest.screenshot({path:path.resolve(__dirname,'../build/design-login-mobile.png')});
    await guest.locator('.auth-card').getByRole('button',{name:'Войти',exact:true}).click();
    const loginModal=guest.getByRole('dialog',{name:'Подтвердите вход',exact:true});
    await loginModal.waitFor();
    await loginModal.getByLabel('Код аутентификатора или резервный код',{exact:true}).fill('000000');
    await loginModal.getByRole('button',{name:'Войти',exact:true}).click();
    await loginModal.getByRole('alert').waitFor();
    assert.equal(await loginModal.getByRole('alert').textContent(),'Неверный или уже использованный код');
    await guest.screenshot({path:path.resolve(__dirname,'../build/design-mfa-login-error-mobile.png')});
    await guest.keyboard.press('Escape');
    await loginModal.waitFor({state:'hidden'});
    assert.equal(await guest.locator('#root').evaluate(el=>el.inert),false,'background is interactive after closing');
    await guest.getByRole('button',{name:'Регистрация',exact:true}).click();
    await guest.getByLabel('Повторите пароль').waitFor();
    await guest.screenshot({path:path.resolve(__dirname,'../build/design-register-mobile.png')});
    await guest.close();
    assert.deepEqual(errors,[]);
    console.log('Common account/security UI passed: lazy load, quota, keyboard focus, 320px/light/dark, credentials, local QR, recovery lock, disable MFA, guest login/register/MFA error, remount/disposal');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
