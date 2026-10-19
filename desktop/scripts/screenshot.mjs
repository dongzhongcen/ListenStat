import pkg from '/workspace/shot/node_modules/playwright-core/index.js';
const { chromium } = pkg;
import { createServer } from 'http';
import { readFileSync, existsSync, mkdirSync } from 'fs';
import { join, extname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const dist = join(__dirname, '..', 'dist');
const outDir = '/workspace/ListenStat-out/desktop-preview';
mkdirSync(outDir, { recursive: true });

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

const server = createServer((req, res) => {
  let url = decodeURIComponent(req.url.split('?')[0]);
  if (url === '/') url = '/index.html';
  const file = join(dist, url);
  if (!file.startsWith(dist) || !existsSync(file)) {
    res.writeHead(404);
    res.end('not found');
    return;
  }
  res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' });
  res.end(readFileSync(file));
});

await new Promise((r) => server.listen(4177, '127.0.0.1', r));
const base = 'http://127.0.0.1:4177';

const browser = await chromium.launch({
  executablePath: '/usr/bin/google-chrome',
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
});

const views = [
  ['overview', 'preview-overview.png'],
  ['rank', 'preview-rank.png'],
  ['detail', 'preview-detail.png'],
  ['achievements', 'preview-achievements.png'],
  ['settings', 'preview-settings.png'],
];

const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });

for (const [view, file] of views) {
  await page.goto(`${base}/?view=${view}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.evaluate((v) => window.__listenstat?.setView(v), view);
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outDir, file), type: 'png' });
  console.log('saved', file);
}

await browser.close();
server.close();
console.log('done', outDir);
