import { ClerkProvider } from "@clerk/clerk-react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import { initializeSupabase } from "./supabaseClient";
import { ThemeProvider } from "./components/theme-provider";
import { SettingsProvider } from "./contexts/SettingsContext";

const queryClient = new QueryClient();

type Config = {
  clerkPublishableKey: string;
  supabaseUrl: string;
  supabaseAnonKey: string;
};

function ConfiguredApp() {
  const [config, setConfig] = useState<Config | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [supabaseReady, setSupabaseReady] = useState(false);

  useEffect(() => {
    const apiBase = import.meta.env.VITE_API_URL ?? "";
    fetch(`${apiBase}/config`)
      .then((res) => {
        if (!res.ok) {
          throw new Error(`Config fetch failed with status ${res.status}`);
        }
        return res.json();
      })
      .then(async (cfg: Config) => {
        setConfig(cfg);
        // Initialize Supabase after fetching config
        if (cfg.supabaseUrl && cfg.supabaseAnonKey) {
          try {
            await initializeSupabase();
            setSupabaseReady(true);
          } catch (err) {
            console.error("Failed to initialize Supabase:", err);
          }
        } else {
          setSupabaseReady(true); // Continue without Supabase
        }
      })
      .catch((err) => setError(err.message));
  }, []);

  if (error) {
    return <div>Failed to load config: {error}</div>;
  }

  if (!config?.clerkPublishableKey || !supabaseReady) {
    return null;
  }

  return (
    <QueryClientProvider client={queryClient}>
      <ClerkProvider publishableKey={config.clerkPublishableKey}>
        <ThemeProvider defaultTheme="dark" storageKey="app-theme">
          <SettingsProvider>
            <App />
          </SettingsProvider>
        </ThemeProvider>
      </ClerkProvider>
    </QueryClientProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ConfiguredApp />
  </StrictMode>,
);
