import { scheduled, completionRate, currentStreak as streakValue } from './domain.js?v=fr1';
const esc = (value = '') => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const fmtQ = value => Number.isInteger(value) ? String(value) : String(Math.round(value * 100) / 100);
const parse = value => new Date(`${value}T12:00:00Z`);
const iso = value => value.toISOString().slice(0, 10);
const plus = (value, days) => { const date = parse(value); date.setUTCDate(date.getUTCDate() + days); return iso(date); };
const monday = value => plus(value, -((parse(value).getUTCDay() + 6) % 7));
const monthStart = value => `${value.slice(0,7)}-01`;
const dayCount = (from, to) => Math.round((parse(to) - parse(from)) / 86400000);
const format = (value, options = {month:'short',day:'numeric'}) => parse(value).toLocaleDateString('en-US', {...options, timeZone:'UTC'});
const eligible=(habit,date,pauses=[])=>scheduled(habit,date,pauses);
const percent=rate=>rate.opportunities?Math.floor(rate.completed/rate.opportunities*100):null;
const rateFor=(state,from,through)=>completionRate(state,from,through);
export function currentStreak(habit,logs,today,pauses=[]) { return streakValue(habit,logs,today,pauses); }

/** Habits that were due on a date but carry no check-in record at all. */
export function unmarkedOn(state, date) {
  return state.habits.filter(habit => !habit.isArchived && scheduled(habit, date, state.pauses||[]) && !state.logs.some(log => log.habitId === habit.id && log.date === date));
}

/** Day-level completion state shared by the heatmap and consistency grids. */
function dayState(state, cellDate, pool, dailyLogs) {
  const dueSet = new Set(pool.filter(habit => eligible(habit, cellDate, state.pauses||[])).map(habit => habit.id));
  const dayLogs = dailyLogs.get(cellDate) || [];
  const complete = dayLogs.filter(log => dueSet.has(log.habitId) && log.status === 'COMPLETED').length;
  const frozen = dayLogs.some(log => dueSet.has(log.habitId) && log.isFrozen);
  const skipped = dayLogs.some(log => dueSet.has(log.habitId) && log.status === 'SKIPPED' && !log.isFrozen);
  return { dueSet, dayLogs, complete, frozen, skipped, fraction: dueSet.size ? complete / dueSet.size : 0 };
}

