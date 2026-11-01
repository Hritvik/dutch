import type { CreateExpenseRequest, ExpenseView } from './expenses-client';
import { ExpenseContractError, ExpenseHttpError, parseExpenseView } from './expenses-client';

export type ApiFetch = (input: string, init?: RequestInit) => Promise<Response>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function apiUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}${path}`;
}

async function toError(response: Response): Promise<ExpenseHttpError> {
  let code = 'error';
  try {
    const payload = await response.json();
    if (isRecord(payload) && isRecord(payload.error) && typeof payload.error.code === 'string') {
      code = payload.error.code;
    }
  } catch {
    // Keep the generic code when the body is not JSON.
  }
  return new ExpenseHttpError(response.status, `Correction request failed with ${response.status}.`, code);
}

/**
 * Sends one exact replacement revision. The caller keeps the draft until the
 * server accepts it; a 409 stale_version leaves it intact for review.
 */
export async function correctExpense(
  baseUrl: string,
  accessToken: string,
  groupId: string,
  expenseId: string,
  idempotencyKey: string,
  expectedRevision: number,
  request: CreateExpenseRequest,
  fetchImpl: ApiFetch = fetch
): Promise<ExpenseView> {
  const response = await fetchImpl(
    apiUrl(baseUrl, `/api/v1/groups/${groupId}/expenses/${expenseId}/corrections`),
    {
      method: 'POST',
      headers: {
        accept: 'application/json',
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
        'idempotency-key': idempotencyKey,
        'if-match': `"${expectedRevision}"`
      },
      body: JSON.stringify(request)
    }
  );
  if (!response.ok) {
    throw await toError(response);
  }
  const payload = await response.json();
  if (!isRecord(payload)) {
    throw new ExpenseContractError('Correction response is missing data.');
  }
  return parseExpenseView(payload.data);
}
