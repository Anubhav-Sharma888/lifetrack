import test from 'node:test';
import assert from 'node:assert/strict';
import { heatmapHTML, trendHTML, renderAnalytics, periodSummary, dailyCompletions, consistencyHTML, habitConsistencyHTML, periodTilesHTML, unmarkedOn } from '../dist/analytics.js';

const today = '2026-10-28'; // Wednesday
const habit = (id, scheduledDays = [1, 2, 3, 4, 5, 6, 7]) => ({
  id, name: `Habit ${id}`, description: null, icon: '🌱', colorHex: '#245f49',
  startDate: '2026-10-01', isArchived: false, sortOrder: id - 1, category: 'Mind',
  afterHabitId: null, archivedOn: null, createdAt: 100, updatedAt: 100,
  scheduledDays: [...scheduledDays], targetCount: 1, targetUnit: 'times',
  reminderMinutes: null, scheduleHistory: []
});
const log = (habitId, date, status, isFrozen = false) => ({ id: habitId * 100 + Number(date.slice(8)), habitId, date, status, note: null, isFrozen, createdAt: 100, updatedAt: 100, progress: status === 'COMPLETED' ? 1 : 0 });
const settings = { id: 1, theme: 'LIGHT', dynamicColor: false, backupReminder: false, lastExportAt: null, hapticsEnabled: true, animationsEnabled: true };

const state = {
  habits: [habit(1), habit(2, [1, 2, 3, 4, 5])],
  logs: [log(1, '2026-10-27', 'COMPLETED'), log(2, '2026-10-27', 'SKIPPED'), log(2, '2026-10-20', 'SKIPPED', true)],
  freezes: [{month: '2026-10', usedAt: 100}], pauses: [], settings
};

test('app-wide heatmap keeps ratio descriptions and counts only past cells', () => {
  const html = heatmapHTML(state, today);
  assert.match(html, /1 of 2 habits completed/);
  assert.doesNotMatch(html, /data-habit=/);
  assert.equal(html.match(/data-action="inspect-day"/g).length, 367, '52 full weeks plus Mon-Wed of the current week');
  assert.equal(html.match(/an-cell-future/g).length, 4, 'Thu-Sun of the current week are future spans');
});