export function heatmapHTML(state, today, compact = false, onlyHabit = null) {
  const start = plus(monday(today), -52 * 7);
  const dailyLogs = new Map();
  for (const log of state.logs) {
    if (!dailyLogs.has(log.date)) dailyLogs.set(log.date, []);
    dailyLogs.get(log.date).push(log);
  }
  const pool = onlyHabit ? state.habits.filter(habit => habit.id === onlyHabit.id) : state.habits;
  const weeks = Array.from({length:53}, (_, week) => {
    const date = plus(start, week * 7);
    const label = week === 0 || date.slice(0,7) !== plus(date,-7).slice(0,7) ? format(date, {month:'short'}) : '';
    const cells = Array.from({length:7}, (_, dayIndex) => {
      const cellDate = plus(date, dayIndex);
      if (cellDate > today) return '<span class="an-cell an-cell-future" aria-hidden="true"></span>';
      const day = dayState(state, cellDate, pool, dailyLogs);
      if (onlyHabit && !day.dueSet.size) return `<span class="an-cell an-cell-off" aria-hidden="true" title="${esc(`${format(cellDate,{weekday:'short',month:'short',day:'numeric'})}: Rest day`)}"></span>`;
      const level = onlyHabit ? (day.complete ? 4 : day.skipped || day.frozen ? 2 : 0) : (day.fraction === 0 ? 0 : Math.min(4, Math.ceil(day.fraction * 4)));
      const marker = day.frozen ? 'F' : day.skipped ? 'S' : '';
      const markerClass = marker === 'F' ? ' an-cell-frozen' : marker === 'S' ? ' an-cell-skip' : '';
      const description = onlyHabit
        ? `${format(cellDate,{weekday:'short',month:'short',day:'numeric'})}: ${day.complete ? 'Completed' : day.skipped || day.frozen ? 'Skipped or frozen' : 'Not completed'}`
        : `${format(cellDate,{weekday:'short',month:'short',day:'numeric'})}: ${day.complete} of ${day.dueSet.size} habits completed${day.dueSet.size ? ` (${Math.round(day.fraction * 100)}%)` : ''}`;
      return `<button class="an-cell an-level-${level}${markerClass}${cellDate === today ? ' an-cell-today' : ''}" data-action="inspect-day" data-date="${cellDate}"${onlyHabit?` data-habit="${onlyHabit.id}"`:''} tabindex="${cellDate===today?0:-1}" aria-label="${esc(description)}" title="${esc(description)}">${marker}</button>`;
    }).join('');
    return `<div class="an-heat-week"><span class="an-month">${esc(label)}</span>${cells}</div>`;
  }).join('');
  const completionCount = state.logs.filter(log => log.status === 'COMPLETED' && (!onlyHabit || log.habitId === onlyHabit.id) && log.date >= start && log.date <= today).length;
  return `<section class="an-panel an-heat-panel${compact ? ' an-heat-compact' : ''}">
    <div class="an-panel-heading"><div><span class="an-eyebrow">A LITTLE, OFTEN</span><h2>Your consistency</h2></div><span class="an-badge">Last 12 months</span></div>
    <p class="an-caption">${onlyHabit ? esc(onlyHabit.name) + ' · ' : ''}Every small square is a step forward. S marks a skipped day and F a frozen day.</p>
    <div class="an-heat-scroll" data-heatmap-scroll><div class="an-heat-grid"><div class="an-weekdays"><span></span><span>M</span><span></span><span>W</span><span></span><span>F</span><span></span><span></span></div>${weeks}</div></div>
    <label class="an-inspect-date">Inspect a day<input type="date" data-heatmap-date min="${start}" max="${today}" value="${today}"></label><div class="an-heat-footer"><span><strong>${completionCount}</strong> check-ins</span><span class="an-legend">Less <i class="an-level-0"></i><i class="an-level-1"></i><i class="an-level-2"></i><i class="an-level-3"></i><i class="an-level-4"></i> More</span></div>
  </section>`;
}

/** Completion rate for a selected period with a fair, elapsed-aligned comparison. */
export function periodSummary(state, today, period = 'week') {
  const windows = {
    week: {label:'This week', from:monday(today)},
    month: {label:'This month', from:monthStart(today)},
    fortnight: {label:'Last 14 days', from:plus(today,-13)}
  };
  const window = windows[period] || windows.week;
  const rate = rateFor(state, window.from, today);
  let previous;
  if (period === 'month') {
    const current = parse(today);
    const year = current.getUTCFullYear(), month = current.getUTCMonth();
    const finalDay = new Date(Date.UTC(year, month, 0, 12)).getUTCDate();
    previous = {from:iso(new Date(Date.UTC(year, month - 1, 1, 12))), through:iso(new Date(Date.UTC(year, month - 1, Math.min(current.getUTCDate(), finalDay), 12)))};
  } else {
    const shift = period === 'fortnight' ? 14 : 7;
    previous = {from:plus(window.from,-shift), through:plus(today,-shift)};
  }
  const previousRate = rateFor(state, previous.from, previous.through);
  const now = percent(rate), before = percent(previousRate);
  return {
    label: window.label, from: window.from, through: today, rate,
    percent: now,
    previous: {from: previous.from, through: previous.through, rate: previousRate, percent: before},
    delta: now === null || before === null ? null : now - before
  };
}

