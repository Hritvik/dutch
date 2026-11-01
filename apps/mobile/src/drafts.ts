/**
 * Local Draft lifecycle for a correction command. A draft keeps the command
 * fields and the source revision, and only acquires an Idempotency Key on the
 * first send. A stale response keeps the draft editable and is never merged
 * silently.
 */
export type DraftState =
  | 'editing'
  | 'readyToReview'
  | 'submitting'
  | 'staleNeedsReview'
  | 'validationBlocked'
  | 'accepted';

export type CorrectionDraft = {
  expenseId: string;
  groupId: string;
  baseRevision: number;
  description: string;
  totalMinorUnits: number;
  state: DraftState;
  idempotencyKey?: string;
};

export function createCorrectionDraft(input: {
  expenseId: string;
  groupId: string;
  baseRevision: number;
  description: string;
  totalMinorUnits: number;
}): CorrectionDraft {
  if (!Number.isInteger(input.totalMinorUnits) || input.totalMinorUnits <= 0) {
    throw new Error('A correction total must be a positive integer.');
  }
  return { ...input, state: 'editing' };
}

/** Editing always keeps the draft; it discards a key that belongs to an old command. */
export function editCorrection(
  draft: CorrectionDraft,
  change: { description?: string; totalMinorUnits?: number }
): CorrectionDraft {
  const total = change.totalMinorUnits ?? draft.totalMinorUnits;
  if (!Number.isInteger(total) || total <= 0) {
    throw new Error('A correction total must be a positive integer.');
  }
  const { idempotencyKey: _discarded, ...rest } = draft;
  return {
    ...rest,
    description: change.description ?? draft.description,
    totalMinorUnits: total,
    state: 'editing'
  };
}

export function reviewCorrection(draft: CorrectionDraft): CorrectionDraft {
  return { ...draft, state: 'readyToReview' };
}

/** The first send freezes the key; later edits start a new command. */
export function submitCorrection(draft: CorrectionDraft, key: string): CorrectionDraft {
  if (draft.state !== 'readyToReview') {
    throw new Error('Review the correction before submitting it.');
  }
  return { ...draft, state: 'submitting', idempotencyKey: draft.idempotencyKey ?? key };
}

export function acceptCorrection(draft: CorrectionDraft): CorrectionDraft {
  return { ...draft, state: 'accepted' };
}

export function blockCorrection(draft: CorrectionDraft): CorrectionDraft {
  return { ...draft, state: 'validationBlocked' };
}

/**
 * A stale response refreshes the source revision in the draft and drops the old
 * key: the next submit is a new command, not a replay. Fields are preserved.
 */
export function markCorrectionStale(
  draft: CorrectionDraft,
  latestRevision: number
): CorrectionDraft {
  const { idempotencyKey: _discarded, ...rest } = draft;
  return { ...rest, baseRevision: latestRevision, state: 'staleNeedsReview' };
}

export function reReviewCorrection(draft: CorrectionDraft): CorrectionDraft {
  if (draft.state !== 'staleNeedsReview') {
    throw new Error('Only a stale draft needs another review.');
  }
  return { ...draft, state: 'readyToReview' };
}

/** Equal split of a total across active members, mirroring the Ledger rule. */
export function equalShares(total: number, memberIds: string[]): { memberId: string; minorUnits: number }[] {
  if (memberIds.length === 0) {
    throw new Error('A correction needs at least one participant.');
  }
  const base = Math.floor(total / memberIds.length);
  const remainder = total % memberIds.length;
  return memberIds.map((memberId, index) => ({
    memberId,
    minorUnits: base + (index < remainder ? 1 : 0)
  }));
}
