'use strict';
let model={document:null,enabled:true,daytime:true,notifications:false},following=true,selectedWeek=1,mode='grid',page='schedule',lastTick='',lastDay='',lastFocus;
const $=id=>document.getElementById(id),T=Timetable;
const dayNames=['一','二','三','四','五','六','日'];
const colors=[['#e2ebff','#6b8bdb'],['#e0f1eb','#57a08d'],['#fff0d8','#c8984e'],['#ece7fb','#9b87cd'],['#f9e3e5','#cc8f9a'],['#dff1f6','#78b4c6']];
function el(tag,cls,text){const node=document.createElement(tag);if(cls)node.className=cls;if(text!==undefined)node.textContent=text;return node;}
function act(name){if(window.App&&typeof App.action==='function')App.action(name);else if(window.previewAction)window.previewAction(name);}
function courseColor(name){let hash=0;for(const c of name)hash=(hash*31+c.charCodeAt(0))|0;return colors[Math.abs(hash)%colors.length];}
function courseWeeks(c){return '第 '+T.periodText(c.weeks)+' 周';}
function showDetail(items){
  const body=$('modalBody');body.replaceChildren();
  $('modalTitle').textContent=items.length>1?'同一时段的课程':items[0].name;
  for(const c of items){
    if(items.length>1)body.append(el('h3','',c.name));
    const pp=c.periods||[],a=T.bells[pp[0]-1],b=T.bells[pp[pp.length-1]-1];
    body.append(el('p','',`周${dayNames[c.weekday-1]||'待核对'} · 第 ${T.periodText(pp)} 节${a&&b?' · '+T.hhmm(a[0])+'–'+T.hhmm(b[1]):''}\n${c.location||'教室待核对'}${c.teacher?' · '+c.teacher:''}\n${courseWeeks(c)}`));
    if(c.raw_text)body.append(el('p','muted',c.raw_text));
  }openModal();
}
function openModal(){lastFocus=document.activeElement;$('modal').hidden=false;$('closeModal').focus();}
function closeModal(){$('modal').hidden=true;if(lastFocus)lastFocus.focus();}
function setPage(name){page=name;document.querySelectorAll('.page').forEach(n=>n.hidden=n.id!=='page-'+name);document.querySelectorAll('[data-page]').forEach(n=>{n.classList.toggle('active',n.dataset.page===name);n.setAttribute('aria-current',n.dataset.page===name?'page':'false');});if(name==='changes'){act('readChanges');$('badge').hidden=true;}window.scrollTo(0,0);}
function dateLabel(ms){const p=T.parts(ms);return `${p.month}月${p.day}日 周${dayNames[p.weekday-1]}`;}
function live(){
 const now=Date.now(),doc=model.document,parts=T.parts(now);$('clock').textContent=[parts.hour,parts.minute,parts.second].map(n=>String(n).padStart(2,'0')).join(':');
 if(!doc)return;
 const state=T.live(doc,now);$('liveDate').textContent=`${dateLabel(now)} · ${state.week!==null&&state.week>0?'第 '+state.week+' 周':'学期日期待确认'}`;
 const active=state.active[0],rest=state.breaks[0],next=state.next;
 let item=active||rest;
 $('liveLabel').textContent=active?'正在上课':rest?'课间休息':'此刻 · 校园时间';
 if(item){$('liveName').textContent=item.course.name+(state.active.length>1?' 等 '+state.active.length+' 门':'');
  $('liveDetail').textContent=active?`第 ${active.period} 节 · ${T.hhmm(T.bells[active.period-1][0])}–${T.hhmm(T.bells[active.period-1][1])} · 距下课 ${Math.ceil((active.end-now)/60000)} 分钟`:`${Math.ceil((rest.resumes-now)/60000)} 分钟后继续上课`;
  $('liveRoom').textContent=[item.course.location||'教室待核对',item.course.teacher].filter(Boolean).join(' · ');
 }else{
  $('liveName').textContent=state.week===null?'同步学期，开启实时课表':next?'现在没有课':'当前学期暂无后续课程';
  $('liveDetail').textContent=state.week===null?'升级后请同步一次，读取学期开始日期。':next?'留一点时间，准备下一程。':'没有后续课程；请确认已同步最新学期。';
  $('liveRoom').textContent='厦门校区 · 北京时间';
 }
 $('hero').onclick=item?()=>showDetail((state.active.length?state.active:state.breaks).map(x=>x.course)):null;
 if(next){$('nextName').textContent=next.course.name;const min=Math.ceil((next.start-now)/60000);$('nextDetail').textContent=`${next.date===parts.date?'今天':dateLabel(next.start)} ${T.hhmm(T.bells[next.period-1][0])} · ${next.course.location||'教室待核对'}${min<=120?' · '+min+'分钟后':''}`;$('next').onclick=()=>showDetail([next.course]);}
 else{$('nextName').textContent=state.week===null?'等待学期信息':'暂无后续课程';$('nextDetail').textContent=state.week===null?'同步后自动计算周次':'以已同步的整学期安排为准';$('next').onclick=null;}
 document.querySelectorAll('.course').forEach(n=>n.classList.toggle('current',selectedWeek===state.week&&state.active.some(x=>n.dataset.indices.split(',').includes(String(x.index)))));
}
function renderGrid(){
 const root=$('timetable');root.replaceChildren();const doc=model.document,now=Date.now(),current=doc?T.week(doc.term_start,now):null;
 if(following&&current!==null)selectedWeek=Math.min(60,Math.max(1,current));
 $('weekTitle').textContent=`第 ${selectedWeek} 周${selectedWeek===current?' · 本周':''}`;
 const start=doc?T.monday(doc.term_start):null,mon=start===null?null:start+(selectedWeek-1)*7*T.DAY;
 $('weekDate').textContent=mon===null?'同步后自动计算周次':`${dateLabel(mon).split(' ')[0]} — ${dateLabel(mon+6*T.DAY).split(' ')[0]}`;
 $('prev').disabled=selectedWeek<=1;$('nextWeek').disabled=selectedWeek>=60;
 if(!doc){const box=el('div','empty');box.append(el('h2','','把课表装进口袋'),el('p','','同步一次，整学期离线可看。'));const b=el('button','primary','登录并同步');b.onclick=()=>act('sync');box.append(b);root.append(box);return;}
 const all=(doc.courses||[]).map((c,index)=>({...c,index})),visible=all.filter(c=>!c.weeks?.length||c.weeks.includes(selectedWeek));
 $('viewHint').textContent=visible.length?`${visible.length} 条安排 · 点击课程查看完整信息${mode==='grid'?' · 同时段课程合并显示':''}`:'本周没有匹配课程，可切换周次或同步最新课表。';
 if(!visible.length){root.append(el('div','empty','这一周，课表留白。'));return;}
 if(mode==='list'){
  for(let day=1;day<=8;day++){const classes=visible.filter(c=>(c.weekday||8)===day).sort((a,b)=>(a.periods?.[0]||99)-(b.periods?.[0]||99));if(!classes.length)continue;
   const group=el('section','daylist');group.append(el('h3','',day<=7?'周'+dayNames[day-1]:'星期待核对'));
   for(const c of classes){const card=el('button','listcard'),a=T.bells[(c.periods||[])[0]-1];const time=el('div','listtime',a?T.hhmm(a[0]):'待核对');time.append(el('small','',`第${T.periodText(c.periods)}节`));const info=el('div');info.append(el('strong','',c.name),el('small','',[c.location,c.teacher].filter(Boolean).join(' · ')));card.append(time,info);card.onclick=()=>showDetail([c]);group.append(card);}root.append(group);
  }return;
 }
 const wrap=el('div','gridwrap'),grid=el('div','grid');grid.style.gridTemplateRows='48px repeat(13,54px)';
 grid.append(el('div'));
 for(let day=1;day<=7;day++){const date=mon===null?null:T.parts(mon+(day-1)*T.DAY),head=el('div','day'+(date&&date.date===T.parts(now).date?' today':''),'周'+dayNames[day-1]);head.style.gridArea=`1 / ${day+1}`;head.append(el('strong','',date?String(date.day):''));grid.append(head);}
 for(let p=1;p<=13;p++){const label=el('div','period',String(p));label.style.gridArea=`${p+1} / 1`;label.append(el('small','',T.hhmm(T.bells[p-1][0])));grid.append(label);for(let day=1;day<=7;day++){const bg=el('div','cell'+(selectedWeek===current&&day===T.parts(now).weekday?' todaycol':''));bg.style.gridArea=`${p+1} / ${day+1}`;grid.append(bg);}}
 // Merge only overlapping time spans; retain every course in a tappable detail list.
 for(let day=1;day<=7;day++){
  let blocks=[];
  for(const c of visible.filter(c=>c.weekday===day)){
   const pp=[...new Set(c.periods||[])].filter(p=>p>=1&&p<=13).sort((a,b)=>a-b);let spans=[];
   for(const p of pp){if(spans.length&&p===spans[spans.length-1][1]+1)spans[spans.length-1][1]=p;else spans.push([p,p]);}
   for(const [a,b] of spans)blocks.push({a,b,items:[c]});
  }
  blocks.sort((a,b)=>a.a-b.a);let merged=[];
  for(const block of blocks){const prev=merged[merged.length-1];if(prev&&block.a<=prev.b){prev.b=Math.max(prev.b,block.b);prev.items.push(...block.items);}else merged.push(block);}
  for(const block of merged){const c=block.items[0],btn=el('button','course'+(block.a===block.b?' single':'')),color=courseColor(c.name);btn.style.background=color[0];btn.style.setProperty('--course-accent',color[1]);btn.style.gridArea=`${block.a+1} / ${day+1} / ${block.b+2} / ${day+2}`;btn.dataset.indices=block.items.map(x=>x.index).join(',');btn.append(el('strong','',c.name),el('small','',c.location||'待核对'));if(block.items.length>1)btn.append(el('span','count',`同段 ${block.items.length} 门`));btn.setAttribute('aria-label',`周${dayNames[day-1]} 第${block.a}至${block.b}节 ${block.items.map(c=>c.name).join('、')}`);btn.onclick=()=>showDetail(block.items);grid.append(btn);}
 }
 wrap.append(grid);root.append(wrap);
 if(visible.some(c=>!c.weekday||(c.periods||[]).some(p=>p>13)||!c.periods?.length))root.append(el('p','footnote','部分安排超出网格范围，请切换列表核对。'));
}
function renderChanges(){
 const history=$('history'),specials=$('specials');history.replaceChildren();specials.replaceChildren();
 const doc=model.document||{},items=doc.change_history||[];
 if(!items.length)history.append(el('div','empty','暂无调整记录。首次同步建立基线，后续有变化才提醒。'));
 for(const b of items){const card=el('article','record');card.append(el('h3','',`${b.count} 条安排变化`),el('time','',new Date(b.at).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})));for(const line of b.lines||[])card.append(el('p',line.startsWith('原安排')?'old':line.startsWith('现安排')?'new':'',line));history.append(card);}
 if(doc.special_arrangements?.length){specials.append(el('h3','','校方特殊安排 · 待核对'));for(const c of doc.special_arrangements){const card=el('article','record');card.append(el('h3','',c.name||'未命名安排'),el('p','',[c.description,c.location,c.teacher].filter(Boolean).join(' · ')||'请打开教务查看详情'),el('p','muted',`校方类型 ${c.type}，未作为正常课程排入网格。`));specials.append(card);}}
}
function renderSettings(){
 for(const [id,value] of [['auto',model.enabled],['day',model.daytime]]){$(id+'Switch').classList.toggle('on',!!value);$(id+'Toggle').setAttribute('aria-checked',String(!!value));}
 $('notifyState').textContent=model.notifications?'已允许课表变化通知':'尚未允许 · 点击开启';$('lastAttempt').textContent=model.lastAttempt?'最近尝试 '+model.lastAttempt:'查看最近请求信息';
 $('scheduleInfo').textContent=model.enabled?`下次计划检查：${model.nextCheck||'等待安排'}。实际时间可能因省电或网络推迟。`:'自动更新已关闭，仍可手动同步。';
}
window.renderModel=function(value){
 model=value;const doc=model.document;
 $('sync').disabled=!!model.busy;$('sync').querySelector('span').textContent=model.busy?'同步中':'同步';
 const scraped=doc?.scraped_at?new Date(doc.scraped_at).getTime():0;
 let label=model.lastSuccess?'更新于 '+model.lastSuccess:scraped?'已载入本地课表':'尚未同步';
 if(model.status==='login')label='登录已过期 · 请重新同步';else if(model.status==='error')label='检查未成功 · 旧课表已保留';
 $('syncStatus').textContent=label;$('status').classList.toggle('warn',model.status==='login'||model.status==='error'||(scraped>0&&Date.now()-scraped>86400000));
 $('checkStatus').textContent=model.scheduleFailed?'后台任务安排失败，请重开更新':!model.enabled?'自动更新已关闭':!model.notifications?'提醒通知未开启':'自动检查已开启';
 $('badge').hidden=!model.unread;renderGrid();renderChanges();renderSettings();live();if(model.openChanges)setPage('changes');
 const specials=doc?.special_arrangements?.length||0;$('specialNotice').hidden=!specials;$('specialNotice').textContent=`${specials} 条校方特殊安排待核对 · 实时课程暂按普通课表显示`;
};
document.querySelectorAll('[data-action]').forEach(n=>n.onclick=()=>act(n.dataset.action));document.querySelectorAll('[data-page]').forEach(n=>n.onclick=()=>setPage(n.dataset.page));
$('prev').onclick=()=>{following=false;selectedWeek=Math.max(1,selectedWeek-1);renderGrid();live();};$('nextWeek').onclick=()=>{following=false;selectedWeek=Math.min(60,selectedWeek+1);renderGrid();live();};$('today').onclick=()=>{following=true;renderGrid();live();};
for(const type of ['grid','list'])$(type+'Mode').onclick=()=>{mode=type;for(const t of ['grid','list']){$(t+'Mode').classList.toggle('active',t===type);$(t+'Mode').setAttribute('aria-pressed',String(t===type));}renderGrid();live();};
$('autoToggle').onclick=()=>act('toggleAuto');$('dayToggle').onclick=()=>act('toggleDay');$('closeModal').onclick=closeModal;
$('specialNotice').onclick=()=>setPage('changes');
$('modal').onclick=e=>{if(e.target===$('modal'))closeModal();};document.addEventListener('keydown',e=>{if(e.key==='Escape')closeModal();if(e.key==='Tab'&&!$('modal').hidden){e.preventDefault();$('closeModal').focus();}});
$('bells').onclick=()=>{$('modalTitle').textContent='厦门校区上课时间';const body=$('modalBody');body.replaceChildren();const table=el('div','belltable');T.bells.forEach((b,i)=>table.append(el('div','',`第 ${i+1} 节  ${T.hhmm(b[0])}–${T.hhmm(b[1])}`)));body.append(table,el('p','footnote','来源：华侨大学教务处《作息时间表》（2022-08-22），与当前官网教学日历一致。第10节官网标注不排课；若有课表记录仍按该节时间显示。'));openModal();};
window.homeBack=()=>{if(!$('modal').hidden){closeModal();return true;}if(page!=='schedule'){setPage('schedule');return true;}return false;};
function tick(){const p=T.parts(Date.now()),minute=p.date+':'+p.hour+':'+p.minute;if(lastDay!==p.date){lastDay=p.date;renderGrid();}if(lastTick!==minute){lastTick=minute;live();}else $('clock').textContent=[p.hour,p.minute,p.second].map(n=>String(n).padStart(2,'0')).join(':');}
setInterval(tick,1000);document.addEventListener('visibilitychange',()=>{if(!document.hidden){renderGrid();live();}});window.renderModel(model);tick();
