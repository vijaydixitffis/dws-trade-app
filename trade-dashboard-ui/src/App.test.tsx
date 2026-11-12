import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

const summary = {
  businessDate: '2026-11-12',
  businessZone: 'Asia/Kolkata',
  trades: 3,
  expired: 1,
  accepted: 4,
  replaced: 1,
  rejectedLowerVersion: 2,
  rejectedMaturityPassed: 5,
};

const trades = {
  total: 3,
  page: 0,
  size: 25,
  items: [
    { tradeId: 'T1', version: 1, counterPartyId: 'CP-1', bookId: 'B1', maturityDate: '2027-05-20', createdDate: '2026-11-12', expired: false },
    { tradeId: 'T2', version: 2, counterPartyId: 'CP-2', bookId: 'B1', maturityDate: '2027-05-20', createdDate: '2026-11-12', expired: false },
    { tradeId: 'T3', version: 3, counterPartyId: 'CP-3', bookId: 'B2', maturityDate: '2026-11-01', createdDate: '2026-10-01', expired: true },
  ],
};

const rejections = [
  {
    id: 'r1',
    reason: 'LOWER_VERSION',
    message: 'Trade T2 version 1 is lower than the stored version 2',
    source: 'PROCESSOR',
    at: '2026-11-12T10:00:00Z',
    trade: { tradeId: 'T2', version: 1, counterPartyId: 'CP-1', bookId: 'B1', maturityDate: '2027-05-20' },
  },
];

const idle = { runId: null, running: false, requested: 0, ratePerSecond: 0, sent: 0, accepted: 0, rejectedAtCapture: 0, error: null };

let calls: { url: string; init?: RequestInit }[];

function stubFetch(overrides: Record<string, unknown> = {}) {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, init });
      const body = url.includes('/summary')
        ? summary
        : url.includes('/trades')
          ? (overrides.trades ?? trades)
          : url.includes('/rejections')
            ? rejections
            : init?.method === 'POST'
              ? { ...idle, runId: 'S1', running: true, requested: 5000 }
              : idle;
      return { ok: true, status: 200, json: async () => body } as Response;
    }),
  );
}

afterEach(() => vi.unstubAllGlobals());

test('shows the store in sequence with dates as dd/MM/yyyy and the expired flag as Y or N', async () => {
  stubFetch();
  render(<App />);

  const rows = await screen.findAllByRole('row');
  const cells = (row: HTMLElement) => within(row).getAllByRole('cell').map((cell) => cell.textContent);
  expect(cells(rows[1])).toEqual(['T1', '1', 'CP-1', 'B1', '20/05/2027', '12/11/2026', 'N']);
  expect(cells(rows[3])).toEqual(['T3', '3', 'CP-3', 'B2', '01/11/2026', '01/10/2026', 'Y']);
  expect(screen.getByText('Business date 12/11/2026 (Asia/Kolkata)')).toBeInTheDocument();
});

test('shows the summary counts', async () => {
  stubFetch();
  render(<App />);

  const tiles = await screen.findByLabelText('Summary');
  expect(within(tiles).getByText('Rejected: lower version').previousSibling).toHaveTextContent('2');
  expect(within(tiles).getByText('Rejected: maturity passed').previousSibling).toHaveTextContent('5');
});

test('lists rejected trades with the reason on the Rejections tab', async () => {
  stubFetch();
  render(<App />);
  await screen.findByText('T1');

  await userEvent.click(screen.getByRole('tab', { name: 'Rejections' }));

  expect(screen.getByText('Lower version')).toBeInTheDocument();
  expect(screen.getByText('Trade store')).toBeInTheDocument();
  expect(screen.getByText('Trade T2 version 1 is lower than the stored version 2')).toBeInTheDocument();
});

test('filters the store by trade id prefix', async () => {
  stubFetch();
  render(<App />);
  await screen.findByText('T1');

  await userEvent.type(screen.getByPlaceholderText('e.g. T2'), 'T2');

  await waitFor(() => expect(calls.some((call) => call.url.includes('tradeId=T2'))).toBe(true));
});

test('starts a simulator run with the values on the form', async () => {
  stubFetch();
  render(<App />);
  const start = await screen.findByRole('button', { name: 'Start run' });
  await waitFor(() => expect(start).toBeEnabled());

  await userEvent.click(start);

  const post = calls.find((call) => call.init?.method === 'POST');
  expect(post?.url).toContain('/api/v1/simulations');
  expect(JSON.parse(String(post?.init?.body))).toMatchObject({ count: 5000, ratePerSecond: 500, lowerVersionPct: 10 });
  expect(await screen.findByRole('button', { name: 'Stop run' })).toBeInTheDocument();
});

test('says so when the store is empty', async () => {
  stubFetch({ trades: { total: 0, page: 0, size: 25, items: [] } });
  render(<App />);

  expect(await screen.findByText('No trades in the store yet.')).toBeInTheDocument();
});