export function periodSummaryHTML(state, today, period = 'week') {
  const summary = periodSummary(state, today, period);
  const detail = summary.rate.opportunities ? `${summary.rate.completed} of ${summary.rate.opportunities} completed` : 'Nothing was scheduled';
  const trend = summary.delta === null
    ? '<span class="an-comparison-neutral">No comparable earlier period yet</span>'
    : summary.delta === 0
      ? '<span class="an-comparison-neutral">Steady against the previous period</span>'
      : `<span class="an-change${summary.delta < 0 ? ' an-change-down' : ''}">${summary.delta > 0 ? '↗ +' : '↘ '}${summary.delta} pts vs previous period</span>`;
  return `<section class="an-panel an-period"><div class="an-panel-heading"><div><span class="an-eyebrow">WHERE YOU STAND</span><h2>${esc(summary.label)}</h2></div>
    <label class="an-period-select">Period<select id="period-select">${[['week','This week'],['month','This month'],['fortnight','Last 14 days']].map(([v,t])=>`<option value="${v}" ${v===period?'selected':''}>${t}</option>`).join('')}</select></label></div>
    <div class="an-period-body"><strong class="an-metric">${summary.percent === null ? '—' : summary.percent + '%'}</strong>
    <div class="an-period-detail"><strong>${esc(detail)}</strong><small>Skipped days stay out of the count; partial days are not completions.</small>${trend}</div></div></section>`;
}

/** Completions per day for a window, counting only days a habit was actually due. */
export function dailyCompletions(state, from, through) {
  const habits = new Map(state.habits.map(habit => [habit.id, habit]));
  const counts = new Map();
  for (const log of state.logs) {
    const habit = habits.get(log.habitId);
    if (log.status !== 'COMPLETED' || log.date < from || log.date > through || !habit || !eligible(habit, log.date, state.pauses||[])) continue;
    counts.set(log.date, (counts.get(log.date) || 0) + 1);
  }
  const days = [];
  for (let date = from; date <= through; date = plus(date, 1)) days.push({date, count: counts.get(date) || 0});
  return days;
}

export function dailyCheckinsHTML(state, today, days = 14) {
  const from = plus(today, -(days - 1));
  const series = dailyCompletions(state, from, today);
  const total = series.reduce((sum, day) => sum + day.count, 0);
  const max = Math.max(1, ...series.map(day => day.count));
  const bars = series.map((day, index) => `<div class="an-bar-column" title="${esc(`${format(day.date)}: ${day.count} ${day.count === 1 ? 'completion' : 'completions'}`)}"><span class="an-bar-count">${day.count}</span><div class="an-bar-space"><div class="an-bar${day.date === today ? ' an-bar-current' : ''}" style="height:${Math.max(4, day.count / max * 84)}px"></div></div>${index === 0 || day.date === today ? `<span>${format(day.date,{month:'numeric',day:'numeric'})}</span>` : '<span></span>'}</div>`).join('');
  return `<section class="an-panel"><div class="an-panel-heading"><div><span class="an-eyebrow">SHOWING UP ADDS UP</span><h2>Daily check-ins</h2></div><span class="an-badge">Last ${days} days</span></div>
  <p class="an-caption">${total} completions across ${days} days · only scheduled days count.</p>
  <div class="an-bars" role="img" aria-label="Daily completions, ${format(from)} to ${format(today)}: ${series.map(day => `${format(day.date)} ${day.count}`).join(', ')}">${bars}</div></section>`;
}

