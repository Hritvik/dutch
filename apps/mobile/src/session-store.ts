export type SessionMaterial = {
  accessToken: string;
  refreshToken: string;
  identityId: string;
};

export type SecureKeyValueStore = {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  deleteItem(key: string): Promise<void>;
};

const SESSION_KEY = 'dutch.session.v1';

export class SessionStore {
  constructor(private readonly secureStore: SecureKeyValueStore) {}

  async save(material: SessionMaterial): Promise<void> {
    await this.secureStore.setItem(SESSION_KEY, JSON.stringify(material));
  }

  async load(): Promise<SessionMaterial | null> {
    const raw = await this.secureStore.getItem(SESSION_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as Partial<SessionMaterial>;
      return typeof parsed.accessToken === 'string'
        && typeof parsed.refreshToken === 'string'
        && typeof parsed.identityId === 'string'
        ? { accessToken: parsed.accessToken, refreshToken: parsed.refreshToken, identityId: parsed.identityId }
        : null;
    } catch {
      return null;
    }
  }

  async wipe(): Promise<void> {
    await this.secureStore.deleteItem(SESSION_KEY);
  }
}
