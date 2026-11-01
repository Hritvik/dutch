import * as SecureStore from 'expo-secure-store';

import { SessionStore } from './session-store';

export const sessionStore = new SessionStore({
  getItem: (key) => SecureStore.getItemAsync(key),
  setItem: (key, value) => SecureStore.setItemAsync(key, value),
  deleteItem: (key) => SecureStore.deleteItemAsync(key)
});