/** Compact 12-week consistency grid with skipped (S) and frozen (F) day markers. */
export function consistencyHTML(state, today, weeks = 12) {
  const start = plus(monday(today), -(weeks - 1) * 7);
  const dailyLogs = new Map();
  for (const log of state.logs) {
    if (!dailyLogs.has(log.date)) dailyLogs.set(log.date, []);
    dailyLogs.get(log.date).push(log);
  }
  let monthMarkers = '';
  const columns = Array.from({length: weeks}, (_, week) => {
    const date = plus(start, week * 7);
    const label = week === 0 || date.slice(0,7) !== plus(date,-7).slice(0,7) ? format(date, {month:'short'}) : '';
    monthMarkers += `<span class="an-month">${esc(label)}</span>`;
    const cells = Array.from({length:7}, (_, dayIndex) => {
      const cellDate = plus(date, dayIndex);
      if (cellDate > today) return '<span class="an-cons-cell an-cell-future" aria-hidden="true"></span>';
      const day = dayState(state, cellDate, state.habits, dailyLogs);
      const level = day.fraction === 0 ? 0 : Math.min(4, Math.ceil(day.fraction * 4));
      const marker = day.frozen ? 'F' : day.skipped ? 'S' : '';
      const markerClass = marker === 'F' ? ' an-cell-frozen' : marker === 'S' ? ' an-cell-skip' : '';
      const description = `${format(cellDate,{weekday:'short',month:'short',day:'numeric'})}: ${day.complete} of ${day.dueSet.size} habits completed${day.dueSet.size ? ` (${Math.round(day.fraction * 100)}%)` : ''}`;
      return `<button class="an-cons-cell an-level-${level}${markerClass}${cellDate === today ? ' an-cell-today' : ''}" data-action="inspect-day" data-date="${cellDate}" tabindex="${cellDate === today ? '0' : '-1'}" aria-label="${esc(description)}" title="${esc(description)}">${marker}</button>`;
    }).join('');
    return `<div class="an-cons-col">${cells}</div>`;
  }).join('');
  return `<section class="an-panel"><div class="an-panel-heading"><div><span class="an-eyebrow">A LITTLE, OFTEN</span><h2>Your consistency</h2></div><span class="an-badge">Last ${weeks} weeks</span></div>
  <p class="an-caption">Tap a day for details. S marks a skipped day, F a frozen one.</p>
  <div class="an-cons-scroll" data-heatmap-scroll><div class="an-cons-wrap"><div class="an-cons-months">${monthMarkers}</div><div class="an-cons-cols">${columns}</div></div></div>
  <div class="an-heat-footer"><label class="an-date-picker">Inspect a day<input type="date" value="${today}" min="${start}" max="${today}" data-heatmap-date></label><span class="an-legend"><i class="an-level-3"></i> Completed <i class="an-cons-cell an-cell-skip an-legend-cell">S</i> Skipped <i class="an-cons-cell an-cell-frozen an-legend-cell">F</i> Frozen</span></div></section>`;
}

