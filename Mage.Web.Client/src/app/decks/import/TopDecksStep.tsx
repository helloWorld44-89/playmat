import { useQuery } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import { useState } from 'react';
import { siteById } from '../../../core/deckImport/sites';
import { api } from '../../connection';
import { Button } from '../../ui/Button';
import { ManaCost } from '../../ui/ManaCost';
import { SiteMark } from './SiteMark';
import { timeAgo } from './text';
import { useTopDecksOverview } from './useTopDecks';
import styles from './TopDecksStep.module.css';

const ordinal = (n: number) => {
  const tens = n % 100;
  const suffix = tens >= 11 && tens <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th';
  return `${n}${suffix}`;
};

const day = new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric' });
const dateOf = (iso: string | undefined) => {
  const at = iso ? new Date(`${iso}T12:00:00`) : null;
  return at && !Number.isNaN(at.getTime()) ? day.format(at) : '';
};

/** Recent top-8 decks from Magic Online Challenges and Showcases, by format. Picking one goes to the import preview. */
export function TopDecksStep({ failure, onPick, onBack }: { failure?: string; onPick(id: string): void; onBack(): void }) {
  const overview = useTopDecksOverview();
  const formats = overview.data?.formats ?? [];
  const [chosen, setChosen] = useState<string | null>(null);
  // a choice the server's list no longer has falls back to the first format with decks
  const format = (chosen && formats.some((entry) => entry.id === chosen) ? chosen : null) ?? formats.find((entry) => (entry.decks ?? 0) > 0)?.id ?? formats[0]?.id ?? null;
  const updatedAt = overview.data?.updatedAt;

  const list = useQuery({
    queryKey: ['topDecksList', format, updatedAt],
    queryFn: () => api.topDecksList(format!),
    enabled: !!format,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const site = siteById('mtgo')!;
  const waiting = overview.data?.enabled && !updatedAt;
  const decks = list.data ?? [];

  return (
    <div className={styles.body}>
      <header className={styles.head}>
        <SiteMark site={site} size="lg" />
        <div>
          <h3 className={styles.title}>Top decks</h3>
          <p className={styles.note}>
            The best finishers of recent Magic Online Challenges and Showcases.
            {updatedAt ? ` Read ${timeAgo(updatedAt)} from ` : ' From '}
            <a href={overview.data?.sourceUrl ?? 'https://www.mtgo.com/decklists'} target="_blank" rel="noopener noreferrer">mtgo.com/decklists</a>.
          </p>
        </div>
      </header>

      {failure && <p className={styles.failure} role="alert">{failure}</p>}

      {overview.isError || overview.data?.enabled === false ? (
        <p className={styles.empty}>This server doesn’t list top decks.</p>
      ) : (
        <>
          <div className={styles.formats} role="group" aria-label="Format">
            {formats.map((entry) => (
              <button
                key={entry.id}
                type="button"
                className={styles.format}
                aria-pressed={entry.id === format}
                disabled={(entry.decks ?? 0) === 0}
                onClick={() => setChosen(entry.id ?? null)}
              >
                {entry.name}
              </button>
            ))}
          </div>

          <div className={styles.listWrap} aria-busy={list.isFetching || undefined}>
            {waiting ? (
              <p className={styles.empty} role="status">The server is reading the first results from Magic Online. This takes about half a minute.</p>
            ) : list.isError ? (
              <p className={styles.empty} role="alert">Couldn’t load the list. Check the connection and try again.</p>
            ) : list.isPending && !!format ? (
              <p className={styles.empty} role="status">Loading…</p>
            ) : decks.length === 0 ? (
              <p className={styles.empty}>No decks listed for this format right now.</p>
            ) : (
              <ul className={styles.list}>
                {decks.map((deck) => (
                  <li key={deck.id} className={styles.row}>
                    <span className={styles.place} aria-label={`${ordinal(deck.rank ?? 0)} place`}>{ordinal(deck.rank ?? 0)}</span>
                    <div className={styles.what}>
                      <span className={styles.player}>
                        {deck.player}
                        <span className={styles.record}> · {deck.wins}–{deck.losses}</span>
                      </span>
                      <span className={styles.event}>
                        {deck.event}{deck.date ? ` · ${dateOf(deck.date)}` : ''}
                        {deck.eventUrl && (
                          <>
                            {' '}
                            <a href={deck.eventUrl} target="_blank" rel="noopener noreferrer" aria-label={`${deck.event} on mtgo.com (opens in a new tab)`}>
                              <ExternalLink size={12} aria-hidden="true" />
                            </a>
                          </>
                        )}
                      </span>
                    </div>
                    <ManaCost cost={deck.colours ? [...deck.colours].map((colour) => `{${colour}}`).join('') : '{C}'} size="sm" />
                    <Button variant="print" size="md" onClick={() => onPick(deck.id ?? '')}>Import</Button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}

      <footer className={styles.foot}>
        <Button variant="quiet" onClick={onBack}>Back</Button>
      </footer>
    </div>
  );
}
