import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  View
} from 'react-native';

import { createAppConfig } from '../src/app-config';
import { sessionStore } from '../src/expo-session-store';
import {
  NotificationHttpError,
  deleteNotificationData,
  fetchPreferences,
  listDevices,
  registerDevice,
  updatePreferences,
  type Device,
  type Preferences
} from '../src/notifications-client';

const config = createAppConfig(process.env);
const DEV_DEVICE = 'dev_devdevice012345678901';

export default function NotificationsScreen() {
  const router = useRouter();
  const [token, setToken] = useState<string | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const material = await sessionStore.load();
      if (!material) {
        setError('Sign in again to manage notifications.');
        return;
      }
      setToken(material.accessToken);
      let registered = await listDevices(config.apiBaseUrl, material.accessToken);
      if (registered.length === 0) {
        await registerDevice(config.apiBaseUrl, material.accessToken, {
          deviceId: DEV_DEVICE,
          platform: 'ANDROID',
          pushToken: 'dev-push-token-0001'
        });
        registered = await listDevices(config.apiBaseUrl, material.accessToken);
      }
      setDevices(registered);
      setPreferences(await fetchPreferences(config.apiBaseUrl, material.accessToken));
    } catch (failure) {
      setError(failure instanceof NotificationHttpError
        ? `Notifications are unavailable (${failure.status}).`
        : 'We could not load notification settings.');
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  const toggle = useCallback(async (change: Partial<Preferences>) => {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      setPreferences(await updatePreferences(config.apiBaseUrl, token, change));
    } catch {
      setError('We could not update your preferences.');
    } finally {
      setBusy(false);
    }
  }, [token]);

  const deleteData = useCallback(async () => {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      await deleteNotificationData(config.apiBaseUrl, token);
      setDevices([]);
      setNotice('Notification data deleted. This device is no longer registered.');
    } catch {
      setError('We could not delete notification data.');
    } finally {
      setBusy(false);
    }
  }, [token]);

  if (!preferences) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={INK} />
        {error ? <Text style={styles.notice}>{error}</Text> : null}
      </View>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Notifications</Text>
      <Text style={styles.title}>Push preferences</Text>
      <Text style={styles.body}>
        Push is a prompt to refresh Activity; the Activity feed stays authoritative.
        Failed pushes never change a balance.
      </Text>

      {notice ? <Text style={styles.noticeOk}>{notice}</Text> : null}
      {error ? <Text style={styles.notice}>{error}</Text> : null}

      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.rowLabel}>Expense and correction push</Text>
          <Switch
            accessibilityLabel="Expense push"
            disabled={busy}
            onValueChange={(value) => void toggle({ activityPush: value })}
            value={preferences.activityPush}
          />
        </View>
        <View style={styles.row}>
          <Text style={styles.rowLabel}>Settlement push</Text>
          <Switch
            accessibilityLabel="Settlement push"
            disabled={busy}
            onValueChange={(value) => void toggle({ settlementPush: value })}
            value={preferences.settlementPush}
          />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionLabel}>Devices</Text>
        {devices.length === 0 ? (
          <Text style={styles.muted}>No device is registered.</Text>
        ) : devices.map((device) => (
          <Text key={device.deviceId} style={styles.muted}>
            {device.deviceId} · {device.platform} · {device.active ? 'ACTIVE' : 'INACTIVE'}
          </Text>
        ))}
      </View>

      <Pressable accessibilityRole="button" disabled={busy} onPress={deleteData}
                 style={styles.danger}>
        <Text style={styles.dangerLabel}>Delete notification data</Text>
      </Pressable>
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
  body: { color: MUTED, fontSize: 14, lineHeight: 21, marginTop: 10 },
  card: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 16, borderWidth: 1, marginTop: 24, paddingHorizontal: 16 },
  row: { alignItems: 'center', borderBottomColor: LINE, borderBottomWidth: 1, flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 14 },
  rowLabel: { color: INK, flex: 1, fontSize: 14, paddingRight: 12 },
  section: { marginTop: 28 },
  sectionLabel: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, textTransform: 'uppercase' },
  muted: { color: MUTED, fontSize: 12, lineHeight: 18, marginTop: 8 },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  noticeOk: { color: ACCENT, fontSize: 13, lineHeight: 19, marginTop: 16 },
  danger: { alignItems: 'center', borderColor: '#8a3b2a', borderRadius: 14, borderWidth: 1, marginTop: 24, paddingVertical: 14 },
  dangerLabel: { color: '#8a3b2a', fontSize: 15, fontWeight: '600' }
});