/** One row per active habit with its last-14-day strip, 7-day rate and current streak. */
export function habitConsistencyHTML(state, today, days = 14) {
  const from = plus(today, -(days - 1));
  const active = state.habits.filter(habit => !habit.isArchived);
  const rows = active.map(habit => {
    const dailyLogs = new Map(state.logs.filter(l => l.habitId === habit.id).map(l => [l.date, l]));
    const cells = Array.from({length: days}, (_, index) => {
      const date = plus(from, index);
      if (!eligible(habit, date, state.pauses||[])) return '<span class="an-hab-dot is-off" aria-hidden="true"></span>';
      const log = dailyLogs.get(date);
      const cls = log?.status === 'COMPLETED' ? 'is-done' : log?.status === 'SKIPPED' ? (log.isFrozen ? 'is-frozen' : 'is-skipped') : log?.status === 'IN_PROGRESS' ? 'is-partial' : log?.status === 'FAILED' ? 'is-missed' : 'is-open';
      const label = log?.status === 'COMPLETED' ? 'Completed' : log?.status === 'SKIPPED' ? (log.isFrozen ? 'Frozen' : 'Skipped') : log?.status === 'IN_PROGRESS' ? 'Partial' : log?.status === 'FAILED' ? 'Missed' : 'Not completed';
      return `<span class="an-hab-dot ${cls}" aria-hidden="true">${log?.status === 'SKIPPED' ? (log.isFrozen ? 'F' : 'S') : ''}</span>`;
    }).join('');
    const week = rateFor({habits:[habit], logs:state.logs, pauses:state.pauses||[]}, plus(today,-6) > habit.startDate ? plus(today,-6) : habit.startDate, today);
    const weekPercent = percent(week);
    const counts = {completed:0, skipped:0, frozen:0, missed:0, open:0};
    for (let date = from; date <= today; date = plus(date, 1)) {
      if (!eligible(habit, date, state.pauses||[])) continue;
      const log = dailyLogs.get(date);
      if (log?.status === 'COMPLETED') counts.completed++;
      else if (log?.status === 'SKIPPED') log.isFrozen ? counts.frozen++ : counts.skipped++;
      else if (log?.status === 'FAILED') counts.missed++;
      else counts.open++;
    }
    const strip = `Last ${days} days for ${habit.name}: ${counts.completed} completed, ${counts.skipped} skipped, ${counts.frozen} frozen, ${counts.missed} missed, ${counts.open} not completed.`;
    const streak = streakValue(habit, state.logs, today, state.pauses||[]);
    return `<div class="an-hab-row"><span class="an-hab-icon" aria-hidden="true">${esc(habit.icon)}</span>
      <div class="an-hab-copy"><strong>${esc(habit.name)}</strong><div class="an-hab-strip" role="img" aria-label="${esc(strip)}">${cells}</div></div>
      <span class="an-hab-stat"><strong>${weekPercent === null ? '—' : weekPercent + '%'}</strong><small>7-day rate</small><strong>${streak}</strong><small>${streak === 1 ? 'day' : 'day'} streak</small></span></div>`;
  }).join('');
  return `<section class="an-panel"><div class="an-panel-heading"><div><span class="an-eyebrow">HABIT BY HABIT</span><h2>Habit consistency</h2></div><span class="an-badge">Last ${days} days</span></div>
  <p class="an-caption">Filled squares are completions; S marks skipped days and F frozen ones.</p>
  ${active.length ? rows : '<p class="an-caption">Add a habit to see its rhythm here.</p>'}</section>`;
}

export function periodTilesHTML(state, today) {
  const summary = periodSummary(state, today, 'fortnight');
  const tile1 = `<div class="an-tile"><span>Last 14 days</span><strong class="an-metric">${summary.percent === null ? '—' : summary.percent + '%'}</strong><small>${summary.rate.completed} of ${summary.rate.opportunities} due</small></div>`;
  const tile2 = summary.delta === null
    ? `<div class="an-tile"><span>Previous period</span><strong class="an-metric">—</strong><small>No earlier history</small></div>`
    : `<div class="an-tile"><span>Previous period</span><strong class="an-metric an-delta${summary.delta < 0 ? ' down' : ''}">${summary.delta > 0 ? '+' : ''}${summary.delta} pts</strong><small>${summary.previous.rate.completed} → ${summary.rate.completed} completions</small></div>`;
  return `<div class="an-tiles">${tile1}${tile2}</div>`;
}

export function trendHTML(habit, logs, today,pauses=[]) {
  const end = habit.archivedOn && habit.archivedOn < today ? habit.archivedOn : today;
  const earliest = plus(end,-89);
  const start = habit.startDate > earliest ? habit.startDate : earliest;
  if (start > end) return '<section class="an-panel"><h2>Completion trend</h2><p class="an-caption">Your timeline begins on your start date.</p></section>';
  const dates = Array.from({length:dayCount(start,end)+1}, (_,index) => plus(start,index));
  const values = dates.map(date => rateFor({habits:[habit],logs,pauses}, plus(date,-6) > habit.startDate ? plus(date,-6) : habit.startDate, date));
  const x = index => 24 + index / Math.max(1,dates.length - 1) * 272;
  const y = rate => 113 - (percent(rate) || 0) / 100 * 88;
  const points = values.map((rate,index) => `${x(index).toFixed(2)},${y(rate).toFixed(2)}`).join(' ');
  const latest = percent(values.at(-1));
  return `<section class="an-panel"><div class="an-panel-heading"><h2>Completion trend</h2><strong class="an-trend-value">${latest === null ? '—' : `${latest}%`}</strong></div><p class="an-caption">7-day rolling completion rate</p><svg class="an-trend" viewBox="0 0 320 145" role="img" aria-label="Seven day rolling rate, latest ${latest === null ? 'no unskipped opportunities' : latest+'%'}"><line x1="24" y1="25" x2="296" y2="25"/><line x1="24" y1="69" x2="296" y2="69"/><line x1="24" y1="113" x2="296" y2="113"/><text x="0" y="28">100</text><text x="7" y="72">50</text><text x="13" y="116">0</text><polygon class="an-trend-area" points="24,113 ${points} ${x(dates.length-1).toFixed(2)},113"/><polyline class="an-trend-line" points="${points}"/><circle cx="${x(dates.length-1)}" cy="${y(values.at(-1))}" r="4"/><text x="24" y="139">${format(start)}</text><text x="296" y="139" text-anchor="end">${format(end)}</text></svg></section>`;
}

