// src/index.js
import { generateTOTP, verifyTOTP } from './totp.js';
import { createUser, getUser, findUserByTOTPSecret, saveBackupMeta, listBackups, deleteBackupMeta, uploadToR2, downloadFromR2, deleteFromR2, enforceBackupQuota } from './storage.js';
import { createSession, validateSession } from './auth.js';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS headers
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      // POST /api/bindtotp - Create user and return TOTP
      if (path === '/api/bindtotp' && request.method === 'POST') {
        const { secret, qrCodeUrl } = generateTOTP();
        const userId = crypto.randomUUID();

        await createUser(env.WORKERS_KV, userId, secret);

        return Response.json({
          userId,
          totpSecret: secret,
          qrCodeUrl,
        }, { headers: corsHeaders });
      }

      // POST /api/verify - Verify TOTP and return session
      if (path === '/api/verify' && request.method === 'POST') {
        const { totpSecret, totpCode } = await request.json();

        // Verify TOTP
        if (!verifyTOTP(totpSecret, totpCode)) {
          return Response.json({ error: 'Invalid TOTP code' }, {
            status: 401,
            headers: corsHeaders,
          });
        }

        // Find or create user
        let userId = await findUserByTOTPSecret(env.WORKERS_KV, totpSecret);
        if (!userId) {
          userId = crypto.randomUUID();
          await createUser(env.WORKERS_KV, userId, totpSecret);
        }

        // Create session
        const session = await createSession(env.WORKERS_KV, userId);

        return Response.json({
          userId,
          ...session,
        }, { headers: corsHeaders });
      }

      // All other routes require auth
      const authHeader = request.headers.get('Authorization');
      if (!authHeader?.startsWith('Bearer ')) {
        return Response.json({ error: 'Unauthorized' }, {
          status: 401,
          headers: corsHeaders,
        });
      }

      const token = authHeader.slice(7);
      const userId = await validateSession(env.WORKERS_KV, token);
      if (!userId) {
        return Response.json({ error: 'Invalid session' }, {
          status: 401,
          headers: corsHeaders,
        });
      }

      // POST /api/upload - Upload backup
      if (path === '/api/upload' && request.method === 'POST') {
        const { type, data, filename } = await request.json();

        if (!['omnidb', 'omniconfig'].includes(type)) {
          return Response.json({ error: 'Invalid backup type' }, {
            status: 400,
            headers: corsHeaders,
          });
        }

        const backupId = crypto.randomUUID();
        const dataBytes = Uint8Array.from(atob(data), c => c.charCodeAt(0));

        await uploadToR2(
          env.BACKUP_R2,
          userId,
          backupId,
          type,
          new Blob([dataBytes]).stream(),
          dataBytes.length
        );

        await saveBackupMeta(env.WORKERS_KV, userId, backupId, {
          type,
          size: dataBytes.length,
          filename,
        });

        await enforceBackupQuota(env.WORKERS_KV, env.BACKUP_R2, userId);

        return Response.json({ backupId, createdAt: Date.now() }, {
          headers: corsHeaders,
        });
      }

      // GET /api/list - List backups
      if (path === '/api/list' && request.method === 'GET') {
        const backups = await listBackups(env.WORKERS_KV, userId);
        return Response.json({ backups }, { headers: corsHeaders });
      }

      // GET /api/download/:backupId - Download backup
      if (path.startsWith('/api/download/') && request.method === 'GET') {
        const backupId = path.split('/').pop();
        const meta = await listBackups(env.WORKERS_KV, userId);
        const backup = meta.find(b => b.id === backupId);

        if (!backup) {
          return Response.json({ error: 'Backup not found' }, {
            status: 404,
            headers: corsHeaders,
          });
        }

        const file = await downloadFromR2(env.BACKUP_R2, userId, backupId, backup.type);
        if (!file) {
          return Response.json({ error: 'File not found' }, {
            status: 404,
            headers: corsHeaders,
          });
        }

        return new Response(file.body, {
          headers: {
            ...corsHeaders,
            'Content-Type': 'application/octet-stream',
            'Content-Disposition': `attachment; filename="${backup.filename}"`,
          },
        });
      }

      // DELETE /api/delete/:backupId - Delete backup
      if (path.startsWith('/api/delete/') && request.method === 'DELETE') {
        const backupId = path.split('/').pop();
        const backups = await listBackups(env.WORKERS_KV, userId);
        const backup = backups.find(b => b.id === backupId);

        if (!backup) {
          return Response.json({ error: 'Backup not found' }, {
            status: 404,
            headers: corsHeaders,
          });
        }

        await deleteFromR2(env.BACKUP_R2, userId, backupId, backup.type);
        await deleteBackupMeta(env.WORKERS_KV, userId, backupId);

        return Response.json({ success: true }, { headers: corsHeaders });
      }

      return Response.json({ error: 'Not found' }, {
        status: 404,
        headers: corsHeaders,
      });

    } catch (error) {
      console.error('Worker error:', error);
      return Response.json({ error: 'Internal server error' }, {
        status: 500,
        headers: corsHeaders,
      });
    }
  },
};
