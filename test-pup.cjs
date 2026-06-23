const puppeteer = require('puppeteer');

(async () => {
  const browser = await puppeteer.launch({ args: ['--no-sandbox', '--disable-setuid-sandbox'] });
  const page = await browser.newPage();

  page.on('console', msg => console.log('PAGE LOG:', msg.text()));
  page.on('pageerror', err => console.log('PAGE ERROR:', err.toString()));
  page.on('response', response => {
    if (!response.ok()) {
      console.log('PAGE RESPONSE ERROR:', response.status(), response.url());
    }
  });
  page.on('requestfailed', request => {
    console.log('PAGE REQUEST FAILED:', request.failure().errorText, request.url());
  });

  await page.goto('http://127.0.0.1:3000', { waitUntil: 'networkidle0' });
  await browser.close();
})();
