/** Pure domain rules shared by the browser UI, import validation, analytics and tests. */
export const MAX_BACKUP_BYTES = 10 * 1024 * 1024;
export const ALL_DAYS = Object.freeze([1, 2, 3, 4, 5, 6, 7]);
export const STATUSES = Object.freeze(['COMPLETED', 'SKIPPED', 'FAILED', 'IN_PROGRESS']);
const HABIT_V1 = ['id','name','description','icon','colorHex','startDate','isArchived','sortOrder','category','afterHabitId','archivedOn','createdAt','updatedAt'];
const HABIT_V2 = [...HABIT_V1,'scheduledDays','targetCount','targetUnit','reminderMinutes','scheduleHistory'];
const LOG_V1 = ['id','habitId','date','status','note','isFrozen','createdAt','updatedAt'];
const LOG_V2 = [...LOG_V1,'progress'];
const SETTINGS_V1 = ['id','theme','dynamicColor','backupReminder','lastExportAt'];
const SETTINGS_V2 = [...SETTINGS_V1,'hapticsEnabled','animationsEnabled'];

export const copy = value => structuredClone(value);
export const localDate = (date = new Date()) => `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
export function isDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T12:00:00Z`);
  return !Number.isNaN(date.valueOf()) && date.toISOString().slice(0,10) === value;
}
export function plusDays(value, days) {
  if (!isDate(value) || !Number.isInteger(days)) throw new Error('Choose a valid calendar date.');
  const date = new Date(`${value}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0,10);
}
export const dayDifference = (from,to) => Math.round((Date.parse(`${to}T12:00:00Z`) - Date.parse(`${from}T12:00:00Z`)) / 86400000);
export const weekday = date => (new Date(`${date}T12:00:00Z`).getUTCDay() || 7);
export const weekStart = date => plusDays(date, 1 - weekday(date));
export const dayNumber = (habit,date) => date < habit.startDate ? null : dayDifference(habit.startDate,date) + 1;
export function scheduledDaysOn(habit,date) {
  const history=habit.scheduleHistory || [];
  return history.length?(history.filter(r=>r.effectiveFrom<=date).at(-1)||history[0]).days:(habit.scheduledDays || ALL_DAYS);
}
export function scheduled(habit,date,pauses=[]) {
  return date >= habit.startDate && (!habit.archivedOn || date <= habit.archivedOn) &&
    scheduledDaysOn(habit,date).includes(weekday(date)) &&
    !pauses.some(p => p.habitId === habit.id && date >= p.startDate && (p.endDate == null || date < p.endDate));
}
export const dueHabits = (state,date) => state.habits.filter(h => !h.isArchived && scheduled(h,date,state.pauses || []));

export function completionRate(state,from,through) {
  const result = {completed:0, opportunities:0, skipped:0};
  if (from > through) return result;
  const byHabit = new Map();
  for (const log of state.logs) {
    if (!byHabit.has(log.habitId)) byHabit.set(log.habitId,new Map());
    byHabit.get(log.habitId).set(log.date,log);
  }
  for (const habit of state.habits) {
    const begin = habit.startDate > from ? habit.startDate : from;
    const end = habit.archivedOn && habit.archivedOn < through ? habit.archivedOn : through;
    const logs = byHabit.get(habit.id);
    for (let date=begin;date<=end;date=plusDays(date,1)) {
      if (!scheduled(habit,date,state.pauses || [])) continue;
      const status=logs?.get(date)?.status;
      if (status === 'SKIPPED') result.skipped++;
      else { result.opportunities++; if (status === 'COMPLETED') result.completed++; }
    }
  }
  return result;
}
export function currentStreak(habit,logs,today,pauses=[]) {
  if (habit.isArchived || today < habit.startDate) return 0;
  const byDate=new Map(logs.filter(l=>l.habitId===habit.id).map(l=>[l.date,l]));
  const todayLog=byDate.get(today);
  let date = !todayLog || todayLog.status === 'IN_PROGRESS' ? plusDays(today,-1) : today;
  let count=0;
  while(date>=habit.startDate) {
    if(scheduled(habit,date,pauses)) {
      const status=byDate.get(date)?.status;
      if(status==='COMPLETED')count++;
      else if(status!=='SKIPPED')break;
    }
    date=plusDays(date,-1);
  }
  return count;
}
export function bestStreak(habit,logs,today,pauses=[]) {
  const byDate=new Map(logs.filter(l=>l.habitId===habit.id).map(l=>[l.date,l]));
  const end = habit.archivedOn && habit.archivedOn<today ? habit.archivedOn : today;
  let best=0,count=0;
  for(let date=habit.startDate;date<=end;date=plusDays(date,1)) {
    if(!scheduled(habit,date,pauses))continue;
    const status=byDate.get(date)?.status;
    if(status==='COMPLETED')best=Math.max(best,++count);
    else if(status!=='SKIPPED')count=0;
  }
  return best;
}

function require(condition,message) { if(!condition)throw new Error(message); }
function record(value,keys,label) {
  require(value !== null && typeof value === 'object' && !Array.isArray(value),`${label} must be an object.`);
  const actual=Object.keys(value);
  require(keys.every(k=>Object.hasOwn(value,k)),`${label} is missing required fields.`);
  require(actual.every(k=>keys.includes(k)),`${label} contains unsupported fields.`);
}
const integer = value => Number.isSafeInteger(value) && value>=0;
const identifier = value => integer(value) && value>0;
const timestamp = (value,label) => require(integer(value),`Invalid ${label} timestamp.`);
const text = (value,max,nullable=false) => nullable && value===null || typeof value==='string' && value.length<=max;
const nonblank = (value,max) => text(value,max) && value.trim().length>0;
function maximumStart(today) {
  const year=Number(today.slice(0,4))+100;
  let value=`${year}${today.slice(4)}`;
  if(!isDate(value))value=`${year}-02-28`;
  return value;
}

/** Validates the complete, versioned native backup before returning a normalized v2 state. */
export function validateBackup(input,today=localDate()) {
  require(input && [1,2].includes(input.formatVersion),'Choose a supported LifeTrack JSON backup (version 1 or 2).');
  const version=input.formatVersion;
  record(input,version===1?['formatVersion','exportedAt','habits','logs','settings','freezes']:['formatVersion','exportedAt','habits','logs','settings','freezes','pauses'],'Backup');
  timestamp(input.exportedAt,'export');
  require(Array.isArray(input.habits)&&Array.isArray(input.logs)&&Array.isArray(input.freezes)&&(version===1||Array.isArray(input.pauses)),'Backup collections must be arrays.');
  record(input.settings,version===1?SETTINGS_V1:SETTINGS_V2,'Settings');
  const settings=input.settings;
  require(settings.id===1&&['SYSTEM','LIGHT','DARK'].includes(settings.theme)&&typeof settings.dynamicColor==='boolean'&&typeof settings.backupReminder==='boolean','Invalid settings record.');
  require(settings.lastExportAt===null||integer(settings.lastExportAt),'Invalid last-export timestamp.');
  if(version===2)require(typeof settings.hapticsEnabled==='boolean'&&typeof settings.animationsEnabled==='boolean','Invalid feedback settings.');
  const ids=new Set(),habits=new Map();
  for(const habit of input.habits) {
    record(habit,version===1?HABIT_V1:HABIT_V2,'Habit');
    require(identifier(habit.id)&&!ids.has(habit.id),'Invalid or duplicate habit IDs.');ids.add(habit.id);
    require(nonblank(habit.name,120)&&text(habit.description,2000,true)&&nonblank(habit.icon,32),'Invalid habit name, description or icon.');
    require(typeof habit.colorHex==='string'&&/^#[0-9a-fA-F]{6}$/.test(habit.colorHex),'Choose a six-digit habit color.');
    require(nonblank(habit.category,40),'Categories must contain 1–40 characters.');
    require(isDate(habit.startDate)&&habit.startDate>='1900-01-01'&&habit.startDate<=maximumStart(today),'Start dates must be between 1900 and 100 years from today.');
    require(typeof habit.isArchived==='boolean'&&integer(habit.sortOrder)&&habit.sortOrder<=2147483647,'Invalid archive flag or habit order.');
    require(habit.afterHabitId===null||identifier(habit.afterHabitId),'Invalid habit stack reference.');
    require(habit.isArchived===(habit.archivedOn!==null),'Archived habits must have an archive date.');
    require(habit.archivedOn===null||isDate(habit.archivedOn)&&habit.archivedOn>='1900-01-01'&&habit.archivedOn<=today,'Invalid archive date.');
    timestamp(habit.createdAt,'habit creation');timestamp(habit.updatedAt,'habit update');require(habit.updatedAt>=habit.createdAt,'A habit update predates its creation.');
    if(version===2) {
      require(Array.isArray(habit.scheduledDays)&&habit.scheduledDays.length>0&&habit.scheduledDays.length<=7&&new Set(habit.scheduledDays).size===habit.scheduledDays.length&&habit.scheduledDays.every(d=>Number.isInteger(d)&&d>=1&&d<=7)&&habit.scheduledDays.join(',')===[...habit.scheduledDays].sort().join(','),'Choose at least one distinct weekday in calendar order.');
      require(Number.isInteger(habit.targetCount)&&habit.targetCount>=1&&habit.targetCount<=100000,'Targets must be between 1 and 100,000.');
      require(nonblank(habit.targetUnit,24),'Choose a target unit of up to 24 characters.');
      require(habit.reminderMinutes===null||Number.isInteger(habit.reminderMinutes)&&habit.reminderMinutes>=0&&habit.reminderMinutes<1440,'Choose a valid reminder time.');
      require(Array.isArray(habit.scheduleHistory),'Schedule history must be an array.');
      let previousDate=null;
      for(const revision of habit.scheduleHistory) {
        record(revision,['effectiveFrom','days'],'Schedule revision');
        require(isDate(revision.effectiveFrom)&&revision.effectiveFrom>='1900-01-01'&&revision.effectiveFrom<=maximumStart(today)&&(!previousDate||revision.effectiveFrom>previousDate),'Schedule revisions must have distinct chronological dates.');
        require(Array.isArray(revision.days)&&revision.days.length>0&&revision.days.length<=7&&new Set(revision.days).size===revision.days.length&&revision.days.every(d=>Number.isInteger(d)&&d>=1&&d<=7)&&revision.days.join(',')===[...revision.days].sort().join(','),'Invalid schedule revision weekdays.');
        previousDate=revision.effectiveFrom;
      }
      require(!habit.scheduleHistory.length||[...habit.scheduleHistory.at(-1).days].sort().join(',')===[...habit.scheduledDays].sort().join(','),'Current weekdays do not match the latest schedule revision.');
    }
    habits.set(habit.id,habit);
  }
  const completedStacks=new Set();
  for(const habit of input.habits) {
    const visited=new Set();let cursor=habit;
    while(cursor&&!completedStacks.has(cursor.id)) {
      require(!visited.has(cursor.id),'Habit stacks cannot form a cycle.');visited.add(cursor.id);
      require(cursor.afterHabitId===null||habits.has(cursor.afterHabitId),'A stack points to a missing habit.');
      cursor=habits.get(cursor.afterHabitId);
    }
    for(const id of visited)completedStacks.add(id);
  }
  let pauses=version===2?copy(input.pauses):[];
  if(version===1) {
    pauses=input.habits.filter(h=>h.isArchived).map((h,index)=>({id:index+1,habitId:h.id,startDate:plusDays(h.archivedOn,1),endDate:null}));
  }
  const pauseIds=new Set(),byHabit=new Map();
  for(const pause of pauses) {
    record(pause,['id','habitId','startDate','endDate'],'Pause');
    require(identifier(pause.id)&&!pauseIds.has(pause.id)&&habits.has(pause.habitId),'Invalid, duplicate or orphan pause.');pauseIds.add(pause.id);
    require(isDate(pause.startDate)&&pause.startDate>='1900-01-01'&&pause.startDate<=plusDays(today,1),'Invalid pause start.');
    require(pause.endDate===null||isDate(pause.endDate)&&pause.endDate>pause.startDate&&pause.endDate<=plusDays(today,1),'Invalid pause end.');
    if(!byHabit.has(pause.habitId))byHabit.set(pause.habitId,[]);byHabit.get(pause.habitId).push(pause);
  }
  for(const list of byHabit.values()) {
    list.sort((a,b)=>a.startDate.localeCompare(b.startDate));
    for(let index=1;index<list.length;index++)require(list[index-1].endDate!==null&&list[index-1].endDate<=list[index].startDate,'Habit pause intervals overlap.');
    if(list.at(-1)?.endDate===null){const habit=habits.get(list[0].habitId);require(habit.isArchived&&list.at(-1).startDate===plusDays(habit.archivedOn,1),'An open pause must match its archived habit.');}
  }
  if(version===2)for(const habit of input.habits.filter(h=>h.isArchived))require(pauses.filter(p=>p.habitId===habit.id&&p.endDate===null&&p.startDate===plusDays(habit.archivedOn,1)).length===1,'Archived habits must retain their open pause.');
  const freezeMonths=new Set();
  for(const freeze of input.freezes) {
    record(freeze,['month','usedAt'],'Freeze');
    require(typeof freeze.month==='string'&&/^\d{4}-(0[1-9]|1[0-2])$/.test(freeze.month)&&freeze.month>='1900-01'&&freeze.month<=today.slice(0,7)&&!freezeMonths.has(freeze.month),'Invalid or duplicate freeze month.');
    timestamp(freeze.usedAt,'freeze');freezeMonths.add(freeze.month);
  }
  const logIds=new Set(),daily=new Set(),frozenMonths=new Set();
  for(const log of input.logs) {
    record(log,version===1?LOG_V1:LOG_V2,'Check-in');
    const habit=habits.get(log.habitId),key=`${log.habitId}:${log.date}`;
    require(identifier(log.id)&&!logIds.has(log.id)&&habit&&!daily.has(key),'Invalid, orphan or duplicate check-in.');logIds.add(log.id);daily.add(key);
    require(isDate(log.date)&&log.date>=habit.startDate&&log.date<=today&&(!habit.archivedOn||log.date<=habit.archivedOn),'A check-in is outside its habit timeline.');
    require((version===1?STATUSES.slice(0,3):STATUSES).includes(log.status)&&text(log.note,4000,true)&&typeof log.isFrozen==='boolean','Invalid check-in status, note or frozen flag.');
    timestamp(log.createdAt,'check-in creation');timestamp(log.updatedAt,'check-in update');require(log.updatedAt>=log.createdAt,'A check-in update predates its creation.');
    if(version===2) {
      require(integer(log.progress)&&log.progress<=habit.targetCount,'Check-in progress is outside its target.');
      require(log.status!=='COMPLETED'||log.progress===habit.targetCount,'Completed check-ins must reach their target.');
      require(log.status!=='IN_PROGRESS'||log.progress>=0&&log.progress<habit.targetCount,'Partial check-ins must be below the target.');
      require(!['SKIPPED','FAILED'].includes(log.status)||log.progress===0,'Skipped or failed check-ins cannot contain progress.');
      require(!pauses.some(p=>p.habitId===habit.id&&log.date>=p.startDate&&(p.endDate===null||log.date<p.endDate)),'A check-in occurs during a pause.');
    }
    if(log.isFrozen) {
      const month=log.date.slice(0,7);
      require(log.status==='SKIPPED'&&freezeMonths.has(month)&&!frozenMonths.has(month),'Invalid frozen check-in or duplicate monthly freeze.');frozenMonths.add(month);
    }
  }
  const normalized={
    habits:input.habits.map(h=>version===1?{...copy(h),scheduledDays:[...ALL_DAYS],targetCount:1,targetUnit:'times',reminderMinutes:null,scheduleHistory:[]}:copy(h)),
    logs:input.logs.map(l=>version===1?{...copy(l),progress:l.status==='COMPLETED'?1:0}:copy(l)),
    settings:version===1?{...copy(settings),hapticsEnabled:true,animationsEnabled:true}:copy(settings),
    freezes:copy(input.freezes),pauses
  };
  return normalized;
}

export function makeBackup(state,exportedAt=Date.now()) {
  return {formatVersion:2,exportedAt,habits:state.habits,logs:state.logs,settings:state.settings,freezes:state.freezes,pauses:state.pauses || []};
}
export function parseBackup(text,today=localDate()) {
  require(typeof text==='string'&&new TextEncoder().encode(text).byteLength<=MAX_BACKUP_BYTES,'Backups must be 10 MB or smaller.');
  let input;try{input=JSON.parse(text.replace(/^\uFEFF/,''));}catch{throw new Error('This file is not valid JSON.');}
  return validateBackup(input,today);
}
export function encodeBackup(state,today=localDate(),exportedAt=Date.now()) {
  const backup=makeBackup(state,exportedAt);validateBackup(backup,today);
  const text=JSON.stringify(backup,null,2);
  require(new TextEncoder().encode(text).byteLength<=MAX_BACKUP_BYTES,'This backup exceeds the 10 MB limit. No file was written.');
  return text;
}
export function restoreStoredState(text,today=localDate()) {
  let state;try{state=JSON.parse(text);}catch{throw new Error('Saved preview data is not valid JSON.');}
  require(state && typeof state==='object','Saved preview data is incomplete.');
  const version=Array.isArray(state.pauses)?2:1;
  const exportedAt=Date.now();
  return validateBackup({formatVersion:version,exportedAt,...state},today);
}

export function changeLog(state,habitId,date,today,status,note=null,progress=null,frozen=false) {
  const habit=state.habits.find(h=>h.id===habitId);
  require(habit&&!habit.isArchived&&date<=today&&scheduled(habit,date,state.pauses || []),'Choose a scheduled, unpaused day for this active habit.');
  require(STATUSES.includes(status),'Choose a valid check-in status.');
  require(text(note,4000,true),'Notes are limited to 4,000 characters.');
  const existing=state.logs.find(l=>l.habitId===habitId&&l.date===date),now=Date.now();
  const count=status==='COMPLETED'?habit.targetCount:status==='IN_PROGRESS'?progress:0;
  require(integer(count)&&count<=habit.targetCount&&(status!=='IN_PROGRESS'||count<habit.targetCount),'Choose progress between zero and the target.');
  const log={id:existing?.id||Math.max(0,...state.logs.map(l=>l.id))+1,habitId,date,status,note:note?.trim()||null,isFrozen:frozen||(existing?.isFrozen&&status==='SKIPPED')||false,progress:count,createdAt:existing?.createdAt||now,updatedAt:Math.max(now,existing?.createdAt||0)};
  return {...state,logs:existing?state.logs.map(l=>l===existing?log:l):[...state.logs,log]};
}
export function changeProgress(state,habitId,date,today,delta) {
  const habit=state.habits.find(h=>h.id===habitId),log=state.logs.find(l=>l.habitId===habitId&&l.date===date);
  require(habit&&Number.isInteger(delta),'Choose a valid habit and amount.');
  const progress=Math.max(0,Math.min(habit.targetCount,(log?.progress || 0)+delta));
  if(progress===0) {
    require(!habit.isArchived&&date<=today&&scheduled(habit,date,state.pauses || []),'Choose a scheduled day.');
    return log?.note ? changeLog(state,habitId,date,today,'IN_PROGRESS',log.note,0) : {...state,logs:state.logs.filter(l=>l!==log)};
  }
  return changeLog(state,habitId,date,today,progress===habit.targetCount?'COMPLETED':'IN_PROGRESS',log?.note,progress);
}
export function redeemFreeze(state,habitId,date,today,note=null) {
  const month=date.slice(0,7),existing=state.logs.find(l=>l.habitId===habitId&&l.date===date);
  require(date<today&&!state.freezes.some(f=>f.month===month)&&(!existing||['FAILED','IN_PROGRESS'].includes(existing.status)),'Choose a past missed day with an unused monthly freeze.');
  const next=changeLog(state,habitId,date,today,'SKIPPED',note,0,true);
  return {...next,freezes:[...state.freezes,{month,usedAt:Date.now()}]};
}
export function archiveHabit(state,id,today) {
  const habit=state.habits.find(h=>h.id===id);require(habit,'This habit no longer exists.');
  let pauses=copy(state.pauses || []);
  if(habit.isArchived) {
    pauses=pauses.flatMap(p=>p.habitId!==id||p.endDate!==null?[p]:p.startDate>=today?[]:[{...p,endDate:today}]);
  } else pauses.push({id:Math.max(0,...pauses.map(p=>p.id))+1,habitId:id,startDate:plusDays(today,1),endDate:null});
  const now=Math.max(Date.now(),habit.createdAt);
  return {...state,pauses,habits:state.habits.map(h=>h.id===id?{...h,isArchived:!h.isArchived,archivedOn:h.isArchived?null:today,updatedAt:now}:h)};
}
export function deleteHabit(state,id) {
  return {...state,habits:state.habits.filter(h=>h.id!==id).map(h=>h.afterHabitId===id?{...h,afterHabitId:null}:h),logs:state.logs.filter(l=>l.habitId!==id),pauses:(state.pauses || []).filter(p=>p.habitId!==id)};
}

/** Historic quantities retain their meaning; changing the goal requires a new habit. */
export function validateGoalEdit(existing,changed,logs) {
  if(existing&&logs.some(log=>log.habitId===existing.id))require(
    changed.targetCount===existing.targetCount&&changed.targetUnit===existing.targetUnit,
    'Existing check-ins keep their original goal. Create a new habit to change its target or unit.'
  );
}

export function contrast(a,b) {
  const luminance=hex=>{
    const parts=hex.slice(1).match(/../g).map(p=>parseInt(p,16)/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4);
    return .2126*parts[0]+.7152*parts[1]+.0722*parts[2];
  };
  const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
}
export const foregroundFor = hex => contrast(hex,'#ffffff')>=contrast(hex,'#000000')?'#ffffff':'#000000';
