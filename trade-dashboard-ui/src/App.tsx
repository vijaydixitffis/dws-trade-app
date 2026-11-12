import { useCallback, useEffect, useRef, useState } from 'react';
import {
  QUERY_URL,
  fetchRejections,
  fetchSimulation,
  fetchSummary,
  fetchTrades,
  startSimulation,
  stopSimulation,
} from './api';
import { RejectionTable } from './components/RejectionTable';
import { SimulatorPanel } from './components/SimulatorPanel';
import { SummaryBar } from './components/SummaryBar';
import { TradeTable } from './components/TradeTable';
import { formatDate } from './format';
import type { Cursor, Rejection, SimulationRequest, SimulationStatus, Summary, TradePage } from './types';

const PAGE_SIZE = 25;
const POLL_MILLIS = 3000;
const LIVE_REFRESH_MILLIS = 500;
const FILTER_PAUSE_MILLIS = 300;

type Tab = 'trades' | 'rejections';

export default function App() {
  const [tab, setTab] = useState<Tab>('trades');
  const [summary, setSummary] = useState<Summary | null>(null);
  const [trades, setTrades] = useState<TradePage | null>(null);
  const [rejections, setRejections] = useState<Rejection[]>([]);
  const [simulation, setSimulation] = useState<SimulationStatus | null>(null);
  // What is typed, and what is asked of the server once typing pauses.
  const [filter, setFilter] = useState('');
  const [appliedFilter, setAppliedFilter] = useState('');
  // The cursor each page was loaded with; the last one is the page shown. The first page has none.
  const [cursors, setCursors] = useState<(Cursor | null)[]>([null]);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only the newest refresh may update the screen, and starting one cancels the one before it.
  const inflight = useRef<AbortController | null>(null);
  const latest = useRef(0);

  const refresh = useCallback(async () => {
    inflight.current?.abort();
    const controller = new AbortController();
    inflight.current = controller;
    const id = ++latest.current;
    const stale = () => id !== latest.current;
    try {
      const [nextSummary, nextTrades, nextRejections] = await Promise.all([
        fetchSummary(controller.signal),
        fetchTrades(appliedFilter, cursors[cursors.length - 1], PAGE_SIZE, controller.signal),
        // The rejections are only needed while their tab is open.
        tab === 'rejections' ? fetchRejections(controller.signal) : Promise.resolve(null),
      ]);
      if (stale()) {
        return;
      }
      setSummary(nextSummary);
      setTrades(nextTrades);
      if (nextRejections) {
        setRejections(nextRejections);
      }
      setError(null);
    } catch {
      if (stale()) {
        return;
      }
      setError('The query service is not reachable. Retrying.');
    }
    try {
      const nextSimulation = await fetchSimulation(controller.signal);
      if (!stale()) {
        setSimulation(nextSimulation);
      }
    } catch {
      if (!stale()) {
        setSimulation(null);
      }
    }
  }, [appliedFilter, cursors, tab]);

  // Wait for a pause in typing, then filter from the first page.
  useEffect(() => {
    const timer = setTimeout(() => {
      const next = filter.trim();
      if (next !== appliedFilter) {
        setAppliedFilter(next);
        setCursors([null]);
      }
    }, FILTER_PAUSE_MILLIS);
    return () => clearTimeout(timer);
  }, [filter, appliedFilter]);

  // Load now, then poll as a fallback for when the live feed is not connected.
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), POLL_MILLIS);
    return () => clearInterval(timer);
  }, [refresh]);

  useEffect(() => () => inflight.current?.abort(), []);

  // The live feed says "something changed"; refresh at most twice a second under load.
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;
  useEffect(() => {
    if (typeof EventSource === 'undefined') {
      return;
    }
    const source = new EventSource(`${QUERY_URL}/api/v1/stream`);
    let pending: ReturnType<typeof setTimeout> | null = null;
    const onChange = () => {
      if (pending === null) {
        pending = setTimeout(() => {
          pending = null;
          void refreshRef.current();
        }, LIVE_REFRESH_MILLIS);
      }
    };
    source.addEventListener('connected', () => setLive(true));
    source.addEventListener('trade-event', onChange);
    source.addEventListener('trade-rejection', onChange);
    source.onerror = () => setLive(false);
    return () => {
      source.close();
      if (pending !== null) {
        clearTimeout(pending);
      }
    };
  }, []);

  /** @returns null when the run started, otherwise the reason it did not */
  const onStart = async (request: SimulationRequest): Promise<string | null> => {
    try {
      const result = await startSimulation(request);
      if (!result.ok) {
        return result.message;
      }
      setSimulation(result.status);
      setError(null);
      return null;
    } catch {
      return 'The simulator is not reachable.';
    }
  };

  const onStop = async () => {
    try {
      setSimulation(await stopSimulation());
    } catch {
      setSimulation(null);
    }
  };

  return (
    <div className="page">
      <header>
        <h1>Trade Store</h1>
        <div className="header-meta">
          {summary && (
            <span>
              Business date {formatDate(summary.businessDate)} ({summary.businessZone})
            </span>
          )}
          <span className={live ? 'pill pill-blue' : 'pill pill-grey'}>{live ? 'Live' : 'Polling'}</span>
        </div>
      </header>

      <main>
        {error && (
          <div className="notice" role="alert">
            {error}
          </div>
        )}
        {summary && <SummaryBar summary={summary} />}
        <SimulatorPanel status={simulation} onStart={onStart} onStop={onStop} />

        <section className="panel">
          <div className="tabs" role="tablist">
            <button role="tab" aria-selected={tab === 'trades'} onClick={() => setTab('trades')}>
              Trades
            </button>
            <button role="tab" aria-selected={tab === 'rejections'} onClick={() => setTab('rejections')}>
              Rejections
            </button>
          </div>
          {tab === 'trades' && trades && (
            <TradeTable
              page={trades}
              pageNumber={cursors.length}
              filter={filter}
              onFilter={setFilter}
              onPrevious={() => setCursors((stack) => (stack.length > 1 ? stack.slice(0, -1) : stack))}
              onNext={() => trades.next && setCursors((stack) => [...stack, trades.next])}
            />
          )}
          {tab === 'rejections' && <RejectionTable rejections={rejections} />}
        </section>
      </main>
    </div>
  );
}
