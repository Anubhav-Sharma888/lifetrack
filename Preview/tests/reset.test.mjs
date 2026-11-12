import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateBackup, restoreStoredState} from '../dist/domain.js';

// Reset LifeTrack guards, executing the real reset sections of app.js against a
// recording localStorage stub: the erased-state payload, the stale-tab write
// guard on all three writers, the submit path and the export-first return.

const source = readFileSync(new URL('../dist/app.js', import.meta.url), 'utf8');
function section(start, end) {
  const from = source.indexOf(start), through = source.indexOf(end, from);
  assert.ok(from >= 0 && through > from, `Missing app section: ${start}`);
  return source.slice(from, through);
}

const TODAY = '2026-10-26';
const STORE = 'lifetrack.browser-preview.v1';
const DRAFT_STORE = STORE + '.drafts';
const PREFS_STORE = STORE + '.prefs';
const RESET_STORE = STORE + '.reset';

function recordingStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  const log = [];
  return {
    log,
    getItem: key => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { log.push(['set', key, String(value)]); map.set(key, String(value)); },
    removeItem: key => { log.push(['remove', key]); map.delete(key); },
    clear: () => { log.push(['clear']); map.clear(); },
    get length() { return map.size; },
    key: index => [...map.keys()][index] ?? null
  };
}

const makeEmptyState = new Function(`${section('function emptyState(', 'function clearVolatileState(')}\nreturn emptyState();`);
const oldHabit = {id: 1, name: 'Old habit', description: null, icon: '📖', colorHex: '#43866B', startDate: '2026-10-01',
  isArchived: false, sortOrder: 0, category: 'Mind', afterHabitId: null, archivedOn: null,
  createdAt: 100, updatedAt: 100, scheduledDays: [1,2,3,4,5,6,7], targetCount: 1, targetUnit: 'times',
  reminderMinutes: null, scheduleHistory: []};
const oldStatePayload = () => JSON.stringify({formatVersion: 3, exportedAt: 100, habits: [oldHabit], logs: [],
  settings: {id: 1, theme: 'LIGHT', dynamicColor: false, backupReminder: false, lastExportAt: null, hapticsEnabled: true, animationsEnabled: true},
  freezes: [], pauses: [], activities: [], activityEntries: []});
const DEFAULT_SETTINGS = {id: 1, theme: 'LIGHT', dynamicColor: false, backupReminder: false, lastExportAt: null, hapticsEnabled: true, animationsEnabled: true};

