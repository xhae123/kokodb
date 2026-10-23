#!/usr/bin/env node
// Export SVG brand assets at 2x using a separate, temporary Chromium profile.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const chrome = process.argv[2];
if (!chrome) throw new Error('Usage: node docs/assets/export-brand.mjs /path/to/chrome');
const here = dirname(fileURLToPath(import.meta.url));
const profile = mkdtempSync(join(tmpdir(), 'kokodb-brand-'));
const child = spawn(chrome, ['--headless', '--disable-gpu', '--disable-extensions', '--no-first-run',
  '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
let launchError;
child.on('error', error => { launchError = error; });
let ws;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
try {
  const active = join(profile, 'DevToolsActivePort');
  const deadline = Date.now() + 15000;
  while (!existsSync(active)) {
    if (launchError) throw launchError;
    if (child.exitCode !== null || Date.now() > deadline) throw new Error('Chromium did not start');
    await delay(100);
  }
  const port = readFileSync(active, 'utf8').split('\n')[0];
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  let id = 0;
  const pending = new Map();
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const request = pending.get(message.id);
    pending.delete(message.id);
    clearTimeout(request.timeout);
    message.error ? request.reject(new Error(message.error.message)) : request.resolve(message.result);
  });
  function call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Timed out: ${method}`)), 15000);
      pending.set(++id, { resolve, reject, timeout });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }
  await call('Page.enable');
  await call('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
  for (const [name, width, height] of [['logo', 256, 256], ['logo-light', 256, 256], ['logo-tile', 256, 256], ['hero', 500, 160]]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 2, mobile: false });
    const loaded = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`Page load timed out: ${name}`)), 15000);
      ws.addEventListener('message', function listener(event) {
        if (JSON.parse(event.data).method !== 'Page.loadEventFired') return;
        ws.removeEventListener('message', listener);
        clearTimeout(timeout);
        resolve();
      });
    });
    await call('Page.navigate', { url: pathToFileURL(join(here, `${name}.svg`)).href });
    await loaded;
    await call('Runtime.evaluate', { expression: 'document.fonts.ready', awaitPromise: true });
    const { data } = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    writeFileSync(join(here, `${name}.png`), Buffer.from(data, 'base64'));
    console.log(`Exported ${name}.png (${width * 2} × ${height * 2})`);
  }
} finally {
  ws?.close();
  child.kill();
  for (let attempt = 0; child.exitCode === null && child.signalCode === null && attempt < 30; attempt++) await delay(100);
  rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
