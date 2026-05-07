const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'
  });
  const page = await context.newPage();

  page.on('request', request => {
    if (request.url().includes('id.mgkomik.cc')) {
      console.log('Request Headers for', request.url());
      console.log(request.headers());
    }
  });

  try {
    await page.goto('https://id.mgkomik.cc/komik/', { waitUntil: 'networkidle' });
  } catch (e) {
    console.error('Error:', e.message);
  }
  await browser.close();
})();
