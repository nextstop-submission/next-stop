// Virtual Console(에뮬레이터 안 웹 화면, http://127.0.0.1:8088/)을 사람 대신 눌러 차량 신호를 바꾼다.
// 사람이 브라우저에서 하는 것과 같은 조작이다(공식 시연 경로). user 빌드라 adb VHAL 주입이 막혀 있어서 쓴다.
//
//   npm i --no-save playwright-core@1.48.2
//   node scripts/probe/vc-drive.mjs <출력 폴더> [순서...]
//   순서 기본값: gear:D odo:+2 gear:P door:open door:close gear:D   (odo:+k는 Virtual Console 제어 API PUT /vehicle/odometer로 누적 km를 보낸다)
//
// 출력: vc-inventory.txt(보이는 조작 요소), vc-network.txt(조작 때 오간 요청·WebSocket 프레임),
//       vc-steps.txt(단계별 결과), vc-*.png(Virtual Console 화면). Virtual Console 파일 자체는 저장하지 않는다.
import fs from 'node:fs';
import path from 'node:path';
let chromium;
try { ({ chromium } = await import('playwright-core')); } catch { ({ chromium } = await import('playwright')); }

const out = process.argv[2] || 'vc-out';
const plan = process.argv.length > 3 ? process.argv.slice(3) : ['gear:D', 'odo:+2', 'gear:P', 'door:open', 'door:close', 'gear:D'];
fs.mkdirSync(out, { recursive: true });
const net = [];
const steps = [];
const log = (s) => { steps.push(s); console.log(s); };

