import type { Rejection, SimulationRequest, SimulationStatus, Summary, TradePage } from './types';

export const QUERY_URL: string = import.meta.env.VITE_QUERY_URL ?? 'http://localhost:8083';
export const SIMULATOR_URL: string = import.meta.env.VITE_SIMULATOR_URL ?? 'http://localhost:8084';

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  // 409 from the simulator still carries the current status in its body.
  if (!response.ok && response.status !== 409) {
    throw new Error(`${response.status} from ${url}`);
  }
  return (await response.json()) as T;
}

export function fetchSummary(): Promise<Summary> {
  return json(`${QUERY_URL}/api/v1/summary`);
}

export function fetchTrades(tradeId: string, page: number, size: number): Promise<TradePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (tradeId.trim()) {
    params.set('tradeId', tradeId.trim());
  }
  return json(`${QUERY_URL}/api/v1/trades?${params}`);
}

export function fetchRejections(): Promise<Rejection[]> {
  return json(`${QUERY_URL}/api/v1/rejections?limit=200`);
}

export function fetchSimulation(): Promise<SimulationStatus> {
  return json(`${SIMULATOR_URL}/api/v1/simulations/current`);
}

export function startSimulation(request: SimulationRequest): Promise<SimulationStatus> {
  return json(`${SIMULATOR_URL}/api/v1/simulations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
}

export function stopSimulation(): Promise<SimulationStatus> {
  return json(`${SIMULATOR_URL}/api/v1/simulations/current`, { method: 'DELETE' });
}
