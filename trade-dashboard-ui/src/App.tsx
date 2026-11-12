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
import type { Rejection, SimulationRequest, SimulationStatus, Summary, TradePage } from './types';

const PAGE_SIZE = 25;
const POLL_MILLIS = 3000;
const LIVE_REFRESH_MILLIS = 500;

type Tab = 'trades' | 'rejections';

export default function App() {
  const [tab, setTab] = useState<Tab>('trades');
  const [summary, setSummary] = useState<Summary | null>(null);
  const [trades, setTrades] = useState<TradePage | null>(null);
  const [rejections, setRejections] = useState<Rejection[]>([]);
  const [simulation, setSimulation] = useState<SimulationStatus | null>(null);
  const [filter, setFilter] = useState('');
  const [page, setPage] = useState(0);
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [nextSummary, nextTrades, nextRejections] = await Promise.all([
        fetchSummary(),
        fetchTrades(filter, page, PAGE_SIZE),
        fetchRejections(),
      ]);
      setSummary(nextSummary);
      setTrades(nextTrades);
      setRejections(nextRejections);
      setError(null);
    } catch {
      setError('The query service is not reachable. Retrying.');
    }
    try {
      setSimulation(await fetchSimulation());
    } catch {
      setSimulation(null);
    }
  }, [filter, page]);

  // Load now, then poll as a fallback for when the live feed is not connected.
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), POLL_MILLIS);
    return () => clearInterval(timer);
  }, [refresh]);

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

  const onStart = async (request: SimulationRequest) => {
    try {
      setSimulation(await startSimulation(request));
    } catch {
      setError('The simulator refused the run. Check the values and try again.');
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
              filter={filter}
              onFilter={(value) => {
                setFilter(value);
                setPage(0);
              }}
              onPage={setPage}
            />
          )}
          {tab === 'rejections' && <RejectionTable rejections={rejections} />}
        </section>
      </main>
    </div>
  );
}
