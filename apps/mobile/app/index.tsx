import * as AuthSession from 'expo-auth-session';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';

import { createAppConfig } from '../src/app-config';
import { screenFor } from '../src/app-screen';
import { sessionStore } from '../src/expo-session-store';
import { loadMobileAuthorizationRequest } from '../src/oauth-client';
import { validateAuthorizationRedirect } from '../src/oauth-redirect';
import { fetchMemberProfile, fetchProfileState, type ProfileStateResult } from '../src/profile-client';
import { initialSessionState, reduceSessionState, type SessionState } from '../src/session-state';
import { createDevAccessToken, readAppSubject } from '../src/token';

const config = createAppConfig(process.env);
const BOOTING: SessionState = { kind: 'booting', ledgerAvailable: false };

export default function HomeScreen() {
  const [session, setSession] = useState<SessionState>(BOOTING);
  const [profile, setProfile] = useState<ProfileStateResult>();
  const [online, setOnline] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const accessToken = useRef<string | null>(null);

  const apply = useCallback((event: Parameters<typeof reduceSessionState>[1]) => {
    setSession((current) => reduceSessionState(current, event));
  }, []);

  const checkProfile = useCallback(async (token: string) => {
    const subject = readAppSubject(token);
    try {
      const state = await fetchProfileState(config.apiBaseUrl, token);
      setOnline(true);
      setProfile(state);
      if (state.state !== 'PROFILE_READY') {
        return;
      }
      const member = await fetchMemberProfile(config.apiBaseUrl, token);
      if (member) {
        setSession((current) => reduceSessionState(current, {
          type: 'memberProfileReady',
          identityId: subject,
          memberId: member.memberId
        }));
      }
    } catch {
      setOnline(false);
    }
  }, []);

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
    if (profile?.state === 'PROFILE_READY') return;
    const delay = (profile?.retryAfterSeconds ?? 5) * 1000;
    const timer = setTimeout(() => {
      if (accessToken.current) void checkProfile(accessToken.current);
    }, delay);
    return () => clearTimeout(timer);
  }, [session.kind, profile, checkProfile]);

  const retry = useCallback(() => {
    if (accessToken.current) void checkProfile(accessToken.current);
  }, [checkProfile]);

  const signIn = useCallback(async () => {
    if (!config.issuer) {
      setMessage('Set EXPO_PUBLIC_IAM_ISSUER before signing in.');
      return;
    }
    setBusy(true);
    setMessage(null);
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
    accessToken.current = null;
    setProfile(undefined);
    apply({ type: 'logoutCompleted' });
  }, [apply]);

  const startDevSession = useCallback(async () => {
    const token = createDevAccessToken();
    const subject = readAppSubject(token);
    await sessionStore.save({ accessToken: token, refreshToken: '', identityId: subject });
    accessToken.current = token;
    setProfile(undefined);
    setSession({ kind: 'profileRequired', identityId: subject, ledgerAvailable: false });
    await checkProfile(token);
  }, [checkProfile]);

  const screen = screenFor({ session, profile, online });

  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />
      {screen === 'loading' ? <Loading /> : null}
      {screen === 'signedOut' ? (
        <SignedOut
          busy={busy}
          message={message}
          onSignIn={signIn}
          devSession={config.devSession}
          onDevSession={startDevSession}
        />
      ) : null}
      {screen === 'profileRequired' ? (
        <ProfileRequired
          retryAfter={profile?.retryAfterSeconds}
          message={message}
          onRetry={retry}
          onSignOut={signOut}
        />
      ) : null}
      {screen === 'profiling' ? <Loading label="Finishing your profile" /> : null}
      {screen === 'offline' ? <Offline message={message} onRetry={retry} /> : null}
      {screen === 'home' && session.kind === 'authenticated' ? (
        <Home memberId={session.memberId} ledgerAvailable={session.ledgerAvailable} onSignOut={signOut} />
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
  onDevSession
}: {
  busy: boolean;
  message: string | null;
  onSignIn: () => void;
  devSession: boolean;
  onDevSession: () => void;
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
        <Pressable accessibilityRole="button" onPress={onDevSession} style={styles.quiet}>
          <Text style={styles.quietLabel}>Use test identity</Text>
        </Pressable>
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

function Offline({ message, onRetry }: { message: string | null; onRetry: () => void }) {
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
    </View>
  );
}

function Home({
  memberId,
  ledgerAvailable,
  onSignOut
}: {
  memberId: string;
  ledgerAvailable: boolean;
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
      <Text style={styles.body}>
        Groups and balances arrive in the next slice. Until then this shell only proves the
        identity and profile path.
      </Text>
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
