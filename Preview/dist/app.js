import { renderAnalytics, heatmapHTML, currentStreak, trendHTML, periodTilesHTML, dailyCheckinsHTML, consistencyHTML, unmarkedOn } from './analytics.js?v=1.5.1';
import { encryptBackup, decryptBackup, MAX_ENVELOPE_BYTES } from './backup-crypto.js?v=1.5.1';
import { ALL_DAYS, localDate, isDate, plusDays, dayDifference, dayNumber as relativeDay,
  scheduled, dueHabits, bestStreak as bestStreakValue, parseBackup, encodeBackup,
  makeBackup, validateBackup as validatePortableBackup, restoreStoredState, changeLog, changeProgress,
  redeemFreeze, archiveHabit as archiveValue, deleteHabit as deleteValue, foregroundFor, copy, validateGoalEdit, goalAt,
  saveActivity, deleteActivity as deleteActivityValue, setActivityEntry, clearActivityEntry,
  activityEntriesFor, activityTotals, formatQuantity } from './domain.js?v=1.5.1';

const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const iso = date => `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`;
let today = localDate();
const plus = (date, n) => { const d = new Date(date + 'T12:00:00'); d.setDate(d.getDate()+n); return iso(d); };
const days = (a,b) => Math.round((Date.parse(b + 'T12:00:00Z')-Date.parse(a + 'T12:00:00Z'))/86400000);
const nice = (date, options={weekday:'short',day:'numeric',month:'short'}) => new Date(date+'T12:00:00').toLocaleDateString('en-GB',options);
const urlParams=new URLSearchParams(location.search);
const qaNamespace=urlParams.get('qa');
const STORE = qaNamespace===null?'lifetrack.browser-preview.v1':`lifetrack.browser-preview.qa.${qaNamespace.replace(/[^a-zA-Z0-9_-]/g,'').slice(0,40)||'review'}`;
const icons = {
  today:'<circle cx="12" cy="12" r="8.5"/><path d="m8 12 3 3 6-7"/>',
  analytics:'<path d="M4 19V5M4 19h16M8 15v-4m5 4V7m5 8v-6"/>',
  settings:'<path d="m9 4 1-2h4l1 2 2 1 2 0 2 4-1 2v2l1 2-2 4h-2l-2 1-1 2h-4l-1-2-2-1H5l-2-4 1-2v-2L3 9l2-4h2z"/><circle cx="12" cy="12" r="3"/>',
  check:'<path d="m6 12 4 4 8-9"/>', plus:'<path d="M12 6v12M6 12h12"/>', skip:'<path d="M6 12h12"/>',
  shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
  export:'<path d="M4 14v6h16v-6M12 15V3m-5 5 5-5 5 5"/>',
  import:'<path d="M4 14v6h16v-6M12 3v12m-5-5 5 5 5-5"/>',
  bell:'<path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5zm5 3h4"/>',
  moon:'<path d="M20 14A8.5 8.5 0 0 1 10 3 8.5 8.5 0 1 0 20 14Z"/>',
  palette:'<path d="M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4 2 2 0 0 1 0-4h3a4 4 0 0 0 0-8z"/><path d="M7 8h.01M7 13h.01M12 7h.01"/>',
  info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v1"/>',
  archive:'<path d="M4 8h16v13H4zM3 3h18v5H3zm6 9h6"/>',
  edit:'<path d="m15 4 5 5-11 11H4v-5zm-1 1 5 5"/>',
  back:'<path d="m14 5-7 7 7 7"/>',
  dots:'<circle cx="5" cy="12" r="1.7" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.7" fill="currentColor" stroke="none"/>',
  sliders:'<path d="M4 7h9m5 0h2M4 17h3m5 0h8"/><circle cx="15.5" cy="7" r="2.4"/><circle cx="9.5" cy="17" r="2.4"/>',
  search:'<circle cx="11" cy="11" r="6.5"/><path d="m20 20-3.8-3.8"/>',
  close:'<path d="M6 6l12 12M18 6 6 18"/>',
  trash:'<path d="M5 7h14M10 7V5h4v2m-8 0 1 13h10l1-13"/>',
};
const icon = (name, attrs='') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" ${attrs}>${icons[name] || icons.info}</svg>`;

function demoData() {
  const definitions = [
    ['Read 10 pages','Make a little room for a new perspective.','📖','#8877B0','Learning',46,null],
    ['Move your body','A walk, a stretch, a little fresh air.','🏃','#B48647','Health',89,null],
    ['A moment of stillness','Five minutes. Just breathe.','🌿','#43866B','Mind',22,1],
    ['Drink more water','A small kindness for your body.','💧','#547DBB','Health',14,null],
    ['Write one paragraph','One thought is enough to begin.','✍️','#B67B98','Creativity',6,3],
    ['Practice a new language','A new chapter starts soon.','🌎','#43866B','Learning',-3,null]
  ];
  const habits = definitions.map((d,i) => {
    const date = plus(today,-d[5]), timestamp = Math.min(Date.now(),Date.parse(date+'T08:00:00'));
    return {id:i+1,name:d[0],description:d[1],icon:d[2],colorHex:d[3],startDate:date,isArchived:false,sortOrder:i,category:d[4],afterHabitId:d[6],archivedOn:null,createdAt:timestamp,updatedAt:timestamp,scheduledDays:i===1?[1,2,3,4,5]:[...ALL_DAYS],targetCount:i===3?8:1,targetUnit:i===3?'glasses':'times',reminderMinutes:null,scheduleHistory:[]};
  });
  const logs = []; let id=1;
  habits.forEach(h => {
    for (let date=h.startDate;date<today;date=plus(date,1)) {
      if(!scheduled(h,date))continue;
      const ago=days(date,today), number=days(h.startDate,date), hash=(number*17+h.id*11)%23;
      if (ago>9 && hash>=17) continue;
      if (h.id===5 && ago>3 && hash>12) continue;
      const status=hash===2 && ago>4 ? 'SKIPPED' : hash===7 && ago>9 ? 'FAILED' : 'COMPLETED';
      const timestamp = Math.min(Date.now(),Date.parse(date+'T18:00:00'));
      logs.push({id:id++,habitId:h.id,date,status,note:h.id===3&&ago===1?'A quiet five minutes before the day began.':null,isFrozen:false,createdAt:timestamp,updatedAt:timestamp,progress:status==='COMPLETED'?h.targetCount:0});
    }
  });
  [1,4].forEach(habitId => logs.push({id:id++,habitId,date:today,status:'COMPLETED',note:null,isFrozen:false,createdAt:Date.now(),updatedAt:Date.now(),progress:habits.find(h=>h.id===habitId).targetCount}));
  const activityDefs = [
    ['Push-ups','💪','pushups',20],
    ['Running','🏃‍♂️','miles',1],
    ['Crunches','🔥','crunches',null],
    ['Jumping jacks','⭐','jacks',null]
  ];
  const activities = activityDefs.map((d,i) => {
    const timestamp = Date.now()-86400000*(10-i);
    return {id:i+1,name:d[0],icon:d[1],unit:d[2],target:d[3],sortOrder:i,createdAt:timestamp,updatedAt:timestamp};
  });
  const activityEntries = []; let aid=1;
  const addEntry = (activityId,date,quantity,note=null) => activityEntries.push({id:aid++,activityId,date,quantity,note,createdAt:Date.now(),updatedAt:Date.now()});
  addEntry(1,plus(today,-3),20); addEntry(2,plus(today,-3),1);
  addEntry(3,plus(today,-2),15); addEntry(1,plus(today,-2),15);
  addEntry(4,plus(today,-1),15); addEntry(1,plus(today,-1),15);
  addEntry(1,today,32,'Felt stronger than the target.'); addEntry(3,today,35); addEntry(2,today,0.75);
  return {habits,logs,freezes:[],pauses:[],settings:{id:1,theme:'LIGHT',dynamicColor:false,backupReminder:false,lastExportAt:null,hapticsEnabled:true,animationsEnabled:true},activities,activityEntries};
}

