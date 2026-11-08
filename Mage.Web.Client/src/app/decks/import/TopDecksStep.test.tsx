// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { FakeConnection } from '../../../test/fakeConnection';

vi.mock('../../connection', async () => (await import('../../../test/fakeConnection')).createFakeConnection());

import * as connection from '../../connection';
import { TopDecksStep } from './TopDecksStep';

const api = (connection as unknown as FakeConnection).api;

const overview = {
  enabled: true,
  updatedAt: Date.now() - 3_600_000,
  refreshing: false,
  sourceUrl: 'https://www.mtgo.com/decklists',
  formats: [
    { id: 'standard', name: 'Standard', decks: 0 },
    { id: 'modern', name: 'Modern', decks: 2 },
    { id: 'legacy', name: 'Legacy', decks: 1 },
  ],
};

const modern = [
  { id: 'modern-1-1', format: 'modern', event: 'Modern Challenge 32', eventUrl: 'https://www.mtgo.com/decklist/modern-challenge-32-2026-11-081', date: '2026-11-08', rank: 1, player: 'Ana', wins: 7, losses: 1, colours: 'UR', cards: 75 },
  { id: 'modern-1-2', format: 'modern', event: 'Modern Challenge 32', eventUrl: 'https://www.mtgo.com/decklist/modern-challenge-32-2026-11-081', date: '2026-11-08', rank: 2, player: 'Bo', wins: 6, losses: 2, colours: '', cards: 75 },
];

function show(props: Partial<Parameters<typeof TopDecksStep>[0]> = {}) {
  const onPick = vi.fn();
  const onBack = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <TopDecksStep onPick={onPick} onBack={onBack} {...props} />
    </QueryClientProvider>,
  );
  return { onPick, onBack };
}

beforeEach(() => {
  api.topDecksOverview.mockResolvedValue(overview);
  api.topDecksList.mockImplementation(async (format: string) => (format === 'modern' ? modern : []));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

test('opens on the first format that has decks and lists them best first', async () => {
  show();
  expect(await screen.findByText('Ana')).toBeTruthy();
  expect(api.topDecksList).toHaveBeenCalledWith('modern');
  expect(screen.getByRole('button', { name: 'Modern' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.getByRole('button', { name: 'Standard' }).hasAttribute('disabled')).toBe(true);
  expect(screen.getByText('1st')).toBeTruthy();
  expect(screen.getByLabelText('2nd place')).toBeTruthy();
  expect(screen.getAllByText('Modern Challenge 32', { exact: false }).length).toBe(2);
});

test('links each deck back to its event page on mtgo.com', async () => {
  show();
  await screen.findByText('Ana');
  const links = screen.getAllByRole('link', { name: /on mtgo\.com/ });
  expect(links[0].getAttribute('href')).toBe('https://www.mtgo.com/decklist/modern-challenge-32-2026-11-081');
  expect(links[0].getAttribute('rel')).toContain('noopener');
});

test('importing a deck hands its id to the flow', async () => {
  const { onPick } = show();
  await screen.findByText('Ana');
  fireEvent.click(screen.getAllByRole('button', { name: 'Import' })[1]);
  expect(onPick).toHaveBeenCalledWith('modern-1-2');
});

test('another format loads its own list', async () => {
  api.topDecksList.mockImplementation(async (format: string) => (format === 'legacy' ? [{ ...modern[0], id: 'legacy-1-1', player: 'Cy' }] : modern));
  show();
  await screen.findByText('Ana');
  fireEvent.click(screen.getByRole('button', { name: 'Legacy' }));
  expect(await screen.findByText('Cy')).toBeTruthy();
});

test('says so while the server reads its first results', async () => {
  api.topDecksOverview.mockResolvedValue({ ...overview, updatedAt: undefined, refreshing: true, formats: overview.formats.map((f) => ({ ...f, decks: 0 })) });
  show();
  expect(await screen.findByText(/reading the first results/i)).toBeTruthy();
});

test('a server with top decks switched off says it has none', async () => {
  api.topDecksOverview.mockResolvedValue({ ...overview, enabled: false, formats: [] });
  show();
  expect(await screen.findByText(/doesn’t list top decks/)).toBeTruthy();
});

test('an older server without the call reads as off', async () => {
  api.topDecksOverview.mockRejectedValue(new Error('Unknown method'));
  show();
  await waitFor(() => expect(screen.getByText(/doesn’t list top decks/)).toBeTruthy());
});

test('shows why a pick failed and keeps the list', async () => {
  show({ failure: 'That list has been refreshed since you opened it. Pick again.' });
  expect(screen.getByRole('alert').textContent).toContain('refreshed since you opened it');
  expect(await screen.findByText('Ana')).toBeTruthy();
});

test('back goes to the input', async () => {
  const { onBack } = show();
  await screen.findByText('Ana');
  fireEvent.click(screen.getByRole('button', { name: 'Back' }));
  expect(onBack).toHaveBeenCalled();
});
