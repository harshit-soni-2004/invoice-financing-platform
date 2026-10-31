import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AuthService } from '../../core/auth/auth.service';
import { ThemeService } from '../../core/theme/theme.service';
import { AccountApi, AccountApiError, Profile } from './account-api';
import { AccountComponent } from './account.component';

describe('AccountComponent', () => {
  let fixture: ComponentFixture<AccountComponent>;
  let el: HTMLElement;
  let load: jasmine.Spy;
  let rename: jasmine.Spy;
  let changePassword: jasmine.Spy;
  let renameUser: jasmine.Spy;

  const profile: Profile = {
    id: 'u-1',
    email: 'asha@example.test',
    displayName: 'Asha Rao',
    role: 'SELLER',
    status: 'ACTIVE',
    createdAt: '2026-10-01T09:30:00Z',
  };

  async function open(): Promise<void> {
    TestBed.configureTestingModule({
      imports: [AccountComponent],
      providers: [
        { provide: AccountApi, useValue: { load, rename, changePassword } },
        { provide: AuthService, useValue: { renameUser } },
      ],
    });
    fixture = TestBed.createComponent(AccountComponent);
    el = fixture.nativeElement;
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  const input = (id: string) => el.querySelector<HTMLInputElement>(`#${id}`)!;
  const button = (label: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
  const type = (id: string, value: string) => {
    input(id).value = value;
    input(id).dispatchEvent(new Event('input'));
  };
  const text = () => el.textContent?.replace(/\s+/g, ' ') ?? '';

  beforeEach(() => {
    localStorage.removeItem('ifp.theme');
    load = jasmine.createSpy('load').and.resolveTo(profile);
    rename = jasmine.createSpy('rename');
    changePassword = jasmine.createSpy('changePassword');
    renameUser = jasmine.createSpy('renameUser');
  });

  it('shows the editable name and the read-only details', async () => {
    await open();

    expect(input('account-name').value).toBe('Asha Rao');
    expect(text()).toContain('asha@example.test');
    expect(text()).toContain('Used to sign in, so it cannot be changed here.');
    expect(text()).toContain('Seller account');
    expect(text()).toContain('Active');
    expect(button('Save name').disabled).toBeTrue();
  });

  it('saves a new name and shows it in the header', async () => {
    rename.and.resolveTo({ ...profile, displayName: 'Asha R. Mehta' });
    await open();

    type('account-name', '  Asha R. Mehta ');
    await settle();
    button('Save name').click();
    await settle();

    expect(rename).toHaveBeenCalledOnceWith('Asha R. Mehta');
    expect(renameUser).toHaveBeenCalledOnceWith('Asha R. Mehta');
    expect(text()).toContain('Your name was saved.');
    expect(el.querySelector('.identity-name')?.textContent).toBe('Asha R. Mehta');
  });

  it('does not send a blank name', async () => {
    await open();

    type('account-name', '   ');
    await settle();
    button('Save name').click();
    await settle();

    expect(rename).not.toHaveBeenCalled();
    expect(text()).toContain('Enter your name.');
  });

  it('checks the new password before sending it', async () => {
    await open();

    type('account-current-password', 'current-password-1');
    type('account-new-password', 'short');
    type('account-confirm-password', 'different');
    button('Change password').click();
    await settle();

    expect(changePassword).not.toHaveBeenCalled();
    expect(text()).toContain('New password must be at least 12 characters.');
    expect(text()).toContain('The new passwords do not match.');
  });

  it('changes the password and clears the form', async () => {
    changePassword.and.resolveTo(undefined);
    await open();

    type('account-current-password', 'current-password-1');
    type('account-new-password', 'a brand new passphrase');
    type('account-confirm-password', 'a brand new passphrase');
    button('Change password').click();
    await settle();

    expect(changePassword).toHaveBeenCalledOnceWith('current-password-1', 'a brand new passphrase');
    expect(input('account-current-password').value).toBe('');
    expect(input('account-new-password').value).toBe('');
    expect(text()).toContain('Your password was changed.');
  });

  it('shows a wrong current password next to that field', async () => {
    changePassword.and.rejectWith(
      new AccountApiError({ kind: 'invalid' }, [{ field: 'currentPassword', message: 'is incorrect' }]),
    );
    await open();

    type('account-current-password', 'not-my-password');
    type('account-new-password', 'a brand new passphrase');
    type('account-confirm-password', 'a brand new passphrase');
    button('Change password').click();
    await settle();

    expect(el.querySelector('#account-current-password-error')?.textContent).toContain(
      'Your current password is incorrect.',
    );
    expect(input('account-current-password').getAttribute('aria-invalid')).toBe('true');
  });

  it('explains a lockout after too many wrong attempts', async () => {
    changePassword.and.rejectWith(new AccountApiError({ kind: 'rate-limited', retryAfterSeconds: 240 }));
    await open();

    type('account-current-password', 'not-my-password');
    type('account-new-password', 'a brand new passphrase');
    type('account-confirm-password', 'a brand new passphrase');
    button('Change password').click();
    await settle();

    expect(text()).toContain('Too many incorrect attempts. Try again in 4 minutes.');
  });

  it('switches the theme from the preview cards', async () => {
    await open();
    const theme = TestBed.inject(ThemeService);
    const card = (name: string) =>
      Array.from(el.querySelectorAll<HTMLButtonElement>('.theme-card')).find((b) =>
        b.querySelector('.theme-name')?.textContent?.includes(name),
      )!;

    expect(card('System').getAttribute('aria-pressed')).toBe('true');
    card('Dark').click();
    await settle();

    expect(theme.preference()).toBe('dark');
    expect(card('Dark').getAttribute('aria-pressed')).toBe('true');
    expect(card('System').getAttribute('aria-pressed')).toBe('false');
    theme.preference.set('system');
  });

  it('says when the account cannot be loaded', async () => {
    load.and.rejectWith(new AccountApiError({ kind: 'unavailable' }));
    await open();

    expect(el.querySelector('[role="alert"]')?.textContent).toContain('could not be loaded');
    expect(el.querySelector('#account-current-password')).toBeNull();
  });
});
