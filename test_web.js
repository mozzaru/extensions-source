const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
    extraHTTPHeaders: {
        'Accept-Language': 'id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7',
        'Sec-CH-UA': '"Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"',
        'Sec-CH-UA-Mobile': '?1',
        'Sec-CH-UA-Platform': '"Android"'
    }
  });
  const page = await context.newPage();
  try {
    console.log('Navigating to id.mgkomik.cc...');
    const response = await page.goto('https://id.mgkomik.cc/komik/', { waitUntil: 'networkidle' });
    console.log('Status:', response.status());
    console.log('Title:', await page.title());
    const content = await page.content();
    console.log('Is Madara:', content.includes('wp-manga'));
    const mangas = await page.$$('div.page-item-detail, .manga__item, .post-item');
    console.log('Manga count:', mangas.length);
    if (content.includes('cloudflare')) {
        console.log('Cloudflare detected in body');
    }
  } catch (e) {
    console.error('Error:', e.message);
  }
  await browser.close();
})();
