import test from 'node:test';
import assert from 'node:assert/strict';
import {
  ALL_DAYS, MAX_BACKUP_BYTES, validateBackup, parseBackup, encodeBackup, makeBackup,
  restoreStoredState, plusDays, dayDifference, weekStart, dayNumber, scheduled,
  completionRate, currentStreak, bestStreak, changeLog, changeProgress, redeemFreeze,
  archiveHabit, deleteHabit, foregroundFor, contrast, validateGoalEdit
} from '../dist/domain.js';

const today='2026-10-26';
function fixture() {
  const habit={id:1,name:'Read',description:null,icon:'📖',colorHex:'#43866B',startDate:'2026-10-01',isArchived:false,sortOrder:0,category:'Mind',afterHabitId:null,archivedOn:null,createdAt:100,updatedAt:100,scheduledDays:[...ALL_DAYS],targetCount:1,targetUnit:'times',reminderMinutes:null,scheduleHistory:[]};
  const log={id:1,habitId:1,date:'2026-10-25',status:'COMPLETED',note:'Keep this note',isFrozen:false,createdAt:100,updatedAt:100,progress:1};
  const settings={id:1,theme:'LIGHT',dynamicColor:false,backupReminder:false,lastExportAt:null,hapticsEnabled:true,animationsEnabled:true};
  return {formatVersion:2,exportedAt:100,habits:[habit],logs:[log],settings,freezes:[],pauses:[]};
}
const stateOf=b=>validateBackup(b,today);
const bad=change=>{const b=fixture();change(b);assert.throws(()=>validateBackup(b,today));};

