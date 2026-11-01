import { useLocalSearchParams, useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
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
  GroupHttpError,
  activateMember,
  archiveGroup,
  changeRole,
  deactivateMember,
  fetchGroup,
  inviteMember,
  reopenGroup,
  type GroupView,
  type MembershipView
} from '../../src/groups-client';
import { fetchMemberProfile } from '../../src/profile-client';

const config = createAppConfig(process.env);

export default function GroupDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const router = useRouter();
  const [group, setGroup] = useState<GroupView | null>(null);
  const [memberId, setMemberId] = useState<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [inviteId, setInviteId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async (): Promise<boolean> => {
    setError(null);
    try {
      const material = await sessionStore.load();
      if (!material) {
        setError('Sign in again to open this group.');
        return false;
      }
      const loaded = await fetchGroup(config.apiBaseUrl, material.accessToken, id);
      setToken(material.accessToken);
      setGroup(loaded);
      try {
        const profile = await fetchMemberProfile(config.apiBaseUrl, material.accessToken);
        setMemberId(profile?.memberId ?? null);
      } catch {
        setMemberId(null);
      }
      return true;
    } catch {
      setError('We could not load this group.');
      return false;
    }
  }, [id]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, [refresh]);

  const run = useCallback(async (action: () => Promise<GroupView>) => {
    setBusy(true);
    setError(null);
    try {
      setGroup(await action());
    } catch (failure) {
      if (failure instanceof GroupHttpError && failure.code === 'stale_version') {
        const refreshed = await refresh();
        setError(refreshed
          ? 'The group changed. Refreshed the latest state.'
          : 'The group changed. Reload to see the latest state.');
      } else if (failure instanceof GroupHttpError && failure.code === 'last_admin') {
        setError('A group must keep at least one active Admin.');
      } else if (failure instanceof GroupHttpError && failure.code === 'forbidden') {
        setError('You do not have permission for that action.');
      } else if (failure instanceof GroupHttpError && failure.code === 'not_found') {
        setError('That group or member is no longer available.');
      } else if (failure instanceof GroupHttpError && failure.code === 'conflict') {
        setError('The group state does not allow that action.');
      } else {
        setError('The action failed. Try again.');
      }
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  if (!group) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={INK} />
        {error ? <Text style={styles.notice}>{error}</Text> : null}
      </View>
    );
  }

  const viewer = group.memberships.find((membership) => membership.memberId === memberId);
  const isAdmin = viewer?.role === 'ADMIN' && viewer.state === 'ACTIVE';

  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Pressable accessibilityRole="button" onPress={() => router.back()} style={styles.back}>
        <Text style={styles.backLabel}>Back</Text>
      </Pressable>
      <Text style={styles.eyebrow}>Group</Text>
      <Text style={styles.title}>{group.name}</Text>
      <Text style={styles.body}>
        {group.currency} · {group.state} · version {group.version}
      </Text>

      {error ? <Text style={styles.notice}>{error}</Text> : null}

      {viewer?.state === 'ACTIVE' ? (
        <Pressable
          accessibilityRole="button"
          onPress={() => router.push(`/expense/new?groupId=${group.id}`)}
          style={styles.primary}
        >
          <Text style={styles.primaryLabel}>New expense</Text>
        </Pressable>
      ) : null}

      <View style={styles.section}>
        <Text style={styles.sectionLabel}>Members</Text>
        {group.memberships.map((membership) => (
          <MemberRow
            key={membership.memberId}
            membership={membership}
            isSelf={membership.memberId === memberId}
            canManage={Boolean(isAdmin) && group.state === 'ACTIVE' && !busy}
            onActivate={() => token
              ? void run(() => activateMember(config.apiBaseUrl, token, group.id, membership.memberId, group.version))
              : undefined}
            onDeactivate={() => token
              ? void run(() => deactivateMember(config.apiBaseUrl, token, group.id, membership.memberId, group.version))
              : undefined}
            onToggleRole={() => token
              ? void run(() => changeRole(
                  config.apiBaseUrl, token, group.id, membership.memberId,
                  membership.role === 'ADMIN' ? 'MEMBER' : 'ADMIN', group.version))
              : undefined}
          />
        ))}
      </View>

      {isAdmin && group.state === 'ACTIVE' ? (
        <View style={styles.section}>
          <Text style={styles.sectionLabel}>Invite a member</Text>
          <TextInput
            autoCapitalize="none"
            onChangeText={setInviteId}
            placeholder="mem_…"
            placeholderTextColor={MUTED}
            style={styles.input}
            testID="invite-member"
            value={inviteId}
          />
          <Pressable
            accessibilityRole="button"
            disabled={busy || inviteId.trim().length === 0 || !token}
            onPress={() => {
              if (!token) return;
              void run(() => inviteMember(
                config.apiBaseUrl, token, group.id, inviteId.trim(), 'MEMBER', group.version));
              setInviteId('');
            }}
            style={styles.primary}
          >
            <Text style={styles.primaryLabel}>Invite</Text>
          </Pressable>
        </View>
      ) : null}

      {isAdmin ? (
        <Pressable
          accessibilityRole="button"
          disabled={busy || !token}
          onPress={() => {
            if (!token) return;
            void run(() => group.state === 'ACTIVE'
              ? archiveGroup(config.apiBaseUrl, token, group.id, group.version)
              : reopenGroup(config.apiBaseUrl, token, group.id, group.version));
          }}
          style={styles.quiet}
        >
          <Text style={styles.quietLabel}>
            {group.state === 'ACTIVE' ? 'Archive group' : 'Reopen group'}
          </Text>
        </Pressable>
      ) : null}
    </ScrollView>
  );
}