// Evaluates the real guard, adopt, performReset and submit sections of app.js
// with only localStorage, the domain parser and the UI surface replaced.
function buildHarness({storage, state, values = {}, lastUndo = null, pendingBackup = null, context = null} = {}) {
  const submitButton = {disabled: false, isConnected: true};
  const errorBox = {textContent: ''};
  const eraseButton = {disabled: true};
  const form = {
    id: 'reset-form', dataset: {}, __values: values, isConnected: true,
    attrs: new Map(),
    setAttribute(name, value) { this.attrs.set(name, value); },
    removeAttribute(name) { this.attrs.delete(name); },
    hasAttribute(name) { return this.attrs.has(name); },
    querySelector: selector => (selector === '[type="submit"]' ? submitButton : null),
  };
  const env = new Function('localStorage', 'restoreStoredState', 'today', 'form', 'deps', `
    const {state: seedState, lastUndo: seedLastUndo, pendingBackup: seedPendingBackup, context: seedContext, selects} = deps;
    let state = seedState, revision = 0, lastUndo = seedLastUndo, pendingBackup = seedPendingBackup, pendingEncryptedText = null, pendingFilename = '';
    let drafts = {}, prefs = {reminderMinutes: null, lastNudgeDate: null}, recoveryText = null, storageWarning = '';
    let filters = {query: '', category: '', status: 'all'}, filterPanelOpen = false, searchOpen = false;
    let nudgeDismissedDate = null, focusUnmarkedDate = null, selectedDate = today, dialogContext = seedContext, dialogOpener = null;
    const STORE = 'lifetrack.browser-preview.v1', DRAFT_STORE = STORE + '.drafts', PREFS_STORE = STORE + '.prefs';
    const listeners = {}, openDialogs = [], calls = {closeModal: [], navigate: [], toasts: []};
    const document = {addEventListener(type, callback) { listeners[type] = callback; }};
    let modal = {open: false, querySelector: () => null, close() { modal.open = false; }};
    const app = {querySelector: () => null};
    const requestAnimationFrame = callback => {};
    const closeModal = saved => { calls.closeModal.push(saved); modal.open = false; };
    const navigate = (tab, id = null) => { calls.navigate.push([tab, id]); };
    const toast = text => { calls.toasts.push(text); };
    const render = () => {};
    const heading = title => '<h2>' + title + '</h2>';
    const formValues = target => target.__values;
    const showModal = (html, context = null) => { modal.open = true; openDialogs.push({html, context}); };
    const $ = selector => selects[selector] ?? null;
    ${section('const RESET_STORE=', 'function savePrefs()')}
    ${section('function savePrefs(', 'const app=')}
    ${section('function save() {', 'function toast(')}
    ${section('function captureDraft(', 'function clearDraft(')}
    ${section('function emptyState(', 'function confirmImport(')}
    ${section("document.addEventListener('submit'", "document.addEventListener('input'")}
    return {listeners, calls, openDialogs,
      get state() { return state; }, get revision() { return revision; }, get lastUndo() { return lastUndo; },
      get pendingBackup() { return pendingBackup; }, get drafts() { return drafts; }, get prefs() { return prefs; },
      get resetToken() { return resetToken; }, get recoveryText() { return recoveryText; },
      get staleAfterReset() { return staleAfterReset; },
      setModal(next) { modal = next; },
      performReset: () => performReset(),
      save: () => save(),
      savePrefs: () => savePrefs(),
      captureDraft: () => captureDraft(),
      submitReset: async () => listeners.submit({target: form, preventDefault() {}})};
  `);
  return Object.assign(env(storage, restoreStoredState, TODAY, form,
    {state, lastUndo, pendingBackup, context, selects: {'#form-error': errorBox, '#reset-erase': eraseButton}}),
    {form, submitButton, errorBox, eraseButton});
}

test('the erased state passes validateBackup and round-trips with default settings', () => {
  const empty = makeEmptyState();
  const normalized = validateBackup(empty, TODAY);
  assert.deepEqual(normalized.habits, []);
  assert.deepEqual(normalized.logs, []);
  assert.deepEqual(normalized.freezes, []);
  assert.deepEqual(normalized.pauses, []);
  assert.deepEqual(normalized.activities, []);
  assert.deepEqual(normalized.activityEntries, []);
  assert.deepEqual(normalized.settings, DEFAULT_SETTINGS);
  const restored = restoreStoredState(JSON.stringify(empty), TODAY);
  assert.deepEqual(restored, {habits: [], logs: [], freezes: [], pauses: [], activities: [], activityEntries: [], settings: DEFAULT_SETTINGS});
});

for (const attempt of ['reset', 'RESET ', 'RESETT', '']) {
  test(`confirmation ${JSON.stringify(attempt)} is rejected without any storage write`, async () => {
    const storage = recordingStorage({[STORE]: oldStatePayload(), [DRAFT_STORE]: '{}', [PREFS_STORE]: '{}'});
    const seed = restoreStoredState(oldStatePayload(), TODAY);
    const h = buildHarness({storage, state: seed, values: {confirmation: attempt}, lastUndo: {state: {}, revision: 1}});
    await h.submitReset();
    assert.deepEqual(storage.log, []);
    assert.equal(h.state, seed);
    assert.notEqual(h.lastUndo, null);
    assert.equal(h.errorBox.textContent, 'Type RESET to confirm.');
    assert.deepEqual(h.calls.closeModal, []);
    assert.deepEqual(h.calls.navigate, []);
    assert.deepEqual(h.calls.toasts, []);
    assert.equal(h.submitButton.disabled, false);
  });
}

