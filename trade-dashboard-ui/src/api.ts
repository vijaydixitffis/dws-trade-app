import type { Cursor, Rejection, SimulationRequest, SimulationStatus, Summary, TradePage } from './types';

// Same-origin paths. The web server that serves this page (nginx, or Vite in development)
// forwards them to the services, so the browser's Basic authentication applies to every call.
export const QUERY_URL: string = import.meta.env.VITE_QUERY_URL ?? '/query';
export const SIMULATOR_URL: string = import.meta.env.VITE_SIMULATOR_URL ?? '/simulator';

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    throw new Error(`${response.status} from ${url}`);
  }
  return (await response.json()) as T;
}

export function fetchSummary(signal?: AbortSignal): Promise<Summary> {
  return json(`${QUERY_URL}/api/v1/summary`, { signal });
}

/** One page of the store, after the cursor the server returned with the previous page. */
export function fetchTrades(tradeId: string, after: Cursor | null, size: number, signal?: AbortSignal): Promise<TradePage> {
  const params = new URLSearchParams({ size: String(size) });
  if (tradeId.trim()) {
    params.set('tradeId', tradeId.trim());
  }
  if (after) {
    params.set('afterTradeId', after.tradeId);
    params.set('afterVersion', String(after.version));
  }
  return json(`${QUERY_URL}/api/v1/trades?${params}`, { signal });
}

export function fetchRejections(signal?: AbortSignal): Promise<Rejection[]> {
  return json(`${QUERY_URL}/api/v1/rejections?limit=200`, { signal });
}

export function fetchSimulation(signal?: AbortSignal): Promise<SimulationStatus> {
  return json(`${SIMULATOR_URL}/api/v1/simulations/current`, { signal });
}

export type StartResult = { ok: true; status: SimulationStatus } | { ok: false; message: string };

/** Starts a run. A refusal is returned with the server's reason so the dialog can show it. */
export async function startSimulation(request: SimulationRequest): Promise<StartResult> {
  const response = await fetch(`${SIMULATOR_URL}/api/v1/simulations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (response.ok) {
    return { ok: true, status: (await response.json()) as SimulationStatus };
  }
  if (response.status === 409) {
    return { ok: false, message: 'Another run is already in progress.' };
  }
  let message = `The simulator refused the run (HTTP ${response.status}).`;
  try {
    const body = (await response.json()) as { detail?: unknown };
    if (typeof body.detail === 'string' && body.detail) {
      message = body.detail;
    }
  } catch {
    // keep the generic message
  }
  return { ok: false, message };
}

export function stopSimulation(): Promise<SimulationStatus> {
  return json(`${SIMULATOR_URL}/api/v1/simulations/current`, { method: 'DELETE' });
}
