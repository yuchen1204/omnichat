// src/auth.js

const SESSION_TTL = 3600 * 1000; // 1 hour in milliseconds

/**
 * Create a session token for a user
 * @param {KVNamespace} kv
 * @param {string} userId
 * @returns {Promise<{token: string, expiresIn: number}>}
 */
export async function createSession(kv, userId) {
  const token = crypto.randomUUID();
  const expiresAt = Date.now() + SESSION_TTL;

  await kv.put(`session:${token}`, JSON.stringify({
    userId,
    expiresAt,
  }), { expirationTtl: 3600 }); // Also set KV TTL

  return { token, expiresIn: SESSION_TTL / 1000 };
}

/**
 * Validate a session token
 * @param {KVNamespace} kv
 * @param {string} token
 * @returns {Promise<string | null>} userId or null if invalid
 */
export async function validateSession(kv, token) {
  const data = await kv.get(`session:${token}`);
  if (!data) return null;

  const session = JSON.parse(data);
  if (session.expiresAt < Date.now()) {
    await kv.delete(`session:${token}`);
    return null;
  }

  return session.userId;
}

/**
 * Delete a session (logout)
 * @param {KVNamespace} kv
 * @param {string} token
 */
export async function deleteSession(kv, token) {
  await kv.delete(`session:${token}`);
}
