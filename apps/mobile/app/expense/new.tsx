import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View
} from 'react-native';

import { createAppConfig } from '../../src/app-config';
import { sessionStore } from '../../src/expo-session-store';
import {
  ExpenseHttpError,
  createExpense,
  fetchExpense,
  formatMinor,
  parseMajorToMinor,
  type ExpenseView
} from '../../src/expenses-client';
import { correctExpense } from '../../src/corrections-client';
import {
  acceptCorrection,
  createCorrectionDraft,
  editCorrection,
  equalShares,
  markCorrectionStale,
  reReviewCorrection,
  retryCorrection,
  reviewCorrection,
  submitCorrection,
  type CorrectionDraft
} from '../../src/drafts';
import { fetchGroup, type GroupView } from '../../src/groups-client';
import { deleteDraft, loadDraft, saveDraft } from '../../src/projection-db';
import { fetchMemberProfile } from '../../src/profile-client';

const config = createAppConfig(process.env);

export default function NewExpenseScreen() {
  const { groupId } = useLocalSearchParams<{ groupId: string }>();
  const router = useRouter();
  const [group, setGroup] = useState<GroupView | null>(null);
  const [memberId, setMemberId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ExpenseView | null>(null);
  const [draft, setDraft] = useState<CorrectionDraft | null>(null);
  const [correctionAmount, setCorrectionAmount] = useState('');
  const [correctionStep, setCorrectionStep] = useState<'edit' | 'review'>('edit');
  const [correcting, setCorrecting] = useState(false);
  const [correctionError, setCorrectionError] = useState<string | null>(null);
  const draftLoaded = useRef(false);
  const idempotencyKey = useRef<string | null>(null);
  const occurredAt = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const material = await sessionStore.load();
        if (!material) {
          setError('Sign in again to add an expense.');
          return;
        }
        const loaded = await fetchGroup(config.apiBaseUrl, material.accessToken, groupId);
        const profile = await fetchMemberProfile(config.apiBaseUrl, material.accessToken);
        if (cancelled) return;
        setToken(material.accessToken);
        setGroup(loaded);
        setMemberId(profile?.memberId ?? null);
      } catch {
        if (!cancelled) setError('We could not load this group.');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [groupId]);

  const submit = useCallback(async () => {
    if (!token || !group || !memberId) return;
    setBusy(true);
    setError(null);
    try {
      const totalMinorUnits = parseMajorToMinor(amount);
      const participants = group.memberships
        .filter((membership) => membership.state === 'ACTIVE')
        .map((membership) => membership.memberId);
      if (!participants.includes(memberId)) {
        setError('Your membership is not active in this group.');
        return;
      }
      let key = idempotencyKey.current;
      if (!key) {
        key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        idempotencyKey.current = key;
        occurredAt.current = new Date().toISOString();
      }
      const created = await createExpense(config.apiBaseUrl, token, groupId, key, {
        description: description.trim(),
        totalMinorUnits,
        currency: group.currency,
        splitMethod: 'EQUAL',
        payers: [{ memberId, minorUnits: totalMinorUnits }],
        participants,
        occurredAt: occurredAt.current ?? new Date().toISOString()
      });
      setResult(created);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'We could not create the expense.');
    } finally {
      setBusy(false);
    }
  }, [amount, description, group, groupId, memberId, token]);

  useEffect(() => {
    if (!memberId || !result || draftLoaded.current) return;
    draftLoaded.current = true;
    void (async () => {
      const saved = await loadDraft<{ amount: string }>(memberId, `correction:${result.id}`);
      if (saved?.amount) {
        setCorrectionAmount(saved.amount);
        setDraft(createCorrectionDraft({
          expenseId: result.id,
          groupId: result.groupId,
          baseRevision: result.revision,
          description: result.description,
          totalMinorUnits: result.totalMinorUnits
        }));
      }
    })();
  }, [memberId, result]);

  useEffect(() => {
    if (!memberId || !result || !draft || draft.state === 'accepted') return;
    void saveDraft(memberId, `correction:${result.id}`, { amount: correctionAmount });
  }, [correctionAmount, draft, memberId, result]);

  const startCorrection = useCallback(() => {
    if (!result) return;
    setCorrectionError(null);
    setCorrectionStep('edit');
    setCorrectionAmount((result.totalMinorUnits / 100).toFixed(2));
    setDraft(createCorrectionDraft({
      expenseId: result.id,
      groupId: result.groupId,
      baseRevision: result.revision,
      description: result.description,
      totalMinorUnits: result.totalMinorUnits
    }));
  }, [result]);

  const reviewCorrectionDraft = useCallback(() => {
    if (!draft) return;
    setCorrectionError(null);
    try {
      const total = parseMajorToMinor(correctionAmount);
      setDraft(reviewCorrection(editCorrection(draft, { totalMinorUnits: total })));
      setCorrectionStep('review');
    } catch (failure) {
      setCorrectionError(failure instanceof Error ? failure.message : 'Enter a valid amount.');
    }
  }, [correctionAmount, draft]);

  const confirmCorrection = useCallback(async () => {
    if (!token || !group || !memberId || !draft || !result) return;
    const current = result;
    if (current.payers.length !== 1) {
      setCorrectionError('This prototype can correct a single-payer expense only.');
      return;
    }
    setCorrecting(true);
    setCorrectionError(null);
    const key = draft.idempotencyKey
      ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const submitting = submitCorrection(draft, key);
    setDraft(submitting);
    try {
      const participants = current.shares.map((share) => share.memberId);
      const corrected = await correctExpense(
        config.apiBaseUrl,
        token,
        group.id,
        draft.expenseId,
        key,
        draft.baseRevision,
        {
          description: draft.description,
          totalMinorUnits: draft.totalMinorUnits,
          currency: current.currency,
          splitMethod: 'EQUAL',
          payers: [{ memberId: current.payers[0].memberId, minorUnits: draft.totalMinorUnits }],
          participants,
          occurredAt: current.occurredAt
        }
      );
      setResult(corrected);
      setDraft(acceptCorrection(submitting));
      await deleteDraft(memberId, `correction:${corrected.id}`);
      setCorrectionStep('edit');
      setCorrectionError(null);
    } catch (failure) {
      if (failure instanceof ExpenseHttpError && failure.code === 'stale_version') {
        setCorrectionStep('edit');
        try {
          const latest = await fetchExpense(config.apiBaseUrl, token, current.groupId, draft.expenseId);
          setResult(latest);
          setDraft(markCorrectionStale(submitting, latest.revision));
          setCorrectionError(
            'This expense changed. Your draft is kept. Review the latest state before submitting.');
        } catch {
          setDraft(markCorrectionStale(submitting, current.revision));
          setCorrectionError(
            'This expense changed. Your draft is kept. Reload the expense before submitting.');
        }
      } else if (failure instanceof ExpenseHttpError && failure.code === 'idempotency_conflict') {
        setDraft(editCorrection(submitting, {}));
        setCorrectionStep('edit');
        setCorrectionError('This draft was already sent with different details. Review it again.');
      } else {
        setDraft(retryCorrection(submitting));
        setCorrectionStep('review');
        setCorrectionError(
          'We could not confirm the correction. Your draft is kept; retry with the same command.');
      }
    } finally {
      setCorrecting(false);
    }
  }, [draft, group, memberId, result, token]);

  if (result) {
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <Text style={styles.eyebrow}>Recorded</Text>
        <Text style={styles.title}>{result.description}</Text>
        <Text style={styles.body}>{formatMinor(result.totalMinorUnits, result.currency)}</Text>

        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Shares</Text>
          {result.shares.map((share) => (
            <Text key={share.memberId} style={styles.row}>
              {shortId(share.memberId)} · {formatMinor(share.minorUnits, result.currency)}
            </Text>
          ))}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Who owes whom</Text>
          {result.entries.length === 0 ? (
            <Text style={styles.muted}>Nothing to settle for this expense.</Text>
          ) : result.entries.map((entry) => (
            <Text key={`${entry.debtor}-${entry.creditor}-${entry.revision}`} style={styles.row}>
              {shortId(entry.debtor)} owes {shortId(entry.creditor)}{' '}
              {formatMinor(entry.minorUnits, result.currency)}
            </Text>
          ))}
        </View>

        <Text style={styles.muted}>
          {result.splitMethod} split · version {result.revision} · {result.entries[0]?.algorithmVersion ?? 'expense-allocation-v1'}
        </Text>

        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Correction</Text>
          {!draft ? (
            <Pressable accessibilityRole="button" onPress={startCorrection} style={styles.secondary}>
              <Text style={styles.secondaryLabel}>Correct this expense</Text>
            </Pressable>
          ) : draft.state === 'accepted' ? (
            <Text style={styles.muted}>Correction recorded at version {result.revision}.</Text>
          ) : draft.state === 'staleNeedsReview' ? (
            <View style={styles.review}>
              {correctionError ? <Text style={styles.notice}>{correctionError}</Text> : null}
              <Text style={styles.reviewValue}>
                Draft kept: {formatMinor(draft.totalMinorUnits, result.currency)}
              </Text>
              <Pressable
                accessibilityRole="button"
                onPress={() => {
                  setDraft(reReviewCorrection(draft));
                  setCorrectionStep('review');
                }}
                style={styles.primary}
              >
                <Text style={styles.primaryLabel}>Review latest</Text>
              </Pressable>
            </View>
          ) : correctionStep === 'review' ? (
            <View style={styles.review}>
              <Text style={styles.sectionLabel}>Scope</Text>
              <Text style={styles.reviewValue}>{group?.name ?? groupId} · {result.currency}</Text>
              <Text style={styles.sectionLabel}>Before</Text>
              <Text style={styles.reviewValue}>
                {formatMinor(result.totalMinorUnits, result.currency)} · you{' '}
                {formatMinor(shareFor(result, memberId), result.currency)}
              </Text>
              <Text style={styles.sectionLabel}>After</Text>
              <Text style={styles.reviewValue}>
                {formatMinor(draft.totalMinorUnits, result.currency)} · you{' '}
                {formatMinor(projectedShare(draft, result, memberId), result.currency)}
              </Text>
              <Text style={styles.sectionLabel}>Every share</Text>
              {draftShares(result, draft).map((share) => (
                <Text key={share.memberId} style={styles.muted}>
                  {shortId(share.memberId)}
                  {share.memberId === memberId ? ' (you)' : ''}:{' '}
                  {formatMinor(share.before, result.currency)} to{' '}
                  {formatMinor(share.after, result.currency)}
                </Text>
              ))}
              <Text style={styles.muted}>
                A correction appends a new revision and reverses the previous entries. Activity
                records both revisions.
              </Text>
              {correctionError ? <Text style={styles.notice}>{correctionError}</Text> : null}
              <Pressable
                accessibilityRole="button"
                disabled={correcting}
                onPress={confirmCorrection}
                style={styles.primary}
              >
                {correcting ? <ActivityIndicator color={PAPER} /> : (
                  <Text style={styles.primaryLabel}>Confirm correction</Text>
                )}
              </Pressable>
              <Pressable
                accessibilityRole="button"
                onPress={() => setCorrectionStep('edit')}
                style={styles.quiet}
              >
                <Text style={styles.quietLabel}>Keep editing</Text>
              </Pressable>
            </View>
          ) : (
            <View style={styles.review}>
              <Text style={styles.label}>New amount ({result.currency})</Text>
              <TextInput
                keyboardType="decimal-pad"
                onChangeText={(value) => {
                  setCorrectionError(null);
                  setCorrectionAmount(value);
                }}
                placeholder={formatMinor(result.totalMinorUnits, result.currency)}
                placeholderTextColor={MUTED}
                style={styles.input}
                testID="correction-amount"
                value={correctionAmount}
              />
              {correctionError ? <Text style={styles.notice}>{correctionError}</Text> : null}
              <Pressable
                accessibilityRole="button"
                onPress={reviewCorrectionDraft}
                style={styles.primary}
              >
                <Text style={styles.primaryLabel}>Review correction</Text>
              </Pressable>
            </View>
          )}
        </View>

        <Pressable
          accessibilityRole="button"
          onPress={() => router.replace(`/group/${groupId}`)}
          style={styles.primary}
        >
          <Text style={styles.primaryLabel}>Done</Text>
        </Pressable>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>New expense</Text>
      <Text style={styles.title}>{group?.name ?? 'Loading…'}</Text>
      <Text style={styles.body}>
        One payer ({memberId ? shortId(memberId) : 'you'}) and an equal split across every active
        member.
      </Text>

      <Text style={styles.label}>Description</Text>
      <TextInput
        maxLength={200}
        onChangeText={(value) => {
          setDescription(value);
          idempotencyKey.current = null;
          occurredAt.current = null;
        }}
        placeholder="Dinner"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="expense-description"
        value={description}
      />

      <Text style={styles.label}>Amount ({group?.currency ?? 'INR'})</Text>
      <TextInput
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          setAmount(value);
          idempotencyKey.current = null;
          occurredAt.current = null;
        }}
        placeholder="0.00"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="expense-amount"
        value={amount}
      />

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <Pressable
        accessibilityRole="button"
        disabled={busy || !token || !group || !memberId}
        onPress={submit}
        style={[styles.primary, busy ? styles.primaryDisabled : null]}
      >
        {busy ? <ActivityIndicator color={PAPER} /> : (
          <Text style={styles.primaryLabel}>Create expense</Text>
        )}
      </Pressable>
    </ScrollView>
  );
}

