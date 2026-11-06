import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Flight Recorder regression guards for the nudge banner contrast and the
// analytics stylesheet precedence (review findings on 8f4cadb).

const css = readFileSync(new URL('../dist/styles.css', import.meta.url), 'utf8');
const acss = readFileSync(new URL('../dist/analytics.css', import.meta.url), 'utf8');

function luminance(hex) {
  const c = hex.replace('#', '');
  const [r, g, b] = [0, 2, 4].map(i => parseInt(c.slice(i, i + 2), 16) / 255)
    .map(v => v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function ratio(a, b) {
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}
function token(name, theme) {
  const themeBlock = theme === 'dark'
    ? css.slice(css.indexOf('body.dark{'), css.indexOf('}', css.indexOf('body.dark{')))
    : css.slice(0, css.indexOf('body{'));
  return themeBlock.match(new RegExp(`--${name}:(${'#[0-9a-f]{6}'})`))?.[1];
}

test('nudge banner button pairs ink background with paper text in both themes', () => {
  assert.doesNotMatch(css, /body\.dark \.nudge-banner \.primary-button\{color:var\(--ink\)/,
    'dark override that made button text match its background must stay removed');
  const base = css.match(/\.nudge-banner \.primary-button\{([^}]+)\}/)?.[1];
  assert.ok(base, 'nudge button rule exists');
  assert.match(base, /background:var\(--ink\)/);
  assert.match(base, /color:var\(--paper\)/);
  // Effective pairings from the actual tokens
  const light = ratio(token('ink', 'light'), token('paper', 'light'));
  const dark = ratio(token('ink', 'dark'), token('paper', 'dark'));
  assert.ok(light >= 4.5, `light banner button contrast ${light}:1`);
  assert.ok(dark >= 4.5, `dark banner button contrast ${dark}:1`);
});

test('dark hover keeps readable text on the electric orange marker', () => {
  const hover = css.match(/body\.dark \.primary-button:hover\{([^}]+)\}/)?.[1];
  assert.ok(hover, 'dark primary hover rule exists');
  assert.match(hover, /background:var\(--orange\)/);
  // On hover the button text is the dark theme's on-primary (charcoal), not cream.
  const charcoal = token('on-green', 'dark');
  const orange = token('orange', 'dark');
  assert.ok(ratio(charcoal, orange) >= 4.5, `dark hover contrast ${ratio(charcoal, orange)}:1`);
});

test('flight recorder an-* overrides live in the owning analytics stylesheet', () => {
  assert.match(acss, /\.an-panel\{background:transparent/);
  assert.doesNotMatch(css, /\.an-panel\{background:transparent/);
  assert.match(acss, /\.an-cell\{border-radius:40%\}/);
});

test('recording strip cells keep at least 48px touch width', () => {
  const strip = css.match(/\.record-strip\{([^}]+)\}/)?.[1];
  assert.ok(strip, 'record-strip container rule exists');
  assert.match(strip, /overflow-x:auto/, 'strip scrolls instead of shrinking cells');
  const button = css.match(/\.record-strip button\{([^}]+)\}/)?.[1];
  assert.match(button, /min-width:48px/);
  assert.match(button, /min-height:48px/);
});
