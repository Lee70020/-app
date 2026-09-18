(function(root){
  'use strict';
  // HQU JWC 2022-08-22 timetable, still linked by the current JWC homepage.
  const bells=[[480,525],[535,580],[600,645],[655,700],[705,750],[870,915],
    [925,970],[980,1025],[1035,1080],[1100,1145],[1150,1195],[1205,1250],[1255,1300]];
  const DAY=86400000;
  const pad=n=>String(n).padStart(2,'0');
  function parts(ms){const d=new Date(ms+8*3600000);return {year:d.getUTCFullYear(),month:d.getUTCMonth()+1,
    day:d.getUTCDate(),weekday:((d.getUTCDay()+6)%7)+1,hour:d.getUTCHours(),minute:d.getUTCMinutes(),second:d.getUTCSeconds(),
    date:d.toISOString().slice(0,10)};}
  function monday(start){
    if(!/^\d{4}-\d{2}-\d{2}$/.test(start||''))return null;
    const ms=Date.parse(start+'T00:00:00+08:00');
    if(!Number.isFinite(ms)||parts(ms).date!==start)return null;
    return ms-(parts(ms).weekday-1)*DAY;
  }
  function week(start,ms){const first=monday(start);return first===null?null:Math.floor((ms-first)/(7*DAY))+1;}
  function hhmm(min){return pad(Math.floor(min/60))+':'+pad(min%60);}
  function periodText(values){return (values||[]).join('、');}
  function live(doc,now){
    const first=monday(doc&&doc.term_start),result={active:[],breaks:[],next:null,week:week(doc&&doc.term_start,now)};
    if(first===null)return result;
    const today=parts(now),courses=doc.courses||[];
    for(let index=0;index<courses.length;index++){
      const course=courses[index],periods=[...new Set(course.periods||[])].filter(p=>bells[p-1]).sort((a,b)=>a-b);
      for(const w of course.weeks||[]){
        if(w<1||w>60||course.weekday<1||course.weekday>7)continue;
        const day=first+((w-1)*7+course.weekday-1)*DAY;
        const isToday=parts(day).date===today.date;
        let inClass=false;
        for(let i=0;i<periods.length;i++){
          const p=periods[i],start=day+bells[p-1][0]*60000,end=day+bells[p-1][1]*60000;
          const event={course,index,period:p,start,end,date:parts(day).date};
          if(now>=start&&now<end){result.active.push(event);inClass=true;}
          const nextP=periods[i+1];
          if(nextP===p+1&&now>=end&&now<day+bells[nextP-1][0]*60000)
            result.breaks.push({...event,resumes:day+bells[nextP-1][0]*60000});
        }
        // While attending a block, show the next distinct course rather than its next period.
        if(inClass&&isToday)continue;
        for(const p of periods){
          const start=day+bells[p-1][0]*60000;
          if(start>now&&(!result.next||start<result.next.start))
            result.next={course,index,period:p,start,end:day+bells[p-1][1]*60000,date:parts(day).date};
        }
      }
    }
    return result;
  }
  const api={bells,parts,monday,week,hhmm,periodText,live,DAY};
  root.Timetable=api;if(typeof module!=='undefined')module.exports=api;
})(typeof window==='undefined'?globalThis:window);
