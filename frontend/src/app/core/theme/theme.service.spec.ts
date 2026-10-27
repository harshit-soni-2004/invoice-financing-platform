import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.removeItem('ifp.theme');
    delete document.documentElement.dataset['theme'];
  });

  afterEach(() => {
    localStorage.removeItem('ifp.theme');
    delete document.documentElement.dataset['theme'];
  });

  it('follows the system by default and cycles light, dark, system', () => {
    const theme = TestBed.inject(ThemeService);
    TestBed.flushEffects();
    expect(theme.preference()).toBe('system');
    expect(document.documentElement.dataset['theme']).toBeUndefined();

    theme.cycle();
    TestBed.flushEffects();
    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem('ifp.theme')).toBe('light');

    theme.cycle();
    TestBed.flushEffects();
    expect(document.documentElement.dataset['theme']).toBe('dark');

    theme.cycle();
    TestBed.flushEffects();
    expect(document.documentElement.dataset['theme']).toBeUndefined();
    expect(localStorage.getItem('ifp.theme')).toBe('system');
  });

  it('restores a stored preference and ignores unknown values', () => {
    localStorage.setItem('ifp.theme', 'dark');
    expect(TestBed.inject(ThemeService).preference()).toBe('dark');

    TestBed.resetTestingModule();
    localStorage.setItem('ifp.theme', 'neon');
    expect(TestBed.inject(ThemeService).preference()).toBe('system');
  });
});