let state, recoveryText=null, storageWarning='';
try { const saved=localStorage.getItem(STORE);state=saved?restoreStoredState(saved,today):demoData(); }
catch(error) { try{recoveryText=localStorage.getItem(STORE);}catch{}state=demoData();storageWarning=error.message||'Browser storage could not be opened.'; }
const DRAFT_STORE=STORE+'.drafts';
let drafts={};try{const value=JSON.parse(localStorage.getItem(DRAFT_STORE)||'{}');if(value&&typeof value==='object'&&!Array.isArray(value))drafts=value;}catch{}
let filters={query:'',category:'',status:'all'}, filterPanelOpen=false, searchOpen=false, analyticsPeriod='week', dialogContext=null, pendingBackup=null, pendingEncryptedText=null, pendingFilename='', lastUndo=null, revision=0;
let route='today', selectedDate=today, selectedId=null, dragging=null, holdTimer=null, held=false, toastTimer, dialogOpener=null, nudgeDismissedDate=null, focusUnmarkedDate=null;
const PREFS_STORE=STORE+'.prefs';
let prefs={reminderMinutes:null,lastNudgeDate:null};
try{const rawPrefs=JSON.parse(localStorage.getItem(PREFS_STORE)||'null');if(rawPrefs&&typeof rawPrefs==='object'&&!Array.isArray(rawPrefs)){if(rawPrefs.reminderMinutes===null||(Number.isInteger(rawPrefs.reminderMinutes)&&rawPrefs.reminderMinutes>=0&&rawPrefs.reminderMinutes<1440))prefs.reminderMinutes=rawPrefs.reminderMinutes;if(typeof rawPrefs.lastNudgeDate==='string')prefs.lastNudgeDate=rawPrefs.lastNudgeDate;}}catch{}
const RESET_STORE=STORE+'.reset';
let resetToken=null;try{resetToken=localStorage.getItem(RESET_STORE);}catch{}
function staleAfterReset(){let stored=null;try{stored=localStorage.getItem(RESET_STORE);}catch{}return stored!==resetToken;}
function savePrefs(){if(staleAfterReset()){adoptRemoteReset();return false;}try{localStorage.setItem(PREFS_STORE,JSON.stringify(prefs));}catch{}}
const app=$('#app-view'), modal=$('#modal');
function save() { if(recoveryText!==null)return;if(staleAfterReset()){adoptRemoteReset();return false;}try { localStorage.setItem(STORE,JSON.stringify(state));storageWarning=''; } catch { storageWarning='Browser storage is unavailable. Changes remain in this session.';toast(storageWarning); } }
function toast(text,undo=false) { const box=$('#toast');box.innerHTML=`<span>${esc(text)}</span>${undo?'<button data-action="undo">Undo</button>':''}`;box.classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>box.classList.remove('visible'),undo?8000:4200); }
function commit(next,message,{undo=true}={}) { const previous=state;state=next;revision++;lastUndo=undo?{state:previous,revision}:null;const persisted=save();render();if(persisted!==false)toast(message,Boolean(lastUndo)); }
function navigate(tab,id=null) { route=tab;selectedId=id;render();app.scrollTop=0; }
function logFor(id,date=selectedDate) { return state.logs.find(l=>l.habitId===id&&l.date===date); }
function habitFor(id) { return state.habits.find(h=>h.id===Number(id)); }
function dayNumber(h,date=selectedDate) { return relativeDay(h,date); }
function theme() { document.body.classList.toggle('dark',state.settings.theme==='DARK'||(state.settings.theme==='SYSTEM'&&matchMedia('(prefers-color-scheme: dark)').matches));document.body.classList.toggle('quiet',!state.settings.animationsEnabled); }
function render() {
  const position=app.scrollTop,active=document.activeElement,nav=$('#bottom-nav');
  const inApp=active instanceof HTMLElement&&(app.contains(active)||nav.contains(active));
  const focused=inApp?active.id:null,selection=inApp?active.selectionStart:null;
  const focusAction=inApp&&active.dataset?.action?`${active.dataset.action}|${active.dataset.id??''}`:null;
  const focusTab=inApp&&active.dataset?.tab?active.dataset.tab:null;
  theme();
  app.innerHTML=route==='analytics'?`<div class="screen">${renderAnalytics(state,today,analyticsPeriod)}</div>`:route==='settings'?settingsHTML():route==='detail'?detailHTML():todayHTML();
  const rail=$('#today-rail');if(rail)rail.innerHTML=route==='today'?todayRailHTML():'';
  app.querySelectorAll('[data-heatmap-scroll]').forEach(grid=>{grid.scrollLeft=grid.scrollWidth;});
  nav.innerHTML=['today','analytics','settings'].map(tab=>`<button data-action="navigate" data-tab="${tab}" class="${route===tab||route==='detail'&&tab==='today'?'active':''}" ${route===tab?'aria-current="page"':''}><span class="nav-icon">${icon(tab)}</span>${tab[0].toUpperCase()+tab.slice(1)}</button>`).join('');
  app.scrollTop=position;
  if(focused){const input=document.getElementById(focused);if(input){input.focus({preventScroll:true});if(selection!==null&&typeof input.setSelectionRange==='function')try{input.setSelectionRange(selection,selection);}catch{}}}
  else if(focusTab){nav.querySelector(`[data-tab="${focusTab}"]`)?.focus({preventScroll:true});}
  else if(focusAction){const [action,id]=focusAction.split('|');const matches=[...app.querySelectorAll(`[data-action="${action}"]`)];const target=matches.find(el=>(el.dataset.id??'')===id)||matches.find(el=>!(el.dataset.id??'')&&!id);if(target)target.focus({preventScroll:true});}
}
function dateNav() {
  return `<div class="day-nav"><button class="icon-button" data-action="previous-day" aria-label="Previous day" ${selectedDate==='1900-01-01'?'disabled':''}>${icon('back','width="18" height="18"')}</button><label class="day-nav-label">${selectedDate===today?'Today, ':''}${nice(selectedDate)}<span class="caret">▾</span><input aria-label="Choose check-in date" type="date" min="1900-01-01" max="${today}" value="${selectedDate}" id="checkin-date"></label><button class="icon-button" data-action="next-day" aria-label="Next day" style="transform:scaleX(-1)" ${selectedDate===today?'disabled':''}>${icon('back','width="18" height="18"')}</button></div>`;
}
const STATUS_FILTERS=[['all','All habits'],['due','Due today'],['open','Still to do'],['done','Completed'],['upcoming','Upcoming']];
function recordStripHTML(selected,today) {
  const days = Array.from({length:7},(_,i)=>plus(today,i-6));
  const doneDays = new Set(state.logs.filter(l=>l.status==='COMPLETED'&&l.date>=days[0]).map(l=>l.date));
  return `<div class="record-strip" role="group" aria-label="Recording strip · last seven days">${days.map(d=>{
    const selectedDay=d===selected, isToday=d===today;
    return `<button type="button" data-action="strip-day" data-date="${d}" aria-pressed="${selectedDay}" aria-label="Record ${nice(d,{weekday:'long',day:'numeric',month:'long'})}${doneDays.has(d)?' · has recordings':''}"${isToday?' data-today':''} class="${selectedDay?'on':''} ${doneDays.has(d)?'has-logs':''}"><span class="tick" aria-hidden="true"></span><span class="w">${nice(d,{weekday:'narrow'})}</span><span class="d">${d.slice(8)}</span></button>`;
  }).join('')}</div>`;
}
function todayHTML() {
  const active=state.habits.filter(h=>!h.isArchived).sort((a,b)=>a.sortOrder-b.sortOrder);
  const due=dueHabits(state,selectedDate), completed=due.filter(h=>logFor(h.id)?.status==='COMPLETED').length;
  const percent=due.length?Math.round(completed/due.length*100):null;
  const categories=[...new Set(active.map(h=>h.category))].sort();
  const visible=active.filter(h=>(!filters.query||`${h.name} ${h.description||''} ${h.category}`.toLowerCase().includes(filters.query.toLowerCase()))&&(!filters.category||h.category===filters.category)&&
    (filters.status==='all'||filters.status==='due'&&scheduled(h,selectedDate,state.pauses)||filters.status==='open'&&scheduled(h,selectedDate,state.pauses)&&logFor(h.id)?.status!=='COMPLETED'||filters.status==='done'&&logFor(h.id)?.status==='COMPLETED'||filters.status==='upcoming'&&h.startDate>selectedDate));
  const activeFilterKeys=[filters.query?['query',`“${filters.query}”`]:null,filters.category?['category',filters.category]:null,filters.status!=='all'?['status',STATUS_FILTERS.find(([v])=>v===filters.status)?.[1]]:null].filter(Boolean);
  const filtersActive=Boolean(filters.query||filters.category||filters.status!=='all');
  const recovery=recoveryText!==null?`<div class="recovery-banner" role="alert"><strong>Your saved records are preserved.</strong><p>${esc(storageWarning)} The sample screen below is temporary. Download the original data before resetting or importing.</p><button class="secondary-button" data-action="recover-storage">Download original data</button><button class="text-button" data-action="reset-demo">Choose a fresh start</button></div>`:storageWarning?`<p role="alert" class="form-error">${esc(storageWarning)}</p>`:'';
  const isToday=selectedDate===today;
  const yesterday=plusDays(today,-1);
  const unmarkedYesterday=unmarkedOn(state,yesterday);
  const showNudge=isToday&&unmarkedYesterday.length&&nudgeDismissedDate!==yesterday;
  return `<div class="screen">${recovery}<div class="today-head"><div><h2 class="today-title">${esc(nice(selectedDate,{weekday:'long',day:'numeric',month:'long'}))}</h2><p class="today-sub">${isToday?'Today · recording':'Past check-ins · edits and backfills are welcome.'}</p></div><button class="add-habit-button" data-action="add-habit">+ Habit</button></div>
    ${recordStripHTML(selectedDate,today)}
    ${dateNav()}
    ${!isToday?'<div style="margin:-6px 0 12px;text-align:center"><button class="back-today" data-action="back-today">Back to today ↗</button></div>':''}
    ${showNudge?`<section class="nudge-banner" role="status"><div><strong>${unmarkedYesterday.length===1?'One habit was left unmarked':`${unmarkedYesterday.length} habits were left unmarked`} on ${nice(yesterday,{weekday:'long',day:'numeric',month:'short'})}</strong><small>${esc(unmarkedYesterday.slice(0,3).map(h=>h.name).join(' · '))}${unmarkedYesterday.length>3?' · …':''}</small></div><div class="nudge-actions"><button class="primary-button" data-action="focus-unmarked">Mark them now ↗</button><button class="text-button" data-action="dismiss-nudge">Leave it</button></div></section>`:''}
    <section class="day-summary${due.length?'':' calm'}" aria-label="Daily summary"><div class="day-summary-top"><div><strong>${due.length?`${completed} of ${due.length} completed`:'No habits scheduled'}</strong><small>${due.length?(completed===due.length?'Every habit done · enjoy your day':`${due.length-completed} still to do · your own pace`):'Room to breathe · rest or plan ahead'}</small></div><span class="day-percent" aria-hidden="true">${due.length?percent+'%':'—'}</span></div>${due.length?`<div class="day-bar" role="img" aria-label="${percent} percent of scheduled habits completed"><i style="width:${percent}%"></i></div>`:''}</section>
    <div class="filter-row"><button class="chip" data-action="clear-filters" aria-pressed="${!filtersActive}">All habits</button><button class="chip" data-action="toggle-filter-panel" aria-expanded="${filterPanelOpen}" aria-controls="filter-panel">${icon('sliders')} Filters${filtersActive?`<span class="chip-count">${activeFilterKeys.length}</span>`:''}</button><button class="chip" data-action="toggle-search" aria-expanded="${searchOpen}" aria-controls="search-row">${icon('search')} Search</button></div>
    ${filterPanelOpen?`<div class="filter-panel" id="filter-panel"><label>Category<select id="category-filter"><option value="">All categories</option>${categories.map(c=>`<option value="${esc(c)}" ${c===filters.category?'selected':''}>${esc(c)}</option>`).join('')}</select></label><label>Status<select id="status-filter">${STATUS_FILTERS.map(([v,t])=>`<option value="${v}" ${v===filters.status?'selected':''}>${t}</option>`).join('')}</select></label>${filtersActive?'<button class="text-button" data-action="clear-filters">Clear all</button>':''}</div>`:''}
    ${searchOpen?`<div class="search-row" id="search-row"><label class="search-field"><span class="sr-only">Search habits</span><input id="habit-search" type="search" placeholder="Search your habits…" value="${esc(filters.query)}"></label></div>`:''}
    ${activeFilterKeys.length?`<div class="active-chips">${activeFilterKeys.map(([key,value])=>`<span class="filter-chip">${esc(value)}<button data-action="remove-filter" data-key="${key}" aria-label="Remove the ${esc(value)} filter">✕</button></span>`).join('')}</div>`:''}
    <div class="section-heading"><h3>Your habits <span class="result-count">${visible.length}</span></h3><button class="text-button" data-action="reorder">Reorder</button></div>
    <div class="habit-list">${visible.length?visible.map(habitCard).join(''):emptyStateHTML(active)}</div>
    ${activitiesSectionHTML()}
    <button class="secondary-button wide" data-action="templates">✧ Start with a simple idea</button><div class="gentle-note"><span>✧</span>Progress isn’t always a straight line.<br>A little today is still a little more than yesterday.</div></div>`;
}
function emptyStateHTML(active){
  let fresh=false;
  if(!active.length&&!state.activities.length){try{fresh=Boolean(localStorage.getItem(RESET_STORE));}catch{}}
  if(fresh)return `<div class="empty-state"><span>🌱</span><h3>A fresh page</h3><p>Everything was erased. Add a habit, or restore a backup to continue where you left off.</p><button class="primary-button" data-action="add-habit">Add a habit</button> <button class="secondary-button" data-action="import-data">Restore a backup</button></div>`;
  return `<div class="empty-state"><span>🌱</span><h3>${active.length?'No matching habits':'Start with something small'}</h3><p>${active.length?'Try another filter, or make room for a new intention.':'A page. A walk. A quiet minute.<br>Choose a habit and a start date that work for you.'}</p><button class="primary-button" data-action="${active.length?'clear-filters':'add-habit'}">${active.length?'Reset all filters':'Create your first habit'}</button></div>`;
}
function activityRow(a) {
  const entry = state.activityEntries.find(e => e.activityId === a.id && e.date === selectedDate);
  const week = activityTotals(state, a.id, plus(selectedDate,-6), selectedDate);
  const targetLabel = a.target ? ` · target ${formatQuantity(a.target)}` : '';
  const logged = entry ? `${formatQuantity(entry.quantity)} ${a.unit}` : null;
  const weekLabel = week.days ? `${formatQuantity(week.total)} ${a.unit} this week` : 'No entries this week';
  return `<article class="activity-row" data-activity="${a.id}"><button class="row-main" data-action="open-activity" data-id="${a.id}"><span class="row-avatar" aria-hidden="true">${esc(a.icon)}</span><span class="row-copy"><strong>${esc(a.name)}</strong><span class="row-meta">${esc(a.unit)}${targetLabel} · <span class="day-count">${weekLabel}</span></span>${entry?.note ? `<span class="stack-cue">${esc(entry.note)}</span>` : ''}</span></button><div class="row-actions">${logged ? `<span class="goal-chip" aria-label="Logged today: ${esc(logged)}">${esc(logged)}</span>` : ''}<button class="primary-button log-button" data-action="log-activity" data-id="${a.id}" aria-label="Log ${esc(a.name)} for ${esc(nice(selectedDate))}">${entry ? 'Edit' : 'Log'}</button></div></article>`;
}
function activitiesSectionHTML() {
  const activities = state.activities.slice().sort((a,b) => a.sortOrder - b.sortOrder);
  const count = activities.length;
  const header = `<div class="section-heading"><h3>Activities <span class="result-count">${count}</span></h3><button class="text-button" data-action="add-activity">＋ Activity</button></div>`;
  if (!count) return `${header}<div class="empty-state slim"><span>🏋️</span><h3>Track a workout or activity</h3><p>Push-ups, runs, crunches — any activity, any day, in any combination. Quantities are yours: 32 of a 20 target is a good day.</p><button class="primary-button" data-action="add-activity">Add your first activity</button></div>`;
  return `${header}<div class="habit-list">${activities.map(activityRow).join('')}</div>`;
}
function todayRailHTML(){return `${periodTilesHTML(state,today)}${dailyCheckinsHTML(state,today)}${consistencyHTML(state,today)}`;}
function habitCard(h) {
  h=goalAt(h,selectedDate);
  const log=logFor(h.id), day=dayNumber(h), status=log?.status,stack=habitFor(h.afterHabitId),isDue=scheduled(h,selectedDate,state.pauses);
  const progress=log?.progress||0;
  const dayLabel=!day?`Starts ${nice(h.startDate,{day:'numeric',month:'short'})}`:`Day ${day}${!isDue?' · Rest day':''}${log?.isFrozen?' · ❄ Frozen':''}`;
  const statusBit=status==='COMPLETED'?(selectedDate===today?'Done today':'Completed'):status==='SKIPPED'?'Skipped':status==='FAILED'?'Missed':h.targetCount>1&&isDue?`${progress} of ${h.targetCount} ${esc(h.targetUnit)}`:'';
  const restState=!isDue?(h.startDate>selectedDate?'future':'rest'):null;
  const checkLabel=restState==='future'?`${h.name} has not started yet`:restState==='rest'?`Rest day for ${h.name}${log?.isFrozen?' · streak frozen':''} — no check-in needed`:status==='COMPLETED'?`Undo completion of ${h.name}`:h.targetCount>1?`Add progress to ${h.name}`:`Complete ${h.name}`;
  return `<article class="habit-row${h.targetCount>1?' is-numeric':''}${focusUnmarkedDate===selectedDate&&isDue&&!log?' is-nudged':''}" draggable="true" data-habit="${h.id}" style="--habit-color:${esc(h.colorHex)};--habit-foreground:${foregroundFor(h.colorHex)}"><span class="drag-handle" aria-hidden="true">⠿</span><div class="row-top"><button class="row-main" data-action="open-habit" data-id="${h.id}"><span class="row-avatar" aria-hidden="true">${esc(h.icon)}</span><span class="row-copy"><strong>${esc(h.name)}</strong><span class="row-meta">${statusBit?`${statusBit} · `:''}${esc(h.category)} · <span class="day-count">${dayLabel}</span></span>${stack?`<span class="stack-cue">↳ After ${esc(stack.name)}</span>`:''}</span></button><div class="row-actions">${h.targetCount>1&&status!=='COMPLETED'&&status!=='IN_PROGRESS'?`<span class="goal-chip" aria-hidden="true">${progress}/${h.targetCount}</span>`:''}<button class="row-menu-button" data-action="row-menu" data-id="${h.id}" aria-label="More actions for ${esc(h.name)}" aria-haspopup="dialog">${icon('dots')}</button><button class="check-button ${status==='COMPLETED'?'is-done':status==='SKIPPED'?'is-skipped':status==='FAILED'?'is-failed':status==='IN_PROGRESS'?'is-partial':restState==='rest'?'is-rest':''}" data-action="check-in" data-id="${h.id}" aria-label="${esc(checkLabel)}" title="${esc(checkLabel)}" aria-pressed="${status==='COMPLETED'}" ${!isDue?'disabled':''}>${status==='IN_PROGRESS'?`<span>${progress}/${h.targetCount}</span>`:icon(status==='COMPLETED'?'check':status==='SKIPPED'||restState==='rest'?'skip':'plus')}</button></div></div>${h.targetCount>1?`<div class="goal-line"><button data-action="decrement" data-id="${h.id}" aria-label="Remove one ${esc(h.targetUnit)} from ${esc(h.name)}" ${!isDue||progress===0?'disabled':''}>−</button><span class="goal-count" aria-live="polite">${progress} / ${h.targetCount} ${esc(h.targetUnit)}</span><button data-action="increment" data-id="${h.id}" aria-label="Add one ${esc(h.targetUnit)} to ${esc(h.name)}" ${!isDue||progress>=h.targetCount?'disabled':''}>+</button></div>`:''}</article>`;
}
function settingsRow(action,title,subtitle,symbol,last='›',extra='') {
  if(last.includes('class="toggle')){const checked=last.includes('toggle on');extra+=` role="switch" aria-checked="${checked}"`;last=last.replace(/ role="switch" aria-checked="[^"]*"/g,'').replace('<span ','<span aria-hidden="true" ');}
  return `<button class="setting-row" data-action="${action}" ${extra}><span class="setting-icon">${icon(symbol)}</span><span class="setting-copy"><strong>${esc(title)}</strong><small>${esc(subtitle)}</small></span>${last}</button>`;
}
function settingsHTML() {
  const settings=state.settings, archived=state.habits.filter(h=>h.isArchived);
  return `<div class="screen"><div class="today-head"><div><h2 class="today-title">Settings.</h2><p class="today-sub">Your data. Your device.</p></div></div><div class="privacy-panel"><div class="privacy-icon">${icon('shield','width="19" height="19"')}</div><h3>Private by design</h3><p>No accounts, cloud sync, ads, or tracking.<br>Your habits belong to you. Always.</p></div>
    <div class="settings-section-title">Data & backups</div>${settingsRow('export-data','Export data','Save your habits, history, notes, and settings','export')}${settingsRow('import-data','Import data','Restore a LifeTrack JSON backup','import')}${settingsRow('reset-lifetrack','Reset LifeTrack','Erase all data in this browser profile','trash')}${settingsRow('toggle-reminder','Monthly backup reminder','A gentle nudge to keep your progress safe.','bell',`<span class="toggle ${settings.backupReminder?'on':''}" role="switch" aria-checked="${settings.backupReminder}"></span>`)}
    <p class="section-note">${settings.lastExportAt?`Last export · ${nice(iso(new Date(settings.lastExportAt)),{day:'numeric',month:'short',year:'numeric'})}`:'No backup exported yet.'}</p>
    <div class="settings-section-title">Gentle reminders</div>${settingsRow('set-reminder','Daily check-in reminder',prefs.reminderMinutes==null?'Off · tap to choose a time':`Daily at ${String(Math.floor(prefs.reminderMinutes/60)).padStart(2,'0')}:${String(prefs.reminderMinutes%60).padStart(2,'0')} · in-app nudge`,'bell')}
    <p class="section-note">In this preview the nudge appears in-app once per day after your time, when habits are still open. The native Android app turns this into a real notification.</p>
    <div class="settings-section-title">Make yourself at home</div>${settingsRow('choose-theme','Theme',settings.theme==='SYSTEM'?'Follow your device':settings.theme==='DARK'?'Dark mode':'Light mode','moon')}${settingsRow('toggle-dynamic','Wallpaper colors','Material You in the native Android app','palette',`<span class="toggle ${settings.dynamicColor?'on':''}" role="switch" aria-checked="${settings.dynamicColor}"></span>`)}
    ${archived.length?`<div class="settings-section-title">Archived habits · ${archived.length}</div>${archived.map(h=>settingsRow('open-habit',`${h.icon} ${h.name}`,'View history or restore','archive','›',`data-id="${h.id}"`)).join('')}`:''}
    <div class="settings-section-title">Comfort & accessibility</div>${settingsRow('toggle-animations','Celebration animations','Respect your pace and reduced-motion preferences.','palette',`<span class="toggle ${settings.animationsEnabled?'on':''}"></span>`)}${settingsRow('toggle-haptics','Check-in haptics','Saved for Android · the browser does not vibrate.','today',`<span class="toggle ${settings.hapticsEnabled?'on':''}"></span>`)}
    <div class="settings-section-title">LifeTrack</div>${settingsRow('about','About LifeTrack','Version 1.1.0 · Made for your own pace','info')}
    <div class="settings-warning">Uninstalling the native app deletes its local data. Export a backup regularly.<br><br>This browser preview has its own sample data and does not access the Android database.</div><div class="settings-footer">Made for real life. Not perfect streaks.<br>🌱</div></div>`;
}
function bestStreak(h) { return bestStreakValue(h,state.logs,today,state.pauses); }
function detailHTML() {
  const selected=habitFor(selectedId);if(!selected){route='today';return todayHTML();}
  const h=goalAt(selected,selectedDate);
  const log=logFor(h.id), total=state.logs.filter(l=>l.habitId===h.id&&l.status==='COMPLETED').length;
  const eligible=!h.isArchived&&scheduled(h,selectedDate,state.pauses), spent=state.freezes.some(f=>f.month===selectedDate.slice(0,7));
  return `<div class="screen" style="--habit-color:${esc(h.colorHex)}"><div class="detail-topbar"><button class="icon-button" data-action="navigate" data-tab="today" aria-label="Back to Today">${icon('back','width="18" height="18"')}</button><span>Your journey</span><button class="icon-button" data-action="edit-habit" data-id="${h.id}" aria-label="Edit habit">${icon('edit','width="17" height="17"')}</button></div><div class="detail-emoji">${esc(h.icon)}</div><h2 class="detail-title">${esc(h.name)}</h2><div class="habit-meta"><span class="day-count">${dayNumber(h)?`Day ${dayNumber(h)}`:'Not started'}</span><i></i>${esc(h.category)}<i></i>Since ${nice(h.startDate,{day:'numeric',month:'short'})}</div><p class="detail-description">${esc(h.description||'A small step, on your own terms.')}</p>
    ${h.isArchived?'<p class="section-note">Archived · Your history is preserved.</p>':''}<div class="detail-stats"><div><strong>${currentStreak(h,state.logs,today,state.pauses)}</strong><span>CURRENT STREAK</span></div><div><strong>${total}</strong><span>COMPLETIONS</span></div><div><strong>${bestStreak(h)}</strong><span>BEST STREAK</span></div></div>
    ${dateNav()}<div class="detail-log"><h4>${log?.isFrozen?'❄ Streak frozen':log?.status==='COMPLETED'?'✓ You showed up.':log?.status==='SKIPPED'?'↳ A little breathing room.':log?.status==='FAILED'?'A fresh start is always possible.':log?.status==='IN_PROGRESS'?`Progress: ${log.progress}/${h.targetCount} ${esc(h.targetUnit)}`:'One small step awaits.'}</h4><p>${log?.note?esc(log.note):h.isArchived?'Restore this habit to change check-ins.':eligible?'There’s no perfect way to make progress. Just your way.':`Your timeline begins ${esc(h.startDate)}.`}</p><div class="detail-log-buttons"><button class="primary-button" data-action="edit-log" data-id="${h.id}" data-default="${log?.status||'COMPLETED'}" ${!eligible?'disabled':''}>${log?'Edit check-in':'Add check-in'}</button>${eligible&&selectedDate<=today&&log?.status!=='SKIPPED'?`<button class="secondary-button" data-action="row-skip" data-id="${h.id}">↳ Skip day</button>`:''}${eligible&&selectedDate<=today&&log?.status!=='FAILED'?`<button class="secondary-button" data-action="row-fail" data-id="${h.id}">✕ Mark missed</button>`:''}${eligible&&selectedDate<today&&(!log||['FAILED','IN_PROGRESS'].includes(log.status))?`<button class="secondary-button" data-action="freeze" data-id="${h.id}" ${spent?'disabled':''}>❄ ${spent?'Freeze used':'Use freeze'}</button>`:''}</div></div>
    ${trendHTML(h,state.logs,today,state.pauses)}<p class="section-note">Skips and freezes protect your chain without adding a completion. An unfinished today has until midnight.</p>${heatmapHTML(state,today,true,h)}<button class="secondary-button wide" data-action="archive-habit" data-id="${h.id}">${h.isArchived?'Restore habit':'Archive habit · keep history'}</button><button class="text-button danger wide" data-action="delete-habit" data-id="${h.id}">Delete habit permanently</button></div>`;
}
function formValues(form) {
  const data=new FormData(form), values=Object.fromEntries(data);
  if(form.id==='habit-form')values.scheduledDays=data.getAll('scheduledDays');
  return values;
}
function captureDraft() {
  if(!modal.open)return;
  const form=modal.querySelector('form[data-draft-key]');if(!form)return;
  if(staleAfterReset()){adoptRemoteReset();return false;}
  drafts[form.dataset.draftKey]=formValues(form);
  try{localStorage.setItem(DRAFT_STORE,JSON.stringify(drafts));}catch{}
}
function clearDraft(key) { if(!key)return;delete drafts[key];try{localStorage.setItem(DRAFT_STORE,JSON.stringify(drafts));}catch{} }
function restoreDraft(form) {
  const values=drafts[form.dataset.draftKey];if(!values||typeof values!=='object'||Array.isArray(values))return;
  for(const field of form.elements) {
    if(field.disabled||field.type==='hidden'||!field.name||!Object.hasOwn(values,field.name))continue;
    if(field.type==='checkbox')field.checked=Array.isArray(values[field.name])&&values[field.name].includes(field.value);
    else if(typeof values[field.name]==='string')field.value=values[field.name];
  }
  for(const chip of form.querySelectorAll('[data-status]')){const selected=chip.dataset.status===form.elements.status?.value;chip.classList.toggle('selected',selected);chip.setAttribute('aria-pressed',String(selected));}
  for(const chip of form.querySelectorAll('[data-color]')){const selected=chip.dataset.color===form.elements.colorHex?.value;chip.classList.toggle('selected',selected);chip.setAttribute('aria-pressed',String(selected));}
  const note=document.createElement('p');note.className='draft-note';note.textContent='Your unfinished draft was restored.';form.prepend(note);
}
function showModal(html,context=null) {
  captureDraft();
  if(!modal.open)dialogOpener=document.activeElement instanceof HTMLElement?document.activeElement:null;
  dialogContext=context;
  $('#modal-content').innerHTML=`<div class="modal-body">${html}</div>`;
  modal.setAttribute('aria-labelledby','modal-title');
  const form=modal.querySelector('form[data-draft-key]');if(form)restoreDraft(form);
  if(!modal.open)modal.showModal();
}
function closeModal(saved=false) { if(!saved)captureDraft();modal.close(); }
function heading(title,subtitle='') { return `<div class="modal-heading"><div><h2 id="modal-title">${esc(title)}</h2>${subtitle?`<p>${esc(subtitle)}</p>`:''}</div><button class="icon-button" data-action="close-modal" aria-label="Close dialog">×</button></div>`; }
const templates=[
  {name:'Read a few pages',icon:'📖',category:'Learning',description:'One page is a perfectly good beginning.',targetCount:10,targetUnit:'pages'},
  {name:'Drink a glass of water',icon:'💧',category:'Health',description:'A little kindness for your body.',targetCount:8,targetUnit:'glasses'},
  {name:'Take a short walk',icon:'🌿',category:'Health',description:'Step outside and give yourself a change of pace.',targetCount:1,targetUnit:'times'},
  {name:'A quiet moment',icon:'🧘',category:'Mind',description:'Pause, breathe, begin again.',targetCount:5,targetUnit:'minutes'},
  {name:'Write one paragraph',icon:'✍️',category:'Creativity',description:'Let one small thought find its way to the page.',targetCount:1,targetUnit:'times'}
];
function editHabit(id,templateIndex=null) {
  const h=habitFor(id),seed=h||templates[templateIndex]||{},selected=seed.colorHex||'#43866B';
  const hasCheckInToday=Boolean(h&&state.logs.some(log=>log.habitId===h.id&&log.date===today));
  const draftKey=h?`habit:${h.id}:${h.createdAt}`:'habit:new';
  const weekdays=seed.scheduledDays||ALL_DAYS;
  const reminder=seed.reminderMinutes==null?'':`${String(Math.floor(seed.reminderMinutes/60)).padStart(2,'0')}:${String(seed.reminderMinutes%60).padStart(2,'0')}`;
  if(templateIndex!==null)clearDraft(draftKey);
  showModal(`${heading(h?'Edit habit':'A new beginning','Make room for what matters.')}<form id="habit-form" data-id="${h?.id||0}" data-draft-key="${draftKey}"><label class="field">Habit name<input name="name" required maxlength="120" placeholder="e.g. Read a few pages" value="${esc(seed.name||'')}"></label><div class="field-row"><label class="field">Emoji<input name="icon" required maxlength="32" value="${esc(seed.icon||'🌱')}"></label><label class="field">Category<input name="category" required maxlength="40" list="categories" value="${esc(seed.category||'Personal')}"><datalist id="categories">${['Health','Mind','Learning','Creativity','Work','Personal'].map(x=>`<option>${x}</option>`).join('')}</datalist></label></div><label class="field">Your Day 1<input type="date" name="startDate" required min="1900-01-01" max="${Number(today.slice(0,4))+100}${today.slice(4)}" value="${seed.startDate||today}"><small>Start in the past to backfill, or choose a future beginning.</small></label>
    <fieldset class="weekday-field"><legend>Repeat on</legend><div class="weekday-options">${['Mon','Tue','Wed','Thu','Fri','Sat','Sun'].map((day,index)=>`<label><input type="checkbox" name="scheduledDays" value="${index+1}" ${weekdays.includes(index+1)?'checked':''}><span>${day}</span></label>`).join('')}</div><p class="section-note">Days off do not break your chain. Schedule changes begin today; previous patterns stay in history.</p></fieldset>
    <div class="field-row equal"><label class="field">Daily target<input type="number" name="targetCount" required min="1" max="100000" step="1" value="${seed.targetCount||1}"></label><label class="field">Unit<input name="targetUnit" required maxlength="24" value="${esc(seed.targetUnit||'times')}" placeholder="pages, glasses…"></label></div>${h?`<p class="section-note">Goal changes start ${hasCheckInToday?'tomorrow':'today'}. Earlier check-ins keep their original goal.</p>`:'<p class="section-note">A target of 1 is a simple done-or-not-done habit. Larger targets track quantities.</p>'}<label class="field">Color<input type="hidden" name="colorHex" value="${selected}"><span class="color-swatches">${['#254A43','#334E68','#6B435C','#8A492D','#77602C','#4D526D','#6E3937','#525B3C'].map(c=>`<button type="button" class="color-swatch ${c===selected?'selected':''}" style="--swatch:${c}" data-action="select-color" data-color="${c}" aria-pressed="${c===selected}" aria-label="Choose color ${c}"></button>`).join('')}</span></label>
    <details class="more-options"><summary>More options · description, reminder, stacking</summary><div><label class="field">Description · optional<textarea name="description" maxlength="2000" placeholder="A gentle intention…">${esc(seed.description||'')}</textarea></label><label class="field">Reminder time · optional<input type="time" name="reminderTime" value="${reminder}"><small>Saved for the native Android app. This browser preview does not schedule notifications.</small></label><label class="field">Habit stacking · optional<select name="afterHabitId"><option value="">No linked habit</option>${state.habits.filter(p=>p.id!==h?.id&&(!p.isArchived||p.id===h?.afterHabitId)).map(p=>`<option value="${p.id}" ${p.id===h?.afterHabitId?'selected':''}>After ${esc(p.name)}</option>`).join('')}</select><small>“After I do this, I will begin my new habit.”</small></label></div></details><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">${h?'Save changes':'Create habit'}</button><button class="text-button wide" type="button" data-action="close-modal">Keep draft & close</button></form>`,{kind:'habit',id:h?.id||0,draftKey});
}
function editLog(id,defaultStatus=null) {
  const original=habitFor(id),log=logFor(id),date=selectedDate;if(!original||original.isArchived||!scheduled(original,date,state.pauses))return;
  const h=goalAt(original,date);
  const status=defaultStatus||log?.status||'COMPLETED',draftKey=`log:${id}:${h.createdAt}:${date}`;
  const statusChoices=[['COMPLETED','✓ Completed'],['SKIPPED','↳ Skipped'],['FAILED','Missed']];if(h.targetCount>1)statusChoices.push(['IN_PROGRESS','In progress']);
  showModal(`${heading(`${h.icon} ${h.name}`,nice(date,{weekday:'long',day:'numeric',month:'long'}))}<form id="log-form" data-id="${id}" data-date="${date}" data-draft-key="${draftKey}"><input name="status" type="hidden" value="${status}"><div class="status-choice" role="group" aria-label="Check-in status">${statusChoices.map(([value,label])=>`<button type="button" class="${value===status?'selected':''}" data-action="select-status" data-status="${value}" aria-pressed="${value===status}">${label}</button>`).join('')}</div>${h.targetCount>1?`<label class="field">Progress, if in progress<input name="progress" type="number" min="0" max="${h.targetCount-1}" step="1" value="${log?.status==='IN_PROGRESS'?log.progress:0}"><small>Target: ${h.targetCount} ${esc(h.targetUnit)}. Completed entries automatically use the target.</small></label>`:'<input name="progress" type="hidden" value="0">'}<p class="section-note">Tap a status to save it instantly. Skipped days protect your chain and give you room to breathe.</p><label class="field">A note for yourself · optional<textarea name="note" maxlength="4000" placeholder="How did today feel?">${esc(log?.note||'')}</textarea></label><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">Save check-in</button>${log?`<button type="button" class="text-button danger wide" data-action="clear-log" data-id="${id}" data-date="${date}">Clear this check-in</button>`:''}<button class="text-button wide" type="button" data-action="close-modal">Keep draft & close</button></form>`,{kind:'log',id,date,draftKey});
}
function setLog(id,status,note=null,frozen=false,progress=null,date=selectedDate) {
  const next=changeLog(state,id,date,today,status,note,progress,frozen);
  commit(next,status==='SKIPPED'?'Skipped. Your chain is protected.':status==='IN_PROGRESS'?'A little progress counts.':'Check-in saved.');
}
function celebrate() {
  if(!state.settings.animationsEnabled||matchMedia('(prefers-reduced-motion: reduce)').matches)return;
  const colors=['#245f49','#a8823f','#77689c','#a26d8a'];
  for(let i=0;i<24;i++){const piece=document.createElement('i');piece.className='confetti';piece.style.cssText=`left:${15+Math.random()*70}%;background:${colors[i%4]};--dx:${Math.random()*160-80}px;animation-delay:${Math.random()*.2}s`;document.body.append(piece);setTimeout(()=>piece.remove(),1900);}
}
function checkIn(id) {
  const original=habitFor(id),existing=logFor(id);if(!original||original.isArchived||!scheduled(original,selectedDate,state.pauses))return;
  const h=goalAt(original,selectedDate);
  if(h.targetCount>1){const next=changeProgress(state,id,selectedDate,today,existing?.status==='COMPLETED'?-1:1);commit(next,'Progress updated.');if(logFor(id)?.status==='COMPLETED'&&state.settings.animationsEnabled&&[7,30,100,365].includes(dayNumber(h)))celebrate();return;}
  if(existing?.status==='COMPLETED'){
    const next=existing.note?changeLog(state,id,selectedDate,today,'IN_PROGRESS',existing.note,0):{...state,logs:state.logs.filter(l=>l!==existing)};
    commit(next,'Completion undone. Your note is safe.');
  } else {
    commit(changeLog(state,id,selectedDate,today,'COMPLETED',existing?.note||null),'A little progress. A little more you. ✓');
    if(state.settings.animationsEnabled&&[7,30,100,365].includes(dayNumber(h)))celebrate();
  }
}
function download(text,name,type='application/json') {
  const url=URL.createObjectURL(new Blob([text],{type})),link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}

function showTemplates() {
  showModal(`${heading('Start with something small','Choose an idea, then make it yours.')}<div class="template-list">${templates.map((t,index)=>`<button data-action="use-template" data-index="${index}"><span>${t.icon}</span><div><strong>${esc(t.name)}</strong><small>${esc(t.description)}</small></div><span>↗</span></button>`).join('')}</div>`);
}
function showReorder() {
  const active=state.habits.filter(h=>!h.isArchived).sort((a,b)=>a.sortOrder-b.sortOrder);
  showModal(`${heading('Make the order yours','Use the arrows, or drag cards on Today.')}<div class="reorder-list">${active.map((h,index)=>`<div><span>${esc(h.icon)} ${esc(h.name)}</span><button class="icon-button" data-action="move-up" data-id="${h.id}" aria-label="Move ${esc(h.name)} up" ${index===0?'disabled':''}>↑</button><button class="icon-button" data-action="move-down" data-id="${h.id}" aria-label="Move ${esc(h.name)} down" ${index===active.length-1?'disabled':''}>↓</button></div>`).join('')}</div><button class="primary-button wide" data-action="close-modal">Done</button>`);
}
function moveHabit(id,delta) {
  const order=state.habits.filter(h=>!h.isArchived).sort((a,b)=>a.sortOrder-b.sortOrder).map(h=>h.id),index=order.indexOf(id),to=index+delta;
  if(index<0||to<0||to>=order.length)return;
  [order[index],order[to]]=[order[to],order[index]];
  const next={...state,habits:state.habits.map(h=>order.includes(h.id)?{...h,sortOrder:order.indexOf(h.id)}:h)};
  commit(next,'Habit order updated.');showReorder();
  const button=modal.querySelector(`[data-action="${delta<0?'move-up':'move-down'}"][data-id="${id}"]`);if(button&&!button.disabled)button.focus();
}
function rowMenu(id) {
  const original=habitFor(id);if(!original)return;
  const h=goalAt(original,selectedDate);
  const log=logFor(id),isDue=scheduled(h,selectedDate,state.pauses);
  const freezeSpent=state.freezes.some(f=>f.month===selectedDate.slice(0,7));
  const canSkip=isDue&&log?.status!=='SKIPPED';
  const canFail=isDue&&log?.status!=='FAILED';
  const canFreeze=isDue&&selectedDate<today&&(!log||['FAILED','IN_PROGRESS'].includes(log.status))&&!freezeSpent;
  const steps=isDue&&h.targetCount>1&&log?.status!=="COMPLETED"?[[1,"+1"],[Math.ceil(h.targetCount/4),"+25% of goal"],[Math.ceil(h.targetCount/2),"+50% of goal"],[h.targetCount,"Complete goal"]]:[];
  const items=[
    ...steps.map(([amount,label])=>`<button data-action="goal-step" data-id="${h.id}" data-step="${amount}"><span class="menu-icon" aria-hidden="true">+</span><span>${label}</span></button>`),
    `<button data-action="open-habit" data-id="${h.id}"><span class="menu-icon" aria-hidden="true">↗</span><span>Open details<small>History, trend, notes and management</small></span></button>`,
    `<button data-action="edit-habit" data-id="${h.id}"><span class="menu-icon" aria-hidden="true">✎</span><span>Edit habit</span></button>`,
    canSkip?`<button data-action="row-skip" data-id="${h.id}"><span class="menu-icon" aria-hidden="true">↳</span><span>Skip this day<small>${log?.status==='COMPLETED'?'Replaces the completion · ':'Protects your chain · '}can be undone</small></span></button>`:'',
    canFail?`<button data-action="row-fail" data-id="${h.id}" class="danger"><span class="menu-icon" aria-hidden="true">✕</span><span>Mark as missed<small>Breaks the chain · can be undone</small></span></button>`:'',
    `<button data-action="edit-log" data-id="${h.id}" data-default="${log?.status||'COMPLETED'}" ${!isDue?'disabled':''}><span class="menu-icon" aria-hidden="true">✓</span><span>${log?'Edit this check-in':'Add check-in with a note'}</span></button>`,
    canFreeze?`<button data-action="freeze" data-id="${h.id}"><span class="menu-icon" aria-hidden="true">❄</span><span>Use freeze for ${esc(selectedDate.slice(0,7))}<small>One freeze per month</small></span></button>`:'',
    `<button data-action="archive-habit" data-id="${h.id}"><span class="menu-icon" aria-hidden="true">⌸</span><span>${h.isArchived?'Restore habit':'Archive · keep history'}</span></button>`
  ].filter(Boolean);
  showModal(`${heading(`${h.icon} ${h.name}`,h.isArchived?'This habit is archived.':'Quick actions')}<div class="menu-list">${items.join('')}</div><button class="text-button wide" type="button" data-action="close-modal">Close</button>`,{kind:'menu',id});
}

function activityEditor(id=null) {
  const a = id ? state.activities.find(x => x.id === id) : null;
  const draftKey = a ? `activity:${a.id}` : 'activity:new';
  if (id === null && !a) clearDraft(draftKey);
  const targetValue = a?.target == null ? '' : formatQuantity(a.target);
  showModal(`${heading(a ? 'Edit activity' : 'New activity', 'Any movement, any day — your numbers.')}<form id="activity-form" data-id="${a?.id||0}" data-draft-key="${draftKey}"><label class="field">Activity name<input name="name" required maxlength="80" placeholder="e.g. Push-ups" value="${esc(a?.name||'')}"></label><div class="field-row"><label class="field">Emoji<input name="icon" required maxlength="32" value="${esc(a?.icon||'💪')}"></label><label class="field">Unit<input name="unit" required maxlength="24" placeholder="pushups, miles…" value="${esc(a?.unit||'')}"></label></div><label class="field">Daily target · optional<input type="number" name="target" min="0.01" max="1000000" step="any" inputmode="decimal" value="${targetValue}" placeholder="No target — log freely"><small>Actual quantities are recorded as-is: logging 32 against a 20 target is a good day, never an overshoot.</small></label><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">${a?'Save activity':'Create activity'}</button><button class="text-button wide" type="button" data-action="close-modal">Keep draft & close</button></form>`,{kind:'activity',id:a?.id||0,draftKey});
}
function activityLogDialog(id,dateOverride=null) {
  const a = state.activities.find(x => x.id === id);if (!a)return;
  const date = dateOverride && isDate(dateOverride) && dateOverride <= today ? dateOverride : selectedDate;
  const entry = state.activityEntries.find(e => e.activityId === id && e.date === date);
  const last = activityEntriesFor(state, id)[0];
  const draftKey = `alog:${id}:${date}`;
  const quantity = entry ? formatQuantity(entry.quantity) : (last && last.date === date ? formatQuantity(last.quantity) : (a.target != null ? formatQuantity(a.target) : ''));
  showModal(`${heading(`${a.icon} ${a.name}`,nice(date,{weekday:'long',day:'numeric',month:'long'}))}<form id="activity-log-form" data-id="${id}" data-date="${date}" data-draft-key="${draftKey}"><label class="field">Quantity · ${esc(a.unit)}<input type="number" name="quantity" required min="0.01" max="1000000" step="any" inputmode="decimal" value="${quantity}"><small>${a.target!=null?`Target ${formatQuantity(a.target)} — actuals are recorded exactly as performed, even beyond it.`:'No target — log the real number.'} Same-day logs replace the previous value.</small></label><label class="field">Note · optional<input name="note" maxlength="1000" value="${esc(entry?.note||'')}" placeholder="How did it go?"></label>${entry?`<button type="button" class="text-button danger wide" data-action="clear-activity-entry" data-id="${id}" data-date="${date}">Clear this entry</button>`:''}<p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">Save entry</button><button class="text-button wide" type="button" data-action="close-modal">Cancel</button></form>`);
}
function activityHistoryDialog(id) {
  const a = state.activities.find(x => x.id === id);if (!a)return;
  const history = activityEntriesFor(state, id);
  const totals = activityTotals(state, id, plus(today,-27), today);
  const rows = history.length ? history.map(e => `<div class="reorder-list" style="border:0"><div style="border:0"><span>${esc(formatQuantity(e.quantity))} ${esc(a.unit)} · ${nice(e.date)}${e.note?` — ${esc(e.note)}`:''}</span><button class="icon-button" data-action="log-activity" data-id="${id}" data-date="${e.date}" aria-label="Edit ${esc(a.name)} entry for ${nice(e.date)}">✎</button></div></div>`).join('') : '<p class="section-note">No entries yet — log the first session from Today.</p>';
  showModal(`${heading(`${a.icon} ${a.name}`,'History · last entry first')}<p class="section-note">${a.target!=null?`Target ${formatQuantity(a.target)} ${esc(a.unit)} per day · actuals may sit above or below it.`:'Free-form — no target.'}</p><div class="detail-stats"><div><strong>${formatQuantity(totals.total)}</strong><span>LAST 28 DAYS</span></div><div><strong>${totals.days}</strong><span>DAYS LOGGED</span></div><div><strong>${formatQuantity(totals.best)}</strong><span>BEST DAY</span></div></div><div style="max-height:280px;overflow:auto">${rows}</div><button class="primary-button wide" data-action="log-activity" data-id="${id}">Log for ${esc(nice(selectedDate))}</button><button class="text-button wide" data-action="close-modal">Close</button>`);
}
function exportDialog(context=null) {  showModal(`${heading('Keep your progress safe','Choose how to protect your backup.')}<form id="backup-export-form"><label class="checkbox-field"><input name="encrypted" type="checkbox" checked> Protect with a password</label><p class="section-note">Encrypted backups use AES-256-GCM. Keep your password somewhere safe; it cannot be recovered by LifeTrack. Passwords are never saved.</p><label class="field">Password<input name="password" type="password" required minlength="10" maxlength="1024" autocomplete="new-password"></label><label class="field">Confirm password<input name="confirmation" type="password" required minlength="10" maxlength="1024" autocomplete="new-password"></label><p class="section-note">Turn password protection off to export readable JSON.</p><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">Prepare backup</button></form>`,context);
}
function emptyState() {
  return {formatVersion:3,exportedAt:Date.now(),habits:[],logs:[],freezes:[],pauses:[],activities:[],activityEntries:[],settings:{id:1,theme:'LIGHT',dynamicColor:false,backupReminder:false,lastExportAt:null,hapticsEnabled:true,animationsEnabled:true}};
}
function clearVolatileState() {
  drafts={};prefs={reminderMinutes:null,lastNudgeDate:null};filters={query:'',category:'',status:'all'};filterPanelOpen=false;searchOpen=false;
  lastUndo=null;pendingBackup=null;pendingEncryptedText=null;pendingFilename='';nudgeDismissedDate=null;focusUnmarkedDate=null;selectedDate=today;recoveryText=null;storageWarning='';
}
function adoptRemoteReset() {
  let stored=null;try{stored=localStorage.getItem(RESET_STORE);}catch{}
  if(stored===resetToken)return;
  resetToken=stored;
  if(modal.open)modal.close();
  let saved=null,restored=null,failed=null;
  try{saved=localStorage.getItem(STORE);}catch{}
  try{restored=restoreStoredState(saved??JSON.stringify(emptyState()),today);}catch(error){failed=error;}
  clearVolatileState();
  if(failed){recoveryText=saved;storageWarning=failed.message||'Browser storage could not be opened.';render();return;}
  state=restored;revision++;render();toast('LifeTrack was reset in another tab.');
}
let resetInFlight=false;
function performReset() {
  if(resetInFlight)return false;
  resetInFlight=true;
  try {
    const token=`r-${Date.now()}-${Math.random().toString(36).slice(2,10)}`,previousToken=resetToken;
    try{localStorage.setItem(RESET_STORE,token);}catch{throw new Error('Browser storage is unavailable. Nothing was deleted. Free up browser storage or close other LifeTrack tabs and try again.');}
    resetToken=token;
    let payload;
    try{payload=JSON.stringify(emptyState());localStorage.setItem(STORE,payload);}catch(error){
      try{localStorage.removeItem(RESET_STORE);}catch{}
      resetToken=previousToken;
      throw new Error('The erased state could not be saved. Nothing was deleted. Free up browser storage or close other LifeTrack tabs and try again.');
    }
    try{localStorage.removeItem(DRAFT_STORE);}catch{}
    try{localStorage.removeItem(PREFS_STORE);}catch{}
    clearVolatileState();
    try{state=restoreStoredState(payload,today);}catch{state=emptyState();}
    revision++;
    return true;
  } finally { resetInFlight=false; }
}
function showResetConfirmation() {
  showModal(`${heading('Reset LifeTrack')}<p class="section-note">This permanently erases all LifeTrack data in this browser profile: active and archived habits, check-ins and their notes, dated goals, workouts and logged activities, the daily reminder, and app preferences. Previously exported backup files are not touched and remain available. Type RESET below to confirm.</p><button class="secondary-button wide" data-action="reset-export-first">Export a backup first</button><form id="reset-form" novalidate><label class="field">Type RESET to confirm<input name="confirmation" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="RESET"></label><p id="form-error" class="form-error" aria-live="assertive"></p><button class="danger-button wide" type="submit" id="reset-erase" disabled>Erase all local data</button><button class="text-button wide" type="button" data-action="close-modal">Cancel</button></form>`);
}
function confirmImport(restored,filename) {
  pendingBackup=restored;
  showModal(`${heading('Restore this backup?')}<p class="section-note">This replaces the browser preview’s ${state.habits.length} habits with ${restored.habits.length} habits and ${restored.logs.length} check-ins from ${esc(filename)}.<br><br>The native Android app is separate. Export the current preview first if you want to keep it.</p><button class="primary-button wide" data-action="confirm-import">Replace and restore</button><button class="text-button wide" data-action="close-modal">Cancel</button>`);
}
document.addEventListener('click',event=>{
  const button=event.target.closest('[data-action]');if(!button||button.disabled)return;
  const action=button.dataset.action,id=Number(button.dataset.id);
  if(action==='check-in'&&held){event.preventDefault();held=false;return;}
  try {
    switch(action) {
      case 'navigate':navigate(button.dataset.tab);break;
      case 'open-habit':closeModal(true);navigate('detail',id);break;
      case 'add-habit':editHabit(null);break;
      case 'edit-habit':closeModal(true);editHabit(id);break;
      case 'templates':showTemplates();break;
      case 'use-template':editHabit(null,Number(button.dataset.index));break;
      case 'reorder':showReorder();break;
      case 'move-up':moveHabit(id,-1);break;
      case 'move-down':moveHabit(id,1);break;
      case 'row-menu':rowMenu(id);break;
      case 'focus-unmarked':{focusUnmarkedDate=plusDays(today,-1);selectedDate=focusUnmarkedDate;render();const first=app.querySelector('.habit-row.is-nudged .row-main');if(first){first.focus({preventScroll:true});first.scrollIntoView({block:'center'});}break;}
      case 'dismiss-nudge':nudgeDismissedDate=plusDays(today,-1);render();document.getElementById('checkin-date')?.focus({preventScroll:true});break;
      case 'row-skip':closeModal(true);setLog(id,'SKIPPED',logFor(id)?.note);break;
      case 'row-fail':closeModal(true);setLog(id,'FAILED',logFor(id)?.note);break;
      case 'toggle-filter-panel':filterPanelOpen=!filterPanelOpen;render();if(filterPanelOpen)$('#category-filter')?.focus();break;
      case 'toggle-search':searchOpen=!searchOpen;render();if(searchOpen)$('#habit-search')?.focus();break;
      case 'remove-filter':{const key=button.dataset.key;if(key==='query')filters.query='';else if(key==='category')filters.category='';else if(key==='status')filters.status='all';render();break;}
      case 'clear-filters':filters={query:'',category:'',status:'all'};render();break;
      case 'check-in':checkIn(id);break;
      case 'goal-step':closeModal(true);commit(changeProgress(state,id,selectedDate,today,Number(button.dataset.step)),'Progress added.');break;
      case 'increment':commit(changeProgress(state,id,selectedDate,today,1),'Progress added.');break;
      case 'decrement':commit(changeProgress(state,id,selectedDate,today,-1),'Progress adjusted.');break;
      case 'edit-log':closeModal(true);editLog(id,button.dataset.default||null);break;
      case 'close-modal':closeModal();break;
      case 'previous-day':if(selectedDate>'1900-01-01'){selectedDate=plusDays(selectedDate,-1);render();}break;
      case 'next-day':if(selectedDate<today){selectedDate=plusDays(selectedDate,1);render();}break;
      case 'back-today':selectedDate=today;render();break;
      case 'select-color':{const form=$('#habit-form');form.elements.colorHex.value=button.dataset.color;form.querySelectorAll('.color-swatch').forEach(chip=>{const selected=chip===button;chip.classList.toggle('selected',selected);chip.setAttribute('aria-pressed',String(selected));});captureDraft();break;}
      case 'select-status':{const form=$('#log-form');if(!form)break;const changed=form.elements.status.value!==button.dataset.status;form.elements.status.value=button.dataset.status;form.querySelectorAll('.status-choice button').forEach(chip=>{const selected=chip===button;chip.classList.toggle('selected',selected);chip.setAttribute('aria-pressed',String(selected));});captureDraft();if(changed){if(typeof form.requestSubmit==='function')form.requestSubmit();else form.dispatchEvent(new Event('submit',{cancelable:true}));}break;}
      case 'clear-log':{
        const date=button.dataset.date,h=habitFor(id);if(!h||h.isArchived||!scheduled(h,date,state.pauses))throw Error('Restore this habit and choose a scheduled day first.');
        clearDraft(dialogContext?.draftKey);closeModal(true);commit({...state,logs:state.logs.filter(l=>!(l.habitId===id&&l.date===date))},'Check-in cleared.');break;
      }
      case 'archive-habit':{closeModal(true);const h=habitFor(id);commit(archiveValue(state,id,today),h.isArchived?'Welcome back. Your timeline continues.':'Archived. Your history is safe.');break;}
      case 'delete-habit':{const h=habitFor(id);showModal(`${heading('Keep your history?')}<p class="section-note">Deleting ${esc(h.name)} erases its check-ins and notes. Archiving keeps your history. Permanent deletion cannot be undone.</p><button class="danger-button wide" data-action="delete-confirm" data-id="${id}">Continue to deletion</button><button class="text-button wide" data-action="close-modal">Keep habit</button>`);break;}
      case 'delete-confirm':showModal(`${heading('Confirm permanent deletion')}<form id="delete-form" data-id="${id}"><p class="section-note">Type DELETE to erase this habit and all its history.</p><label class="field">Confirmation<input name="confirmation" required pattern="DELETE" autocomplete="off" placeholder="DELETE"></label><button class="danger-button wide" type="submit">Delete forever</button></form>`);break;
      case 'freeze':closeModal(true);showModal(`${heading('A little breathing room',selectedDate)}<p class="section-note">Use the app’s one freeze for ${selectedDate.slice(0,7)} to protect this missed day. It stays spent if the entry is later deleted.</p><button class="primary-button wide" data-action="confirm-freeze" data-id="${id}" data-date="${selectedDate}">❄ Protect this day</button>`);break;
      case 'confirm-freeze':{const date=button.dataset.date;const next=redeemFreeze(state,id,date,today,logFor(id,date)?.note||null);closeModal(true);commit(next,'Streak protected. It’s okay to pause.',{undo:false});break;}
      case 'choose-theme':showModal(`${heading('Make yourself at home')}<div class="status-choice" role="group" aria-label="Theme">${['LIGHT','DARK','SYSTEM'].map(t=>`<button class="${state.settings.theme===t?'selected':''}" data-action="set-theme" data-theme="${t}" aria-pressed="${state.settings.theme===t}">${t==='SYSTEM'?'System':t==='LIGHT'?'☀ Light':'☾ Dark'}</button>`).join('')}</div><p class="section-note">Your preference is saved only in this browser preview.</p>`);break;
      case 'set-theme':closeModal(true);commit({...state,settings:{...state.settings,theme:button.dataset.theme}},'Theme updated.',{undo:false});break;
      case 'toggle-reminder':commit({...state,settings:{...state.settings,backupReminder:!state.settings.backupReminder}},'Saved for Android. This browser does not schedule notifications.',{undo:false});break;
      case 'set-reminder':showModal(`${heading('Daily check-in reminder','A gentle nudge at your time.')}<form id="reminder-form"><label class="field">Remind me at<input type="time" name="reminderTime" value="${prefs.reminderMinutes==null?'':`${String(Math.floor(prefs.reminderMinutes/60)).padStart(2,'0')}:${String(prefs.reminderMinutes%60).padStart(2,'0')}`}" required><small>In this preview the nudge appears in-app once per day after this time while habits are still open. The native app delivers a real notification.</small></label><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">Save reminder time</button>${prefs.reminderMinutes!=null?'<button class="text-button wide" type="button" data-action="clear-reminder">Turn off</button>':''}<button class="text-button wide" type="button" data-action="close-modal">Cancel</button></form>`);break;
      case 'clear-reminder':prefs.reminderMinutes=null;prefs.lastNudgeDate=null;savePrefs();closeModal(true);render();toast('Daily reminder turned off.');break;
      case 'toggle-dynamic':commit({...state,settings:{...state.settings,dynamicColor:!state.settings.dynamicColor}},'Saved for Android. Wallpaper colors require Material You.',{undo:false});break;
      case 'toggle-haptics':commit({...state,settings:{...state.settings,hapticsEnabled:!state.settings.hapticsEnabled}},'Saved for Android. This browser preview does not vibrate.',{undo:false});break;
      case 'toggle-animations':commit({...state,settings:{...state.settings,animationsEnabled:!state.settings.animationsEnabled}},'Animation preference updated.',{undo:false});break;
      case 'export-data':exportDialog();break;
      case 'import-data':$('#import-file').click();break;
      case 'confirm-import':if(pendingBackup){const restored=validatePortableBackup(makeBackup(pendingBackup),today);pendingBackup=null;recoveryText=null;storageWarning='';selectedDate=today;closeModal(true);commit(restored,'Backup restored in this browser preview.',{undo:false});}break;
      case 'about':showModal(`${heading('LifeTrack 1.3.0','Small steps. Your own pace.')}<p class="section-note">A private habit companion built for your own timeline. Every habit has its own Day 1. Activities track workouts and movements with real quantities.<br><br>This browser adaptation uses separate local sample data. The native app uses Kotlin, Compose, Room and Hilt and provides actual Android reminders, widgets, activities and haptics.<br><br>No external requests, accounts, ads or telemetry. Backup encryption is optional; plain JSON and browser storage remain readable to anyone with access to this device.</p><button class="primary-button wide" data-action="close-modal">Keep growing 🌱</button>`);break;
      case 'reset-demo':showModal(`${heading('Start fresh?')}<p class="section-note">Reset the browser preview to its original sample habits. Current preview changes will be replaced. Your native Android data is separate.${recoveryText!==null?'<br><br>Download the preserved original records before replacing them.':''}</p><button class="primary-button wide" data-action="confirm-reset">Reset sample data</button><button class="text-button wide" data-action="close-modal">Keep exploring</button>`);break;
      case 'strip-day':selectedDate=button.dataset.date;render();app.querySelector('.day-summary')?.scrollIntoView({block:'nearest'});break;
      case 'confirm-reset':recoveryText=null;storageWarning='';selectedDate=today;closeModal(true);commit(demoData(),'A fresh little beginning.',{undo:false});navigate('today');break;
      case 'reset-lifetrack':showResetConfirmation();break;
      case 'reset-export-first':exportDialog({kind:'reset-export'});break;
      case 'add-activity':activityEditor(null);break;
      case 'edit-activity':activityEditor(id);break;
      case 'open-activity':activityHistoryDialog(id);break;
      case 'log-activity':closeModal(true);activityLogDialog(id,button.dataset.date||null);break;
      case 'delete-activity':{const a=state.activities.find(x=>x.id===id);const entries=state.activityEntries.filter(e=>e.activityId===id).length;showModal(`${heading('Delete this activity?')}<p class="section-note">Deleting ${esc(a.name)} also removes its ${entries} logged ${entries===1?'entry':'entries'}. This cannot be undone.</p><button class="danger-button wide" data-action="delete-activity-confirm" data-id="${id}">Delete activity</button><button class="text-button wide" data-action="close-modal">Keep activity</button>`);break;}
      case 'delete-activity-confirm':closeModal(true);commit(deleteActivityValue(state,id),'Activity and its history deleted.',{undo:false});break;
      case 'clear-activity-entry':{const date=button.dataset.date;clearDraft(`alog:${id}:${date}`);commit(clearActivityEntry(state,id,date),'Entry cleared.');closeModal(true);activityHistoryDialog(id);break;}
      case 'recover-storage':if(recoveryText!==null)download(recoveryText,`lifetrack-preserved-records-${today}.json`);break;
      case 'undo':if(lastUndo&&lastUndo.revision===revision){state=lastUndo.state;lastUndo=null;revision++;save();render();toast('Previous status, progress and note restored.');}else toast('A newer change is already saved.');break;
      case 'inspect-day':{const date=button.dataset.date,habit=button.dataset.habit?habitFor(Number(button.dataset.habit)):null,due=state.habits.filter(h=>(!habit||h.id===habit.id)&&scheduled(h,date,state.pauses)),done=state.logs.filter(l=>l.date===date&&l.status==='COMPLETED'&&due.some(h=>h.id===l.habitId)).length;toast(habit?`${habit.name} · ${nice(date,{day:'numeric',month:'short',year:'numeric'})} · ${done?'Completed':due.length?'Scheduled · not completed':'Rest day'}`:`${nice(date,{day:'numeric',month:'short',year:'numeric'})} · ${done} of ${due.length} habits completed${due.length?` · ${Math.round(done/due.length*100)}%`:''}`);break;}
    }
  } catch(error){toast(error.message||'That change could not be saved.');}
});


document.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target,values=formValues(form),submit=form.querySelector('[type="submit"]');
  try {
    if(form.id==='habit-form') {
      const id=Number(form.dataset.id),old=habitFor(id),after=values.afterHabitId?Number(values.afterHabitId):null,startDate=values.startDate,scheduledDays=values.scheduledDays.map(Number).sort((a,b)=>a-b),targetCount=values.targetCount===undefined?old?.targetCount:Number(values.targetCount),targetUnit=values.targetUnit===undefined?old?.targetUnit:values.targetUnit.trim();
      const goalHistory=validateGoalEdit(old,{targetCount,targetUnit},state.logs,today);
      if(!values.name.trim()||!values.icon.trim()||!values.category.trim())throw Error('Give your habit a name, icon, and category.');
      if(!scheduledDays.length)throw Error('Choose at least one weekday.');
      if(old&&state.logs.some(l=>l.habitId===id&&l.date<startDate))throw Error('Your start date cannot move past an existing check-in.');
      const visited=new Set([id]);let pointer=after;while(pointer){if(visited.has(pointer))throw Error('Habit stacks cannot form a cycle.');visited.add(pointer);pointer=habitFor(pointer)?.afterHabitId;}
      if(after&&habitFor(after)?.isArchived&&old?.afterHabitId!==after)throw Error('Choose an active habit for a new stack link.');
      let history=copy(old?.scheduleHistory||[]);
      if(old&&[...old.scheduledDays].sort().join(',')!==scheduledDays.join(',')) {
        if(!history.length)history=[{effectiveFrom:old.startDate,days:[...old.scheduledDays]}];
        history=history.filter(r=>r.effectiveFrom<today);history.push({effectiveFrom:today,days:scheduledDays});
      }
      const reminderMinutes=values.reminderTime?Number(values.reminderTime.slice(0,2))*60+Number(values.reminderTime.slice(3)):null;
      const now=Math.max(Date.now(),old?.createdAt||0),habit={id:id||Math.max(0,...state.habits.map(h=>h.id))+1,name:values.name.trim(),description:values.description.trim()||null,icon:values.icon.trim(),colorHex:values.colorHex,startDate,isArchived:old?.isArchived||false,sortOrder:old?.sortOrder??Math.max(-1,...state.habits.map(h=>h.sortOrder))+1,category:values.category.trim(),afterHabitId:after,archivedOn:old?.archivedOn||null,createdAt:old?.createdAt||now,updatedAt:now,scheduledDays,targetCount,targetUnit,reminderMinutes,scheduleHistory:history,...(goalHistory.length?{goalHistory}:{})};
      const habits=old?state.habits.map(h=>h.id===id?habit:h):[...state.habits,habit];
      const logs=state.logs;
      const next={...state,habits,logs};validatePortableBackup(makeBackup(next),today);
      clearDraft(form.dataset.draftKey);closeModal(true);commit(next,old?'A little adjustment. Same intention.':'Your new beginning is ready. 🌱');
    } else if(form.id==='log-form') {
      const next=changeLog(state,Number(form.dataset.id),form.dataset.date,today,values.status,values.note,Number(values.progress));
      clearDraft(form.dataset.draftKey);closeModal(true);commit(next,values.status==='SKIPPED'?'Skipped. Your chain is protected.':'Check-in saved.');
    } else if(form.id==='activity-form') {
      const next=saveActivity(state,{id:Number(form.dataset.id)||null,name:values.name,icon:values.icon,unit:values.unit,target:values.target},today);
      validatePortableBackup(makeBackup(next),today);
      clearDraft(form.dataset.draftKey);closeModal(true);commit(next,form.dataset.id==='0'?'Activity ready — log the first session.':'Activity updated.');
    } else if(form.id==='activity-log-form') {
      const date=form.dataset.date;
      const next=setActivityEntry(state,Number(form.dataset.id),date,values.quantity,values.note,today);
      validatePortableBackup(makeBackup(next),today);
      clearDraft(form.dataset.draftKey);closeModal(true);commit(next,`Logged for ${nice(date,{day:'numeric',month:'short'})}.`);
    } else if(form.id==='reminder-form') {
      const time=values.reminderTime;
      if(!time)throw Error('Choose a time for the reminder.');
      const minutes=Number(time.slice(0,2))*60+Number(time.slice(3));
      if(!Number.isInteger(minutes)||minutes<0||minutes>1439)throw Error('Choose a valid time.');
      prefs.reminderMinutes=minutes;prefs.lastNudgeDate=null;savePrefs();closeModal(true);render();toast(`Daily reminder set for ${time}.`);
    } else if(form.id==='delete-form'&&values.confirmation==='DELETE') {
      closeModal(true);commit(deleteValue(state,Number(form.dataset.id)),'Habit and history deleted.',{undo:false});navigate('today');
    } else if(form.id==='reset-form') {
      const box=$('#form-error');
      if(values.confirmation!=='RESET'){if(box)box.textContent='Type RESET to confirm.';return;}
      submit.disabled=true;form.setAttribute('aria-busy','true');
      try {
        if(performReset()){dialogOpener=null;closeModal(true);navigate('today');toast('LifeTrack was reset.');requestAnimationFrame(()=>app.querySelector('[data-action="add-habit"]')?.focus({preventScroll:true}));}
      } catch(error) {
        if(box)box.textContent=error.message;else toast(error.message||'LifeTrack could not be reset.');
        if(submit.isConnected){submit.disabled=false;form.removeAttribute('aria-busy');}
      }
    } else if(form.id==='backup-export-form') {
      submit.disabled=true;form.setAttribute('aria-busy','true');
      const now=Date.now(),sourceRevision=revision,next={...state,settings:{...state.settings,lastExportAt:now}},plain=encodeBackup(next,today,now),encrypted=form.elements.encrypted.checked;
      if(encrypted&&values.password!==values.confirmation)throw Error('The passwords do not match.');
      const data=encrypted?await encryptBackup(plain,values.password):plain;
      if(!form.isConnected||!modal.open)return;
      form.reset();download(data,`lifetrack-preview-${today}${encrypted?'.enc':''}.json`);if(revision===sourceRevision){state=next;revision++;lastUndo=null;save();}closeModal(true);render();toast('Backup prepared for download. Keep the file and password safe.');
    } else if(form.id==='backup-import-form') {
      submit.disabled=true;form.setAttribute('aria-busy','true');
      const plain=await decryptBackup(pendingEncryptedText,values.password);if(!form.isConnected||!modal.open)return;form.reset();const restored=parseBackup(plain,today);pendingEncryptedText=null;confirmImport(restored,pendingFilename);
    }
  } catch(error) { const box=$('#form-error');if(box)box.textContent=error.message;else toast(error.message||'The change could not be saved.'); }
  finally { if(submit?.isConnected){submit.disabled=false;form.removeAttribute('aria-busy');} }
});


