// src/storage.js

/**
 * Create a new user with TOTP secret.
 * Also writes a reverse index (totp:{secret} -> userId) for O(1) lookup.
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} totpSecret
 */
export async function createUser(kv, userId, totpSecret) {
  await Promise.all([
    kv.put(`user:${userId}`, JSON.stringify({
      totpSecret,
      createdAt: Date.now(),
    })),
    kv.put(`totp:${totpSecret}`, userId),
  ]);
}

/**
 * Find user by TOTP secret — O(1) reverse index lookup.
 * @param {KVNamespace} kv
 * @param {string} totpSecret
 * @returns {Promise<string | null>} userId or null
 */
export async function findUserByTOTPSecret(kv, totpSecret) {
  return await kv.get(`totp:${totpSecret}`);
}

/**
 * List all user IDs.
 * @param {KVNamespace} kv
 * @returns {Promise<Array<{userId: string, totpSecret: string}>>}
 */
export async function listAllUsers(kv) {
  const list = await kv.list({ prefix: 'user:' });
  const users = [];
  for (const key of list.keys) {
    const data = await kv.get(key.name, { type: 'json' });
    if (data) {
      users.push({
        userId: key.name.replace('user:', ''),
        totpSecret: data.totpSecret,
      });
    }
  }
  return users;
}

/**
 * Save backup metadata (as JSON value for efficient retrieval).
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} backupId
 * @param {{ type: string, size: number, filename: string }} meta
 */
export async function saveBackupMeta(kv, userId, backupId, meta) {
  await kv.put(`backup:${userId}:${backupId}`, JSON.stringify({
    id: backupId,
    ...meta,
    createdAt: Date.now(),
  }));
}

/**
 * Get backup metadata.
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} backupId
 */
export async function getBackupMeta(kv, userId, backupId) {
  const data = await kv.get(`backup:${userId}:${backupId}`, { type: 'json' });
  return data || null;
}

/**
 * List all backups for a user.
 * @param {KVNamespace} kv
 * @param {string} userId
 * @returns {Promise<Array>}
 */
export async function listBackups(kv, userId) {
  const list = await kv.list({ prefix: `backup:${userId}:` });
  const backups = await Promise.all(
    list.keys.map(key => kv.get(key.name, { type: 'json' }))
  );
  return backups.filter(Boolean).sort((a, b) => a.createdAt - b.createdAt);
}

/**
 * Delete backup metadata.
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} backupId
 */
export async function deleteBackupMeta(kv, userId, backupId) {
  await kv.delete(`backup:${userId}:${backupId}`);
}

/**
 * Upload file to R2.
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 * @param {ReadableStream} body
 */
export async function uploadToR2(bucket, userId, backupId, body) {
  const key = `backups/${userId}/${backupId}.omnifile`;
  await bucket.put(key, body, {
    httpMetadata: { contentType: 'application/octet-stream' },
  });
}

/**
 * Download file from R2.
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 * @returns {Promise<R2ObjectBody | null>}
 */
export async function downloadFromR2(bucket, userId, backupId) {
  const key = `backups/${userId}/${backupId}.omnifile`;
  return await bucket.get(key);
}

/**
 * Delete file from R2.
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 */
export async function deleteFromR2(bucket, userId, backupId) {
  const key = `backups/${userId}/${backupId}.omnifile`;
  await bucket.delete(key);
}

/**
 * Enforce backup quota (max 5), delete oldest if exceeded.
 * Each backup is its own group (omnifile-only).
 * @param {KVNamespace} kv
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {number} maxBackups
 */
export async function enforceBackupQuota(kv, bucket, userId, maxBackups = 5) {
  const backups = await listBackups(kv, userId);
  if (backups.length <= maxBackups) return;

  const toDelete = backups.slice(0, backups.length - maxBackups);
  await Promise.all(toDelete.map(b =>
    Promise.all([
      deleteFromR2(bucket, userId, b.id),
      deleteBackupMeta(kv, userId, b.id),
    ])
  ));
}
