import test from 'node:test';
import assert from 'node:assert/strict';
import {
  validateBackup, parseBackup, encodeBackup, makeBackup, restoreStoredState,
  saveActivity, deleteActivity, setActivityEntry, clearActivityEntry,
  activityEntriesFor, activityTotals, formatQuantity
} from '../dist/domain.js';

const today = '2026-10-31';
function baseBackup() {
  const habit = {id: 1, name: 'Read', description: null, icon: '📖', colorHex: '#43866B', startDate: '2026-10-01',
    isArchived: false, sortOrder: 0, category: 'Mind', afterHabitId: null, archivedOn: null,
    createdAt: 100, updatedAt: 100, scheduledDays: [1,2,3,4,5,6,7], targetCount: 1, targetUnit: 'times',
    reminderMinutes: null, scheduleHistory: []};
  const log = {id: 1, habitId: 1, date: '2026-10-30', status: 'COMPLETED', note: null, isFrozen: false,
    createdAt: 100, updatedAt: 100, progress: 1};
  const settings = {id: 1, theme: 'LIGHT', dynamicColor: false, backupReminder: false, lastExportAt: null,
    hapticsEnabled: true, animationsEnabled: true};
  return {formatVersion: 3, exportedAt: 100, habits: [habit], logs: [log], settings, freezes: [], pauses: [],
    activities: [], activityEntries: []};
}
const stateOf = b => validateBackup(b, today);
function withActivities(entries = [], activities) {
  const b = baseBackup();
  b.activities = activities ?? [
    {id: 1, name: 'Push-ups', icon: '💪', unit: 'pushups', target: 20, sortOrder: 0, createdAt: 100, updatedAt: 100},
    {id: 2, name: 'Running', icon: '🏃', unit: 'miles', target: 1, sortOrder: 1, createdAt: 100, updatedAt: 100},
    {id: 3, name: 'Crunches', icon: '🔥', unit: 'crunches', target: null, sortOrder: 2, createdAt: 100, updatedAt: 100}
  ];
  b.activityEntries = entries;
  return b;
}

test('version 3 roundtrip preserves activities, entries and quantities', () => {
  const b = withActivities([
    {id: 1, activityId: 1, date: '2026-10-30', quantity: 20, note: null, createdAt: 100, updatedAt: 100},
    {id: 2, activityId: 2, date: '2026-10-30', quantity: 1, note: null, createdAt: 100, updatedAt: 100}
  ]);
  const state = stateOf(b);
  assert.equal(state.activities.length, 3);
  assert.equal(state.activityEntries.length, 2);
  assert.deepEqual(parseBackup(encodeBackup(state, today, 100), today), state);
});

test('an actual quantity may exceed the target (32 of a 20 target is valid)', () => {
  const b = withActivities([
    {id: 1, activityId: 1, date: '2026-10-31', quantity: 32, note: null, createdAt: 100, updatedAt: 100}
  ]);
  const state = stateOf(b);
  assert.equal(state.activityEntries[0].quantity, 32);
});

test('version 2 backups import with empty activities and re-export as version 3', () => {
  const b = baseBackup();
  b.formatVersion = 2;
  delete b.activities;
  delete b.activityEntries;
  const state = stateOf(b);
  assert.deepEqual(state.activities, []);
  assert.deepEqual(state.activityEntries, []);
  assert.equal(makeBackup(state).formatVersion, 3);
});

test('duplicate activity-and-date entries and missing activity references are rejected', () => {
  const bad = change => assert.throws(() => stateOf(change));
  const entries = [
    {id: 1, activityId: 1, date: '2026-10-31', quantity: 10, note: null, createdAt: 100, updatedAt: 100}
  ];
  bad(() => withActivities([...entries, {...entries[0], id: 2}]));
  bad(() => withActivities([{...entries[0], activityId: 99}]));
  bad(() => withActivities([{...entries[0], id: 0}]));
});

test('entry dates cannot be in the future and quantities must be positive', () => {
  const bad = change => assert.throws(() => stateOf(change));
  bad(() => withActivities([{id: 1, activityId: 1, date: '2026-11-01', quantity: 10, note: null, createdAt: 100, updatedAt: 100}]));
  bad(() => withActivities([{id: 1, activityId: 1, date: '2026-10-31', quantity: 0, note: null, createdAt: 100, updatedAt: 100}]));
  bad(() => withActivities([{id: 1, activityId: 1, date: '2026-10-31', quantity: -5, note: null, createdAt: 100, updatedAt: 100}]));
  bad(() => withActivities([{id: 1, activityId: 1, date: '2026-10-31', quantity: 'lots', note: null, createdAt: 100, updatedAt: 100}]));
});

