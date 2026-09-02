import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";
import {
  accessTokenSubject,
  api,
  AUTH_EXPIRED_EVENT,
  clearToken,
  storeToken,
} from "../api/client";
import { clearAgentSession } from "../agent/sessionStorage";
import { clearPendingAction } from "../agent/pendingActionStorage";

type AuthContextValue = {
  authenticated: boolean;
  login(username: string, password: string): Promise<void>;
  register(username: string, email: string, password: string): Promise<void>;
  logout(): Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [authenticated, setAuthenticated] = useState(() =>
    Boolean(sessionStorage.getItem("fitpilot_access")),
  );
  useEffect(() => {
    const expire = (event: Event) => {
      const userId = (event as CustomEvent<string | null>).detail ?? null;
      clearAgentSession(userId);
      clearPendingAction(userId);
      setAuthenticated(false);
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, expire);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, expire);
  }, []);
  const value = useMemo<AuthContextValue>(
    () => ({
      authenticated,
      async login(username, password) {
        const result = await api<{ accessToken: string }>(
          "/auth/login",
          {
            method: "POST",
            body: JSON.stringify({ username, password }),
          },
          false,
        );
        storeToken(result.accessToken);
        setAuthenticated(true);
      },
      async register(username, email, password) {
        await api(
          "/auth/register",
          {
            method: "POST",
            body: JSON.stringify({ username, email, password }),
          },
          false,
        );
        const result = await api<{ accessToken: string }>(
          "/auth/login",
          {
            method: "POST",
            body: JSON.stringify({ username, password }),
          },
          false,
        );
        storeToken(result.accessToken);
        setAuthenticated(true);
      },
      async logout() {
        const userId = accessTokenSubject();
        try {
          await api("/auth/logout", { method: "POST" }, false);
        } catch {
          // Local logout must still complete when the session is already invalid
          // or the backend is temporarily unavailable.
        } finally {
          clearAgentSession(userId);
          clearPendingAction(userId);
          clearToken();
          setAuthenticated(false);
        }
      },
    }),
    [authenticated],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("AuthProvider is missing");
  return value;
}