const palette = ['#245f49','#5b7f95','#a26d8a','#a8823f','#77689c','#4d8a85'];

function donutHTML(state, today) {
  const from = plus(today, -27);
  const habits = new Map(state.habits.map(habit => [habit.id, habit]));
  const counts = new Map();
  for (const log of state.logs) {
    const habit = habits.get(log.habitId);
    if (log.status !== 'COMPLETED' || log.date < from || log.date > today || !habit || !eligible(habit,log.date,state.pauses||[])) continue;
    counts.set(habit.category || 'Personal', (counts.get(habit.category || 'Personal') || 0) + 1);
  }
  const groups = [...counts].sort((a,b) => b[1]-a[1]);
  const total = groups.reduce((sum, [,count]) => sum + count, 0);
  const circumference = 2 * Math.PI * 43;
  let accumulated = 0;
  const arcs = groups.map(([,count],index) => {
    const length = count / total * circumference;
    const html = `<circle cx="60" cy="60" r="43" fill="none" stroke="${palette[index % palette.length]}" stroke-width="15" stroke-dasharray="${Math.max(0,length-(groups.length > 1 ? 3 : 0)).toFixed(3)} ${circumference.toFixed(3)}" stroke-dashoffset="${(-accumulated).toFixed(3)}" transform="rotate(-90 60 60)"/>`;
    accumulated += length;
    return html;
  }).join('');
  return `<section class="an-panel"><span class="an-eyebrow">WHAT MATTERS TO YOU</span><h2>Where your energy goes</h2><p class="an-caption">Completions by category · last 28 days</p><div class="an-donut-layout"><div class="an-donut" role="img" aria-label="Category breakdown, ${total} completions"><svg viewBox="0 0 120 120" aria-hidden="true"><circle class="an-donut-track" cx="60" cy="60" r="43"/>${arcs}</svg><span><strong>${total}</strong><small>check-ins</small></span></div><div class="an-category-list">${groups.length ? groups.map(([name,count],index) => `<div><i style="background:${palette[index % palette.length]}"></i><span>${esc(name)}</span><strong>${count}</strong></div>`).join('') : '<p class="an-caption">Your first check-in starts the picture.</p>'}</div></div></section>`;
}

