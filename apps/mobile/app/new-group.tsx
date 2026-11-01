import { useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { createAppConfig } from '../src/app-config';
import { sessionStore } from '../src/expo-session-store';
import { createGroup } from '../src/groups-client';

const config = createAppConfig(process.env);

export default function NewGroupScreen() {
  const router = useRouter();
  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('INR');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = useCallback(async () => {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      setError('Give the group a name.');
      return;
    }
    if (!/^[A-Z]{3}$/.test(currency)) {
      setError('Use a three-letter currency code.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const material = await sessionStore.load();
      if (!material) {
        setError('Sign in again to create a group.');
        return;
      }
      await createGroup(config.apiBaseUrl, material.accessToken, {
        name: trimmed,
        currency
      });
      router.replace('/groups');
    } catch {
      setError('We could not create the group. Try again.');
    } finally {
      setBusy(false);
    }
  }, [currency, name, router]);

  return (
    <View style={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Dutch</Text>
      <Text style={styles.title}>New group.</Text>
      <Text style={styles.body}>
        A group keeps one currency. Balances in different currencies never combine.
      </Text>

      <Text style={styles.label}>Name</Text>
      <TextInput
        autoFocus
        maxLength={120}
        onChangeText={setName}
        placeholder="Group name"
        placeholderTextColor={MUTED}
        style={styles.input}
        testID="group-name"
        value={name}
      />

      <Text style={styles.label}>Currency</Text>
      <TextInput
        autoCapitalize="characters"
        maxLength={3}
        onChangeText={(value) => setCurrency(value.toUpperCase())}
        style={styles.input}
        testID="group-currency"
        value={currency}
      />

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <Pressable
        accessibilityRole="button"
        disabled={busy}
        onPress={submit}
        style={[styles.primary, busy ? styles.primaryDisabled : null]}
      >
        <Text style={styles.primaryLabel}>{busy ? 'Creating…' : 'Create group'}</Text>
      </Pressable>
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
  back: { alignSelf: 'flex-start', paddingVertical: 6, paddingRight: 12 },
  backLabel: { color: ACCENT, fontSize: 14, fontWeight: '600' },
  eyebrow: { color: ACCENT, fontSize: 12, fontWeight: '700', letterSpacing: 1.6, marginTop: 18, textTransform: 'uppercase' },
  title: { color: INK, fontSize: 30, fontWeight: '600', letterSpacing: -0.6, lineHeight: 36, marginTop: 8 },
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 10 },
  label: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, marginTop: 26, textTransform: 'uppercase' },
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
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 18 },
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
