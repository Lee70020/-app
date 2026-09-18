const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root=path.join(__dirname,'../app/src/main/assets');
const output=path.join(__dirname,'../build/ui-tests');fs.mkdirSync(output,{recursive:true});
const html=fs.readFileSync(path.join(root,'home.html'),'utf8').replace('/*TIMETABLE_SCRIPT*/',fs.readFileSync(path.join(root,'timetable.js'),'utf8')).replace('/*HOME_SCRIPT*/',fs.readFileSync(path.join(root,'home.js'),'utf8'));
const courses=[
 {name:'外交学导论',weekday:1,periods:[3,4],location:'D2-404',teacher:'示例教师'},
 {name:'大学英语 3',weekday:2,periods:[1,2],location:'C2-403',teacher:'示例教师'},
 {name:'外交政策分析',weekday:3,periods:[3,4],location:'F5-102',teacher:'示例教师'},
 {name:'习近平新时代中国特色社会主义思想概论',weekday:4,periods:[3,4],location:'E1-202',teacher:'示例教师'},
 {name:'国际组织概论',weekday:5,periods:[1,2],location:'D4-301',teacher:'示例教师'},
 {name:'体育',weekday:1,periods:[6,7],location:'体育馆',teacher:'示例教师'},
 {name:'世界经济概论',weekday:4,periods:[6,7],location:'D2-302',teacher:'示例教师'},
 {name:'国际关系史',weekday:3,periods:[8,9],location:'C4-302',teacher:'示例教师'}
].map(c=>({...c,weeks:Array.from({length:16},(_,i)=>i+1),raw_text:'预览示例数据，用于界面验证。'}));
const model={document:{term_start:'2026-09-14',semester:'2026-2027学年第1学期',scraped_at:'2026-09-17T00:00:00Z',courses,change_history:[{id:'fixture',at:'2026-09-17T00:00:00Z',count:2,lines:['原安排移除：外交学导论 · 周一 第3-4节 · 第1-16周 · A101','现安排新增：外交学导论 · 周一 第3-4节 · 第1-16周 · D2-404']}],special_arrangements:[]},enabled:true,daytime:true,notifications:true,unread:true,lastSuccess:'09-17 08:00',lastAttempt:'09-17 08:00',nextCheck:'09-17 14:00',status:'ok'};
(async()=>{
 const browser=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL || 'msedge'});
 const p=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2});
 const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.clock.install({time:new Date('2026-09-17T10:20:00+08:00')});
 await p.setContent(html);await p.evaluate(m=>window.renderModel(m),model);
 assert.equal(await p.locator('#liveLabel').textContent(),'正在上课');
 assert.match(await p.locator('#liveName').textContent(),/习近平/);
 assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 await p.screenshot({path:path.join(output,'ui-mobile.png'),fullPage:true});
 await p.locator('.course').first().click();assert.equal(await p.locator('#modal').isVisible(),true);await p.locator('#closeModal').click();
 await p.locator('#nextWeek').click();assert.match(await p.locator('#weekTitle').textContent(),/第 2 周/);assert.match(await p.locator('#liveName').textContent(),/习近平/);
 await p.locator('#today').click();assert.match(await p.locator('#weekTitle').textContent(),/第 1 周/);
 await p.locator('#listMode').click();assert.equal(await p.locator('.listcard').count(),8);
 await p.locator('[data-page="changes"]').click();assert.equal(await p.locator('.record').count(),1);await p.screenshot({path:path.join(output,'ui-changes.png'),fullPage:true});
 await p.locator('[data-page="settings"]').click();await p.locator('#bells').click();assert.equal(await p.locator('.belltable div').count(),13);await p.locator('#closeModal').click();await p.screenshot({path:path.join(output,'ui-settings.png'),fullPage:true});
 await p.evaluate(()=>{window.actions=[];window.previewAction=a=>window.actions.push(a);});await p.locator('#autoToggle').click();await p.locator('[data-action="notifications"]').click();assert.deepEqual(await p.evaluate(()=>window.actions),['toggleAuto','notifications']);
 await p.locator('[data-page="schedule"]').click();await p.locator('#gridMode').click();
 await p.setViewportSize({width:320,height:740});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await p.screenshot({path:path.join(output,'ui-small.png'),fullPage:true});
 await p.setViewportSize({width:768,height:1024});assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await p.screenshot({path:path.join(output,'ui-tablet.png'),fullPage:true});
 const hostile=JSON.parse(JSON.stringify(model));hostile.document.courses[0].name='<img src=x onerror="window.PWNED=1">';await p.evaluate(m=>window.renderModel(m),hostile);assert.equal(await p.evaluate(()=>window.PWNED),undefined);assert.equal(await p.locator('#timetable img').count(),0);
 await p.evaluate(m=>window.renderModel(m),model);await p.clock.setFixedTime(new Date('2026-09-21T00:00:00+08:00'));await p.evaluate(()=>tick());assert.match(await p.locator('#weekTitle').textContent(),/第 2 周/);
 await p.evaluate(()=>window.renderModel({document:null,enabled:true}));assert.match(await p.locator('#timetable').textContent(),/登录并同步/);
 assert.deepEqual(errors,[]);console.log('UI verified: live course, browse/current isolation, details, lists, navigation, settings actions, 320/390/768px, XSS escaping, week rollover, empty state; no page errors.');
 fs.writeFileSync(path.join(output,'ui-preview.html'),html.replace('</body>','<script>window.renderModel('+JSON.stringify(model)+');</script></body>'));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