test('exact RESET writes the token first, then the erased state, then removes drafts and prefs', async () => {
  const storage = recordingStorage({
    [STORE]: oldStatePayload(),
    [DRAFT_STORE]: JSON.stringify({'habit:new': {name: 'Half typed'}}),
    [PREFS_STORE]: JSON.stringify({reminderMinutes: 480, lastNudgeDate: '2026-10-25'})
  });
  const h = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY),
    values: {confirmation: 'RESET'}, lastUndo: {state: {}, revision: 7}, pendingBackup: {habits: [oldHabit]}});
  await h.submitReset();
  const ops = storage.log.map(([kind, key]) => [kind, key]);
  assert.deepEqual(ops, [['set', RESET_STORE], ['set', STORE], ['remove', DRAFT_STORE], ['remove', PREFS_STORE]]);
  const token = storage.getItem(RESET_STORE);
  assert.match(token, /^r-\d+-[0-9a-z]+$/);
  assert.equal(h.resetToken, token);
  const stored = JSON.parse(storage.getItem(STORE));
  assert.equal(stored.formatVersion, 3);
  assert.deepEqual(stored.habits, []);
  assert.deepEqual(stored.settings, DEFAULT_SETTINGS);
  assert.deepEqual(h.state.habits, []);
  assert.deepEqual(h.state.settings, DEFAULT_SETTINGS);
  assert.equal(h.lastUndo, null);
  assert.equal(h.pendingBackup, null);
  assert.deepEqual(h.drafts, {});
  assert.deepEqual(h.prefs, {reminderMinutes: null, lastNudgeDate: null});
  assert.deepEqual(h.calls.closeModal, [true]);
  assert.deepEqual(h.calls.navigate, [['today', null]]);
  assert.equal(h.calls.toasts.at(-1), 'LifeTrack was reset.');
  assert.equal(h.errorBox.textContent, '');
  assert.equal(h.submitButton.disabled, false);
});

for (const writer of ['save', 'savePrefs', 'captureDraft']) {
  test(`a stale tab adopts the erased state on ${writer}() and writes nothing`, () => {
    const storage = recordingStorage({[STORE]: oldStatePayload(), [DRAFT_STORE]: '{}', [PREFS_STORE]: '{}'});
    const h = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY)});
    assert.equal(h.staleAfterReset(), false);
    const token = 'r-1700000000000-ab42cd';
    storage.setItem(RESET_STORE, token); // another tab erased LifeTrack
    storage.setItem(STORE, JSON.stringify(makeEmptyState()));
    const staleModal = {open: true, querySelector: () => ({dataset: {draftKey: 'habit:new'}, __values: {name: 'x'}}), close() { this.open = false; }};
    if (writer === 'captureDraft') h.setModal(staleModal);
    const before = storage.log.length, revision = h.revision;
    assert.equal(h[writer](), false);
    // Adoption may only clear the stored drafts; it must never set records.
    const addedOps = storage.log.slice(before).map(([kind, key]) => [kind, key]);
    assert.deepEqual(addedOps.filter(([kind]) => kind === 'set'), [], `${writer} must not write after a remote reset`);
    assert.deepEqual(addedOps, [['remove', DRAFT_STORE]], 'adoption clears stored drafts only');
    assert.equal(h.resetToken, token);
    assert.equal(h.staleAfterReset(), false);
    assert.deepEqual(h.state.habits, []);
    assert.deepEqual(h.prefs, {reminderMinutes: null, lastNudgeDate: null});
    if (writer === 'captureDraft') { assert.deepEqual(h.drafts, {}); assert.equal(staleModal.open, false, 'an open dialog is closed when a remote reset is adopted'); }
    assert.equal(h.revision, revision + 1);
    assert.deepEqual(h.calls.toasts, ['LifeTrack was reset in another tab.']);
  });
}

test('after adopting, the tab is current and may persist the erased state again', () => {
  const storage = recordingStorage({[STORE]: oldStatePayload()});
  const h = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY)});
  const token = 'r-1700000000001-ab42cd';
  storage.setItem(RESET_STORE, token);
  storage.setItem(STORE, JSON.stringify(makeEmptyState()));
  assert.equal(h.save(), false);
  const before = storage.log.length;
  assert.notEqual(h.save(), false);
  const write = storage.log.at(-1);
  assert.deepEqual([write[0], write[1]], ['set', STORE]);
  assert.equal(storage.log.length, before + 1);
  assert.deepEqual(JSON.parse(write[2]).habits, []);
});

