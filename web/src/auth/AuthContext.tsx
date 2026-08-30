import {
  createContext,
  useContext,
  useMemo,
  useState,
  type PropsWithChildren,
} from "react";
import { accessTokenSubject, api, clearToken, storeToken } from "../api/client";
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
