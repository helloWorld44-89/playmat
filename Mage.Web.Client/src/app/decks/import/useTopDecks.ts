import { useQuery } from '@tanstack/react-query';
import { api } from '../../connection';

/** What the server lists, and whether it's switched on. An older server without the calls reads as off. */
export function useTopDecksOverview() {
  return useQuery({
    queryKey: ['topDecksOverview'],
    queryFn: () => api.topDecksOverview(),
    staleTime: 5 * 60_000,
    retry: false,
    // the server reads mtgo.com in the background the first time, then once a day
    refetchInterval: (query) => (query.state.data?.refreshing ? 5_000 : false),
  });
}