test('a reset re-entered while one is already in flight is a no-op', () => {
  const storage = recordingStorage({[STORE]: oldStatePayload()});
  const h = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY)});
  const rawSetItem = storage.setItem;
  let reentryResult, reentered = false;
  storage.setItem = function (key, value) {
    if (key === RESET_STORE && !reentered) { reentered = true; reentryResult = h.performReset(); }
    return rawSetItem.call(storage, key, value);
  };
  assert.equal(h.performReset(), true);
  assert.equal(reentryResult, false);
  assert.equal(storage.log.filter(([kind, key]) => kind === 'set' && key === RESET_STORE).length, 1);
  assert.equal(storage.log.filter(([kind, key]) => kind === 'set' && key === STORE).length, 1);
});

test('a failing erased-state write keeps the generation marker and deletes nothing', () => {
  const payload = oldStatePayload();
  const storage = recordingStorage({[STORE]: payload, [RESET_STORE]: 'r-1-prev'});
  const h = buildHarness({storage, state: restoreStoredState(payload, TODAY)});
  const rawSetItem = storage.setItem;
  storage.setItem = function (key, value) {
    if (key === STORE) throw new Error('QuotaExceededError');
    return rawSetItem.call(storage, key, value);
  };
  assert.throws(() => h.performReset(), /Nothing was deleted/);
  // The marker was replaced, never removed: no tab may see the world as pre-reset after a failed erase.
  assert.match(storage.getItem(RESET_STORE), /^r-/);
  assert.equal(h.resetToken, 'r-1-prev');
  assert.equal(storage.getItem(STORE), payload);
  // The stale resetting tab adopts the surviving records instead of overwriting them.
  assert.equal(h.save(), false);
  assert.equal(h.state.habits.length, 1);
  assert.notEqual(h.save(), false);
});

test('a failed second reset preserves the marker and a pre-reset tab cannot write erased data back', async () => {
  const storage = recordingStorage({[STORE]: oldStatePayload(), [DRAFT_STORE]: '{}', [PREFS_STORE]: '{}'});
  // A tab booted before any reset: its marker is null and its memory still holds the old records.
  const preResetTab = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY)});
  const main = buildHarness({storage, state: restoreStoredState(oldStatePayload(), TODAY),
    values: {confirmation: 'RESET'}, lastUndo: {state: {}, revision: 3}});
  await main.submitReset(); // first reset succeeds
  const firstToken = storage.getItem(RESET_STORE);
  assert.match(firstToken, /^r-/);
  assert.deepEqual(JSON.parse(storage.getItem(STORE)).habits, []);
  const rawSetItem = storage.setItem;
  storage.setItem = function (key, value) {
    if (key === STORE) throw new Error('QuotaExceededError');
    return rawSetItem.call(storage, key, value);
  };
  await main.submitReset(); // second reset fails to save
  assert.match(main.errorBox.textContent, /Nothing was deleted/);
  // The first reset's marker must survive the failed second reset.
  assert.match(storage.getItem(RESET_STORE), /^r-/);
  assert.deepEqual(JSON.parse(storage.getItem(STORE)).habits, []);
  // The pre-reset tab stays stale: it writes nothing and adopts the erased state.
  const before = storage.log.length;
  assert.equal(preResetTab.save(), false);
  assert.deepEqual(preResetTab.state.habits, []);
  assert.deepEqual(storage.log.slice(before).map(([kind]) => kind).filter(kind => kind === 'set'), []);
  assert.deepEqual(JSON.parse(storage.getItem(STORE)).habits, []);
});

test('a failed erase leaves the dialog open with the error and every record intact', async () => {
  const payload = oldStatePayload();
  const storage = recordingStorage({[STORE]: payload});
  const seed = restoreStoredState(payload, TODAY);
  const h = buildHarness({storage, state: seed, values: {confirmation: 'RESET'}});
  const rawSetItem = storage.setItem;
  storage.setItem = function (key, value) {
    if (key === STORE) throw new Error('QuotaExceededError');
    return rawSetItem.call(storage, key, value);
  };
  await h.submitReset();
  assert.match(h.errorBox.textContent, /Nothing was deleted/);
  assert.deepEqual(h.calls.closeModal, []);
  assert.deepEqual(h.calls.navigate, []);
  assert.deepEqual(h.calls.toasts, []);
  assert.equal(h.submitButton.disabled, false);
  assert.equal(h.state, seed);
  assert.equal(storage.getItem(STORE), payload);
  // Even a failed first reset leaves its marker: adoption, not removal, keeps every tab consistent.
  assert.match(storage.getItem(RESET_STORE), /^r-/);
});

