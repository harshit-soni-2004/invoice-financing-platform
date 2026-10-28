/** Mirrors auth-service's PasswordPolicy; the server still applies it, this only gives earlier feedback. */
export const PASSWORD_MIN_CHARACTERS = 12;
/** BCrypt ignores input beyond 72 bytes, so auth-service rejects longer passwords. */
export const PASSWORD_MAX_BYTES = 72;

/** @returns why the password is unacceptable, phrased to follow the word "Password", or null if it is acceptable. */
export function passwordViolation(password: string, email: string): string | null {
  if (password.trim() === '') {
    return 'is required';
  }
  if (Array.from(password).length < PASSWORD_MIN_CHARACTERS) {
    return `must be at least ${PASSWORD_MIN_CHARACTERS} characters`;
  }
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return `is too long (at most ${PASSWORD_MAX_BYTES} bytes; accented letters and emoji count as more than one)`;
  }
  if (email.trim() !== '' && password.trim().toLowerCase() === email.trim().toLowerCase()) {
    return 'must not be the same as your email address';
  }
  return null;
}
