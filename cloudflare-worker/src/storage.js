// src/storage.js

/**
 * Create a new user with TOTP secret
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} totpSecret
 */
export async function createUser(kv, userId, totpSecret) {
  await kv.put(`user:${userId}`, JSON.stringify({
    totpSecret,
    createdAt: Date.now(),
  }));
}

/**
 * Get user by ID
 * @param {KVNamespace} kv
 * @param {string} userId
 * @returns {Promise<{totpSecret: string, createdAt: number} | null>}
 */
export async function getUser(kv, userId) {
  const data = await kv.get(`user:${userId}`);
  return data ? JSON.parse(data) : null;
}

/**
 * Find user by TOTP secret (for rebind/recovery)
 * @param {KVNamespace} kv
 * @param {string} totpSecret
 * @returns {Promise<string | null>} userId or null
 */
export async function findUserByTOTPSecret(kv, totpSecret) {
  const list = await kv.list({ prefix: 'user:' });
  for (const key of list.keys) {
    const data = await kv.get(key.name);
    if (data) {
      const user = JSON.parse(data);
      if (user.totpSecret === totpSecret) {
        return key.name.replace('user:', '');
      }
    }
  }
  return null;
}

/**
 * List all user IDs
 * @param {KVNamespace} kv
 * @returns {Promise<Array<{userId: string, totpSecret: string}>>}
 */
export async function listAllUsers(kv) {
  const list = await kv.list({ prefix: 'user:' });
  const users = [];
  for (const key of list.keys) {
    const data = await kv.get(key.name);
    if (data) {
      const user = JSON.parse(data);
      users.push({
        userId: key.name.replace('user:', ''),
        totpSecret: user.totpSecret,
      });
    }
  }
  return users;
}

/**
 * Save backup metadata
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
 * Get backup metadata
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} backupId
 */
export async function getBackupMeta(kv, userId, backupId) {
  const data = await kv.get(`backup:${userId}:${backupId}`);
  return data ? JSON.parse(data) : null;
}

/**
 * List all backups for a user
 * @param {KVNamespace} kv
 * @param {string} userId
 * @returns {Promise<Array>}
 */
export async function listBackups(kv, userId) {
  const list = await kv.list({ prefix: `backup:${userId}:` });
  const backups = [];
  for (const key of list.keys) {
    const data = await kv.get(key.name);
    if (data) {
      backups.push(JSON.parse(data));
    }
  }
  return backups.sort((a, b) => a.createdAt - b.createdAt);
}

/**
 * Delete backup metadata
 * @param {KVNamespace} kv
 * @param {string} userId
 * @param {string} backupId
 */
export async function deleteBackupMeta(kv, userId, backupId) {
  await kv.delete(`backup:${userId}:${backupId}`);
}

/**
 * Upload file to R2
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 * @param {string} type
 * @param {ReadableStream} body
 * @param {number} size
 */
export async function uploadToR2(bucket, userId, backupId, type, body, size) {
  const key = `backups/${userId}/${backupId}.${type}`;
  await bucket.put(key, body, {
    httpMetadata: { contentType: 'application/octet-stream' },
  });
  return size;
}

/**
 * Download file from R2
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 * @param {string} type
 * @returns {Promise<R2ObjectBody | null>}
 */
export async function downloadFromR2(bucket, userId, backupId, type) {
  const key = `backups/${userId}/${backupId}.${type}`;
  return await bucket.get(key);
}

/**
 * Delete file from R2
 * @param {R2Bucket} bucket
 * @param {string} userId
 * @param {string} backupId
 * @param {string} type
 */
export async function deleteFromR2(bucket, userId, backupId, type) {
  const key = `backups/${userId}/${backupId}.${type}`;
  await bucket.delete(key);
}

/**
 * Enforce backup quota (max 5), delete oldest if exceeded
 * @param {KVNamespace} kv
 * @param {R2Bucket} bucket
 * @param {string} userId
 */
export async function enforceBackupQuota(kv, bucket, userId, maxBackups = 5) {
  const backups = await listBackups(kv, userId);
  while (backups.length > maxBackups) {
    const oldest = backups.shift();
    await deleteFromR2(bucket, userId, oldest.id, oldest.type);
    await deleteBackupMeta(kv, userId, oldest.id);
  }
}
