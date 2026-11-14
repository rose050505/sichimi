// 시연 녹화용 가상 콘솔 탭(11/13). 콘솔 페이지(http://127.0.0.1:8088/)를 헤드리스 크로미움으로 열어
// 화면 전송(CDP Page.startScreencast) 프레임을 호스트 시각과 함께 저장하고, 표준 입력 명령으로 페이지의 실제 버튼을 누른다.
// Wayland 데스크톱은 셸에서 화면 녹화를 할 수 없어서 콘솔 탭은 이렇게 따로 찍고 compose_demo.py에서 에뮬레이터 화면과 합친다.
//
//   node tools/console_tab.js <프레임 폴더>
//   표준 입력: "click X Y" (1600x1000 페이지 좌표) → "clicked <epoch초>",  "quit"
// playwright 위치: 환경 변수 PLAYWRIGHT(없으면 node 기본 검색 경로의 playwright), 브라우저는 ~/.cache/ms-playwright.
const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright');

const dir = process.argv[2];
fs.mkdirSync(dir, { recursive: true });
const index = fs.createWriteStream(path.join(dir, 'frames.tsv'));

// 누른 자리를 보이게: 누를 때 0.7초 동안 원이 퍼진다(영상에서 어떤 버튼을 눌렀는지 보이도록).
const RIPPLE = `
addEventListener('mousedown', e => {
  const d = document.createElement('div');
  d.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border-radius:50%;' +
    'border:6px solid #ffd400;background:rgba(255,212,0,.35);width:24px;height:24px;' +
    'left:' + (e.clientX - 12) + 'px;top:' + (e.clientY - 12) + 'px;transition:all .7s ease-out;opacity:1';
  document.documentElement.appendChild(d);
  requestAnimationFrame(() => requestAnimationFrame(() => {
    d.style.width = d.style.height = '96px'; d.style.left = (e.clientX - 48) + 'px'; d.style.top = (e.clientY - 48) + 'px'; d.style.opacity = '0';
  }));
  setTimeout(() => d.remove(), 900);
}, true);`;

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  await page.addInitScript(RIPPLE);
  await page.goto('http://127.0.0.1:8088/', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const cdp = await page.context().newCDPSession(page);
  let n = 0;
  cdp.on('Page.screencastFrame', async f => {
    const name = String(n++).padStart(6, '0') + '.jpg';
    fs.writeFileSync(path.join(dir, name), Buffer.from(f.data, 'base64'));
    // metadata.timestamp: 프레임을 그린 시각(epoch초), 받은 시각도 함께 적는다
    index.write(`${f.metadata.timestamp}\t${Date.now() / 1000}\t${name}\n`);
    try { await cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }); } catch (e) { /* 끝날 때 */ }
  });
  await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 85, maxWidth: 1600, maxHeight: 1000, everyNthFrame: Number(process.env.SCREENCAST_NTH || 1) });  // 본 녹화는 2(호스트 CPU 아끼기)
  console.log('ready ' + Date.now() / 1000);

  const rl = readline.createInterface({ input: process.stdin });
  for await (const line of rl) {
    const [cmd, a, b] = line.trim().split(/\s+/);
    if (cmd === 'click') {
      await page.mouse.move(+a, +b);
      const t = Date.now() / 1000;
      await page.mouse.click(+a, +b);
      console.log('clicked ' + t);
    } else if (cmd === 'quit') {
      break;
    }
  }
  try { await cdp.send('Page.stopScreencast'); } catch (e) { /* 이미 닫힘 */ }
  index.end();
  await browser.close();
  console.log('bye');
})();
