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
let expensesByKey = new Map();
let expensesById = new Map();
let settlements = new Map();
let activity = [];
let settlementIdempotency = new Map();
let staleInjected = new Set();
let directExpensesByKey = new Map();
let directExpensesById = new Map();
let directActivity = [];
let seriesById = new Map();
let occurrencesBySeries = new Map();

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

function buildExpense(group, payload, expenseId, revision) {
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
  const entries = [];
  let debtorIndex = 0;
  let creditorIndex = 0;
  while (debtorIndex < debtors.length && creditorIndex < creditors.length) {
    const amount = Math.min(debtors[debtorIndex].amount, creditors[creditorIndex].amount);
    if (amount > 0) {
      entries.push({
        expenseId,
        revision,
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
  return {
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
    revision
  };
}

function recordActivity(group, action, targetId, targetRevision, details) {
  if (!group) return;
  activity.push({
    activityId: `act_${randomUUID().replaceAll('-', '')}`,
    scopeId: `group:${group.id}:${group.currency}`,
    actor: ADMIN_MEMBER,
    action,
    targetId,
    targetRevision,
    occurredAt: new Date().toISOString(),
    details
  });
}

const server = createServer(async (request, response) => {
  if (request.method === 'POST' && request.url === '/reset') {
    profileStateReads = 0;
    groups = seedGroups();
    expensesByKey = new Map();
    expensesById = new Map();
    settlements = new Map();
    activity = [];
    settlementIdempotency = new Map();
    staleInjected = new Set();
    directExpensesByKey = new Map();
    directExpensesById = new Map();
    directActivity = [];
    seriesById = new Map();
    occurrencesBySeries = new Map();
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
  if (parts[0] === 'api' && parts[1] === 'v1' && parts[2] === 'series') {
    if (request.method === 'POST' && parts.length === 3) {
      const payload = JSON.parse((await readBody(request)) || '{}');
      const id = `ser_${randomUUID().replaceAll('-', '')}`;
      const series = {
        id,
        scope: payload.scope,
        description: payload.description,
        totalMinorUnits: Number(payload.totalMinorUnits),
        currency: payload.currency,
        splitMethod: payload.splitMethod,
        payers: payload.payers,
        participants: payload.participants,
        cadence: payload.cadence,
        dueDay: Number(payload.dueDay),
        timezone: payload.timezone,
        status: 'ACTIVE',
        nextPeriodStart: payload.nextPeriodStart,
        version: 1
      };
      seriesById.set(id, series);
      occurrencesBySeries.set(id, []);
      json(response, 200, { data: series });
      return;
    }
    if (request.method === 'GET' && parts.length === 3) {
      json(response, 200, { data: [...seriesById.values()] });
      return;
    }
    if (parts[3] === 'occurrences' && parts[4] && parts[5]) {
      const occurrenceId = parts[4];
      let owner;
      let occurrence;
      for (const [seriesId, list] of occurrencesBySeries.entries()) {
        const found = list.find((entry) => entry.id === occurrenceId);
        if (found) {
          owner = seriesById.get(seriesId);
          occurrence = found;
          break;
        }
      }
      if (!owner || !occurrence) {
        json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
        return;
      }
      if (request.method === 'POST' && parts[5] === 'skip') {
        if (occurrence.status !== 'PENDING') {
          json(response, 409, { error: { code: 'conflict', message: 'No longer pending.' } });
          return;
        }
        occurrence.status = 'SKIPPED';
        json(response, 200, { data: occurrence });
        return;
      }
      if (request.method === 'POST' && parts[5] === 'submit') {
        if (occurrence.status !== 'PENDING') {
          json(response, 409, { error: { code: 'conflict', message: 'No longer pending.' } });
          return;
        }
        const expenseId = `exp_${randomUUID().replaceAll('-', '')}`;
        const participants = owner.participants.map((participant) => participant.memberId);
        const expense = buildExpense({ id: owner.scope.groupId }, {
          description: owner.description,
          totalMinorUnits: owner.totalMinorUnits,
          currency: owner.currency,
          splitMethod: owner.splitMethod,
          payers: owner.payers,
          participants,
          occurredAt: occurrence.dueAt
        }, expenseId, 1);
        expensesById.set(expenseId, expense);
        occurrence.status = 'SUBMITTED';
        occurrence.expenseId = expenseId;
        recordActivity(groups.find((group) => group.id === owner.scope.groupId),
          'EXPENSE_CREATED', expenseId, 1, {
            description: owner.description,
            totalMinorUnits: String(owner.totalMinorUnits),
            currency: owner.currency
          });
        json(response, 200, { data: { occurrence, expenseId } });
        return;
      }
    }
    const series = seriesById.get(parts[3]);
    if (!series) {
      json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
      return;
    }
    if (request.method === 'GET' && parts.length === 4) {
      json(response, 200, { data: series });
      return;
    }
    if (request.method === 'GET' && parts[4] === 'occurrences' && parts.length === 5) {
      json(response, 200, { data: occurrencesBySeries.get(series.id) ?? [] });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'occurrences' && parts.length === 5) {
      const expected = String(request.headers['if-match'] ?? '').replaceAll('"', '');
      if (Number(expected) !== series.version) {
        json(response, 409, {
          error: { code: 'stale_version', message: 'The group changed. Refresh and retry.' }
        });
        return;
      }
      const start = new Date(`${series.nextPeriodStart}T00:00:00Z`);
      const end = series.cadence === 'MONTHLY'
        ? new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0))
        : new Date(start.getTime() + 6 * 86400000);
      const due = series.cadence === 'MONTHLY'
        ? new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(),
            Math.min(series.dueDay, new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate())))
        : new Date(start.getTime() + (series.dueDay - 1) * 86400000);
      const occurrence = {
        id: `occ_${randomUUID().replaceAll('-', '')}`,
        seriesId: series.id,
        templateVersion: series.version,
        periodStart: series.nextPeriodStart,
        periodEnd: end.toISOString().slice(0, 10),
        dueAt: due.toISOString().replace('.000Z', 'Z'),
        status: 'PENDING',
        expenseId: null
      };
      const next = series.cadence === 'MONTHLY'
        ? new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 1))
        : new Date(start.getTime() + 7 * 86400000);
      series.nextPeriodStart = next.toISOString().slice(0, 10);
      series.version += 1;
      occurrencesBySeries.get(series.id).push(occurrence);
      json(response, 200, { data: occurrence });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'status' && parts.length === 5) {
      const expected = String(request.headers['if-match'] ?? '').replaceAll('"', '');
      if (Number(expected) !== series.version) {
        json(response, 409, {
          error: { code: 'stale_version', message: 'The series changed.' }
        });
        return;
      }
      const payload = JSON.parse((await readBody(request)) || '{}');
      series.status = payload.action === 'PAUSE' ? 'PAUSED'
        : payload.action === 'RESUME' ? 'ACTIVE' : 'ARCHIVED';
      series.version += 1;
      json(response, 200, { data: series });
      return;
    }
  }
  if (parts[0] === 'api' && parts[1] === 'v1' && parts[2] === 'groups' && parts[4] === 'series') {
    const group = groups.find((entry) => entry.id === parts[3]);
    json(response, 200, {
      data: [...seriesById.values()].filter((entry) => entry.scope.groupId === group?.id)
    });
    return;
  }
  if (parts[0] === 'api' && parts[1] === 'v1' && parts[2] === 'direct-expenses') {
    if (request.method === 'POST' && parts.length === 3) {
      const idempotencyKey = request.headers['idempotency-key'];
      if (idempotencyKey && directExpensesByKey.has(idempotencyKey)) {
        json(response, 200, { data: directExpensesByKey.get(idempotencyKey) });
        return;
      }
      const payload = JSON.parse((await readBody(request)) || '{}');
      const expenseId = `exp_${randomUUID().replaceAll('-', '')}`;
      const base = buildExpense({ id: 'direct' }, payload, expenseId, 1);
      const streamIds = [...new Set(base.entries.map((entry) => {
        const [first, second] = [entry.debtor, entry.creditor].sort();
        return `direct:${first}:${second}:${payload.currency}`;
      }))];
      const direct = {
        id: expenseId,
        description: payload.description,
        totalMinorUnits: Number(payload.totalMinorUnits),
        currency: payload.currency,
        splitMethod: payload.splitMethod,
        payers: base.payers,
        shares: base.shares,
        entries: base.entries,
        streamIds,
        occurredAt: payload.occurredAt,
        revision: 1
      };
      directExpensesById.set(expenseId, direct);
      if (idempotencyKey) directExpensesByKey.set(idempotencyKey, direct);
      directActivity.push({
        activityId: `act_${randomUUID().replaceAll('-', '')}`,
        scopeId: `direct-expense:${expenseId}`,
        actor: memberFor(subjectFrom(request)),
        action: 'EXPENSE_CREATED',
        targetId: expenseId,
        targetRevision: 1,
        occurredAt: new Date().toISOString(),
        details: {
          description: payload.description,
          totalMinorUnits: String(payload.totalMinorUnits),
          currency: payload.currency
        }
      });
      json(response, 200, { data: direct });
      return;
    }
    if (request.method === 'GET' && parts.length === 4) {
      const direct = directExpensesById.get(parts[3]);
      if (!direct) {
        json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
        return;
      }
      json(response, 200, { data: direct });
      return;
    }
    if (request.method === 'GET' && parts[4] === 'activity' && parts.length === 5) {
      json(response, 200, {
        data: directActivity.filter((entry) => entry.targetId === parts[3])
      });
      return;
    }
  }
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
      if (idempotencyKey && expensesByKey.has(idempotencyKey)) {
        json(response, 200, { data: expensesByKey.get(idempotencyKey) });
        return;
      }
      const payload = JSON.parse((await readBody(request)) || '{}');
      const expenseId = `exp_${randomUUID().replaceAll('-', '')}`;
      const expense = buildExpense(group, payload, expenseId, 1);
      if (idempotencyKey) expensesByKey.set(idempotencyKey, expense);
      expensesById.set(expenseId, expense);
      recordActivity(group, 'EXPENSE_CREATED', expenseId, 1, {
        description: payload.description,
        totalMinorUnits: String(payload.totalMinorUnits),
        currency: payload.currency
      });
      json(response, 200, { data: expense });
      return;
    }
    if (request.method === 'GET' && parts[4] === 'expenses' && parts.length === 6) {
      const expense = expensesById.get(parts[5]);
      if (!expense || expense.groupId !== group.id) {
        json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
        return;
      }
      json(response, 200, { data: expense });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'expenses' && parts[6] === 'corrections') {
      const expenseId = parts[5];
      const current = expensesById.get(expenseId);
      if (!current || current.groupId !== group.id) {
        json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
        return;
      }
      const correctionPayload = JSON.parse((await readBody(request)) || '{}');
      // Fault injection: the first 999.00 attempt answers stale_version so the
      // device flow can prove that the Local Draft survives and recovers after
      // the refresh; the retry then succeeds.
      if (Number(correctionPayload.totalMinorUnits) === 99900 && !staleInjected.has(expenseId)) {
        staleInjected.add(expenseId);
        json(response, 409, {
          error: { code: 'stale_version', message: 'The group changed. Refresh and retry.' }
        });
        return;
      }
      const ifMatch = String(request.headers['if-match'] ?? '').replaceAll('"', '');
      if (Number(ifMatch) !== current.revision) {
        json(response, 409, {
          error: { code: 'stale_version', message: 'The group changed. Refresh and retry.' }
        });
        return;
      }
      const idempotencyKey = request.headers['idempotency-key'];
      if (idempotencyKey && expensesByKey.has(idempotencyKey)) {
        json(response, 200, { data: expensesByKey.get(idempotencyKey) });
        return;
      }
      const payload = correctionPayload;
      const revision = current.revision + 1;
      const corrected = buildExpense(group, payload, expenseId, revision);
      if (idempotencyKey) expensesByKey.set(idempotencyKey, corrected);
      expensesById.set(expenseId, corrected);
      recordActivity(group, 'EXPENSE_CORRECTED', expenseId, revision, {
        description: payload.description,
        totalMinorUnits: String(payload.totalMinorUnits),
        currency: payload.currency,
        fromRevision: String(current.revision),
        toRevision: String(revision)
      });
      json(response, 200, { data: corrected });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'settlements' && parts.length === 5) {
      const idempotencyKey = request.headers['idempotency-key'];
      if (idempotencyKey && settlementIdempotency.has(idempotencyKey)) {
        json(response, 200, { data: settlementIdempotency.get(idempotencyKey) });
        return;
      }
      const payload = JSON.parse((await readBody(request)) || '{}');
      const settlementId = `stl_${randomUUID().replaceAll('-', '')}`;
      const total = Number(payload.totalMinorUnits);
      const allocations = payload.allocations?.length
        ? payload.allocations.map((allocation) => ({
            scopeId: allocation.scopeId,
            currency: payload.currency,
            minorUnits: Number(allocation.minorUnits),
            payer: payload.payerMemberId,
            payee: payload.payeeMemberId
          }))
        : [{
            scopeId: `group:${group.id}:${group.currency}`,
            currency: payload.currency,
            minorUnits: total,
            payer: payload.payerMemberId,
            payee: payload.payeeMemberId
          }];
      const settlement = {
        id: settlementId,
        groupId: group.id,
        payer: payload.payerMemberId,
        payee: payload.payeeMemberId,
        totalMinorUnits: total,
        currency: payload.currency,
        status: 'ACTIVE',
        allocations,
        occurredAt: payload.occurredAt
      };
      settlements.set(settlementId, settlement);
      if (idempotencyKey) settlementIdempotency.set(idempotencyKey, settlement);
      recordActivity(group, 'SETTLEMENT_RECORDED', settlementId, 1, {
        totalMinorUnits: String(total),
        currency: payload.currency,
        status: 'ACTIVE'
      });
      json(response, 200, { data: settlement });
      return;
    }
    if (request.method === 'POST' && parts[4] === 'settlements' && parts[6] === 'void' && parts[5]) {
      const settlement = settlements.get(parts[5]);
      if (!settlement || settlement.groupId !== group.id) {
        json(response, 404, { error: { code: 'not_found', message: 'Resource was not found' } });
        return;
      }
      const idempotencyKey = request.headers['idempotency-key'];
      if (idempotencyKey && settlementIdempotency.has(idempotencyKey)) {
        json(response, 200, { data: settlementIdempotency.get(idempotencyKey) });
        return;
      }
      if (settlement.status === 'VOIDED') {
        json(response, 409, {
          error: { code: 'conflict', message: 'The request conflicts with the current state.' }
        });
        return;
      }
      settlement.status = 'VOIDED';
      if (idempotencyKey) settlementIdempotency.set(idempotencyKey, settlement);
      recordActivity(group, 'SETTLEMENT_VOIDED', settlement.id, 2, {
        totalMinorUnits: String(settlement.totalMinorUnits),
        currency: settlement.currency,
        status: 'VOIDED'
      });
      json(response, 200, { data: settlement });
      return;
    }
    if (request.method === 'GET' && parts[4] === 'activity' && parts.length === 5) {
      json(response, 200, {
        data: activity.filter((entry) => entry.scopeId === `group:${group.id}:${group.currency}`)
      });
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
