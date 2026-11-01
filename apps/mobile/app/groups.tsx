import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';

import { createAppConfig } from '../src/app-config';
import { sessionStore } from '../src/expo-session-store';
import { fetchGroups, type GroupView } from '../src/groups-client';

const config = createAppConfig(process.env);

export default function GroupsScreen() {
  const router = useRouter();
  const [groups, setGroups] = useState<GroupView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const material = await sessionStore.load();
      if (!material) {
        setError('Sign in again to see your groups.');
        setGroups([]);
        return;
      }
      setGroups(await fetchGroups(config.apiBaseUrl, material.accessToken));
    } catch {
      setError('We could not load your groups. Try again.');
      setGroups([]);
    }
  }, []);

  useEffect(() => {
    // Fetching the authoritative group list is the effect's purpose; the
    // lint rule assumes synchronous state writes.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  return (
    <View style={styles.screen}>
      <View style={styles.header}>
        <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
          <Text style={styles.backLabel}>Back</Text>
        </Pressable>
        <Text style={styles.eyebrow}>Dutch</Text>
      </View>
      <Text style={styles.title}>Your groups.</Text>
      <Text style={styles.body}>
        Balances stay separate per group and currency. Archived groups remain readable.
      </Text>

      <Pressable
        accessibilityRole="button"
        onPress={() => router.push('/new-group')}
        style={styles.primary}
      >
        <Text style={styles.primaryLabel}>New group</Text>
      </Pressable>

      {groups === null ? (
        <View style={styles.centered}>
          <ActivityIndicator color={INK} />
          <Text style={styles.muted}>Loading groups</Text>
        </View>
      ) : null}

      {error ? (
        <View style={styles.error}>
          <Text style={styles.notice}>{error}</Text>
          <Pressable accessibilityRole="button" onPress={load} style={styles.secondary}>
            <Text style={styles.secondaryLabel}>Try again</Text>
          </Pressable>
        </View>
      ) : null}

      {groups !== null && !error && groups.length === 0 ? (
        <View style={styles.error}>
          <Text style={styles.muted}>No groups yet. Create one from Home when the composer lands.</Text>
        </View>
      ) : null}

      <FlatList
        data={groups ?? []}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.list}
        renderItem={({ item }) => (
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push(`/group/${item.id}`)}
            style={styles.card}
          >
            <View style={styles.cardHeader}>
              <Text style={styles.cardTitle}>{item.name}</Text>
              <Text style={styles.badge}>{item.state}</Text>
            </View>
            <Text style={styles.muted}>
              {item.currency} · {item.memberships.length} member
              {item.memberships.length === 1 ? '' : 's'}
            </Text>
          </Pressable>
        )}
      />
    </View>
  );
}

const PAPER = '#f6f4ef';
const INK = '#12211c';
const MUTED = '#5f6b64';
const LINE = '#d9d5ca';
const ACCENT = '#0f6b4f';

const styles = StyleSheet.create({
  screen: { backgroundColor: PAPER, flex: 1, paddingHorizontal: 24, paddingTop: 64 },
  header: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  back: { paddingVertical: 6, paddingRight: 12 },
  backLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' },
  eyebrow: { color: ACCENT, fontSize: 12, fontWeight: '700', letterSpacing: 1.6, textTransform: 'uppercase' },
  title: { color: INK, fontSize: 30, fontWeight: '600', letterSpacing: -0.6, lineHeight: 36, marginTop: 18 },
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 10 },
  centered: { alignItems: 'center', marginTop: 40 },
  muted: { color: MUTED, fontSize: 13, marginTop: 8 },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19 },
  error: { marginTop: 28 },
  list: { gap: 10, paddingBottom: 32, paddingTop: 24 },
  card: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 16, borderWidth: 1, padding: 16 },
  cardHeader: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  cardTitle: { color: INK, flex: 1, fontSize: 17, fontWeight: '600' },
  badge: { color: MUTED, fontSize: 10, fontWeight: '700', letterSpacing: 1, textTransform: 'uppercase' },
  primary: {
    alignItems: 'center',
    backgroundColor: ACCENT,
    borderRadius: 14,
    marginTop: 22,
    paddingVertical: 14
  },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' },
  secondary: { alignSelf: 'flex-start', marginTop: 14, paddingVertical: 8 },
  secondaryLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' }
});
