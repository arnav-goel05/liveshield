'use strict';

const fs = require('fs');
const { chromium } = require('playwright');

const [url, outputPath] = process.argv.slice(2);
if (!url || !outputPath) {
  throw new Error('Usage: verify-webrtc-viewer.cjs URL OUTPUT_JSON');
}

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage();
    const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 10000 });
    if (!response || !response.ok()) {
      throw new Error(`Viewer HTTP status was ${response ? response.status() : 'unavailable'}`);
    }
    await page.waitForSelector('video', { timeout: 10000 });
    await page.locator('video').evaluate(video => video.play().catch(() => {}));
    await page.waitForFunction(() => {
      const video = document.querySelector('video');
      return video && video.readyState >= 2 && video.currentTime > 0;
    }, null, { timeout: 12000 });
    const evidence = await page.locator('video').evaluate(video => ({
      currentTime: video.currentTime,
      readyState: video.readyState,
      videoWidth: video.videoWidth,
      videoHeight: video.videoHeight,
      paused: video.paused,
    }));
    fs.writeFileSync(outputPath, `${JSON.stringify(evidence)}\n`, { encoding: 'utf8' });
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error.stack || String(error));
  process.exit(1);
});
