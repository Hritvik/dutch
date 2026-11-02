import * as AuthSession from 'expo-auth-session';
import { useRouter } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';

import { createAppConfig } from '../src/app-config';
import { screenFor } from '../src/app-screen';
import { sessionStore } from '../src/expo-session-store';
import { loadMobileAuthorizationRequest } from '../src/oauth-client';
import { validateAuthorizationRedirect } from '../src/oauth-redirect';
import {
  ProfileContractError,
  ProfileHttpError,
  fetchMemberProfile,
  fetchProfileState,
  type ProfileStateResult
} from '../src/profile-client';
import { initialSessionState, reduceSessionState, type SessionState } from '../src/session-state';
import { wipeProjection } from '../src/projection-db';
import { sqliteStore } from '../src/offline-store';
import { syncProjection } from '../src/sync-coordinator';
import { createDevAccessToken, readAppSubject } from '../src/token';

const config = createAppConfig(process.env);
const BOOTING: SessionState = { kind: 'booting', ledgerAvailable: false };
const DEV = process.env.NODE_ENV !== 'production';
const DEV_ADMIN_SUBJECT = 'aps_dev_0123456789012345678901';
const DEV_MEMBER_SUBJECT = 'aps_member_012345678901234567890';

type Failure = { kind: 'offline' | 'contract'; message: string };