test('single-habit heatmap filters cells, names the habit and marks rest days', () => {
  const html = heatmapHTML(state, today, true, state.habits[1]);
  assert.match(html, /data-habit="2"/);
  assert.doesNotMatch(html, /of 2 habits completed/);
  assert.match(html, /Habit 2 · Every small square is a step forward/);
  assert.match(html, /an-cell-off/, 'weekend cells for a weekday habit are rest spans');
  assert.doesNotMatch(html, /data-action="inspect-day" data-date="2026-10-24"/, 'rest days are not interactive');
  assert.match(html, /an-level-2 an-cell-skip" data-action="inspect-day" data-date="2026-10-27"/, 'skipped day renders at level 2 with an S marker');
  assert.doesNotMatch(html, /data-habit="1"/, 'other habits never leak into the grid');
});

test('single-habit heatmap shows completions and habit-specific footer count', () => {
  const html = heatmapHTML(state, today, true, state.habits[0]);
  assert.match(html, /an-level-4" data-action="inspect-day" data-date="2026-10-27"/);
  assert.match(html, /<strong>1<\/strong> check-ins/);
  const all = heatmapHTML(state, today);
  assert.match(all, /<strong>1<\/strong> check-ins/, 'skipped check-ins do not inflate completion counts');
});

test('heatmap marks skipped and frozen days with letters, not color alone', () => {
  const html = heatmapHTML(state, today);
  assert.match(html, /an-cell-skip[^>]*data-date="2026-10-27"[^>]*>S<\/button>/);
  assert.match(html, /an-cell-frozen[^>]*data-date="2026-10-20"[^>]*>F<\/button>/);
  assert.match(html, /S marks a skipped day and F a frozen day/);
});

test('period summary computes rate, denominators and a fair elapsed-aligned comparison', () => {
  const week = periodSummary(state, today, 'week');
  assert.equal(week.label, 'This week');
  assert.equal(week.from, '2026-10-26');
  assert.equal(week.rate.opportunities, 5, '3 days × habit 1 + Mon-Wed × habit 2, minus the skipped Tuesday');
  assert.equal(week.rate.completed, 1);
  assert.equal(week.rate.skipped, 1);
  assert.equal(week.percent, 20);
  assert.equal(week.previous.rate.opportunities, 5, 'last Mon-Wed: 3 + 2, the frozen Tuesday stays out');
  assert.equal(week.previous.percent, 0);
  assert.equal(week.delta, 20);

  const fortnight = periodSummary(state, today, 'fortnight');
  assert.equal(fortnight.from, '2026-10-15');
  assert.equal(fortnight.rate.opportunities, 22, '14 all-days + 10 weekday days, minus two skipped Tuesdays');
  assert.equal(fortnight.rate.skipped, 2);
  assert.equal(fortnight.percent, 4);
  assert.equal(fortnight.previous.rate.opportunities, 24);
  assert.equal(fortnight.delta, 4);
});

test('period summary suppresses the comparison when history is empty', () => {
  const empty = {habits: [{...habit(9, [1]), startDate: '2026-10-28', name: 'Habit 9'}], logs: [], freezes: [], pauses: [], settings};
  const summary = periodSummary(empty, today, 'week');
  assert.equal(summary.rate.opportunities, 0, 'a Monday-only habit has no due days in this window');
  assert.equal(summary.percent, null);
  assert.equal(summary.delta, null);
});

test('daily completions count only eligible scheduled days', () => {
  const series = dailyCompletions(state, '2026-10-26', '2026-10-28');
  assert.deepEqual(series, [{date: '2026-10-26', count: 0}, {date: '2026-10-27', count: 1}, {date: '2026-10-28', count: 0}]);
});

test('unmarkedOn lists due habits without any record, skipping archived and future ones', () => {
  assert.deepEqual(unmarkedOn(state, '2026-10-26').map(h => h.id), [1, 2], 'Monday: both habits due, neither has a record');
  assert.deepEqual(unmarkedOn(state, '2026-10-27').map(h => h.id), [], 'completed and skipped days count as marked');
  const wider = {...state, habits: [...state.habits, {...habit(3), isArchived: true, archivedOn: '2026-10-20'}, {...habit(4), startDate: '2026-10-29'}]};
  assert.deepEqual(unmarkedOn(wider, '2026-10-26').map(h => h.id), [1, 2], 'archived and not-yet-started habits are excluded');
});

test('consistency grid marks skipped and frozen days with letters and a legend', () => {
  const html = consistencyHTML(state, today);
  assert.match(html, /data-date="2026-10-27"[^>]*>S<\/button>/);
  assert.match(html, /data-date="2026-10-20"[^>]*>F<\/button>/);
  assert.match(html, /S marks a skipped day, F a frozen one/);
  assert.equal(html.match(/an-cons-col"/g).length, 12, 'twelve week columns');
  assert.match(html, /data-action="inspect-day"/);
});

test('habit consistency rows describe the 14-day strip accessibly', () => {
  const html = habitConsistencyHTML(state, today);
  assert.match(html, /Last 14 days for Habit 1: 1 completed, 0 skipped, 0 frozen, 0 missed/);
  assert.match(html, /an-hab-dot is-done/);
  assert.match(html, /an-hab-dot is-frozen[^>]*>F</);
  assert.match(html, /7-day rate/);
  assert.match(html, /day streak/);
});

test('period tiles surface the fortnight rate and previous-period delta', () => {
  const html = periodTilesHTML(state, today);
  assert.match(html, /Last 14 days/);
  assert.match(html, /4%/);
  assert.match(html, /\+4 pts/);
  assert.match(html, /0 → 1 completions/);
});

test('trend and redesigned analytics render summary-first content', () => {
  assert.match(trendHTML(state.habits[0], state.logs, today), /<polyline/);
  const page = renderAnalytics(state, today);
  assert.match(page, /Analytics/);
  assert.match(page, /WHERE YOU STAND/);
  assert.match(page, /Daily check-ins/);
  assert.match(page, /Habit consistency/);
  assert.match(page, /Current streaks/, 'competitive leaderboard wording is replaced');
  assert.doesNotMatch(page, /leaderboard|You are your only competition/);
  assert.match(page, /LONGER HISTORY/);
  assert.match(page, /period-select/);
});

test('desktop consistency grid has one keyboard entry point and an accessible date alternative', () => {
  const html = consistencyHTML(state, today);
  assert.equal((html.match(/tabindex="0"/g) || []).length, 1);
  assert.match(html, new RegExp(`data-date="${today}"[^>]*tabindex="0"`));
  assert.match(html, /<label[^>]*>Inspect a day<input type="date"[^>]*data-heatmap-date/);
});