function MemberRow({
  membership,
  isSelf,
  canManage,
  onActivate,
  onDeactivate,
  onToggleRole
}: {
  membership: MembershipView;
  isSelf: boolean;
  canManage: boolean;
  onActivate: () => void;
  onDeactivate: () => void;
  onToggleRole: () => void;
}) {
  return (
    <View style={styles.member}>
      <View style={styles.memberHeader}>
        <Text style={styles.memberId}>
          {membership.memberId}
          {isSelf ? ' (you)' : ''}
        </Text>
        <Text style={styles.badge}>{membership.state}</Text>
      </View>
      <Text style={styles.muted}>
        {membership.role === 'ADMIN' ? 'Admin' : 'Member'}
      </Text>
      {canManage ? (
        <View style={styles.actions}>
          {membership.state === 'INVITED' || membership.state === 'PLACEHOLDER' ? (
            <Pressable accessibilityRole="button" onPress={onActivate} style={styles.action}>
              <Text style={styles.actionLabel}>Activate</Text>
            </Pressable>
          ) : null}
          {membership.state === 'ACTIVE' && !isSelf ? (
            <>
              <Pressable accessibilityRole="button" onPress={onToggleRole} style={styles.action}>
                <Text style={styles.actionLabel}>
                  {membership.role === 'ADMIN' ? 'Make member' : 'Make admin'}
                </Text>
              </Pressable>
              <Pressable accessibilityRole="button" onPress={onDeactivate} style={styles.action}>
                <Text style={styles.actionLabel}>Deactivate</Text>
              </Pressable>
            </>
          ) : null}
        </View>
      ) : null}
    </View>
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
  member: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 14, borderWidth: 1, marginTop: 10, padding: 14 },
  memberHeader: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  memberId: { color: INK, flex: 1, fontFamily: 'Courier', fontSize: 12 },
  badge: { color: MUTED, fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  muted: { color: MUTED, fontSize: 12, marginTop: 6 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 12, marginTop: 10 },
  action: { paddingVertical: 4 },
  actionLabel: { color: ACCENT, fontSize: 13, fontWeight: '600' },
  input: {
    backgroundColor: '#fff',
    borderColor: LINE,
    borderRadius: 12,
    borderWidth: 1,
    color: INK,
    fontFamily: 'Courier',
    fontSize: 14,
    marginTop: 10,
    paddingHorizontal: 14,
    paddingVertical: 12
  },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: {
    alignItems: 'center',
    backgroundColor: ACCENT,
    borderRadius: 14,
    marginTop: 12,
    paddingVertical: 14
  },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' },
  quiet: { alignItems: 'center', marginTop: 24, paddingVertical: 10 },
  quietLabel: { color: MUTED, fontSize: 13, fontWeight: '600' }
});
