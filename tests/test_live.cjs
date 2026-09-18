const assert = require('node:assert/strict');
const tt = require('../app/src/main/assets/timetable.js');
assert.equal(typeof tt.live, 'function', 'live timetable engine must exist');
const at=s=>Date.parse(s+'+08:00');
const doc={term_start:'2026-09-14',courses:[
 {name:'上午课',weekday:1,weeks:[1,2],periods:[1,2],location:'A101'},
 {name:'下午课',weekday:1,weeks:[1,2],periods:[6,7],location:'B202'},
 {name:'次日课',weekday:2,weeks:[1,2],periods:[3,4],location:'C303'}]};
assert.equal(tt.live(doc,at('2026-09-14T08:00:00')).active[0].course.name,'上午课');
assert.equal(tt.live(doc,at('2026-09-14T08:45:00')).active.length,0);
assert.equal(tt.live(doc,at('2026-09-14T08:45:00')).breaks[0].course.name,'上午课');
assert.equal(tt.live(doc,at('2026-09-14T08:55:00')).active[0].period,2);
assert.equal(tt.live(doc,at('2026-09-14T09:40:00')).next.course.name,'下午课');
assert.equal(tt.live(doc,at('2026-09-14T14:30:00')).active[0].course.name,'下午课');
assert.equal(tt.live(doc,at('2026-09-14T16:10:00')).next.course.name,'次日课');
assert.equal(tt.week(doc.term_start,at('2026-09-20T23:59:59')),1);
assert.equal(tt.week(doc.term_start,at('2026-09-21T00:00:00')),2);
assert.equal(tt.live(doc,at('2026-09-21T08:00:00')).active[0].course.name,'上午课');
assert.equal(tt.live(doc,at('2026-09-28T08:00:00')).active.length,0);
assert.equal(tt.live(doc,at('2026-09-28T08:00:00')).next,null);
assert.equal(tt.week('',at('2026-09-14T08:00:00')),null);
assert.equal(tt.live({...doc,term_start:''},at('2026-09-14T08:00:00')).active.length,0);
const gaps={...doc,courses:[{...doc.courses[0],periods:[1,4]}]};
assert.equal(tt.live(gaps,at('2026-09-14T09:00:00')).breaks.length,0);
assert.equal(tt.live({...doc,courses:[...doc.courses,doc.courses[0]]},at('2026-09-14T08:10:00')).active.length,2);
console.log('Live timetable: 16 assertions passed');
