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
import { parseMajorToMinor } from '../../src/expenses-client';
import { fetchGroup, type GroupView } from '../../src/groups-client';
import { fetchMemberProfile } from '../../src/profile-client';
import { SeriesHttpError, createSeries, type SeriesView } from '../../src/series-client';

const config = createAppConfig(process.env);

export default function NewSeriesScreen() {
  const { groupId } = useLocalSearchParams<{ groupId: string }>();
  const router = useRouter();
  const [group, setGroup] = useState<GroupView | null>(null);
  const [memberId, setMemberId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [cadence, setCadence] = useState<'MONTHLY' | 'WEEKLY'>('MONTHLY');
  const [dueDay, setDueDay] = useState('5');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = useRef<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const material = await sessionStore.load();
        if (!material) {
          setError('Sign in again to add a Series.');
          return;
        }
        const [loaded, profile] = await Promise.all([
          fetchGroup(config.apiBaseUrl, material.accessToken, groupId),
          fetchMemberProfile(config.apiBaseUrl, material.accessToken)
        ]);
        setToken(material.accessToken);
        setGroup(loaded);
        setMemberId(profile?.memberId ?? null);
      } catch {
        setError('We could not load this group.');
      }
    })();
  }, [groupId]);

  const submit = useCallback(async () => {
    if (!token || !group || !memberId) return;
    setBusy(true);
    setError(null);
    try {
      const total = parseMajorToMinor(amount);
      const day = Number(dueDay);
      if (!Number.isInteger(day) || day < 1 || day > (cadence === 'WEEKLY' ? 7 : 31)) {
        setError('Enter a valid due day.');
        return;
      }
      let commandKey = key.current;
      if (!commandKey) {
        commandKey = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        key.current = commandKey;
      }
      const participants = group.memberships
        .filter((membership) => membership.state === 'ACTIVE')
        .map((membership) => ({ memberId: membership.memberId }));
      const created: SeriesView = await createSeries(config.apiBaseUrl, token, commandKey, {
        scope: { type: 'GROUP', groupId: group.id },
        description: description.trim(),
        totalMinorUnits: total,
        currency: group.currency,
        splitMethod: 'EQUAL',
        payers: [{ memberId, minorUnits: total }],
        participants,
        cadence,
        dueDay: day,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
        nextPeriodStart: periodStart(cadence)
      });
      router.replace(`/series/${created.id}`);
    } catch (failure) {
      // A lost/5xx response must replay the same key; only a terminal 4xx resets it.
      if (failure instanceof SeriesHttpError && failure.status >= 400 && failure.status < 500) {
        key.current = null;
      }
      setError(failure instanceof SeriesHttpError && failure.code === 'idempotency_conflict'
        ? 'This Series was already sent with different details. Review it again.'
        : failure instanceof SeriesHttpError && failure.code === 'forbidden'
          ? 'You do not have permission for that action.'
          : 'We could not create the Series. Try again.');
    } finally {
      setBusy(false);
    }
  }, [amount, cadence, description, dueDay, group, memberId, router, token]);

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Recurring series</Text>
      <Text style={styles.title}>{group?.name ?? 'Loading…'}</Text>
      <Text style={styles.body}>
        A template that creates one reviewable occurrence per due period. Nothing is charged
        until a member explicitly submits an occurrence.
      </Text>

      <Text style={styles.label}>Description</Text>
      <TextInput
        maxLength={200}
        onChangeText={(value) => {
          setDescription(value);
          key.current = null;
        }}
        placeholder="Rent"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="series-description"
        value={description}
      />

      <Text style={styles.label}>Amount ({group?.currency ?? 'INR'})</Text>
      <TextInput
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          setAmount(value);
          key.current = null;
        }}
        placeholder="0.00"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="series-amount"
        value={amount}
      />

      <Text style={styles.label}>Cadence</Text>
      <View style={styles.choices}>
        {(['MONTHLY', 'WEEKLY'] as const).map((option) => (
          <Pressable
            key={option}
            accessibilityRole="button"
            onPress={() => {
              setCadence(option);
              setDueDay(option === 'MONTHLY' ? '5' : '5');
              key.current = null;
            }}
            style={[styles.choice, option === cadence ? styles.choiceOn : null]}
          >
            <Text style={option === cadence ? styles.choiceLabelOn : styles.choiceLabel}>
              {option === 'MONTHLY' ? 'Monthly' : 'Weekly'}
            </Text>
          </Pressable>
        ))}
      </View>

      <Text style={styles.label}>
        Due day ({cadence === 'MONTHLY' ? '1–31, clamped' : '1 = Monday'})
      </Text>
      <TextInput
        keyboardType="number-pad"
        onChangeText={(value) => {
          setDueDay(value);
          key.current = null;
        }}
        style={styles.input}
        testID="series-due-day"
        value={dueDay}
      />

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <Pressable
        accessibilityRole="button"
        disabled={busy || !token || !group || !memberId}
        onPress={submit}
        style={styles.primary}
      >
        {busy ? <ActivityIndicator color={PAPER} /> : (
          <Text style={styles.primaryLabel}>Create series</Text>
        )}
      </Pressable>
    </ScrollView>
  );
}

function periodStart(cadence: 'MONTHLY' | 'WEEKLY'): string {
  const now = new Date();
  if (cadence === 'MONTHLY') {
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  }
  const day = now.getDay() === 0 ? 7 : now.getDay();
  const monday = new Date(now);
  monday.setDate(now.getDate() - (day - 1));
  return `${monday.getFullYear()}-${String(monday.getMonth() + 1).padStart(2, '0')}-${String(monday.getDate()).padStart(2, '0')}`;
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
  label: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, marginTop: 24, textTransform: 'uppercase' },
  input: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 12, borderWidth: 1, color: INK, fontSize: 16, marginTop: 8, paddingHorizontal: 14, paddingVertical: 12 },
  choices: { flexDirection: 'row', gap: 8, marginTop: 8 },
  choice: { borderColor: LINE, borderRadius: 999, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 8 },
  choiceOn: { backgroundColor: ACCENT, borderColor: ACCENT },
  choiceLabel: { color: INK, fontSize: 13 },
  choiceLabelOn: { color: PAPER, fontSize: 13, fontWeight: '600' },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: { alignItems: 'center', backgroundColor: ACCENT, borderRadius: 14, marginTop: 28, paddingVertical: 15 },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' }
});
