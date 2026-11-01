import assert from 'node:assert/strict';
import test from 'node:test';

import {
  acceptCorrection,
  blockCorrection,
  createCorrectionDraft,
  editCorrection,
  equalShares,
  markCorrectionStale,
  reReviewCorrection,
  reviewCorrection,
  retryCorrection,
  submitCorrection
} from './drafts';

const draft = createCorrectionDraft({
  expenseId: 'exp_000000000000000001',
  groupId: 'grp_000000000000000001',
  baseRevision: 1,
  description: 'Dinner',
  totalMinorUnits: 100
});

test('a correction can only be submitted after review', () => {
  assert.throws(() => submitCorrection(draft, 'key-1'));
  const submitting = submitCorrection(reviewCorrection(draft), 'key-1');
  assert.equal(submitting.state, 'submitting');
  assert.equal(submitting.idempotencyKey, 'key-1');
});

test('editing keeps the draft and discards an old command key', () => {
  const ready = submitCorrection(reviewCorrection(draft), 'key-1');
  const edited = editCorrection(ready, { totalMinorUnits: 140 });
  assert.equal(edited.state, 'editing');
  assert.equal(edited.totalMinorUnits, 140);
  assert.equal(edited.idempotencyKey, undefined);
});

test('a stale response preserves fields and refreshes the source revision', () => {
  const submitting = submitCorrection(reviewCorrection(draft), 'key-1');
  const stale = markCorrectionStale(submitting, 2);
  assert.equal(stale.state, 'staleNeedsReview');
  assert.equal(stale.baseRevision, 2);
  assert.equal(stale.totalMinorUnits, 100);
  assert.equal(stale.description, 'Dinner');
  assert.equal(stale.idempotencyKey, undefined);
  assert.equal(reReviewCorrection(stale).state, 'readyToReview');
  assert.throws(() => reReviewCorrection(draft));
});

test('an ambiguous failure keeps the command key for the retry', () => {
  const submitting = submitCorrection(reviewCorrection(draft), 'key-1');
  const retry = retryCorrection(submitting);
  assert.equal(retry.state, 'readyToReview');
  assert.equal(retry.idempotencyKey, 'key-1');
  assert.equal(submitCorrection(retry, 'ignored').idempotencyKey, 'key-1');
  assert.throws(() => retryCorrection(draft));
});

test('acceptance and validation outcomes are terminal for the send', () => {
  const submitting = submitCorrection(reviewCorrection(draft), 'key-1');
  assert.equal(acceptCorrection(submitting).state, 'accepted');
  assert.equal(blockCorrection(submitting).state, 'validationBlocked');
});

test('equal shares use largest remainder in participant order', () => {
  assert.deepEqual(equalShares(100, ['a', 'b']), [
    { memberId: 'a', minorUnits: 50 },
    { memberId: 'b', minorUnits: 50 }
  ]);
  assert.deepEqual(equalShares(100, ['a', 'b', 'c']), [
    { memberId: 'a', minorUnits: 34 },
    { memberId: 'b', minorUnits: 33 },
    { memberId: 'c', minorUnits: 33 }
  ]);
});