export default function HomeScreen() {
  const router = useRouter();
  const [session, setSession] = useState<SessionState>(BOOTING);
  const [profile, setProfile] = useState<ProfileStateResult>();
  const [online, setOnline] = useState(true);
  const [memberPending, setMemberPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [syncStatus, setSyncStatus] = useState<string | null>(null);
  const accessToken = useRef<string | null>(null);
  const expectedSubject = useRef<string | null>(null);

  const apply = useCallback((event: Parameters<typeof reduceSessionState>[1]) => {
    setSession((current) => reduceSessionState(current, event));
  }, []);

  const expireSession = useCallback(async (reason: string) => {
    await sessionStore.wipe();
    await wipeProjection();
    accessToken.current = null;
    expectedSubject.current = null;
    setProfile(undefined);
    setMemberPending(false);
    setFailure(null);
    setOnline(true);
    setSession(initialSessionState);
    setMessage(reason);
  }, []);

  const checkProfile = useCallback(async (token: string) => {
    const subject = readAppSubject(token);
    if (expectedSubject.current && subject !== expectedSubject.current) {
      await expireSession('Your session changed. Sign in again.');
      return;
    }
    try {
      const state = await fetchProfileState(config.apiBaseUrl, token);
      setOnline(true);
      setFailure(null);
      setProfile(state);
      if (state.state !== 'PROFILE_READY') {
        setMemberPending(false);
        return;
      }
      const member = await fetchMemberProfile(config.apiBaseUrl, token);
      if (!member) {
        setMemberPending(true);
        return;
      }
      setMemberPending(false);
      setSession((current) => reduceSessionState(current, {
        type: 'memberProfileReady',
        identityId: subject,
        memberId: member.memberId
      }));
      void syncProjection(config.apiBaseUrl, token, member.memberId, subject, fetch, sqliteStore)
        .then((outcome) => setSyncStatus(outcome.mode === 'offline'
          ? 'Offline · showing cached data'
          : `Synced ${outcome.changes} change${outcome.changes === 1 ? '' : 's'}`))
        .catch(() => setSyncStatus('Cached data may be out of date'));
    } catch (error) {
      if (error instanceof ProfileHttpError && (error.status === 401 || error.status === 403)) {
        await expireSession('Your session expired. Sign in again.');
        return;
      }
      if (error instanceof ProfileHttpError || error instanceof ProfileContractError) {
        setOnline(true);
        setFailure({ kind: 'contract', message: 'Dutch could not confirm your profile. Try again.' });
        return;
      }
      setOnline(false);
      setFailure({ kind: 'offline', message: 'We could not reach Dutch.' });
    }
  }, [expireSession]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const material = await sessionStore.load();
        if (cancelled) return;
        if (!material) {
          setSession(initialSessionState);
          return;
        }
        accessToken.current = material.accessToken;
        expectedSubject.current = material.identityId;
        setSession({ kind: 'profileRequired', identityId: material.identityId, ledgerAvailable: false });
        await checkProfile(material.accessToken);
      } catch {
        if (!cancelled) setSession(initialSessionState);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [checkProfile]);

  useEffect(() => {
    if (session.kind !== 'profileRequired' || !accessToken.current) return;
    if (profile?.state === 'PROFILE_READY' && !memberPending) return;
    const delay = memberPending ? 2 : (profile?.retryAfterSeconds ?? 5);
    const timer = setTimeout(() => {
      if (accessToken.current) void checkProfile(accessToken.current);
    }, delay * 1000);
    return () => clearTimeout(timer);
  }, [session.kind, profile, memberPending, checkProfile]);

  const retry = useCallback(() => {
    setFailure(null);
    setMessage(null);
    if (accessToken.current) void checkProfile(accessToken.current);
  }, [checkProfile]);

  const signIn = useCallback(async () => {
    if (!config.issuer) {
      setMessage('Set EXPO_PUBLIC_IAM_ISSUER before signing in.');
      return;
    }
    setBusy(true);
    setMessage(null);
    setFailure(null);
    setMemberPending(false);
    try {
      const { request, redirectUri } = await loadMobileAuthorizationRequest(config.issuer);
      const discovery = await AuthSession.fetchDiscoveryAsync(config.issuer);
      const result = await request.promptAsync(discovery);
      if (result.type !== 'success') {
        setMessage('Sign-in was cancelled.');
        return;
      }
      const returned = `${redirectUri}?${new URLSearchParams(result.params).toString()}`;
      const validated = validateAuthorizationRedirect(returned, redirectUri, request.state);
      if (!validated.valid) {
        setMessage('Sign-in response failed validation.');
        return;
      }
      const tokens = await AuthSession.exchangeCodeAsync(
        {
          clientId: 'dutch-mobile',
          code: validated.authorizationCode,
          redirectUri,
          extraParams: { code_verifier: request.codeVerifier ?? '' }
        },
        discovery
      );
      if (!tokens.accessToken) {
        setMessage('Sign-in returned no access token.');
        return;
      }
      const subject = readAppSubject(tokens.accessToken);
      await sessionStore.save({
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken ?? '',
        identityId: subject
      });
      accessToken.current = tokens.accessToken;
      expectedSubject.current = subject;
      setProfile(undefined);
      setSession({ kind: 'profileRequired', identityId: subject, ledgerAvailable: false });
      await checkProfile(tokens.accessToken);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Sign-in failed.');
    } finally {
      setBusy(false);
    }
  }, [checkProfile]);

  const signOut = useCallback(async () => {
    await sessionStore.wipe();
    await wipeProjection();
    accessToken.current = null;
    expectedSubject.current = null;
    setProfile(undefined);
    setMemberPending(false);
    setFailure(null);
    setOnline(true);
    setMessage(null);
    apply({ type: 'logoutCompleted' });
  }, [apply]);

  const startDevSession = useCallback(async (subject = DEV_ADMIN_SUBJECT) => {
    if (!DEV || !config.devSession) return;
    const token = createDevAccessToken(subject);
    await sessionStore.save({ accessToken: token, refreshToken: '', identityId: subject });
    accessToken.current = token;
    expectedSubject.current = subject;
    setProfile(undefined);
    setSession({ kind: 'profileRequired', identityId: subject, ledgerAvailable: false });
    await checkProfile(token);
  }, [checkProfile]);

  const screen = screenFor({ session, profile, online });
  const contractFailure = failure?.kind === 'contract' && session.kind === 'profileRequired';

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />
      {screen === 'loading' ? <Loading /> : null}
      {screen === 'signedOut' ? (
        <SignedOut
          busy={busy}
          message={message}
          onSignIn={signIn}
          devSession={DEV && config.devSession}
          onDevSession={() => void startDevSession()}
          onDevMember={() => void startDevSession(DEV_MEMBER_SUBJECT)}
        />
      ) : null}
      {!contractFailure && screen === 'profileRequired' ? (
        <ProfileRequired
          retryAfter={profile?.retryAfterSeconds}
          message={message}
          onRetry={retry}
          onSignOut={signOut}
        />
      ) : null}
      {!contractFailure && screen === 'profiling' ? <Loading label="Finishing your profile" /> : null}
      {screen === 'offline' ? (
        <Offline message={failure?.message ?? message} onRetry={retry} onSignOut={signOut} />
      ) : null}
      {contractFailure ? (
        <Unavailable message={failure?.message ?? null} onRetry={retry} onSignOut={signOut} />
      ) : null}
      {screen === 'home' && session.kind === 'authenticated' ? (
        <Home
          memberId={session.memberId}
          ledgerAvailable={session.ledgerAvailable}
          onOpenGroups={() => router.push('/groups')}
          onOpenDirect={() => router.push('/direct/new')}
          onOpenNotifications={() => router.push('/notifications')}
          syncStatus={syncStatus}
          onSignOut={signOut}
        />
      ) : null}
    </View>
  );
}

