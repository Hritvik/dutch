import { useRouter } from 'expo-router';
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
import { createDirectExpense, type DirectExpenseView } from '../../src/direct-expenses-client';
import { formatMinor, parseMajorToMinor } from '../../src/expenses-client';
import { fetchGroups, type GroupView } from '../../src/groups-client';
import { fetchMemberProfile } from '../../src/profile-client';

const config = createAppConfig(process.env);

export default function NewDirectExpenseScreen() {
  const router = useRouter();
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [memberId, setMemberId] = useState<string | null>(null);
  const [otherId, setOtherId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<DirectExpenseView | null>(null);
  const key = useRef<string | null>(null);
  const occurredAt = useRef<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const material = await sessionStore.load();
        if (!material) {
          setError('Sign in again to add a Direct Expense.');
          return;
        }
        const [loadedGroups, profile] = await Promise.all([
          fetchGroups(config.apiBaseUrl, material.accessToken),
          fetchMemberProfile(config.apiBaseUrl, material.accessToken)
        ]);
        setToken(material.accessToken);
        setGroups(loadedGroups);
        setMemberId(profile?.memberId ?? null);
      } catch {
        setError('We could not load your members.');
      }
    })();
  }, []);

  const others = Array.from(new Set(
    groups.flatMap((group) => group.memberships
      .filter((membership) => membership.state === 'ACTIVE'
        && membership.memberId !== memberId)
      .map((membership) => membership.memberId))
  )).sort();

  const submit = useCallback(async () => {
    if (!token || !memberId || !otherId) return;
    setBusy(true);
    setError(null);
    try {
      const total = parseMajorToMinor(amount);
      let commandKey = key.current;
      if (!commandKey) {
        commandKey = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        key.current = commandKey;
        occurredAt.current = new Date().toISOString();
      }
      setResult(await createDirectExpense(config.apiBaseUrl, token, commandKey, {
        description: description.trim(),
        totalMinorUnits: total,
        currency: groups[0]?.currency ?? 'INR',
        splitMethod: 'EQUAL',
        payers: [{ memberId, minorUnits: total }],
        participants: [memberId, otherId],
        occurredAt: occurredAt.current ?? new Date().toISOString()
      }));
    } catch (failure) {
      key.current = null;
      setError(failure instanceof Error ? failure.message : 'We could not record it.');
    } finally {
      setBusy(false);
    }
  }, [amount, description, groups, memberId, otherId, token]);

  if (result) {
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <Text style={styles.eyebrow}>Direct expense</Text>
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
          {result.entries.map((entry) => (
            <Text key={`${entry.debtor}-${entry.creditor}`} style={styles.row}>
              {shortId(entry.debtor)} owes {shortId(entry.creditor)}{' '}
              {formatMinor(entry.minorUnits, result.currency)}
            </Text>
          ))}
        </View>

        <Text style={styles.muted}>
          {result.streamIds[0]} · version {result.revision}
        </Text>

        <Pressable accessibilityRole="button" onPress={() => router.replace('/')} style={styles.primary}>
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
      <Text style={styles.eyebrow}>Direct expense</Text>
      <Text style={styles.title}>Outside any group.</Text>
      <Text style={styles.body}>
        One payer, an equal split with one other active Member, and a canonical
        direct-pair balance.
      </Text>

      <Text style={styles.label}>Description</Text>
      <TextInput
        maxLength={200}
        onChangeText={(value) => {
          setDescription(value);
          key.current = null;
        }}
        placeholder="Dinner"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="direct-description"
        value={description}
      />

      <Text style={styles.label}>Amount (INR)</Text>
      <TextInput
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          setAmount(value);
          key.current = null;
        }}
        placeholder="0.00"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="direct-amount"
        value={amount}
      />

      <Text style={styles.label}>With</Text>
      <View style={styles.choices}>
        {others.map((member) => (
          <Pressable
            key={member}
            accessibilityRole="button"
            onPress={() => {
              setOtherId(member);
              key.current = null;
            }}
            style={[styles.choice, member === otherId ? styles.choiceOn : null]}
          >
            <Text style={member === otherId ? styles.choiceLabelOn : styles.choiceLabel}>
              {shortId(member)}
            </Text>
          </Pressable>
        ))}
      </View>

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <Pressable
        accessibilityRole="button"
        disabled={busy || !token || !memberId || !otherId || groups.length === 0}
        onPress={submit}
        style={styles.primary}
      >
        {busy ? <ActivityIndicator color={PAPER} /> : (
          <Text style={styles.primaryLabel}>Create direct expense</Text>
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
  section: { marginTop: 24 },
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
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  choice: { borderColor: LINE, borderRadius: 999, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 8 },
  choiceOn: { backgroundColor: ACCENT, borderColor: ACCENT },
  choiceLabel: { color: INK, fontSize: 13 },
  choiceLabelOn: { color: PAPER, fontSize: 13, fontWeight: '600' },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: { alignItems: 'center', backgroundColor: ACCENT, borderRadius: 14, marginTop: 28, paddingVertical: 15 },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' }
});
