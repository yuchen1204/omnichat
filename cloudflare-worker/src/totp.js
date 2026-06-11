// src/totp.js
import { TOTP } from 'otpauth';

/**
 * Generate a new TOTP secret for user binding
 * @returns {{ secret: string, qrCodeUrl: string }}
 */
export function generateTOTP() {
  const totp = new TOTP({
    issuer: 'OmniChat',
    label: 'Cloud Backup',
    algorithm: 'SHA1',
    digits: 6,
    period: 30,
  });

  return {
    secret: totp.secret.base32,
    qrCodeUrl: totp.toString(), // otpauth:// URI
  };
}

/**
 * Verify a TOTP code against a secret
 * @param {string} secret - Base32 encoded secret
 * @param {string} token - 6-digit code from authenticator
 * @returns {boolean}
 */
export function verifyTOTP(secret, token) {
  const totp = new TOTP({
    issuer: 'OmniChat',
    label: 'Cloud Backup',
    algorithm: 'SHA1',
    digits: 6,
    period: 30,
    secret: secret,
  });

  const delta = totp.validate({ token, window: 1 }); // Allow 1 period drift
  return delta !== null;
}