function Loading({ label = 'Loading Dutch' }: { label?: string }) {
  return (
    <View style={styles.centered}>
      <ActivityIndicator color={INK} />
      <Text style={styles.muted}>{label}</Text>
    </View>
  );
}

function SignedOut({
  busy,
  message,
  onSignIn,
  devSession,
  onDevSession,
  onDevMember
}: {
  busy: boolean;
  message: string | null;
  onSignIn: () => void;
  devSession: boolean;
  onDevSession: () => void;
  onDevMember: () => void;
}) {
  return (
    <View style={styles.centered}>
      <Text style={styles.eyebrow}>Dutch</Text>
      <Text style={styles.title}>Share costs without losing the plot.</Text>
      <Text style={styles.body}>
        Sign in to see your balances. Dutch never moves or holds your money.
      </Text>
      {message ? <Text style={styles.notice}>{message}</Text> : null}
      <Pressable accessibilityRole="button" disabled={busy} onPress={onSignIn} style={styles.primary}>
        <Text style={styles.primaryLabel}>{busy ? 'Opening…' : 'Sign in'}</Text>
      </Pressable>
      {devSession ? (
        <>
          <Pressable accessibilityRole="button" onPress={onDevSession} style={styles.quiet}>
            <Text style={styles.quietLabel}>Use test identity</Text>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={onDevMember} style={styles.quiet}>
            <Text style={styles.quietLabel}>Use member identity</Text>
          </Pressable>
        </>
      ) : null}
    </View>
  );
}

function ProfileRequired({
  retryAfter,
  message,
  onRetry,
  onSignOut
}: {
  retryAfter?: number;
  message: string | null;
  onRetry: () => void;
  onSignOut: () => void;
}) {
  return (
    <View style={styles.centered}>
      <Text style={styles.eyebrow}>Almost there</Text>
      <Text style={styles.title}>Setting up your profile.</Text>
      <Text style={styles.body}>
        Your sign-in worked. Balances stay locked until your Member Profile is ready.
      </Text>
      {retryAfter ? <Text style={styles.muted}>Checking again in {retryAfter}s.</Text> : null}
      {message ? <Text style={styles.notice}>{message}</Text> : null}
      <Pressable accessibilityRole="button" onPress={onRetry} style={styles.primary}>
        <Text style={styles.primaryLabel}>Check again</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={onSignOut} style={styles.quiet}>
        <Text style={styles.quietLabel}>Sign out</Text>
      </Pressable>
    </View>
  );
}

function Offline({
  message,
  onRetry,
  onSignOut
}: {
  message: string | null;
  onRetry: () => void;
  onSignOut: () => void;
}) {
  return (
    <View style={styles.centered}>
      <Text style={styles.eyebrow}>Offline</Text>
      <Text style={styles.title}>We could not reach Dutch.</Text>
      <Text style={styles.body}>
        Your session is safe. We will not show a balance we cannot confirm.
      </Text>
      {message ? <Text style={styles.notice}>{message}</Text> : null}
      <Pressable accessibilityRole="button" onPress={onRetry} style={styles.primary}>
        <Text style={styles.primaryLabel}>Try again</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={onSignOut} style={styles.quiet}>
        <Text style={styles.quietLabel}>Sign out</Text>
      </Pressable>
    </View>
  );
}

function Unavailable({
  message,
  onRetry,
  onSignOut
}: {
  message: string | null;
  onRetry: () => void;
  onSignOut: () => void;
}) {
  return (
    <View style={styles.centered}>
      <Text style={styles.eyebrow}>Not confirmed</Text>
      <Text style={styles.title}>Your profile is not ready.</Text>
      <Text style={styles.body}>
        Dutch could not confirm your Member Profile. Balances stay locked until it can.
      </Text>
      {message ? <Text style={styles.notice}>{message}</Text> : null}
      <Pressable accessibilityRole="button" onPress={onRetry} style={styles.primary}>
        <Text style={styles.primaryLabel}>Try again</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={onSignOut} style={styles.quiet}>
        <Text style={styles.quietLabel}>Sign out</Text>
      </Pressable>
    </View>
  );
}

