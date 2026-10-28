import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { RegisterComponent } from './register.component';
import { RegisteredAccount, RegistrationError, RegistrationFailure, RegistrationService } from './registration.service';

const PASSWORD = 'violet harbour lantern';

describe('RegisterComponent', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let el: HTMLElement;
  let register: jasmine.Spy;

  beforeEach(async () => {
    register = jasmine.createSpy('register');
    TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [provideRouter([]), { provide: RegistrationService, useValue: { register } }],
    });
    fixture = TestBed.createComponent(RegisterComponent);
    el = fixture.nativeElement;
    await settle();
  });

  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function input(id: string): HTMLInputElement {
    return el.querySelector<HTMLInputElement>(`#${id}`)!;
  }

  function type(id: string, value: string): void {
    const field = input(id);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('blur'));
  }

  async function fill(values: { role?: 'seller' | 'financier'; name?: string; email?: string; password?: string; confirm?: string }) {
    if (values.role) {
      input(`reg-role-${values.role}`).click();
    }
    type('reg-name', values.name ?? 'Asha Rao');
    type('reg-email', values.email ?? 'asha@example.test');
    type('reg-password', values.password ?? PASSWORD);
    type('reg-confirm', values.confirm ?? values.password ?? PASSWORD);
    await settle();
  }

  async function submit(): Promise<void> {
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    await settle();
  }

  function errorText(id: string): string | undefined {
    return el.querySelector(`#${id}-error`)?.textContent?.trim();
  }

  const account = (overrides: Partial<RegisteredAccount> = {}): RegisteredAccount => ({
    id: 'u-1',
    email: 'asha@example.test',
    displayName: 'Asha Rao',
    role: 'SELLER',
    status: 'ACTIVE',
    ...overrides,
  });

  it('offers only the self-registrable roles, labels every field and links to sign-in', () => {
    const radios = Array.from(el.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
    expect(radios.map((radio) => radio.value)).toEqual(['SELLER', 'FINANCIER']);
    // One named group: a single Tab stop, with arrow keys moving between the options.
    expect(radios.map((radio) => radio.name)).toEqual(['role', 'role']);
    for (const id of ['reg-name', 'reg-email', 'reg-password', 'reg-confirm']) {
      expect(el.querySelector(`label[for="${id}"]`)).withContext(id).not.toBeNull();
    }
    expect(input('reg-password').type).toBe('password');
    expect(input('reg-password').autocomplete).toBe('new-password');
    expect(el.querySelector('a[href="/login"]')?.textContent?.trim()).toBe('Sign in');
  });

  it('explains every missing field, focuses the first one and sends nothing', async () => {
    await submit();

    expect(register).not.toHaveBeenCalled();
    expect(errorText('reg-role')).toContain('seller or a financier');
    expect(errorText('reg-name')).toBe('Enter your full name.');
    expect(errorText('reg-email')).toBe('Enter your email address.');
    expect(errorText('reg-password')).toBe('Password is required.');
    expect(errorText('reg-confirm')).toBe('Re-enter your password.');
    expect(input('reg-email').getAttribute('aria-invalid')).toBe('true');
    expect(input('reg-email').getAttribute('aria-describedby')).toBe('reg-email-error');
    expect(document.activeElement).toBe(input('reg-role-seller'));
  });

  it('checks the email format, the password policy and the confirmation', async () => {
    await fill({ role: 'seller', email: 'not-an-email', password: 'too-short', confirm: 'different' });
    await submit();

    expect(errorText('reg-email')).toContain('valid email address');
    expect(errorText('reg-password')).toBe('Password must be at least 12 characters.');
    expect(errorText('reg-confirm')).toBe('The passwords do not match.');
    expect(document.activeElement).toBe(input('reg-email'));
    expect(register).not.toHaveBeenCalled();

    type('reg-email', 'asha@example.test');
    type('reg-password', 'ASHA@example.test');
    type('reg-confirm', 'ASHA@example.test');
    await settle();
    expect(errorText('reg-password')).toBe('Password must not be the same as your email address.');
  });

  it('registers a seller with trimmed details and offers sign-in', async () => {
    register.and.resolveTo(account());
    await fill({ role: 'seller', name: '  Asha Rao ', email: ' asha@example.test ' });
    await submit();

    expect(register).toHaveBeenCalledOnceWith({
      role: 'SELLER',
      displayName: 'Asha Rao',
      email: 'asha@example.test',
      password: PASSWORD,
    });
    const heading = el.querySelector('h1')!;
    expect(heading.textContent).toContain('Your seller account is ready');
    expect(document.activeElement).toBe(heading);
    expect(el.querySelector('form')).toBeNull();
    expect(el.querySelector('a[href="/login"]')?.textContent?.trim()).toBe('Sign in');
  });

  it('tells a new financier that approval comes before sign-in', async () => {
    input('reg-role-financier').click();
    await settle();
    expect(el.querySelector('.alert-info')?.textContent).toContain('after an administrator approves');

    register.and.resolveTo(account({ role: 'FINANCIER', status: 'PENDING_APPROVAL' }));
    await fill({ role: 'financier' });
    await submit();

    expect(register.calls.mostRecent().args[0].role).toBe('FINANCIER');
    expect(el.querySelector('h1')?.textContent).toContain('waiting for approval');
    expect(el.textContent).toContain('until then, sign-in is refused');
    expect(el.querySelector('a[href="/login"]')).toBeNull();
  });

  it('marks a duplicate email and points to sign-in', async () => {
    register.and.rejectWith(new RegistrationError({ kind: 'duplicate-email' }));
    await fill({ role: 'seller' });
    await submit();

    const alert = el.querySelector<HTMLElement>('[role="alert"]')!;
    expect(alert.textContent).toContain('already exists');
    expect(alert.querySelector('a[href="/login"]')).not.toBeNull();
    expect(document.activeElement).toBe(alert);
    expect(errorText('reg-email')).toBe('An account with this email already exists.');

    type('reg-email', 'other@example.test');
    await settle();
    expect(errorText('reg-email')).toBeUndefined();
  });

  it("shows the server's field errors next to the fields", async () => {
    register.and.rejectWith(
      new RegistrationError({ kind: 'invalid', fieldErrors: { displayName: 'must not be blank', role: 'must be SELLER or FINANCIER' } }),
    );
    await fill({ role: 'seller' });
    await submit();

    expect(errorText('reg-name')).toBe('Full name must not be blank.');
    expect(errorText('reg-role')).toBe('Account type must be SELLER or FINANCIER.');
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('highlighted fields');
    expect(document.activeElement).toBe(input('reg-role-seller'));
  });

  it('shows progress and ignores further submits while the request is in flight', async () => {
    let finish!: (value: RegisteredAccount) => void;
    register.and.returnValue(new Promise<RegisteredAccount>((resolve) => (finish = resolve)));
    await fill({ role: 'seller' });
    await submit();

    const button = el.querySelector<HTMLButtonElement>('button[type="submit"]')!;
    expect(button.disabled).toBeTrue();
    expect(button.textContent?.trim()).toBe('Creating account…');
    expect(el.querySelector('form')?.getAttribute('aria-busy')).toBe('true');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await settle();
    expect(register).toHaveBeenCalledTimes(1);

    finish(account());
    await settle();
    expect(el.querySelector('h1')?.textContent).toContain('ready');
  });

  const failures: [RegistrationFailure, RegExp][] = [
    [{ kind: 'rate-limited', retryAfterSeconds: 12 }, /Wait 12 seconds/],
    [{ kind: 'rate-limited', retryAfterSeconds: null }, /Wait a minute/],
    [{ kind: 'network' }, /could not be reached/],
    [{ kind: 'unavailable' }, /temporarily unavailable/],
    [{ kind: 'unexpected' }, /may or may not have been created/],
  ];
  for (const [failure, message] of failures) {
    it(`explains a ${failure.kind} failure and lets the user retry`, async () => {
      register.and.rejectWith(new RegistrationError(failure));
      await fill({ role: 'seller' });
      await submit();

      const alert = el.querySelector<HTMLElement>('[role="alert"]')!;
      expect(alert.textContent).toMatch(message);
      expect(document.activeElement).toBe(alert);
      expect(input('reg-password').value).toBe(PASSWORD);
      expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBeFalse();
    });
  }

  it('keeps the password out of browser storage and the console', async () => {
    const consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => spyOn(console, method));
    register.and.rejectWith(new RegistrationError({ kind: 'unexpected' }));
    await fill({ role: 'seller' });
    await submit();
    register.and.resolveTo(account());
    await submit();

    const stored = JSON.stringify({ ...localStorage, ...sessionStorage });
    expect(stored).not.toContain(PASSWORD);
    for (const spy of consoleSpies) {
      expect(JSON.stringify(spy.calls.allArgs())).not.toContain(PASSWORD);
    }
  });

  it('can show the passwords while typing', async () => {
    const toggle = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Show passwords'))!;
    toggle.click();
    await settle();
    expect(input('reg-password').type).toBe('text');
    expect(input('reg-confirm').type).toBe('text');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
  });
});