document.addEventListener('input',event=>{
  if(event.target.id==='habit-search'&&!event.isComposing){filters.query=event.target.value;render();}
  if(event.target.name==='confirmation'&&event.target.closest('#reset-form')){const erase=$('#reset-erase');if(erase)erase.disabled=event.target.value!=='RESET';}
  if(event.target.closest('form[data-draft-key]'))captureDraft();
});
document.addEventListener('change',event=>{
  const target=event.target;
  if(target.id==='checkin-date'){const date=target.value;if(date&&date<=today&&date>='1900-01-01'){focusUnmarkedDate=null;selectedDate=date;render();}}
  if(target.id==='category-filter'){filters.category=target.value;render();}
  if(target.id==='status-filter'){filters.status=target.value;render();}
  if(target.id==='period-select'){analyticsPeriod=target.value;render();}
  if(target.closest('form[data-draft-key]'))captureDraft();
  if(target.name==='encrypted'){const form=target.form;for(const name of ['password','confirmation']){form.elements[name].required=target.checked;form.elements[name].disabled=!target.checked;}}
  if(target.name==='confirmation'&&target.closest('#reset-form')){const erase=$('#reset-erase');if(erase)erase.disabled=target.value!=='RESET';}
  if(target.hasAttribute('data-heatmap-date')){const date=target.value;if(date&&date<=today){const panel=target.closest('.an-panel'),cell=panel?.querySelector(`[data-date="${date}"]`);if(cell){cell.click();cell.scrollIntoView({block:'nearest',inline:'nearest'});}}}
});
document.addEventListener('keydown',event=>{
  const cell=event.target.closest('.an-cell[data-date],.an-cons-cell[data-date]');if(!cell)return;
  const movement={ArrowLeft:-7,ArrowRight:7,ArrowUp:-1,ArrowDown:1}[event.key];if(movement===undefined)return;
  const date=plusDays(cell.dataset.date,movement),panel=cell.closest('.an-panel'),next=panel.querySelector(`[data-date="${date}"]`);if(!next)return;
  event.preventDefault();cell.tabIndex=-1;next.tabIndex=0;next.focus();next.scrollIntoView({block:'nearest',inline:'nearest'});next.click();const picker=panel.querySelector('[data-heatmap-date]');if(picker)picker.value=date;
});
$('#import-file').addEventListener('change',async event=>{
  const file=event.target.files[0];event.target.value='';if(!file)return;
  try {
    if(file.size>MAX_ENVELOPE_BYTES)throw Error('This file exceeds the 15 MB encrypted backup limit.');
    const text=(await file.text()).replace(/^\uFEFF/,''),parsed=JSON.parse(text);
    if(parsed?.kind==='lifetrack-encrypted') {
      pendingEncryptedText=text;pendingFilename=file.name;
      showModal(`${heading('Unlock your backup','Your password stays in this session only.')}<form id="backup-import-form"><label class="field">Backup password<input name="password" type="password" required autocomplete="current-password" maxlength="1024"></label><p id="form-error" class="form-error" aria-live="polite"></p><button class="primary-button wide" type="submit">Unlock backup</button></form>`);
    } else confirmImport(parseBackup(text,today),file.name);
  } catch(error) { toast(error.message||'This file is not a valid LifeTrack backup.'); }
});
document.addEventListener('pointerdown',event=>{
  const button=event.target.closest('[data-action="check-in"]');if(!button||button.disabled)return;held=false;clearTimeout(holdTimer);holdTimer=setTimeout(()=>{held=true;const id=Number(button.dataset.id);editLog(id,logFor(id)?.status||'SKIPPED');},520);
});
['pointerup','pointercancel','pointermove'].forEach(type=>document.addEventListener(type,()=>clearTimeout(holdTimer)));
document.addEventListener('contextmenu',event=>{const button=event.target.closest('[data-action="check-in"]');if(button&&!button.disabled){event.preventDefault();const id=Number(button.dataset.id);editLog(id,logFor(id)?.status||'SKIPPED');}});
document.addEventListener('dragstart',event=>{const card=event.target.closest('.habit-row');if(!card)return;clearTimeout(holdTimer);dragging=Number(card.dataset.habit);card.classList.add('is-dragging');event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',String(dragging));});
document.addEventListener('dragover',event=>{if(dragging&&event.target.closest('.habit-row'))event.preventDefault();});
document.addEventListener('drop',event=>{const card=event.target.closest('.habit-row');if(!card||!dragging)return;event.preventDefault();const order=state.habits.filter(h=>!h.isArchived).sort((a,b)=>a.sortOrder-b.sortOrder).map(h=>h.id),from=order.indexOf(dragging),to=order.indexOf(Number(card.dataset.habit));if(from>=0&&to>=0){order.splice(to,0,order.splice(from,1)[0]);commit({...state,habits:state.habits.map(h=>order.includes(h.id)?{...h,sortOrder:order.indexOf(h.id)}:h)},'Habit order updated.');}dragging=null;});
document.addEventListener('dragend',()=>{dragging=null;document.querySelectorAll('.is-dragging').forEach(c=>c.classList.remove('is-dragging'));});
modal.addEventListener('click',event=>{if(event.target===modal){const r=modal.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)closeModal();}});

modal.addEventListener('cancel',()=>{captureDraft();dialogOpener=null;});
modal.addEventListener('close',()=>{const context=dialogContext;dialogContext=null;if(dialogOpener&&document.contains(dialogOpener)&&!dialogOpener.disabled)dialogOpener.focus({preventScroll:true});dialogOpener=null;if(context?.kind==='reset-export')showResetConfirmation();});
window.addEventListener('beforeunload',captureDraft);
function checkDailyNudge() {
  if(prefs.reminderMinutes==null||recoveryText!==null)return;
  if(document.visibilityState!=='visible')return;
  const now=new Date(),minutes=now.getHours()*60+now.getMinutes();
  if(minutes<prefs.reminderMinutes)return;
  if(prefs.lastNudgeDate===today)return;
  const open=unmarkedOn(state,today);
  if(!open.length)return;
  prefs.lastNudgeDate=today;savePrefs();
  if(route!=='today')navigate('today');
  toast(`Time to check in · ${open.length} habit${open.length===1?'':'s'} still open`);
}
function refreshClock() {
  const next=localDate(),previous=today;today=next;
  if(previous!==next){if(selectedDate===previous||selectedDate>next)selectedDate=next;lastUndo=null;render();toast('A new day, a new small beginning.');}
  checkDailyNudge();
}
window.addEventListener('focus',refreshClock);
window.addEventListener('pageshow',refreshClock);
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')refreshClock();});
setInterval(refreshClock,30000);
window.addEventListener('storage',event=>{
  if(event.key!==STORE&&event.key!==RESET_STORE)return;
  if(event.key===STORE&&!event.newValue)return;
  if(staleAfterReset()){adoptRemoteReset();return;}
  if(event.key===RESET_STORE)return;
  try{const next=restoreStoredState(event.newValue,today);if(JSON.stringify(next)===JSON.stringify(state))return;state=next;recoveryText=null;storageWarning='';lastUndo=null;revision++;render();toast('Preview data updated in another tab. Your open draft is kept.');}
  catch(error){recoveryText=event.newValue;storageWarning=error.message;render();}
});
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',theme);

const focusDateParam=urlParams.get('date');
const focusDateValid=focusDateParam&&isDate(focusDateParam)&&focusDateParam<=today&&focusDateParam>='1900-01-01';
if(focusDateValid)selectedDate=focusDateParam;
if(urlParams.get('focus')==='unmarked')focusUnmarkedDate=focusDateValid?selectedDate:plusDays(today,-1);
save();render();
if(focusUnmarkedDate)requestAnimationFrame(()=>{app.querySelector('.habit-row.is-nudged')?.scrollIntoView({block:'center'});});
checkDailyNudge();