test('closing the export dialog returns to the reset confirmation only for the reset-export context', () => {
  const closeListeners = section("modal.addEventListener('cancel'", "window.addEventListener('beforeunload'");
  let resets = 0;
  const handlers = {};
  const modal = {open: true, addEventListener(type, callback) { handlers[type] = callback; }, close() { this.open = false; handlers.close?.(); }};
  const env = new Function('modal', 'document', 'captureDraft', 'showResetConfirmation', 'seedContext', `
    let dialogContext = seedContext, dialogOpener = null;
    ${closeListeners}
    return {get context() { return dialogContext; }, set context(value) { dialogContext = value; }};
  `)(modal, {contains: () => false}, () => {}, () => resets++, {kind: 'reset-export'});
  handlers.cancel(); // Esc: cancel listener, then the native close
  modal.close();
  assert.equal(resets, 1);
  assert.equal(env.context, null);
  // The normal Settings export path must not loop back into the reset flow.
  env.context = {kind: 'habit'};
  modal.open = true;
  modal.close();
  assert.equal(resets, 1);
  env.context = null;
  modal.open = true;
  modal.close();
  assert.equal(resets, 1);
});

test('the erase button enables exactly when the confirmation input equals RESET', () => {
  const inputListener = section("document.addEventListener('input'", "document.addEventListener('change'");
  const eraseButton = {disabled: true};
  const listeners = new Function('$', 'captureDraft', `
    let filters = {query: ''};
    const render = () => {};
    const listeners = {};
    const document = {addEventListener(type, callback) { listeners[type] = callback; }};
    ${inputListener}
    return listeners;
  `)(selector => (selector === '#reset-erase' ? eraseButton : null), () => {});
  const fire = value => listeners.input({target: {id: '', name: 'confirmation', value,
    closest: selector => (selector === '#reset-form' ? {} : null)}});
  fire('RESET');
  assert.equal(eraseButton.disabled, false);
  fire('reset');
  assert.equal(eraseButton.disabled, true);
  fire('RESET ');
  assert.equal(eraseButton.disabled, true);
  fire('RESETT');
  assert.equal(eraseButton.disabled, true);
  fire('RESET');
  assert.equal(eraseButton.disabled, false);
});

test('the post-reset Today renders the fresh-page variant with restore actions', () => {
  const emptyStateHTMLCode = section('function isFreshPage(', 'function activityRow(');
  const render = (storage, partialState, active) => { const state = {habits: active, logs: [], activityEntries: [], ...partialState}; return new Function('localStorage', 'RESET_STORE', 'state', 'active', 'icon',
    `${emptyStateHTMLCode}\nreturn emptyStateHTML(active);`)(storage, RESET_STORE, state, active, () => ''); };
  const storage = recordingStorage({[RESET_STORE]: 'r-1-abc'});
  const fresh = render(storage, {activities: []}, []);
  assert.match(fresh, /A fresh page/);
  assert.match(fresh, /Everything was erased\. Add a habit, or restore a backup to continue where you left off\./);
  assert.match(fresh, /data-action="add-habit"/);
  assert.match(fresh, /data-action="import-data"/);
  assert.doesNotMatch(fresh, /Create your first habit/);
  const generic = render(recordingStorage(), {activities: []}, []);
  assert.match(generic, /Create your first habit/);
  assert.doesNotMatch(generic, /A fresh page/);
  assert.doesNotMatch(render(storage, {activities: [{id: 1}]}, []), /A fresh page/);
  assert.doesNotMatch(render(storage, {activities: [], logs: [{id: 1}]}, []), /A fresh page/);
  assert.doesNotMatch(render(storage, {activities: [], habits: [{id: 1, isArchived: true}]}, []), /A fresh page/);
  assert.match(render(storage, {activities: []}, [{id: 1}]), /No matching habits/);
});
