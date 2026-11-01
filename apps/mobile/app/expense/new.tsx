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
  createExpense,
  formatMinor,
  parseMajorToMinor,
  type ExpenseView
} from '../../src/expenses-client';
import { fetchGroup, type GroupView } from '../../src/groups-client';
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
  const idempotencyKey = useRef<string | null>(null);

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
      const key = idempotencyKey.current
        ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      idempotencyKey.current = key;
      const created = await createExpense(config.apiBaseUrl, token, groupId, key, {
        description: description.trim(),
        totalMinorUnits,
        currency: group.currency,
        splitMethod: 'EQUAL',
        payers: [{ memberId, minorUnits: totalMinorUnits }],
        participants,
        occurredAt: new Date().toISOString()
      });
      setResult(created);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'We could not create the expense.');
    } finally {
      setBusy(false);
    }
  }, [amount, description, group, groupId, memberId, token]);

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
        onChangeText={setDescription}
        placeholder="Dinner"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="expense-description"
        value={description}
      />

      <Text style={styles.label}>Amount ({group?.currency ?? 'INR'})</Text>
      <TextInput
        keyboardType="decimal-pad"
        onChangeText={setAmount}
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
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' }
});