/** Activities summary beside habit consistency: per-activity totals over the last 28 days. */
export function activitiesPanelHTML(state, today, days = 28) {
  const from = plus(today, -(days - 1));
  const allActivities = state.activities || [];
  const allEntries = state.activityEntries || [];
  const rows = allActivities.slice().sort((a,b) => a.sortOrder - b.sortOrder).map(activity => {
    const inRange = allEntries.filter(e => e.activityId === activity.id && e.date >= from && e.date <= today);
    const total = inRange.reduce((sum, e) => sum + e.quantity, 0);
    const best = inRange.reduce((max, e) => Math.max(max, e.quantity), 0);
    const target = activity.target;
    const targetNote = target == null ? '' : ` · target ${fmtQ(target)}/day`;
    const aboveTarget = target != null && best > target ? ' · best above target ✓' : '';
    return `<div class="an-hab-row"><span class="an-hab-icon" aria-hidden="true">${esc(activity.icon)}</span>
      <div class="an-hab-copy"><strong>${esc(activity.name)}</strong><span class="an-hab-strip" role="img" aria-label="${esc(`${activity.name}: ${fmtQ(total)} ${activity.unit} across ${inRange.length} of the last ${days} days${targetNote}`)}">${inRange.length ? inRange.slice().sort((a,b) => a.date.localeCompare(b.date)).map(e => `<span class="an-hab-dot ${target != null && e.quantity >= target ? 'is-done' : 'is-partial'}" aria-hidden="true"></span>`).join('') : '<span class="an-caption">No entries in this window.</span>'}</span><small class="an-caption">${esc(`${fmtQ(total)} ${activity.unit} · ${inRange.length} day${inRange.length === 1 ? '' : 's'} logged${targetNote}${aboveTarget}`)}</small></div>
      <span class="an-hab-stat"><strong>${fmtQ(total)}</strong><small>${esc(activity.unit)}</small></span></div>`;
  }).join('');
  return `<section class="an-panel"><div class="an-panel-heading"><div><span class="an-eyebrow">ACTIVITIES & WORKOUTS</span><h2>Activity log</h2></div><span class="an-badge">Last ${days} days</span></div>
  <p class="an-caption">Actual quantities, recorded as performed — dots mark days at or above the target when one is set.</p>
  ${allActivities.length ? rows : '<p class="an-caption">No activities yet — add one from Today to track workouts, runs and repetitions.</p>'}</section>`;
}

export function renderAnalytics(state, today, period = 'week') {
  const start = state.habits.reduce((first,habit) => habit.startDate < first ? habit.startDate : first,today);
  const allTime = rateFor(state,start,today);
  const allTimePercent = percent(allTime);
  const leaders = state.habits.filter(habit => !habit.isArchived).map(habit => ({habit,streak:currentStreak(habit,state.logs,today,state.pauses||[])})).filter(item => item.streak > 0).sort((a,b) => b.streak-a.streak).slice(0,5);
  return `<div class="an-page"><div class="an-page-intro"><span class="an-eyebrow">YOUR PROGRESS, IN PERSPECTIVE</span><h1>Analytics<span class="an-title-dot">.</span></h1><p>Only you set the pace.</p></div>
  ${periodSummaryHTML(state,today,period)}
  ${dailyCheckinsHTML(state,today)}
  ${habitConsistencyHTML(state,today)}
  ${activitiesPanelHTML(state,today)}
  ${donutHTML(state,today)}
  <section class="an-panel"><div class="an-panel-heading"><div><span class="an-eyebrow">KEEP YOUR MOMENTUM</span><h2>Current streaks</h2></div><span class="an-streak-icon" aria-hidden="true">♧</span></div><div class="an-streak-list">${leaders.length ? leaders.map(({habit,streak},index) => `<div class="an-leader"><span class="an-rank">${String(index+1).padStart(2,'0')}</span><span class="an-habit-icon">${esc(habit.icon)}</span><strong>${esc(habit.name)}</strong><span class="an-streak-count">${streak}<small>${streak === 1 ? 'day' : 'days'}</small></span></div>`).join('') : '<p class="an-caption">Your next check-in can start a new streak.</p>'}</div><p class="an-footnote">Completions add days. Skips and freezes protect the chain without counting as completions.</p></section>
  <section class="an-secondary"><span class="an-eyebrow">LONGER HISTORY</span>${heatmapHTML(state,today)}</section>
  <p class="an-end-note">All-time rate ${allTimePercent === null ? '—' : allTimePercent + '%'} of unskipped days since ${format(start,{month:'short',day:'numeric',year:'numeric'})}. Progress has a rhythm, not a finish line.</p></div>`;
}
