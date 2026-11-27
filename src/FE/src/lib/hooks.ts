import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { session } from "./api";
export const useUser = () =>
  useSyncExternalStore(session.subscribe, session.get);
export function useNow() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}
export function useHash() {
  const [path, setPath] = useState(location.hash.slice(1) || "/");
  useEffect(() => {
    const change = () => setPath(location.hash.slice(1) || "/");
    window.addEventListener("hashchange", change);
    return () => window.removeEventListener("hashchange", change);
  }, []);
  return path;
}
export const navigate = (path: string) => {
  location.hash = path;
};
/** Serial polling; no overlapping responses, cancellation on route change and refresh. */
export function useResource<T>(
  load: (signal: AbortSignal) => Promise<T>,
  deps: unknown[],
  interval = 0,
) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const loader = useRef(load);
  loader.current = load;
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    setData(undefined);
    setLoading(true);
    setError(undefined);
    async function run() {
      try {
        const value = await loader.current(controller.signal);
        if (!controller.signal.aborted) {
          setData(value);
          setError(undefined);
        }
      } catch (e) {
        if (!controller.signal.aborted) setError(e);
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
          if (interval) timer = setTimeout(run, interval);
        }
      }
    }
    void run();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
    // Callers supply request identity explicitly.
  }, [...deps, interval, revision]);
  return {
    data,
    error,
    loading,
    refresh: useCallback(() => setRevision((value) => value + 1), []),
  };
}
