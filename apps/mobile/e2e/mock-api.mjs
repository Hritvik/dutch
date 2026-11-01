import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 4010);
const REQUIRED_READS = 2;
const DEV_MEMBER = 'mem_dev_012345678901234567';
let profileStateReads = 0;

function seedGroups() {
  return [
    {
      id: 'grp_012345678901234567',
      name: 'Flat 12',
      currency: 'INR',
      state: 'ACTIVE',
      version: 1,
      memberships: [
        { memberId: DEV_MEMBER, role: 'ADMIN', state: 'ACTIVE' },
        { memberId: 'mem_112345678901234567', role: 'MEMBER', state: 'INACTIVE' }
      ]
    }
  ];
}

let groups = seedGroups();

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) });
  response.end(payload);
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

const server = createServer(async (request, response) => {
  if (request.method === 'POST' && request.url === '/reset') {
    profileStateReads = 0;
    groups = seedGroups();
    response.writeHead(204).end();
    return;
  }
  if (request.method === 'GET' && request.url === '/api/v1/me/profile-state') {
    profileStateReads += 1;
    if (profileStateReads > REQUIRED_READS) {
      json(response, 200, { data: { state: 'PROFILE_READY' } });
    } else {
      json(response, 200, { data: { state: 'PROFILE_REQUIRED', retryAfterSeconds: 2 } });
    }
    return;
  }
  if (request.method === 'GET' && request.url === '/api/v1/groups') {
    json(response, 200, { data: groups });
    return;
  }
  if (request.method === 'POST' && request.url === '/api/v1/groups') {
    const payload = JSON.parse((await readBody(request)) || '{}');
    const group = {
      id: `grp_${randomUUID().replaceAll('-', '')}`,
      name: String(payload.name ?? '').trim(),
      currency: String(payload.currency ?? 'INR'),
      state: 'ACTIVE',
      version: 0,
      memberships: [{ memberId: DEV_MEMBER, role: 'ADMIN', state: 'ACTIVE' }]
    };
    groups = [group, ...groups];
    json(response, 200, { data: group });
    return;
  }
  if (request.method === 'GET' && request.url === '/api/v1/me') {
    if (profileStateReads > REQUIRED_READS) {
      json(response, 200, {
        data: {
          memberId: DEV_MEMBER,
          displayName: 'Ada',
          profileState: 'PROFILE_READY',
          lifecycleState: 'ACTIVE'
        }
      });
    } else {
      json(response, 409, {
        error: {
          code: 'profile_required',
          message: 'Complete your profile to continue.',
          correlationId: 'cor_dev_0123456789012345'
        }
      });
    }
    return;
  }
  json(response, 404, { error: { code: 'not_found', message: 'Unknown mock route.' } });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Dutch mock API listening on http://127.0.0.1:${PORT}`);
});
