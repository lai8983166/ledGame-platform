import {expect} from '@playwright/test';
import {test} from '../support/storeTest';

test('编辑器单页调试：真实多人准入、暂停、重新准入、自然结算与两类编辑器返回', async ({store}, testInfo) => {
  test.setTimeout(240_000);
  const page=store.mainPage;
  const request=async (route:string,method='GET',body?:unknown)=>{
    const response=await fetch(`${store.gameBaseUrl}${route}`,{method,headers:{'content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
    expect(response.ok,await response.clone().text()).toBe(true);
    return (await response.json()).data;
  };
  const list=await request('/games/manageable');
  const simple=list.find((g:any)=>g.name==='simple');
  const rank=list.find((g:any)=>g.type==='rank');
  expect(simple).toBeTruthy();expect(rank).toBeTruthy();
  const simpleDoc=await request(`/game-editor/${simple.gameId}`);
  simpleDoc.participants=2;simpleDoc.globalTimeLimit=true;simpleDoc.globalTimeLimitValue=60;
  simpleDoc.levels=[simpleDoc.levels[0]];
  simpleDoc.siteSizeWidth=16;simpleDoc.siteSizeHeight=36;
  simpleDoc.wiringData={runtimeEnabled:true,width:16,height:36,mode:'SINGLE_ROW_PRIORITY',maxPointsPerChannel:64,lines:[[[0,0],[1,0]]]};
  await request(`/game-editor/${simple.gameId}`,'PUT',simpleDoc);
  const rankDoc=await request(`/rank-game-editor/${rank.gameId}`);
  rankDoc.wiringData={runtimeEnabled:true,width:rankDoc.siteSizeWidth,height:rankDoc.siteSizeHeight,mode:'SINGLE_COL_PRIORITY',maxPointsPerChannel:64,lines:[[[1,0],[0,0]]]};
  await request(`/rank-game-editor/${rank.gameId}`,'PUT',rankDoc);
  for(const [index,uid] of ['810000001','810000002'].entries()){
    await store.chargeWristband(uid,60);
    await store.registerAndBind({phone:`1396000000${index}`,name:`调试验收玩家${index+1}`,uid});
  }
  const button=(name:string)=>page.getByRole('button',{name,exact:true});
  const workspace=page.locator('.editor-debug-workspace');
  const state=()=>request('/engine/game/state');
  async function enter(id:number){
    await page.getByTestId('game-menu-button').click();
    await page.getByRole('menuitem',{name:'游戏列表',exact:true}).click();
    await page.locator(`.game-card[data-id="${id}"] .game-card-main`).click();
    await button('启动游戏').click();
    if(await page.getByRole('dialog').count())await button('使用已保存版本').click();
    await workspace.waitFor();
    expect(store.gameWindows.filter(w=>/window=(touch|debug)/.test(w.url()))).toHaveLength(0);
  }
  await enter(simple.gameId);
  await workspace.locator('select').first().selectOption('wristband');
  await workspace.locator('input').first().fill('2');
  async function scan(uid:string){await button('请刷手环').click();await page.locator('.debug-scan-backdrop').waitFor();await expect(button('取消')).toBeEnabled();await page.keyboard.type(uid);await page.keyboard.press('Enter');await page.locator('.debug-scan-backdrop').waitFor({state:'hidden'});}
  await scan('810000001');expect((await state()).playerAccesses).toHaveLength(1);
  await button('启动游戏').click();await page.locator('.debug-scan-backdrop').waitFor();
  expect((await state()).engineState).toBe('PREPARING');await button('取消').click();
  await scan('810000002');await button('启动游戏').click();
  await expect.poll(async()=> (await state()).engineState).toBe('RUNNING');
  const first=await state();expect(first.runtimeMode).toBe('SIMULATION');expect(first.userCount).toBe(2);expect(first.playerAccesses).toHaveLength(2);
  await button('暂停').click();const frozen=await state();
  await page.waitForTimeout(1200);expect((await state()).gameTime.remainingMillis).toBe(frozen.gameTime.remainingMillis);
  await button('继续').click();await button('重新开始').click();
  await page.locator('.debug-scan-backdrop').waitFor();await button('取消').click();
  expect((await state()).sessionId).not.toBe(first.sessionId);expect((await state()).playerAccesses).toHaveLength(0);
  await scan('810000001');await scan('810000002');await button('启动游戏').click();
  await expect.poll(async()=> (await state()).engineState).toBe('RUNNING');
  await expect.poll(async()=>page.locator('.debug-workspace-rgb canvas').evaluate((canvas:HTMLCanvasElement)=>canvas.width)).toBeGreaterThan(0);
  // Same coordinate as the seeded blue target; derive the click from the real canvas geometry.
  await page.waitForTimeout(350);
  await page.locator('.debug-workspace-rgb canvas').click({position:{x:20,y:20}});
  await expect.poll(async()=> (await state()).engineState,{timeout:30_000}).toBe('STOPPED');
  expect((await state()).terminationReason).toBe('NATURAL_SUCCESS');
  await expect.poll(async()=>{
    const info=await (await fetch(`${store.platformBaseUrl}/api/player-info?phone=13960000000`)).json();
    return info.points.total;
  }).toBe(3);
  await expect.poll(async()=>{
    const info=await (await fetch(`${store.platformBaseUrl}/api/player-info?phone=13960000001`)).json();
    return info.points.total;
  }).toBe(3);
  await page.screenshot({path:testInfo.outputPath('多人调试自然结算.png')});
  await button('退出调试，返回编辑').click();await button('启动游戏').waitFor();
  await button('启动游戏').click();if(await page.getByRole('dialog').count())await button('使用已保存版本').click();
  await workspace.waitFor();await workspace.locator('select').first().selectOption('debug');await button('启动游戏').click();
  await expect.poll(async()=> (await state()).engineState).toBe('RUNNING');await button('退出调试，返回编辑').click();
  await page.getByRole('button',{name:'返回列表',exact:true}).click();
  await enter(rank.gameId);await button('启动游戏').click();
  // Real seeded startup voice/animation must finish; do not apply the 15s UI-action timeout to media playback.
  await expect.poll(async()=> (await state()).engineState,{timeout:60_000}).toBe('RUNNING');
  expect((await state()).gameplay.players).not.toHaveLength(0);
  await button('暂停').click();
  const rankPaused=await state();
  expect(rankPaused.gameplay.remainingMillis).toBeGreaterThan(0);
  const rankRemaining=rankPaused.gameTime?.mode==='LIMITED'
    ? Math.min(rankPaused.gameplay.remainingMillis,rankPaused.gameTime.remainingMillis)
    : rankPaused.gameplay.remainingMillis;
  await expect(workspace.locator('dl dd').nth(4)).toHaveText(String(Math.ceil(rankRemaining/1000)));
  await page.screenshot({path:testInfo.outputPath('Rank调试暂停.png')});
  await button('退出调试，返回编辑').click();await expect(workspace).toHaveCount(0);await button('启动游戏').waitFor();
  await expect.poll(async()=> (await state()).engineState).toBe('STOPPED');
  expect((await request(`/game-editor/${simple.gameId}`)).wiringData.lines).toEqual([[[0,0],[1,0]]]);
  expect((await request(`/rank-game-editor/${rank.gameId}`)).wiringData.lines).toEqual([[[1,0],[0,0]]]);
});
