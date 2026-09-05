// 简化的截图脚本
import { chromium } from 'playwright';

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1920, height: 1080 }
  });
  const page = await context.newPage();

  console.log('访问登录页面...');
  await page.goto('http://localhost:5173/login');
  await page.waitForLoadState('networkidle');

  console.log('登录...');
  await page.fill('input[type="email"]', 'town-demo-1788569807@example.com');
  await page.fill('input[type="password"]', 'Correct-Horse-Battery-2026!');
  await page.click('button[type="submit"]');

  console.log('等待跳转到小镇...');
  await page.waitForURL('**/town', { timeout: 10000 });
  await page.waitForTimeout(3000); // 等待场景加载

  console.log('截图：改造后全景...');
  await page.screenshot({
    path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-after-wide.png',
    fullPage: false
  });

  console.log('移动到咖啡馆区域...');
  await page.mouse.click(600, 600);
  await page.waitForTimeout(2000);
  await page.screenshot({
    path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-venue-cafe.png'
  });

  console.log('移动到公园区域...');
  await page.mouse.click(800, 600);
  await page.waitForTimeout(2000);
  await page.screenshot({
    path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-venue-park.png'
  });

  console.log('完成！');
  await browser.close();
})();
