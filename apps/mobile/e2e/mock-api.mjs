import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

const PORT = Number(process.env.PORT ?? 4010);
const REQUIRED_READS = 2;
const ADMIN_SUBJECT = 'aps_dev_0123456789012345678901';
const MEMBER_SUBJECT = 'aps_member_012345678901234567890';
const ADMIN_MEMBER = 'mem_dev_012345678901234567';
const MEMBER_MEMBER = 'mem_member_012345678901234567';
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
        { memberId: ADMIN_MEMBER, role: 'ADMIN', state: 'ACTIVE' },
        { memberId: MEMBER_MEMBER, role: 'MEMBER', state: 'ACTIVE' },
        { memberId: 'mem_112345678901234567', role: 'MEMBER', state: 'INACTIVE' }
      ]
    }
  ];
}

let groups = seedGroups();
const expenses = new Map();

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) });
  response.end(payload);
}

function subjectFrom(request) {
  const token = String(request.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
  const payload = token.split('.')[1];
  if (!payload) return null;
  try {
    return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')).sub ?? null;
  } catch {
    return null;
  }
}

function memberFor(subject) {
  return subject === MEMBER_SUBJECT ? MEMBER_MEMBER : ADMIN_MEMBER;
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
    expenses.clear();
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
      memberships: [{ memberId: memberFor(subjectFrom(request)), role: 'ADMIN', state: 'ACTIVE' }]
    };
    groups = [group, ...groups];
    json(response, 200, { data: group });
    return;
  }
  const url = new URL(request.url, 'http://localhost');
  const parts = url.pathname.split('/').filter(Boolean);
  if (parts[0] === 'api' && parts[1] === 'v1' && parts[2] === 'groups' && parts[3]) {
    const group = groups.find((entry) => entry.id === parts[3]);
    if (!group) {
      json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
      return;
    }
    const membershipId = parts[5];
    const membership = membershipId
      ? group.memberships.find((entry) => entry.memberId === membershipId)
      : null;
    if (request.method === 'GET' && parts.length === 4) {
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'expenses' && parts.length === 5) {
      const idempotencyKey = request.headers['idempotency-key'];
      if (idempotencyKey && expenses.has(idempotencyKey)) {
        json(response, 200, { data: expenses.get(idempotencyKey) });
        return;
      }
      const payload = JSON.parse((await readBody(request)) || '{}');
      const total = Number(payload.totalMinorUnits);
      const participants = payload.participants ?? [];
      const payers = payload.payers ?? [];
      const base = Math.floor(total / participants.length);
      const remainder = total % participants.length;
      const shares = participants.map((memberId, index) => ({
        memberId,
        minorUnits: base + (index < remainder ? 1 : 0)
      }));
      const positions = new Map();
      for (const payer of payers) {
        positions.set(payer.memberId, (positions.get(payer.memberId) ?? 0) + payer.minorUnits);
      }
      for (const share of shares) {
        positions.set(share.memberId, (positions.get(share.memberId) ?? 0) - share.minorUnits);
      }
      const debtors = [...positions].filter(([, value]) => value < 0)
        .map(([memberId, value]) => ({ memberId, amount: -value }))
        .sort((a, b) => b.amount - a.amount || a.memberId.localeCompare(b.memberId));
      const creditors = [...positions].filter(([, value]) => value > 0)
        .map(([memberId, value]) => ({ memberId, amount: value }))
        .sort((a, b) => b.amount - a.amount || a.memberId.localeCompare(b.memberId));
      const expenseId = `exp_${randomUUID().replaceAll('-', '')}`;
      const entries = [];
      let debtorIndex = 0;
      let creditorIndex = 0;
      while (debtorIndex < debtors.length && creditorIndex < creditors.length) {
        const amount = Math.min(debtors[debtorIndex].amount, creditors[creditorIndex].amount);
        if (amount > 0) {
          entries.push({
            expenseId,
            revision: 1,
            debtor: debtors[debtorIndex].memberId,
            creditor: creditors[creditorIndex].memberId,
            minorUnits: amount,
            currency: payload.currency,
            algorithmVersion: 'expense-allocation-v1'
          });
        }
        debtors[debtorIndex].amount -= amount;
        creditors[creditorIndex].amount -= amount;
        if (debtors[debtorIndex].amount === 0) debtorIndex += 1;
        if (creditors[creditorIndex].amount === 0) creditorIndex += 1;
      }
      const expense = {
        id: expenseId,
        groupId: group.id,
        description: payload.description,
        totalMinorUnits: total,
        currency: payload.currency,
        splitMethod: payload.splitMethod,
        payers,
        shares,
        entries,
        occurredAt: payload.occurredAt,
        revision: 1
      };
      if (idempotencyKey) expenses.set(idempotencyKey, expense);
      json(response, 200, { data: expense });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'members' && parts.length === 5) {
      const payload = JSON.parse((await readBody(request)) || '{}');
      group.memberships.push({
        memberId: String(payload.memberId),
        role: payload.role === 'ADMIN' ? 'ADMIN' : 'MEMBER',
        state: payload.placeholder ? 'PLACEHOLDER' : 'INVITED'
      });
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'members' && parts[6] === 'activate' && membership) {
      membership.state = 'ACTIVE';
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'DELETE' && parts[4] === 'members' && membership) {
      membership.state = 'INACTIVE';
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'PATCH' && parts[4] === 'members' && membership) {
      const payload = JSON.parse((await readBody(request)) || '{}');
      membership.role = payload.role === 'ADMIN' ? 'ADMIN' : 'MEMBER';
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'archive') {
      group.state = 'ARCHIVED';
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'reopen') {
      group.state = 'ACTIVE';
      group.version += 1;
      json(response, 200, { data: group });
      return;
    }
  }
  if (request.method === 'GET' && request.url === '/api/v1/me') {
    if (profileStateReads > REQUIRED_READS) {
      json(response, 200, {
        data: {
          memberId: memberFor(subjectFrom(request)),
          displayName: subjectFrom(request) === MEMBER_SUBJECT ? 'Bela' : 'Ada',
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