function shareFor(expense: ExpenseView, memberId: string | null): number {
  return expense.shares.find((share) => share.memberId === memberId)?.minorUnits ?? 0;
}

function projectedShare(draft: CorrectionDraft, expense: ExpenseView,
                        memberId: string | null): number {
  return draftShares(expense, draft)
    .find((share) => share.memberId === memberId)?.after ?? 0;
}

/** The correction replaces the same participant set, so no share moves silently. */
function draftShares(expense: ExpenseView, draft: CorrectionDraft): {
  memberId: string;
  before: number;
  after: number;
}[] {
  const participants = expense.shares.map((share) => share.memberId);
  const after = equalShares(draft.totalMinorUnits, participants);
  return expense.shares.map((share) => ({
    memberId: share.memberId,
    before: share.minorUnits,
    after: after.find((candidate) => candidate.memberId === share.memberId)?.minorUnits ?? 0
  }));
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 7)}…${value.slice(-4)}` : value;
}

const PAPER = '#f6f4ef';
const INK = '#12211c';
const MUTED = '#5f6b64';
const LINE = '#d9d5ca';
const ACCENT = '#0f6b4f';

const styles = StyleSheet.create({
  screen: { backgroundColor: PAPER, paddingBottom: 40, paddingHorizontal: 24, paddingTop: 64 },
  back: { alignSelf: 'flex-start', paddingVertical: 6, paddingRight: 12 },
  backLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' },
  eyebrow: { color: ACCENT, fontSize: 12, fontWeight: '700', letterSpacing: 1.6, marginTop: 18, textTransform: 'uppercase' },
  title: { color: INK, fontSize: 30, fontWeight: '600', letterSpacing: -0.6, lineHeight: 36, marginTop: 8 },
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 10 },
  section: { marginTop: 28 },
  sectionLabel: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, textTransform: 'uppercase' },
  row: { color: INK, fontFamily: 'Courier', fontSize: 13, marginTop: 8 },
  muted: { color: MUTED, fontSize: 12, marginTop: 10 },
  label: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, marginTop: 24, textTransform: 'uppercase' },
  input: {
    backgroundColor: '#fff',
    borderColor: LINE,
    borderRadius: 12,
    borderWidth: 1,
    color: INK,
    fontSize: 16,
    marginTop: 8,
    paddingHorizontal: 14,
    paddingVertical: 12
  },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: {
    alignItems: 'center',
    backgroundColor: ACCENT,
    borderRadius: 14,
    marginTop: 28,
    paddingVertical: 15
  },
  primaryDisabled: { opacity: 0.6 },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' },
  secondary: {
    alignItems: 'center',
    borderColor: ACCENT,
    borderRadius: 14,
    borderWidth: 1,
    marginTop: 8,
    paddingVertical: 13
  },
  secondaryLabel: { color: ACCENT, fontSize: 15, fontWeight: '600' },
  review: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 16, borderWidth: 1, marginTop: 10, padding: 16 },
  reviewValue: { color: INK, fontSize: 15, marginTop: 4 },
  quiet: { alignItems: 'center', marginTop: 14, paddingVertical: 10 },
  quietLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' }
});
