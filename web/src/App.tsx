import { lazy, Suspense, useEffect, useState, type ComponentType } from "react";
import { useAuth } from "./auth/AuthContext";
import { AppShell } from "./components/AppShell";
import { AuthPage } from "./pages/AuthPage";
import { DashboardPage } from "./pages/DashboardPage";
const PlansPage = lazy(() =>
  import("./pages/PlansPage").then((m) => ({ default: m.PlansPage })),
);
const WorkoutsPage = lazy(() =>
  import("./pages/WorkoutsPage").then((m) => ({ default: m.WorkoutsPage })),
);
const ExercisesPage = lazy(() =>
  import("./pages/ExercisesPage").then((m) => ({ default: m.ExercisesPage })),
);
const ProgressPage = lazy(() =>
  import("./pages/ProgressPage").then((m) => ({ default: m.ProgressPage })),
);
const CoachPage = lazy(() =>
  import("./pages/CoachPage").then((m) => ({ default: m.CoachPage })),
);
const NotificationsPage = lazy(() =>
  import("./pages/NotificationsPage").then((m) => ({
    default: m.NotificationsPage,
  })),
);
const ProfilePage = lazy(() =>
  import("./pages/ProfilePage").then((m) => ({ default: m.ProfilePage })),
);

const routes: Record<string, ComponentType> = {
  "/": DashboardPage,
  "/plans": PlansPage,
  "/workouts": WorkoutsPage,
  "/exercises": ExercisesPage,
  "/progress": ProgressPage,
  "/coach": CoachPage,
  "/notifications": NotificationsPage,
  "/profile": ProfilePage,
};

export function App() {
  const auth = useAuth();
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    const update = () => setPath(location.pathname);
    addEventListener("popstate", update);
    return () => removeEventListener("popstate", update);
  }, []);
  if (!auth.authenticated || path === "/login" || path === "/register")
    return <AuthPage />;
  const Page = routes[path] ?? DashboardPage;
  return (
    <AppShell>
      <Suspense fallback={<div className="route-loading">加载训练数据…</div>}>
        <Page />
      </Suspense>
    </AppShell>
  );
}
