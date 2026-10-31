import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';

import { AuthService } from '../../core/auth/auth.service';
import { ROLE_LABELS } from '../../core/auth/role-labels';
import { ThemePreference, ThemeService } from '../../core/theme/theme.service';
import { ApiFailure, commonFailureMessage } from '../../shared/api-failure';
import { passwordViolation } from '../auth/password-policy';
import { AccountApi, AccountApiError, FieldRejection, Profile } from './account-api';

interface ThemeOption {
  value: ThemePreference;
  name: string;
  description: string;
}

type Outcome = { kind: 'success' | 'error'; message: string } | null;

const STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Active',
  PENDING_APPROVAL: 'Pending approval',
  REJECTED: 'Rejected',
  DISABLED: 'Disabled',
};

function notBlank(control: AbstractControl<string>): ValidationErrors | null {
  return control.value.trim() === '' ? { required: true } : null;
}

/**
 * Account settings: the display name and password can be changed here. Email is the sign-in identity, and role and
 * status are set by registration and administrators, so those are shown read-only.
 */
@Component({
  selector: 'ifp-account',
  imports: [DatePipe, ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account.component.html',
  styleUrl: './account.component.scss',
})
export class AccountComponent {
  private readonly api = inject(AccountApi);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly theme = inject(ThemeService);

  protected readonly roleLabels = ROLE_LABELS;
  protected readonly profile = signal<Profile | null>(null);
  protected readonly loadFailed = signal(false);
  protected readonly initials = computed(() => initialsOf(this.profile()?.displayName ?? ''));

  protected readonly nameForm = this.fb.group({
    displayName: this.fb.control('', [notBlank, Validators.maxLength(100)]),
  });
  protected readonly savingName = signal(false);
  protected readonly nameOutcome = signal<Outcome>(null);
  private readonly nameValue = signal('');
  protected readonly nameUnchanged = computed(() => this.nameValue().trim() === (this.profile()?.displayName ?? ''));

  protected readonly passwordForm = this.fb.group({
    currentPassword: this.fb.control('', notBlank),
    newPassword: this.fb.control('', (control: AbstractControl<string>) => {
      const violation = passwordViolation(control.value, this.profile()?.email ?? '');
      return violation ? { policy: violation } : null;
    }),
    confirmPassword: this.fb.control('', (control: AbstractControl<string>) =>
      control.value === '' ? { required: true } : control.value !== control.parent?.value.newPassword ? { mismatch: true } : null,
    ),
  });
  protected readonly passwordSubmitted = signal(false);
  protected readonly changingPassword = signal(false);
  protected readonly showPasswords = signal(false);
  protected readonly passwordOutcome = signal<Outcome>(null);

  protected readonly themeOptions: readonly ThemeOption[] = [
    { value: 'system', name: 'System', description: 'Follows your device setting' },
    { value: 'light', name: 'Light', description: 'Bright surfaces for daytime' },
    { value: 'dark', name: 'Dark', description: 'Easier on the eyes at night' },
  ];

  constructor() {
    const { newPassword, confirmPassword } = this.passwordForm.controls;
    newPassword.valueChanges.subscribe(() => confirmPassword.updateValueAndValidity({ emitEvent: false }));
    this.nameForm.controls.displayName.valueChanges.subscribe((value) => {
      this.nameValue.set(value);
      this.nameOutcome.set(null);
    });
    void this.load();
  }

  protected statusLabel(status: string): string {
    return STATUS_LABELS[status] ?? status;
  }

  protected nameError(): string | null {
    const control = this.nameForm.controls.displayName;
    if (!control.errors || !control.touched) {
      return null;
    }
    if (typeof control.errors['server'] === 'string') {
      return control.errors['server'];
    }
    return control.errors['maxlength'] ? 'Name must be at most 100 characters.' : 'Enter your name.';
  }

  protected passwordError(field: 'currentPassword' | 'newPassword' | 'confirmPassword'): string | null {
    const control = this.passwordForm.controls[field];
    if (!control.errors || !(control.touched || this.passwordSubmitted())) {
      return null;
    }
    const errors = control.errors;
    if (typeof errors['server'] === 'string') {
      return errors['server'];
    }
    switch (field) {
      case 'currentPassword':
        return 'Enter your current password.';
      case 'newPassword':
        return `New password ${errors['policy'] ?? 'is not acceptable'}.`;
      case 'confirmPassword':
        return errors['required'] ? 'Re-enter the new password.' : 'The new passwords do not match.';
    }
  }

  protected async saveName(): Promise<void> {
    const control = this.nameForm.controls.displayName;
    control.markAsTouched();
    if (this.savingName() || this.nameForm.invalid || this.nameUnchanged()) {
      return;
    }
    this.savingName.set(true);
    this.nameOutcome.set(null);
    try {
      const updated = await this.api.rename(control.value.trim());
      this.profile.set(updated);
      this.auth.renameUser(updated.displayName);
      control.setValue(updated.displayName);
      this.nameOutcome.set({ kind: 'success', message: 'Your name was saved.' });
    } catch (error) {
      const rejection = rejectionFor(error, 'displayName');
      if (rejection) {
        control.setErrors({ server: 'This name was not accepted. Use 1 to 100 characters.' });
      } else {
        this.nameOutcome.set({ kind: 'error', message: messageFor(error) });
      }
    } finally {
      this.savingName.set(false);
    }
  }

  protected async changePassword(): Promise<void> {
    if (this.changingPassword()) {
      return;
    }
    this.passwordSubmitted.set(true);
    this.passwordOutcome.set(null);
    if (this.passwordForm.invalid) {
      this.passwordForm.markAllAsTouched();
      return;
    }
    const { currentPassword, newPassword } = this.passwordForm.getRawValue();
    this.changingPassword.set(true);
    try {
      await this.api.changePassword(currentPassword, newPassword);
      this.passwordForm.reset();
      this.passwordSubmitted.set(false);
      this.showPasswords.set(false);
      this.passwordOutcome.set({
        kind: 'success',
        message: 'Your password was changed. Use the new password the next time you sign in.',
      });
    } catch (error) {
      const current = rejectionFor(error, 'currentPassword');
      const next = rejectionFor(error, 'newPassword');
      if (current) {
        this.passwordForm.controls.currentPassword.setErrors({ server: 'Your current password is incorrect.' });
      } else if (next) {
        this.passwordForm.controls.newPassword.setErrors({
          server: next.message.includes('different')
            ? 'Choose a new password that is different from your current one.'
            : 'This new password was not accepted. Use at least 12 characters, not your email address.',
        });
      } else {
        this.passwordOutcome.set({ kind: 'error', message: passwordFailureMessage(error) });
      }
    } finally {
      this.changingPassword.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      const profile = await this.api.load();
      this.profile.set(profile);
      this.nameForm.controls.displayName.setValue(profile.displayName);
    } catch {
      this.loadFailed.set(true);
    }
  }
}

function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const letters = words.length > 1 ? [words[0], words[words.length - 1]] : words;
  return letters.map((word) => Array.from(word)[0]?.toUpperCase() ?? '').join('');
}

function failureOf(error: unknown): ApiFailure {
  return error instanceof AccountApiError ? error.failure : { kind: 'unexpected' };
}

function rejectionFor(error: unknown, field: string): FieldRejection | undefined {
  return error instanceof AccountApiError ? error.rejections.find((rejection) => rejection.field === field) : undefined;
}

function messageFor(error: unknown): string {
  return commonFailureMessage(failureOf(error), 'sign-in service');
}

function passwordFailureMessage(error: unknown): string {
  const failure = failureOf(error);
  if (failure.kind !== 'rate-limited') {
    return messageFor(error);
  }
  const minutes = failure.retryAfterSeconds ? Math.max(1, Math.ceil(failure.retryAfterSeconds / 60)) : null;
  return minutes
    ? `Too many incorrect attempts. Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`
    : 'Too many incorrect attempts. Wait a few minutes and try again.';
}
