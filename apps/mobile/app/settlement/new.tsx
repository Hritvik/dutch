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
import { formatMinor, parseMajorToMinor } from '../../src/expenses-client';
import { fetchGroup, type GroupView } from '../../src/groups-client';
import { fetchMemberProfile } from '../../src/profile-client';
import { deleteDraft, loadDraft, saveDraft } from '../../src/projection-db';
import {
  SettlementHttpError,
  recordSettlement,
  voidSettlement,
  type SettlementView
} from '../../src/settlements-client';

const config = createAppConfig(process.env);

type Step = 'edit' | 'review' | 'result';

export default function NewSettlementScreen() {
  const { groupId } = useLocalSearchParams<{ groupId: string }>();
  const router = useRouter();
  const [group, setGroup] = useState<GroupView | null>(null);
  const [accountMemberId, setAccountMemberId] = useState<string | null>(null);
  const [payerId, setPayerId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [payeeId, setPayeeId] = useState<string | null>(null);
  const [amount, setAmount] = useState('');
  const [step, setStep] = useState<Step>('edit');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SettlementView | null>(null);
  const [confirmVoid, setConfirmVoid] = useState(false);
  const idempotencyKey = useRef<string | null>(null);
  const occurredAt = useRef<string | null>(null);
  const voidKey = useRef<string | null>(null);
  const draftLoaded = useRef(false);
  const [draftHydrated, setDraftHydrated] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const material = await sessionStore.load();
        if (!material) {
          setError('Sign in again to record a settlement.');
          return;
        }
        const loaded = await fetchGroup(config.apiBaseUrl, material.accessToken, groupId);
        const profile = await fetchMemberProfile(config.apiBaseUrl, material.accessToken);
        if (cancelled) return;
        setToken(material.accessToken);
        setGroup(loaded);
        setAccountMemberId(profile?.memberId ?? null);
        setPayerId(profile?.memberId ?? null);
        const other = loaded.memberships.find(
          (membership) => membership.state === 'ACTIVE'
            && membership.memberId !== profile?.memberId);
        setPayeeId(other?.memberId ?? null);
      } catch {
        if (!cancelled) setError('We could not load this group.');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [groupId]);

  useEffect(() => {
    if (!accountMemberId || draftLoaded.current) return;
    draftLoaded.current = true;
    void (async () => {
      const draft = await loadDraft<{
        payerId: string | null; payeeId: string | null; amount: string;
      }>(accountMemberId, `settlement:${groupId}`);
      if (draft) {
        if (draft.payerId) setPayerId(draft.payerId);
        if (draft.payeeId) setPayeeId(draft.payeeId);
        setAmount(draft.amount);
      }
      setDraftHydrated(true);
    })();
  }, [accountMemberId, groupId]);

  useEffect(() => {
    if (!accountMemberId || !draftHydrated) return;
    void saveDraft(accountMemberId, `settlement:${groupId}`, {
      payerId, payeeId, amount
    });
  }, [accountMemberId, amount, draftHydrated, groupId, payeeId, payerId]);

  const activeMembers = group?.memberships.filter(
    (membership) => membership.state === 'ACTIVE') ?? [];

  const reviewed = useCallback((): number | null => {
    try {
      return parseMajorToMinor(amount);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Enter a valid amount.');
      return null;
    }
  }, [amount]);

  const review = useCallback(() => {
    setError(null);
    if (!payeeId || !payerId) {
      setError('Choose who paid and who received the money.');
      return;
    }
    if (payeeId === payerId) {
      setError('A settlement needs two different members.');
      return;
    }
    if (reviewed() === null) return;
    setStep('review');
  }, [payerId, payeeId, reviewed]);

  const submit = useCallback(async () => {
    if (!token || !group || !payerId || !payeeId) return;
    const totalMinorUnits = reviewed();
    if (totalMinorUnits === null) return;
    setBusy(true);
    setError(null);
    let key = idempotencyKey.current;
    if (!key) {
      key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      idempotencyKey.current = key;
      occurredAt.current = new Date().toISOString();
    }
    try {
      const recorded = await recordSettlement(config.apiBaseUrl, token, group.id, key, {
        payerMemberId: payerId,
        payeeMemberId: payeeId,
        totalMinorUnits,
        currency: group.currency,
        occurredAt: occurredAt.current ?? new Date().toISOString()
      });
      setResult(recorded);
      setStep('result');
      await deleteDraft(accountMemberId ?? payerId, `settlement:${groupId}`);
    } catch (failure) {
      // Keep the key and occurredAt: an ambiguous failure must replay the same
      // command rather than record a second settlement.
      if (failure instanceof SettlementHttpError && failure.code === 'idempotency_conflict') {
        setError('This draft was sent with a different amount. Review it again.');
      } else if (failure instanceof SettlementHttpError && failure.code === 'conflict') {
        setError('The group state does not allow that settlement.');
      } else if (failure instanceof SettlementHttpError && failure.code === 'not_found') {
        setError('That group or member is no longer available.');
      } else {
        setError('We could not record the settlement. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }, [accountMemberId, group, groupId, payeeId, payerId, reviewed, token]);

  const voidIt = useCallback(async () => {
    if (!token || !group || !result) return;
    if (!confirmVoid) {
      setConfirmVoid(true);
      return;
    }
    setBusy(true);
    setError(null);
    let key = voidKey.current;
    if (!key) {
      key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      voidKey.current = key;
    }
    try {
      setResult(await voidSettlement(config.apiBaseUrl, token, group.id, result.id, key));
      setConfirmVoid(false);
    } catch (failure) {
      // Keep the key so an ambiguous failure replays the same void.
      if (failure instanceof SettlementHttpError && failure.code === 'conflict') {
        setError('This settlement was already voided.');
      } else {
        setError('We could not void the settlement. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }, [confirmVoid, group, result, token]);

  if (!group) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={INK} />
        {error ? <Text style={styles.notice}>{error}</Text> : null}
      </View>
    );
  }

  if (step === 'result' && result) {
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <Text style={styles.eyebrow}>{result.status === 'VOIDED' ? 'Voided' : 'Recorded'}</Text>
        <Text style={styles.title}>{formatMinor(result.totalMinorUnits, result.currency)}</Text>
        <Text style={styles.body}>
          {shortId(result.payer)} paid {shortId(result.payee)}.
        </Text>

        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Applied to</Text>
          {result.allocations.map((allocation) => (
            <View key={allocation.scopeId} style={styles.row}>
              <Text style={styles.rowText}>{allocation.scopeId}</Text>
              <Text style={styles.muted}>{formatMinor(allocation.minorUnits, allocation.currency)}</Text>
            </View>
          ))}
        </View>

        {error ? <Text style={styles.notice}>{error}</Text> : null}

        {result.status === 'ACTIVE' ? (
          <Pressable
            accessibilityRole="button"
            disabled={busy}
            onPress={voidIt}
            style={styles.danger}
          >
            <Text style={styles.dangerLabel}>
              {confirmVoid ? 'Confirm void' : 'Void settlement'}
            </Text>
          </Pressable>
        ) : null}

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

  if (step === 'review') {
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <Text style={styles.eyebrow}>Review settlement</Text>
        <Text style={styles.title}>{formatMinor(parseMajorToMinor(amount), group.currency)}</Text>
        <View style={styles.card}>
          <Text style={styles.sectionLabel}>Scope</Text>
          <Text style={styles.cardValue}>{group.name} · {group.currency}</Text>
          <Text style={styles.sectionLabel}>From</Text>
          <Text style={styles.cardValue}>{shortId(payerId ?? '')}</Text>
          <Text style={styles.sectionLabel}>To</Text>
          <Text style={styles.cardValue}>{shortId(payeeId ?? '')}</Text>
          <Text style={styles.muted}>
            Recording this reduces the payer&apos;s balance in this group. It can be voided once,
            and every change appears in Activity.
          </Text>
        </View>

        {error ? <Text style={styles.notice}>{error}</Text> : null}

        <Pressable
          accessibilityRole="button"
          disabled={busy}
          onPress={submit}
          style={styles.primary}
        >
          {busy ? <ActivityIndicator color={PAPER} /> : (
            <Text style={styles.primaryLabel}>Confirm settlement</Text>
          )}
        </Pressable>
        <Pressable accessibilityRole="button" onPress={() => setStep('edit')} style={styles.quiet}>
          <Text style={styles.quietLabel}>Keep editing</Text>
        </Pressable>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Record settlement</Text>
      <Text style={styles.title}>{group.name}</Text>
      <Text style={styles.body}>
        A payment made outside Dutch. It applies to this group only, in {group.currency}.
      </Text>

      <Text style={styles.label}>Who paid</Text>
      <View style={styles.choices}>
        {activeMembers.map((membership) => (
          <Pressable
            key={membership.memberId}
            accessibilityRole="button"
            onPress={() => {
              if (membership.memberId === payerId) return;
              setPayerId(membership.memberId);
              idempotencyKey.current = null;
              occurredAt.current = null;
            }}
            style={[styles.choice, membership.memberId === payerId ? styles.choiceOn : null]}
          >
            <Text style={membership.memberId === payerId ? styles.choiceLabelOn : styles.choiceLabel}>
              {shortId(membership.memberId)}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>Who received it</Text>
      <View style={styles.choices}>
        {activeMembers.map((membership) => (
          <Pressable
            key={membership.memberId}
            accessibilityRole="button"
            onPress={() => {
              if (membership.memberId === payeeId) return;
              setPayeeId(membership.memberId);
              idempotencyKey.current = null;
              occurredAt.current = null;
            }}
            style={[styles.choice, membership.memberId === payeeId ? styles.choiceOn : null]}
          >
            <Text style={membership.memberId === payeeId ? styles.choiceLabelOn : styles.choiceLabel}>
              {shortId(membership.memberId)}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>Amount ({group.currency})</Text>
      <TextInput
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          if (value !== amount) {
            idempotencyKey.current = null;
            occurredAt.current = null;
          }
          setAmount(value);
        }}
        placeholder="0.00"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="settlement-amount"
        value={amount}
      />

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <Pressable
        accessibilityRole="button"
        disabled={busy || !token || !payerId || !payeeId}
        onPress={review}
        style={styles.primary}
      >
        <Text style={styles.primaryLabel}>Review settlement</Text>
      </Pressable>
    </ScrollView>
  );
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
  centered: { alignItems: 'center', backgroundColor: PAPER, flex: 1, justifyContent: 'center' },
  back: { alignSelf: 'flex-start', paddingVertical: 6, paddingRight: 12 },
  backLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' },
  eyebrow: { color: ACCENT, fontSize: 12, fontWeight: '700', letterSpacing: 1.6, marginTop: 18, textTransform: 'uppercase' },
  title: { color: INK, fontSize: 30, fontWeight: '600', letterSpacing: -0.6, lineHeight: 36, marginTop: 8 },
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 10 },
  section: { marginTop: 24 },
  sectionLabel: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, marginTop: 14, textTransform: 'uppercase' },
  row: { borderBottomColor: LINE, borderBottomWidth: 1, paddingVertical: 10 },
  rowText: { color: INK, fontFamily: 'Courier', fontSize: 12 },
  muted: { color: MUTED, fontSize: 12, lineHeight: 18, marginTop: 8 },
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
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  choice: { borderColor: LINE, borderRadius: 999, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 8 },
  choiceOn: { backgroundColor: ACCENT, borderColor: ACCENT },
  choiceLabel: { color: INK, fontSize: 13 },
  choiceLabelOn: { color: PAPER, fontSize: 13, fontWeight: '600' },
  card: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 16, borderWidth: 1, marginTop: 24, padding: 18 },
  cardValue: { color: INK, fontSize: 16, marginTop: 4 },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: {
    alignItems: 'center',
    backgroundColor: ACCENT,
    borderRadius: 14,
    marginTop: 28,
    paddingVertical: 15
  },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' },
  danger: {
    alignItems: 'center',
    borderColor: '#8a3b2a',
    borderRadius: 14,
    borderWidth: 1,
    marginTop: 24,
    paddingVertical: 14
  },
  dangerLabel: { color: '#8a3b2a', fontSize: 15, fontWeight: '600' },
  quiet: { alignItems: 'center', marginTop: 16, paddingVertical: 10 },
  quietLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' }
});
