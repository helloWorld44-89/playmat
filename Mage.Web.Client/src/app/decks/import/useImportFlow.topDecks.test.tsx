// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { RPC_ERROR, RpcError } from '../../../core/rpc/RpcClient';
import type { FakeConnection } from '../../../test/fakeConnection';

vi.mock('../../connection', async () => (await import('../../../test/fakeConnection')).createFakeConnection());

import * as connection from '../../connection';
import { useImportFlow } from './useImportFlow';

const api = (connection as unknown as FakeConnection).api;

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
);

const deck = {
  site: 'mtgo',
  remoteId: 'modern-1-1',
  url: 'https://www.mtgo.com/decklist/modern-challenge-32-2026-11-081',
  name: 'Modern Challenge 32, 1st place',
  author: 'Ana',
  format: 'Constructed - Modern',
  main: [{ cardName: 'Lightning Bolt', amount: 4 }],
  side: [{ cardName: 'Smash to Smithereens', amount: 2 }],
};

beforeEach(() => {
  api.deckImportSources.mockResolvedValue([]);
  api.lookupCards.mockResolvedValue([]);
  api.searchCards.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

test('browsing top decks is a step of its own', () => {
  const { result } = renderHook(() => useImportFlow(), { wrapper });
  act(() => result.current.browseTopDecks());
  expect(result.current.step).toEqual({ kind: 'topDecks' });
});

test('picking a deck fetches it by id and reads it as a Magic Online deck', async () => {
  api.topDeckGet.mockResolvedValue(deck);
  const { result } = renderHook(() => useImportFlow(), { wrapper });
  act(() => result.current.browseTopDecks());

  await act(async () => {
    await result.current.pickTopDeck('modern-1-1');
  });

  expect(api.topDeckGet).toHaveBeenCalledWith('modern-1-1');
  await waitFor(() => expect(result.current.step.kind).toBe('preview'));
  const step = result.current.step;
  if (step.kind !== 'preview') throw new Error('expected the preview');
  expect(step.draft.source).toMatchObject({ site: 'mtgo', remoteId: 'modern-1-1', url: deck.url });
  expect(step.draft.name).toBe('Modern Challenge 32, 1st place');
});

test('a deck that has left the list sends the player back to it with a reason', async () => {
  api.topDeckGet.mockRejectedValue(new RpcError('topDeckGet', RPC_ERROR.DECK_IMPORT_FAILED, 'gone', { reason: 'not_found' }));
  const { result } = renderHook(() => useImportFlow(), { wrapper });

  await act(async () => {
    await result.current.pickTopDeck('modern-9-9');
  });

  expect(result.current.step).toEqual({ kind: 'topDecks', failure: expect.stringContaining('refreshed') });
});

test('any other failure keeps the list and says to try again', async () => {
  api.topDeckGet.mockRejectedValue(new Error('socket closed'));
  const { result } = renderHook(() => useImportFlow(), { wrapper });

  await act(async () => {
    await result.current.pickTopDeck('modern-1-1');
  });

  expect(result.current.step).toEqual({ kind: 'topDecks', failure: expect.stringContaining('Try again') });
});
