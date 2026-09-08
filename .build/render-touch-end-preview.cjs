const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
(async () => {
  const source = fs.readFileSync('../ledGame/src/views/LedGameTouchView.vue', 'utf8');
  const matrix = fs.readFileSync('../ledGame/src/components/TouchMatrixCanvas.vue', 'utf8');
  const css = source.match(/<style scoped>([\s\S]*?)<\/style>/)[1];
  const matrixCss = matrix.match(/<style scoped>([\s\S]*?)<\/style>/)[1];
  const drawing = matrix.slice(matrix.indexOf('function draw(now)'), matrix.indexOf('</script>')).replace('animationFrame = requestAnimationFrame(draw);', '');
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1 });
    await page.setContent(`<html lang="zh-CN"><meta charset="utf-8"><style>body{margin:0;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}button{font:inherit}*{box-sizing:border-box}${css}${matrixCss}</style><main class="touch-shell touch-game-presentation"><canvas class="touch-matrix-canvas"></canvas><div class="touch-game-motion"><span></span><span></span><span></span></div><section class="touch-center touch-status-panel"><span class="touch-kicker">GAME OVER</span><h1>本局游戏已结束</h1><button class="touch-primary-button">返回待机</button></section></main></html>`);
    await page.evaluate(drawing => {
      const script = document.createElement('script');
      script.textContent = `const canvas={value:document.querySelector('canvas')};const props={mode:'failure'};const startedAt=0;canvas.value.width=1280;canvas.value.height=720;${drawing};draw(1000);`;
      document.body.append(script);
    }, drawing);
    await page.screenshot({ path: path.resolve('.build/touch-end-preview.png') });
  } finally { await browser.close(); }
})();