test('full v2 JSON roundtrip preserves IDs, notes, settings, pauses and timestamps',()=>{
  const b=fixture();b.habits[0].isArchived=true;b.habits[0].archivedOn='2026-10-25';b.pauses=[{id:3,habitId:1,startDate:today,endDate:null}];
  const state=stateOf(b);assert.deepEqual(parseBackup(encodeBackup(state,today,100),today),state);
});
test('v1 migrates daily schedule, target, progress and feedback defaults',()=>{
  const b=fixture();b.formatVersion=1;delete b.pauses;
  for(const h of b.habits)for(const key of ['scheduledDays','targetCount','targetUnit','reminderMinutes','scheduleHistory'])delete h[key];
  b.logs.forEach(l=>delete l.progress);delete b.settings.hapticsEnabled;delete b.settings.animationsEnabled;
  const state=stateOf(b);assert.equal(state.logs[0].progress,1);assert.deepEqual(state.habits[0].scheduledDays,ALL_DAYS);assert.equal(state.settings.animationsEnabled,true);assert.equal(makeBackup(state).formatVersion,3);
});
test('v1 archived habit receives a pause beginning after its inclusive archive date',()=>{
  const b=fixture();b.formatVersion=1;delete b.pauses;for(const h of b.habits){h.isArchived=true;h.archivedOn='2026-10-25';for(const key of ['scheduledDays','targetCount','targetUnit','reminderMinutes','scheduleHistory'])delete h[key];}b.logs.forEach(l=>delete l.progress);delete b.settings.hapticsEnabled;delete b.settings.animationsEnabled;
  assert.deepEqual(stateOf(b).pauses,[{id:1,habitId:1,startDate:today,endDate:null}]);
});
test('UTF-8 BOM is accepted',()=>assert.deepEqual(parseBackup('\uFEFF'+JSON.stringify(fixture()),today),stateOf(fixture())));
test('missing fields are rejected rather than defaulting personal records',()=>bad(b=>delete b.habits[0].createdAt));
test('unknown fields cannot silently escape into a native backup',()=>bad(b=>b.habits[0].unknown=true));
test('invalid timestamps and timestamp ordering are rejected',()=>{bad(b=>b.logs[0].createdAt=-1);bad(b=>b.habits[0].updatedAt=99);bad(b=>b.exportedAt='100');});
test('settings IDs, booleans and date timestamps are checked',()=>{bad(b=>b.settings.id=2);bad(b=>delete b.settings.lastExportAt);bad(b=>b.settings.animationsEnabled='true');});
test('description and note data types are checked',()=>{bad(b=>b.habits[0].description={text:'not a string'});bad(b=>b.logs[0].note=4);});
test('calendar normalization does not accept impossible dates',()=>{bad(b=>b.habits[0].startDate='2026-02-30');bad(b=>b.logs[0].date='2026-13-01');});
test('future, before-start and post-archive logs are rejected',()=>{bad(b=>b.logs[0].date='2026-10-27');bad(b=>b.logs[0].date='2026-09-30');bad(b=>{b.habits[0].isArchived=true;b.habits[0].archivedOn='2026-10-20';});});
test('future habit without check-ins is valid within supported range',()=>{const b=fixture();b.habits[0].startDate='2026-11-01';b.logs=[];assert.equal(stateOf(b).habits[0].startDate,'2026-11-01');bad(b=>{b.habits[0].startDate='2126-10-27';b.logs=[];});});
test('archive flags and dates must agree',()=>{bad(b=>b.habits[0].isArchived=true);bad(b=>b.habits[0].archivedOn=today);});
test('orphan references and cyclic stacks are rejected',()=>{bad(b=>b.logs[0].habitId=2);bad(b=>b.habits[0].afterHabitId=2);bad(b=>b.habits[0].afterHabitId=1);});
test('duplicate habit IDs and duplicate daily check-ins are rejected',()=>{bad(b=>b.habits.push({...b.habits[0]}));bad(b=>b.logs.push({...b.logs[0],id:2}));});
test('unsafe JS integer IDs are rejected rather than rounded',()=>bad(b=>b.habits[0].id=Number.MAX_SAFE_INTEGER+1));
test('weekday schedules must have distinct valid days',()=>{bad(b=>b.habits[0].scheduledDays=[]);bad(b=>b.habits[0].scheduledDays=[1,1]);bad(b=>b.habits[0].scheduledDays=[8]);});
test('paused check-ins are rejected; historical entries survive later weekday changes',()=>{const b=fixture();b.habits[0].scheduledDays=[1];assert.equal(stateOf(b).logs.length,1);bad(b=>b.pauses=[{id:1,habitId:1,startDate:'2026-10-24',endDate:today}]);});
test('pause dates are ordered and intervals cannot overlap',()=>{bad(b=>b.pauses=[{id:1,habitId:1,startDate:'2026-10-20',endDate:'2026-10-20'}]);bad(b=>b.pauses=[{id:1,habitId:1,startDate:'2026-10-10',endDate:'2026-10-15'},{id:2,habitId:1,startDate:'2026-10-14',endDate:'2026-10-18'}]);});
test('open pause belongs to an archived habit',()=>bad(b=>b.pauses=[{id:1,habitId:1,startDate:today,endDate:null}]));
test('a frozen log requires a spent month and skipped status',()=>{bad(b=>b.logs[0].isFrozen=true);bad(b=>{b.logs[0].isFrozen=true;b.freezes=[{month:'2026-10',usedAt:100}];});});
test('only one frozen check-in and one credit per calendar month are accepted',()=>{bad(b=>b.freezes=[{month:'2026-10',usedAt:100},{month:'2026-10',usedAt:101}]);bad(b=>{b.logs[0]={...b.logs[0],status:'SKIPPED',progress:0,isFrozen:true};b.logs.push({...b.logs[0],id:2,date:'2026-10-24'});b.freezes=[{month:'2026-10',usedAt:100}];});});
test('numeric progress must agree with status and target',()=>{bad(b=>b.logs[0].progress=0);bad(b=>{b.logs[0].status='IN_PROGRESS';});bad(b=>{b.logs[0].status='FAILED';});});
test('reminder minute and target bounds are checked',()=>{bad(b=>b.habits[0].reminderMinutes=1440);bad(b=>b.habits[0].targetCount=0);bad(b=>b.habits[0].targetUnit='');});
test('UTF-8 byte size is bounded before parsing',()=>assert.throws(()=>parseBackup('é'.repeat(MAX_BACKUP_BYTES/2+1),today),/10 MB/));
test('corrupt persisted state is rejected without modifying caller input',()=>{const raw='{"habits":[],"logs":[]}';assert.throws(()=>restoreStoredState(raw,today));assert.equal(raw,'{"habits":[],"logs":[]}');});
test('relative date and DST calculations use calendar days',()=>{const h=fixture().habits[0];assert.equal(dayNumber(h,h.startDate),1);assert.equal(dayNumber(h,'2026-09-30'),null);assert.equal(plusDays('2026-03-08',1),'2026-03-09');assert.equal(dayDifference('2026-03-08','2026-03-09'),1);assert.equal(weekStart('2027-01-01'),'2026-12-28');});
test('weekends are neutral for weekday habits',()=>{const state=stateOf(fixture());const h={...state.habits[0],scheduledDays:[1,2,3,4,5]};const logs=[{habitId:1,date:'2026-10-23',status:'COMPLETED'},{habitId:1,date:today,status:'COMPLETED'}];assert.equal(scheduled(h,'2026-10-24'),false);assert.equal(currentStreak(h,logs,today),2);});
test('partial today has grace while partial yesterday breaks the chain',()=>{const h=stateOf(fixture()).habits[0],logs=[{habitId:1,date:'2026-10-25',status:'COMPLETED'},{habitId:1,date:today,status:'IN_PROGRESS'}];assert.equal(currentStreak(h,logs,today),1);assert.equal(currentStreak(h,[{habitId:1,date:'2026-10-25',status:'IN_PROGRESS'}],today),0);});
test('skips bridge chains without counting a completed day',()=>{const h=stateOf(fixture()).habits[0],logs=[{habitId:1,date:'2026-10-24',status:'COMPLETED'},{habitId:1,date:'2026-10-25',status:'SKIPPED'},{habitId:1,date:today,status:'COMPLETED'}];assert.equal(currentStreak(h,logs,today),2);assert.equal(bestStreak(h,logs,today),2);});
test('pause-aware denominators exclude archived intervals after restoration',()=>{let state=stateOf(fixture());state.logs=[];state=archiveHabit(state,1,'2026-10-20');state=archiveHabit(state,1,'2026-10-24');assert.deepEqual(state.pauses,[{id:1,habitId:1,startDate:'2026-10-21',endDate:'2026-10-24'}]);assert.equal(completionRate(state,'2026-10-20',today).opportunities,4);});
test('archive and restore on the same day removes the empty pause',()=>{let state=stateOf(fixture());state=archiveHabit(state,1,today);assert.equal(scheduled(state.habits[0],today,state.pauses),true);state=archiveHabit(state,1,today);assert.equal(state.pauses.length,0);});
test('a note-only edit preserves a frozen skipped entry',()=>{let state=stateOf(fixture());state.logs=[];state=redeemFreeze(state,1,'2026-10-25',today,'Rest');state=changeLog(state,1,'2026-10-25',today,'SKIPPED','Updated note');assert.equal(state.logs[0].isFrozen,true);assert.equal(state.logs[0].note,'Updated note');assert.equal(state.freezes.length,1);});
test('numeric progress increments, completes, decrements and returns to missing',()=>{let state=stateOf(fixture());state.logs=[];state.habits[0].targetCount=2;state=changeProgress(state,1,today,today,1);assert.equal(state.logs[0].status,'IN_PROGRESS');state=changeProgress(state,1,today,today,1);assert.equal(state.logs[0].status,'COMPLETED');state=changeProgress(state,1,today,today,-1);assert.equal(state.logs[0].status,'IN_PROGRESS');state=changeProgress(state,1,today,today,-1);assert.equal(state.logs.length,0);});
test('failed freeze attempt cannot spend a credit',()=>{const state=stateOf(fixture()),before=structuredClone(state);assert.throws(()=>redeemFreeze(state,1,'2026-10-25',today));assert.deepEqual(state,before);});
test('deleting a habit detaches stacks while spent freeze stays spent',()=>{const state=stateOf(fixture());state.habits.push({...state.habits[0],id:2,afterHabitId:1});state.freezes=[{month:'2026-10',usedAt:100}];const next=deleteHabit(state,1);assert.equal(next.habits[0].afterHabitId,null);assert.equal(next.logs.length,0);assert.equal(next.freezes.length,1);});
test('check-in colors choose a foreground meeting 4.5:1 contrast',()=>{for(const color of ['#43866B','#FFFFFF','#000000','#8877B0','#B48647','#B67B98'])assert.ok(contrast(color,foregroundFor(color))>=4.5);});
test('schedule revisions preserve earlier weekday patterns',()=>{
  const state=stateOf(fixture()),habit={...state.habits[0],scheduledDays:[1],scheduleHistory:[{effectiveFrom:'2026-10-01',days:[...ALL_DAYS]},{effectiveFrom:today,days:[1]}]};
  assert.equal(scheduled(habit,'2026-10-25'),true);assert.equal(scheduled(habit,'2026-10-27'),false);
  state.habits=[habit];assert.equal(completionRate(state,'2026-10-25',today).opportunities,2);
});
test('archived v2 habits require an exactly matching open pause',()=>{
  bad(b=>{b.habits[0].isArchived=true;b.habits[0].archivedOn='2026-10-25';});
  bad(b=>{b.habits[0].isArchived=true;b.habits[0].archivedOn='2026-10-25';b.pauses=[{id:1,habitId:1,startDate:'2026-10-27',endDate:null}];});
});
test('returning progress to zero keeps a saved note',()=>{
  let state=stateOf(fixture());state.habits[0].targetCount=2;state.logs[0]={...state.logs[0],date:today,progress:2};state=changeProgress(state,1,today,today,-2);
  assert.equal(state.logs[0].status,'IN_PROGRESS');assert.equal(state.logs[0].progress,0);assert.equal(state.logs[0].note,'Keep this note');
});
test('saved quantities and units cannot be changed after any check-in',()=>{
  const state=stateOf(fixture()),habit=state.habits[0],original=structuredClone(state.logs);
  assert.throws(()=>validateGoalEdit(habit,{...habit,targetCount:10},state.logs),/original goal/);
  assert.throws(()=>validateGoalEdit(habit,{...habit,targetUnit:'pages'},state.logs),/original goal/);
  assert.doesNotThrow(()=>validateGoalEdit(habit,{...habit,name:'Edited name'},state.logs));
  assert.deepEqual(state.logs,original);
});
test('a habit without check-ins can choose a new goal or unit',()=>{
  const habit=fixture().habits[0];assert.doesNotThrow(()=>validateGoalEdit(habit,{...habit,targetCount:10,targetUnit:'pages'},[]));
});
