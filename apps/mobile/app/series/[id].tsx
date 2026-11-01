import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View
} from 'react-native';

import { createAppConfig } from '../../src/app-config';
import { sessionStore } from '../../src/expo-session-store';
import { formatMinor } from '../../src/expenses-client';
import {
  SeriesHttpError,
  changeSeriesStatus,
  fetchOccurrences,
  fetchSeries,
  generateOccurrence,
  skipOccurrence,
  submitOccurrence,
  type OccurrenceView,
  type SeriesView
} from '../../src/series-client';

const config = createAppConfig(process.env);

export default function SeriesScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const [series, setSeries] = useState<SeriesView | null>(null);
  const [occurrences, setOccurrences] = useState<OccurrenceView[]>([]);
  const [token, setToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const commandKey = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    setError(null);
    try {
      const material = await sessionStore.load();
      if (!material) {
        setError('Sign in again to open this Series.');
        return;
      }
      setToken(material.accessToken);
      const [loaded, list] = await Promise.all([
        fetchSeries(config.apiBaseUrl, material.accessToken, id),
        fetchOccurrences(config.apiBaseUrl, material.accessToken, id)
      ]);
      setSeries(loaded);
      setOccurrences(list);
    } catch {
      setError('We could not load this Series.');
    }
  }, [id]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const generate = useCallback(async () => {
    if (!token || !series) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    const key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    try {
      const occurrence = await generateOccurrence(
        config.apiBaseUrl, token, series.id, key, series.version);
      setNotice(`Occurrence ${occurrence.periodStart} is pending review.`);
      await refresh();
    } catch (failure) {
      if (failure instanceof SeriesHttpError && failure.code === 'stale_version') {
        setError('The series changed. Refreshed the latest state.');
        await refresh();
      } else {
        setError('We could not generate the next occurrence.');
      }
    } finally {
      setBusy(false);
    }
  }, [refresh, series, token]);

  const submit = useCallback(async (occurrence: OccurrenceView) => {
    if (!token || !series) return;
    setBusy(true);
    setError(null);
    let key = commandKey.current;
    if (!key) {
      key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      commandKey.current = key;
    }
    try {
      const result = await submitOccurrence(config.apiBaseUrl, token, occurrence.id, key);
      commandKey.current = null;
      setNotice(`Recorded ${result.expenseId}.`);
      await refresh();
    } catch (failure) {
      commandKey.current = null;
      setError(failure instanceof SeriesHttpError && failure.code === 'conflict'
        ? 'That occurrence is no longer pending.'
        : 'We could not submit the occurrence.');
    } finally {
      setBusy(false);
    }
  }, [refresh, series, token]);

  const skip = useCallback(async (occurrence: OccurrenceView) => {
    if (!token || !series) return;
    setBusy(true);
    setError(null);
    try {
      await skipOccurrence(config.apiBaseUrl, token, occurrence.id,
        `${Date.now()}-${Math.random().toString(36).slice(2)}`);
      setNotice(`Skipped ${occurrence.periodStart}.`);
      await refresh();
    } catch {
      setError('We could not skip the occurrence.');
    } finally {
      setBusy(false);
    }
  }, [refresh, series, token]);

  const changeStatus = useCallback(async (action: 'PAUSE' | 'RESUME' | 'ARCHIVE') => {
    if (!token || !series) return;
    setBusy(true);
    setError(null);
    try {
      await changeSeriesStatus(config.apiBaseUrl, token, series.id,
        `${Date.now()}-${Math.random().toString(36).slice(2)}`, action, series.version);
      await refresh();
    } catch (failure) {
      setError(failure instanceof SeriesHttpError && failure.code === 'stale_version'
        ? 'The series changed. Refreshed the latest state.'
        : 'We could not change the series state.');
      await refresh();
    } finally {
      setBusy(false);
    }
  }, [refresh, series, token]);

  if (!series) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={INK} />
        {error ? <Text style={styles.notice}>{error}</Text> : null}
      </View>
    );
  }

  const pending = occurrences.filter((occurrence) => occurrence.status === 'PENDING');

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Recurring series</Text>
      <Text style={styles.title}>{series.description}</Text>
      <Text style={styles.body}>
        {formatMinor(series.totalMinorUnits, series.currency)} · {series.cadence} ·{' '}
        {series.status} · version {series.version}
      </Text>
      <Text style={styles.body}>
        Next period starts {series.nextPeriodStart} · due day {series.dueDay} ·{' '}
        {series.timezone}
      </Text>

      {notice ? <Text style={styles.noticeOk}>{notice}</Text> : null}
      {error ? <Text style={styles.notice}>{error}</Text> : null}

      {series.status === 'ACTIVE' ? (
        <Pressable accessibilityRole="button" disabled={busy} onPress={generate}
                   style={styles.primary}>
          <Text style={styles.primaryLabel}>Generate next occurrence</Text>
        </Pressable>
      ) : null}

      <View style={styles.section}>
        <Text style={styles.sectionLabel}>Pending occurrences</Text>
        {pending.length === 0 ? (
          <Text style={styles.muted}>No occurrence is waiting for review.</Text>
        ) : pending.map((occurrence) => (
          <View key={occurrence.id} style={styles.card}>
            <Text style={styles.cardTitle}>{occurrence.periodStart} to {occurrence.periodEnd}</Text>
            <Text style={styles.muted}>Due {occurrence.dueAt}</Text>
            <View style={styles.actions}>
              <Pressable accessibilityRole="button" disabled={busy}
                         onPress={() => void submit(occurrence)} style={styles.action}>
                <Text style={styles.actionLabel}>Submit</Text>
              </Pressable>
              <Pressable accessibilityRole="button" disabled={busy}
                         onPress={() => void skip(occurrence)} style={styles.action}>
                <Text style={styles.actionLabel}>Skip</Text>
              </Pressable>
            </View>
          </View>
        ))}
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionLabel}>Series state</Text>
        <View style={styles.actions}>
          {series.status === 'ACTIVE' ? (
            <Pressable accessibilityRole="button" disabled={busy}
                       onPress={() => void changeStatus('PAUSE')} style={styles.action}>
              <Text style={styles.actionLabel}>Pause</Text>
            </Pressable>
          ) : null}
          {series.status === 'PAUSED' ? (
            <Pressable accessibilityRole="button" disabled={busy}
                       onPress={() => void changeStatus('RESUME')} style={styles.action}>
              <Text style={styles.actionLabel}>Resume</Text>
            </Pressable>
          ) : null}
          {series.status !== 'ARCHIVED' ? (
            <Pressable accessibilityRole="button" disabled={busy}
                       onPress={() => void changeStatus('ARCHIVE')} style={styles.action}>
              <Text style={styles.actionLabel}>Archive</Text>
            </Pressable>
          ) : null}
        </View>
      </View>
    </ScrollView>
  );
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
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 8 },
  section: { marginTop: 28 },
  sectionLabel: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, textTransform: 'uppercase' },
  card: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 14, borderWidth: 1, marginTop: 10, padding: 16 },
  cardTitle: { color: INK, fontSize: 15, fontWeight: '600' },
  muted: { color: MUTED, fontSize: 12, lineHeight: 18, marginTop: 6 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 16, marginTop: 10 },
  action: { paddingVertical: 6 },
  actionLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 12 },
  noticeOk: { color: ACCENT, fontSize: 13, lineHeight: 19, marginTop: 12 },
  primary: { alignItems: 'center', backgroundColor: ACCENT, borderRadius: 14, marginTop: 20, paddingVertical: 15 },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' }
});
