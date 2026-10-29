import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {changeLog} from '../dist/domain.js';

// Execute the actual event listener and write/undo helpers with only the DOM
// surface replaced. This catches regressions in action wiring, not just rules.
const source = readFileSync(new URL('../dist/app.js', import.meta.url), 'utf8');
function section(start, end) {
  const from = source.indexOf(start), through = source.indexOf(end, from);
  assert.ok(from >= 0 && through > from, `Missing app section: ${start}`);
  return source.slice(from, through);
}
const clickCode = section("document.addEventListener('click'", "document.addEventListener('submit'");
const commitCode = section('function commit(', 'function navigate(');
const setLogCode = section('function setLog(', 'function celebrate(');
const act = new Function('initial', 'changeLog', `
  let state = structuredClone(initial), revision = 0, lastUndo = null;
  const today = '2026-10-26', selectedDate = today, held = false;
  let listener;
  const document = {addEventListener(type, callback) { listener = callback; }};
  const closeModal = () => {}, save = () => {}, render = () => {}, toast = () => {};
  const logFor = (id, date = selectedDate) => state.logs.find(log => log.habitId === id && log.date === date);
  ${commitCode}
  ${setLogCode}
  ${clickCode}
  const click = action => listener({target: {closest: () => ({disabled: false, dataset: {action, id: '1'}})}});
  click('row-skip');
  const skipped = structuredClone(state);
  click('undo');
  return {skipped, undone: state};
`);

for (const status of ['IN_PROGRESS', 'FAILED']) {
  test(`quick skip preserves a ${status} note and undo restores the original entry`, () => {
    const initial = {
      habits: [{id: 1, isArchived: false, startDate: '2026-10-01', targetCount: 8, scheduledDays: [0,1,2,3,4,5,6], scheduleHistory: []}],
      logs: [{id: 1, habitId: 1, date: '2026-10-26', status, note: 'Keep my note', progress: status === 'IN_PROGRESS' ? 3 : 0, isFrozen: false, createdAt: 100, updatedAt: 100}],
      pauses: [], freezes: []
    };
    const {skipped, undone} = act(initial, changeLog);
    assert.equal(skipped.logs[0].status, 'SKIPPED');
    assert.equal(skipped.logs[0].progress, 0);
    assert.equal(skipped.logs[0].note, 'Keep my note');
    assert.deepEqual(undone, initial);
  });
}