function Home({
  memberId,
  ledgerAvailable,
  onOpenGroups,
  onOpenDirect,
  onOpenNotifications,
  syncStatus,
  onSignOut
}: {
  memberId: string;
  ledgerAvailable: boolean;
  onOpenGroups: () => void;
  onOpenDirect: () => void;
  onOpenNotifications: () => void;
  syncStatus: string | null;
  onSignOut: () => void;
}) {
  return (
    <View style={styles.home}>
      <View style={styles.homeHeader}>
        <Text style={styles.eyebrow}>Dutch</Text>
        <Pressable accessibilityRole="button" onPress={onSignOut} style={styles.quiet}>
          <Text style={styles.quietLabel}>Sign out</Text>
        </Pressable>
      </View>
      <Text style={styles.title}>You are signed in.</Text>
      <View style={styles.card}>
        <Text style={styles.cardLabel}>Member</Text>
        <Text style={styles.mono}>{memberId}</Text>
        <Text style={[styles.badge, ledgerAvailable ? styles.badgeOn : styles.badgeOff]}>
          {ledgerAvailable ? 'Ledger access active' : 'Ledger access locked'}
        </Text>
      </View>
      <Pressable accessibilityRole="button" onPress={onOpenGroups} style={styles.primary}>
        <Text style={styles.primaryLabel}>View groups</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={onOpenDirect} style={styles.quiet}>
        <Text style={styles.quietLabel}>Direct expense</Text>
      </Pressable>
      <Pressable accessibilityRole="button" onPress={onOpenNotifications} style={styles.quiet}>
        <Text style={styles.quietLabel}>Notifications</Text>
      </Pressable>
      <Text style={styles.body}>
        Groups, one-off Direct Expenses, and recurring Series are available.
      </Text>
      {syncStatus ? <Text style={styles.muted}>{syncStatus}</Text> : null}
    </View>
  );
}

const PAPER = '#f6f4ef';
const INK = '#12211c';
const MUTED = '#5f6b64';
const LINE = '#d9d5ca';
const ACCENT = '#0f6b4f';

const styles = StyleSheet.create({
  screen: { backgroundColor: PAPER, flex: 1 },
  centered: { flex: 1, justifyContent: 'center', paddingHorizontal: 28 },
  home: { flex: 1, paddingHorizontal: 24, paddingTop: 72 },
  homeHeader: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  eyebrow: { color: ACCENT, fontSize: 12, fontWeight: '700', letterSpacing: 1.6, textTransform: 'uppercase' },
  title: { color: INK, fontSize: 30, fontWeight: '600', letterSpacing: -0.6, lineHeight: 36, marginTop: 10 },
  body: { color: MUTED, fontSize: 15, lineHeight: 22, marginTop: 12 },
  muted: { color: MUTED, fontSize: 13, marginTop: 12 },
  notice: { color: '#8a3b2a', fontSize: 13, lineHeight: 19, marginTop: 16 },
  primary: {
    alignItems: 'center',
    backgroundColor: ACCENT,
    borderRadius: 14,
    marginTop: 28,
    paddingVertical: 15
  },
  primaryLabel: { color: PAPER, fontSize: 15, fontWeight: '600' },
  quiet: { alignItems: 'center', marginTop: 16, paddingVertical: 8 },
  quietLabel: { color: MUTED, fontSize: 13, fontWeight: '500' },
  card: { backgroundColor: '#fff', borderColor: LINE, borderRadius: 16, borderWidth: 1, marginTop: 28, padding: 18 },
  cardLabel: { color: MUTED, fontSize: 11, fontWeight: '700', letterSpacing: 1.2, textTransform: 'uppercase' },
  mono: { color: INK, fontFamily: 'Courier', fontSize: 14, marginTop: 8 },
  badge: { alignSelf: 'flex-start', borderRadius: 999, fontSize: 12, fontWeight: '600', marginTop: 16, overflow: 'hidden', paddingHorizontal: 10, paddingVertical: 5 },
  badgeOn: { backgroundColor: '#dcefe5', color: '#0f5a41' },
  badgeOff: { backgroundColor: '#f1e5d8', color: '#7a4a1e' }
});