// GitHub 러너에는 Google Chrome이 깔려 있다(channel: chrome). VC_BROWSER=chromium이면 Playwright 기본 Chromium.
const browser = await chromium.launch(process.env.VC_BROWSER === 'chromium' ? { headless: true } : { channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
page.on('request', (r) => {
  if (/\.(js|mjs|css|png|jpe?g|svg|glb|gltf|hdr|bin|woff2?|ico)(\?|$)/i.test(r.url())) return;
  net.push(`${r.method()} ${r.url()} ${(r.postData() || '').slice(0, 400)}`);
});
let received = 0;
page.on('websocket', (ws) => {
  net.push(`WS open ${ws.url()}`);
  ws.on('framesent', (f) => net.push(`WS> ${String(f.payload).slice(0, 400)}`));
  ws.on('framereceived', (f) => { if (received++ < 300) net.push(`WS< ${String(f.payload).slice(0, 200)}`); });
});

await page.goto(process.env.VC_URL || 'http://127.0.0.1:8088/', { waitUntil: 'networkidle', timeout: 120_000 }).catch((e) => log(`goto: ${e.message}`));
await page.waitForTimeout(5_000);
await page.screenshot({ path: path.join(out, 'vc-0-start.png') });

async function inventory(name) {
  const items = await page.evaluate(() => {
    const vis = (e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    return [...document.querySelectorAll('button,[role=button],[role=tab],[role=option],input,select,label,a,li,h1,h2,h3,h4')]
      .filter(vis)
      .map((e) => {
        const t = (e.innerText || e.value || e.getAttribute('aria-label') || e.placeholder || e.title || '').trim().replace(/\s+/g, ' ').slice(0, 70);
        const cls = (e.className && e.className.toString ? e.className.toString() : '').split(/\s+/).slice(0, 2).join('.');
        return `${e.tagName.toLowerCase()}${e.type ? '[' + e.type + ']' : ''}${e.id ? '#' + e.id : ''}${cls ? '.' + cls : ''} "${t}"`;
      })
      .slice(0, 500);
  });
  fs.writeFileSync(path.join(out, `vc-inventory-${name}.txt`), items.join('\n') + '\n');
  return items;
}

async function visible(locator) {
  const n = await locator.count();
  for (let i = 0; i < n; i++) {
    const el = locator.nth(i);
    if (await el.isVisible().catch(() => false)) return el;
  }
  return null;
}

// 카드 열기: 검색칸이 있으면 이름을 넣고, 없으면 이름이 보이는 요소를 누른다.
async function openCard(names) {
  for (const name of names) {
    const search = await visible(page.locator('input[type=search], input[placeholder*="earch" i], input[placeholder*="검색"]'));
    if (search) {
      await search.fill(name);
      await page.waitForTimeout(800);
    }
    const hit = await visible(page.getByText(name, { exact: false }));
    if (hit) {
      await hit.click().catch(() => {});
      await page.waitForTimeout(1_000);
      return name;
    }
  }
  return null;
}

async function clickExact(labels) {
  for (const label of labels) {
    for (const loc of [page.getByRole('button', { name: label, exact: true }), page.getByRole('tab', { name: label, exact: true }), page.getByText(label, { exact: true })]) {
      const el = await visible(loc);
      if (el) { await el.click().catch(() => {}); await page.waitForTimeout(1_000); return label; }
    }
  }
  return null;
}

async function vehicleState() {
  return page.evaluate(() => fetch('/vehicle/state').then((r) => r.text()).catch((e) => `err ${e}`));
}
async function stateJson() {
  try { return JSON.parse(await vehicleState()); } catch { return null; }
}
// 실측(2026-10-30): /vehicle/state 예 {"gear":{"bridge_ready":false,...,"actual":null},"body":{"doors":{"front_left":false,...}}}
async function doorState() {
  const s = await stateJson();
  const v = s?.body?.doors?.front_left;
  return v === undefined || v === null ? null : (v ? 1 : 0);
}
async function gearState() {
  const s = await stateJson();
  return s?.gear?.actual ?? null;
}
// Virtual Console이 차량 쪽(DDS 브리지)과 연결되기 전에 누르면 아무 일도 일어나지 않는다 → 준비될 때까지 기다린다.
async function waitBridge(maxSec) {
  for (let t = 0; t < maxSec; t += 3) {
    const s = await stateJson();
    if (s?.gear?.bridge_ready) return t;
    await page.waitForTimeout(3_000);
  }
  return -1;
}
fs.writeFileSync(path.join(out, 'vc-state-0.txt'), (await vehicleState()).slice(0, 4000) + '\n');

const readyAfter = await waitBridge(Number(process.env.VC_BRIDGE_WAIT || 240));
log(`bridge_ready after ${readyAfter}s (−1 = never)`);
fs.writeFileSync(path.join(out, 'vc-state-1.txt'), (await vehicleState()).slice(0, 4000) + '\n');

const firstInv = await inventory('0');
log(`inventory: ${firstInv.length} items`);

let i = 0;
let odoKm = 0;
for (const step of plan) {
  i++;
  const [what, value] = step.split(':');
  const before = net.length;
  let card = null; let clicked = null;
  // 요청 시각(호스트 epoch ms). 앱 로그의 NS|SIGNAL 수신 시각과 짝지어 신호 지연을 잰다(scripts/demo/signal-latency.py).
  const at = Date.now();
  if (what === 'gear') {
    // 실측(2026-10-30): 기어는 화면 아래 P/R/N/D 버튼(button.prnd-i) → PUT /vehicle/gear?gear=Drive|Park
    card = 'prnd';
    const btn = await visible(page.locator('button.prnd-i', { hasText: new RegExp(`^${value}$`) }));
    if (btn) { await btn.click(); clicked = value; } else {
      card = await openCard(['Gear Selection', 'Gear', '기어']);
      clicked = await clickExact([value]);
    }
  } else if (what === 'door') {
    // 실측: 앞 왼쪽 문은 BODY 카드의 "FL door" 토글(button.door-fl) → PUT /vehicle/body?door_fl=1|0
    card = 'body';
    const want = value === 'open' ? 1 : 0;
    const now = await doorState();
    if (now === want) { clicked = `already ${want}`; } else {
      const btn = await visible(page.locator('button.door-fl'));
      if (btn) { await btn.click(); clicked = `FL door → ${want}`; }
    }
    await page.waitForTimeout(1_000);
    if ((await doorState()) !== want || !String(clicked).startsWith('FL door')) {
      // VC 화면의 문 토글이 굳는 일이 있다(11/5 리허설 ci-20261105-2035: door_fl=0을 보낸 뒤에도 상태가 1에 머물고,
      // 이후 클릭은 요청을 아예 보내지 않음) → 화면이 쓰는 같은 제어 API로 직접 보낸다(값이 같으면 차량 쪽 변화 없음).
      const st = await page.evaluate(async (v) => (await fetch(`/vehicle/body?door_fl=${v}`, { method: 'PUT' })).status, want).catch((e) => e.message);
      clicked = `${clicked ?? '-'} + api door_fl=${want} → ${st}`;
    }
  } else if (what === 'odo') {
    // 이 화면에는 Odometer 버튼이 없다 → Virtual Console의 같은 제어 API로 보낸다(docs/pleos/virtual-console.md). +k면 누적
    odoKm = value.startsWith('+') ? odoKm + Number(value.slice(1)) : Number(value);
    const status = await page.evaluate(async (km) => (await fetch(`/vehicle/odometer?traveled_distance_km=${km}`, { method: 'PUT' })).status, odoKm).catch((e) => e.message);
    clicked = `odometer ${odoKm}km → ${status}`;
  }
  await page.waitForTimeout(1_500);
  await page.screenshot({ path: path.join(out, `vc-${i}-${what}-${value}.png`) });
  if (i === 1) await inventory('1');
  const sent = net.slice(before).filter((l) => l.startsWith('PUT')).map((l) => l.split(' ')[1].replace('http://127.0.0.1:8088', '')).join(' ');
  log(`${i} ${step}: at=${at} via=${card ?? '-'} clicked=${clicked ?? '-'} sent=[${sent}] state: gear=${await gearState()} door_fl=${await doorState()}`);
  if (process.env.VC_AFTER_STEP) { try { (await import('node:child_process')).execSync(process.env.VC_AFTER_STEP + ' ' + i, { stdio: 'ignore', timeout: 15000 }); } catch {} }
  // 앱 쪽에서 판정이 일어날 시간(정차·문 사이클)을 둔다
  await page.waitForTimeout(2_500);
}

fs.writeFileSync(path.join(out, 'vc-network.txt'), net.join('\n') + '\n');
fs.writeFileSync(path.join(out, 'vc-steps.txt'), steps.join('\n') + '\n');
await browser.close();
