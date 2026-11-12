import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

async function openDialog(button: 'Quick run' | 'Detailed run') {
  const opener = await screen.findByRole('button', { name: button });
  await waitFor(() => expect(opener).toBeEnabled());
  await userEvent.click(opener);
  return screen.findByRole('dialog');
}

function lastPost() {
  const post = calls.filter((call) => call.init?.method === 'POST').pop();
  return { url: post?.url, body: JSON.parse(String(post?.init?.body ?? '{}')) };
}

test('quick run opens a dialog with the bulk settings and starts a run with them', async () => {
  stubFetch();
  render(<App />);

  const dialog = await openDialog('Quick run');
  expect(within(dialog).getByRole('heading', { name: 'Quick run' })).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Start run' }));

  const { url, body } = lastPost();
  expect(url).toContain('/api/v1/simulations');
  expect(body).toMatchObject({ count: 5000, ratePerSecond: 500, lowerVersionPct: 10 });
  expect(body.counterParties).toBeUndefined();
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(await screen.findByRole('button', { name: 'Stop run' })).toBeInTheDocument();
});

test('quick run can be cancelled with the button, Escape or a click outside the dialog', async () => {
  stubFetch();
  render(<App />);

  await openDialog('Quick run');
  await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  await openDialog('Quick run');
  await userEvent.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

  await openDialog('Quick run');
  await userEvent.click(screen.getByTestId('modal-backdrop'));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(calls.some((call) => call.init?.method === 'POST')).toBe(false);
});

test('detailed run sends the chosen counter-parties, books, maturity window and trade id prefix', async () => {
  stubFetch();
  render(<App />);

  const dialog = await openDialog('Detailed run');
  expect(within(dialog).getByRole('heading', { name: 'Detailed run' })).toBeInTheDocument();
  await userEvent.type(within(dialog).getByLabelText('Counter-party ids'), 'ACME, GLOBEX{enter}ACME');
  await userEvent.type(within(dialog).getByLabelText('Book ids'), 'EQ-1');
  await userEvent.clear(within(dialog).getByLabelText('Earliest maturity (days ahead)'));
  await userEvent.type(within(dialog).getByLabelText('Earliest maturity (days ahead)'), '30');
  await userEvent.clear(within(dialog).getByLabelText('Latest maturity (days ahead)'));
  await userEvent.type(within(dialog).getByLabelText('Latest maturity (days ahead)'), '90');
  await userEvent.type(within(dialog).getByLabelText('Trade id prefix'), 'DEMO');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Start run' }));

  expect(lastPost().body).toMatchObject({
    count: 5000,
    counterParties: ['ACME', 'GLOBEX'],
    books: ['EQ-1'],
    minMaturityDays: 30,
    maxMaturityDays: 90,
    tradeIdPrefix: 'DEMO',
  });
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

test('detailed run lets the scenario mix be chosen with sliders and shows the remainder as new trades', async () => {
  stubFetch();
  render(<App />);
  const dialog = await openDialog('Detailed run');
  expect(within(dialog).getByText('New trades: 50 %')).toBeInTheDocument();

  fireEvent.change(within(dialog).getByLabelText('Lower version %'), { target: { value: '40' } });
  expect(within(dialog).getByText('New trades: 20 %')).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Start run' }));

  expect(lastPost().body).toMatchObject({ lowerVersionPct: 40, higherVersionPct: 20, pastMaturityPct: 10 });
});

test('detailed run refuses a mix above 100 % or a maturity window that ends before it starts', async () => {
  stubFetch();
  render(<App />);
  const dialog = await openDialog('Detailed run');
  const start = within(dialog).getByRole('button', { name: 'Start run' });

  fireEvent.change(within(dialog).getByLabelText('Higher version %'), { target: { value: '90' } });
  expect(within(dialog).getByText('The four percentages must not exceed 100.')).toBeInTheDocument();
  expect(start).toBeDisabled();
  fireEvent.change(within(dialog).getByLabelText('Higher version %'), { target: { value: '20' } });
  expect(start).toBeEnabled();

  fireEvent.change(within(dialog).getByLabelText('Earliest maturity (days ahead)'), { target: { value: '50' } });
  fireEvent.change(within(dialog).getByLabelText('Latest maturity (days ahead)'), { target: { value: '10' } });
  expect(within(dialog).getByText('The latest maturity must not be before the earliest.')).toBeInTheDocument();
  expect(start).toBeDisabled();
});

test('the run buttons are unavailable while a run is in progress', async () => {
  stubFetch();
  render(<App />);
  const dialog = await openDialog('Quick run');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Start run' }));

  expect(await screen.findByRole('button', { name: 'Stop run' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Quick run' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Detailed run' })).toBeDisabled();
});

test('calls the services through same-origin paths so the browser login covers them', async () => {
  stubFetch();
  render(<App />);
  await screen.findByText('T1');

  expect(calls.map((call) => call.url)).toEqual(
    expect.arrayContaining([
      '/query/api/v1/summary',
      expect.stringMatching(/^\/query\/api\/v1\/trades\?/),
      '/simulator/api/v1/simulations/current',
    ]),
  );
});

test('says so when the store is empty', async () => {
  stubFetch({ trades: { total: 0, page: 0, size: 25, items: [] } });
  render(<App />);

  expect(await screen.findByText('No trades in the store yet.')).toBeInTheDocument();
});
