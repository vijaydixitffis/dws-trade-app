export interface Trade {
  tradeId: string;
  version: number;
  counterPartyId: string;
  bookId: string;
  maturityDate: string;
}

export interface StoredTrade extends Trade {
  createdDate: string;
  expired: boolean;
}

export interface TradePage {
  total: number;
  page: number;
  size: number;
  items: StoredTrade[];
}

export type RejectionReason = 'LOWER_VERSION' | 'MATURITY_DATE_PASSED' | 'INVALID_TRADE';

export interface Rejection {
  id: string;
  reason: RejectionReason;
  message: string;
  source: string;
  at: string;
  /** A trade that failed validation may be missing fields. */
  trade: Partial<Trade>;
}

export interface Summary {
  businessDate: string;
  businessZone: string;
  trades: number;
  expired: number;
  accepted: number;
  replaced: number;
  rejectedLowerVersion: number;
  rejectedMaturityPassed: number;
}

export interface SimulationRequest {
  count: number;
  ratePerSecond: number;
  higherVersionPct: number;
  sameVersionPct: number;
  lowerVersionPct: number;
  pastMaturityPct: number;
  /** Optional variety of the generated trades; left out, the simulator uses its built-in pools. */
  counterParties?: string[];
  books?: string[];
  minMaturityDays?: number;
  maxMaturityDays?: number;
  tradeIdPrefix?: string;
}

export interface SimulationStatus {
  runId: string | null;
  running: boolean;
  requested: number;
  ratePerSecond: number;
  sent: number;
  accepted: number;
  rejectedAtCapture: number;
  error: string | null;
}