test('activity records validate names, units, targets and timestamps', () => {
  const bad = activities => assert.throws(() => stateOf(withActivities([], activities)));
  bad([{...withActivities().activities[0] ?? {}, id: 1, name: '   ', icon: '💪', unit: 'pushups', target: null, sortOrder: 0, createdAt: 100, updatedAt: 100}]);
  bad([{id: 1, name: 'Push-ups', icon: '', unit: 'pushups', target: null, sortOrder: 0, createdAt: 100, updatedAt: 100}]);
  bad([{id: 1, name: 'Push-ups', icon: '💪', unit: '', target: null, sortOrder: 0, createdAt: 100, updatedAt: 100}]);
  bad([{id: 1, name: 'Push-ups', icon: '💪', unit: 'pushups', target: 0, sortOrder: 0, createdAt: 100, updatedAt: 100}]);
  bad([{id: 1, name: 'Push-ups', icon: '💪', unit: 'pushups', target: 2000000, sortOrder: 0, createdAt: 100, updatedAt: 100}]);
  bad([{id: 1, name: 'Push-ups', icon: '💪', unit: 'pushups', target: null, sortOrder: 0, createdAt: 200, updatedAt: 100}]);
});

test('saveActivity creates, edits and validates through the same path', () => {
  let state = {...stateOf(withActivities()), activities: [], activityEntries: []};
  state = saveActivity(state, {name: '  Push-ups  ', icon: '💪', unit: 'pushups', target: 20});
  assert.equal(state.activities[0].name, 'Push-ups');
  assert.equal(state.activities[0].target, 20);
  const id = state.activities[0].id;
  state = saveActivity(state, {id, name: 'Push-ups', icon: '💪', unit: 'pushups', target: null});
  assert.equal(state.activities[0].target, null);
  assert.throws(() => saveActivity(state, {id, name: '', icon: '💪', unit: 'pushups'}), /1–80/);
  assert.throws(() => saveActivity(state, {name: 'X', icon: '💪', unit: 'pushups', target: -1}), /0 and 1,000,000/);
});

test('setActivityEntry upserts, trims notes, clears on empty and refuses future dates', () => {
  let state = stateOf(withActivities());
  state = setActivityEntry(state, 1, '2026-10-31', '32', '  felt strong  ');
  assert.equal(state.activityEntries[0].quantity, 32);
  assert.equal(state.activityEntries[0].note, 'felt strong');
  state = setActivityEntry(state, 1, '2026-10-31', '35', null);
  assert.equal(state.activityEntries.length, 1);
  assert.equal(state.activityEntries[0].quantity, 35);
  assert.throws(() => setActivityEntry(state, 1, '2026-11-05', 10, null, today), /future/);
  assert.throws(() => setActivityEntry(state, 1, '2026-10-31', 0), /0 and 1,000,000/);
  state = setActivityEntry(state, 1, '2026-10-31', '');
  assert.deepEqual(state.activityEntries, []);
  state = setActivityEntry(state, 2, today, 1.5, null);
  assert.equal(state.activityEntries[0].quantity, 1.5);
});

test('deleteActivity removes the activity and all of its entries', () => {
  let state = stateOf(withActivities([
    {id: 1, activityId: 1, date: '2026-10-31', quantity: 20, note: null, createdAt: 100, updatedAt: 100},
    {id: 2, activityId: 2, date: '2026-10-31', quantity: 1, note: null, createdAt: 100, updatedAt: 100}
  ]));
  state = deleteActivity(state, 1);
  assert.equal(state.activities.length, 2);
  assert.equal(state.activityEntries.length, 1);
  assert.equal(state.activityEntries[0].activityId, 2);
});

test('history sorting, totals and quantity formatting behave', () => {
  let state = stateOf(withActivities([
    {id: 1, activityId: 1, date: '2026-10-29', quantity: 15, note: null, createdAt: 100, updatedAt: 100},
    {id: 2, activityId: 1, date: '2026-10-31', quantity: 32, note: null, createdAt: 100, updatedAt: 100},
    {id: 3, activityId: 1, date: '2026-10-30', quantity: 20, note: null, createdAt: 100, updatedAt: 100}
  ]));
  const history = activityEntriesFor(state, 1);
  assert.deepEqual(history.map(e => e.date), ['2026-10-31', '2026-10-30', '2026-10-29']);
  const totals = activityTotals(state, 1, '2026-10-25', today);
  assert.equal(totals.total, 67);
  assert.equal(totals.days, 3);
  assert.equal(totals.best, 32);
  assert.equal(formatQuantity(32), '32');
  assert.equal(formatQuantity(1.5), '1.5');
  assert.equal(formatQuantity(2.25), '2.25');
});

test('restoreStoredState accepts v3 saved preview data with activities', () => {
  const state = stateOf(withActivities([
    {id: 1, activityId: 1, date: '2026-10-31', quantity: 32, note: null, createdAt: 100, updatedAt: 100}
  ]));
  const restored = restoreStoredState(JSON.stringify(state), today);
  assert.equal(restored.activityEntries[0].quantity, 32);
});
