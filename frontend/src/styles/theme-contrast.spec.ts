/**
 * Reads the real CSS custom properties from the global stylesheet (loaded by Karma) and checks WCAG AA contrast
 * (4.5:1 for text) for every foreground/background pair the components use, in both themes.
 */
const TEXT_PAIRS: readonly [string, string][] = [
  ['--color-text', '--color-bg'],
  ['--color-text', '--color-surface'],
  ['--color-text', '--color-surface-2'],
  ['--color-text-muted', '--color-bg'],
  ['--color-text-muted', '--color-surface'],
  ['--color-text-muted', '--color-surface-2'],
  ['--color-primary', '--color-surface'],
  ['--color-primary', '--color-bg'],
  ['--color-primary', '--color-primary-soft'],
  ['--color-on-primary', '--color-primary'],
  ['--color-on-primary', '--color-primary-hover'],
  ['--color-surface', '--color-danger'],
  ['--color-success', '--color-success-soft'],
  ['--color-success', '--color-surface'],
  ['--color-warning', '--color-warning-soft'],
  ['--color-warning', '--color-surface'],
  ['--color-danger', '--color-danger-soft'],
  ['--color-danger', '--color-surface'],
  ['--color-info', '--color-info-soft'],
  ['--color-info', '--color-surface'],
];

/** Focus rings and input borders are non-text UI components: WCAG 1.4.11 asks for 3:1. */
const UI_PAIRS: readonly [string, string][] = [
  ['--color-focus', '--color-surface'],
  ['--color-focus', '--color-bg'],
  ['--color-border-strong', '--color-surface'],
];

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex.trim());
  if (!match) {
    throw new Error(`Not a 6-digit hex colour: "${hex}"`);
  }
  const [r, g, b] = match.slice(1).map((part) => channel(parseInt(part, 16)));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('theme contrast', () => {
  const root = document.documentElement;

  afterEach(() => delete root.dataset['theme']);

  for (const theme of ['light', 'dark']) {
    it(`meets WCAG AA in the ${theme} theme`, () => {
      root.dataset['theme'] = theme;
      const style = getComputedStyle(root);
      const read = (name: string) => style.getPropertyValue(name);
      expect(read('--color-text')).withContext('global stylesheet loaded').not.toBe('');

      for (const [fg, bg] of TEXT_PAIRS) {
        expect(contrast(read(fg), read(bg))).withContext(`${theme}: ${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
      }
      for (const [fg, bg] of UI_PAIRS) {
        expect(contrast(read(fg), read(bg))).withContext(`${theme}: ${fg} on ${bg}`).toBeGreaterThanOrEqual(3);
      }
    });
  }
});
