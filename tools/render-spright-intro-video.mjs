import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdirSync, renameSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE ?? 'playwright');

const root = resolve(import.meta.dirname, '..');
const source = `file:///${resolve(root, 'docs', 'spright-intro-animation.html').replaceAll('\\', '/')}`;
const outputDirectory = resolve(root, 'docs', 'video');
const webmOutput = resolve(outputDirectory, 'spright-intro-16x9.webm');
const mp4Output = resolve(outputDirectory, 'spright-intro-16x9.mp4');
const durationMs = 38_500;

mkdirSync(outputDirectory, { recursive: true });
rmSync(webmOutput, { force: true });
rmSync(mp4Output, { force: true });

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1920, height: 1080 },
  deviceScaleFactor: 1,
  recordVideo: { dir: outputDirectory, size: { width: 1920, height: 1080 } },
});
const page = await context.newPage();
await page.goto(source, { waitUntil: 'networkidle' });
await page.waitForTimeout(durationMs);
const video = page.video();
await page.close();
await context.close();
await browser.close();

renameSync(await video.path(), webmOutput);
execFileSync(
  'ffmpeg',
  [
    '-y', '-i', webmOutput,
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '18',
    '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
    mp4Output,
  ],
  { stdio: 'inherit' },
);
console.log(`Created ${mp4Output}`);