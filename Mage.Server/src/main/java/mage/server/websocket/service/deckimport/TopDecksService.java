package mage.server.websocket.service.deckimport;

import com.google.gson.Gson;
import mage.utils.MageVersion;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Web client bridge: the top finishers of recent Magic Online Challenges and Showcases, from mtgo.com/decklists,
 * for the "top decks" import (TopDecksApi). Read only.
 * <p>
 * The server reads mtgo.com at most once a day. The result is a snapshot kept in memory and in a file, so players are
 * served from it and a restart doesn't read the site again. A request that finds the snapshot a day old (or missing)
 * starts one refresh in the background and is answered from the old snapshot meanwhile. A refresh reads the index page
 * and the few newest events per format, one every second or so. If it fails the old snapshot stays and the next try
 * waits an hour. Set {@code -Dxmage.web.topDecks=false} to switch the feature off.
 */
public final class TopDecksService {

    private static final Logger logger = Logger.getLogger(TopDecksService.class);

    public static final String ENABLED_PROP = "xmage.web.topDecks";
    static final String FILE_PROP = "xmage.web.topDecks.file";
    static final long REFRESH_MILLIS = 24 * 3_600_000L;
    static final long RETRY_MILLIS = 3_600_000L;
    static final long PAUSE_MILLIS = 1_500L;
    static final int EVENTS_PER_FORMAT = 3;
    static final int TOP = 8;

    private static final Map<String, String> FORMATS = new LinkedHashMap<>();

    static {
        FORMATS.put("standard", "Standard");
        FORMATS.put("pioneer", "Pioneer");
        FORMATS.put("modern", "Modern");
        FORMATS.put("legacy", "Legacy");
        FORMATS.put("vintage", "Vintage");
        FORMATS.put("pauper", "Pauper");
    }

    /** A card of a finisher's deck. */
    static final class Card {
        final String name;
        final int qty;
        final boolean side;

        Card(String name, int qty, boolean side) {
            this.name = name;
            this.qty = qty;
            this.side = side;
        }
    }

    /** A finisher and their deck, as kept in the snapshot. */
    static final class Entry {
        String id;
        String format;
        String event;
        String eventUrl;
        String date;
        int rank;
        String player;
        int wins;
        int losses;
        String colours;
        List<Card> cards;
    }

    static final class Snapshot {
        long updatedAt;
        List<Entry> entries = new ArrayList<>();
    }

    /** A finisher without the cards, for the list. */
    public static final class TopDeckSummary {
        private final String id;
        private final String format;
        private final String event;
        private final String eventUrl;
        private final String date;
        private final int rank;
        private final String player;
        private final int wins;
        private final int losses;
        private final String colours;
        private final int cards;

        TopDeckSummary(Entry e) {
            id = e.id;
            format = e.format;
            event = e.event;
            eventUrl = e.eventUrl;
            date = e.date;
            rank = e.rank;
            player = e.player;
            wins = e.wins;
            losses = e.losses;
            colours = e.colours;
            int count = 0;
            for (Card card : e.cards) {
                count += card.qty;
            }
            cards = count;
        }

        public String getId() {
            return id;
        }
    }

    /** One format and how many decks are listed for it. */
    public static final class TopDeckFormat {
        private final String id;
        private final String name;
        private final int decks;

        TopDeckFormat(String id, String name, int decks) {
            this.id = id;
            this.name = name;
            this.decks = decks;
        }
    }

    /** What the screen needs before listing: whether the feature is on, how fresh it is, and the formats. */
    public static final class TopDecksOverview {
        private final boolean enabled;
        private final Long updatedAt;
        private final boolean refreshing;
        private final String sourceName = "Magic Online decklists";
        private final String sourceUrl = MtgoResultsSource.INDEX_URL;
        private final List<TopDeckFormat> formats;

        TopDecksOverview(boolean enabled, Long updatedAt, boolean refreshing, List<TopDeckFormat> formats) {
            this.enabled = enabled;
            this.updatedAt = updatedAt;
            this.refreshing = refreshing;
            this.formats = formats;
        }

        int formatCount() {
            return formats.size();
        }

        int deckCount() {
            int count = 0;
            for (TopDeckFormat format : formats) {
                count += format.decks;
            }
            return count;
        }
    }

    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final Executor executor;
    private final LongConsumer pause;
    private final Path file;
    private final boolean enabled;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Gson gson = new Gson();
    private volatile Snapshot snapshot;
    private volatile long nextAttempt;

    public TopDecksService() {
        this(null, System::currentTimeMillis, TopDecksService::inBackground, TopDecksService::sleep,
                Paths.get(System.getProperty(FILE_PROP, "db/topdecks.json")), !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROP)));
    }

    TopDecksService(Fetcher fetcher, LongSupplier clock, Executor executor, LongConsumer pause, Path file, boolean enabled) {
        this.fetcher = fetcher != null ? fetcher : new HttpFetcher(Collections.singleton(MtgoResultsSource.HOST), userAgent());
        this.clock = clock;
        this.executor = executor;
        this.pause = pause;
        this.file = file;
        this.enabled = enabled;
        this.snapshot = enabled ? load() : null;
    }

    private static String userAgent() {
        return "Playmat/" + MageVersion.MAGE_VERSION_MAJOR + "." + MageVersion.MAGE_VERSION_MINOR + "." + MageVersion.MAGE_VERSION_RELEASE
                + " (XMage web client; reads Magic Online top decks once a day)";
    }

    private static void inBackground(Runnable task) {
        Thread thread = new Thread(task, "top-decks-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public TopDecksOverview overview() {
        refreshIfDue();
        Snapshot current = snapshot;
        List<TopDeckFormat> formats = new ArrayList<>();
        if (enabled) {
            for (Map.Entry<String, String> format : FORMATS.entrySet()) {
                formats.add(new TopDeckFormat(format.getKey(), format.getValue(), count(current, format.getKey())));
            }
        }
        return new TopDecksOverview(enabled, current == null ? null : current.updatedAt, refreshing.get(), formats);
    }

    /** The listed decks of one format: newest event first, best finisher first. */
    public List<TopDeckSummary> decks(String format) throws DeckImportException {
        if (!enabled || !FORMATS.containsKey(format)) {
            throw new DeckImportException(DeckImportException.Reason.UNSUPPORTED, "No top decks for that format");
        }
        refreshIfDue();
        List<TopDeckSummary> list = new ArrayList<>();
        Snapshot current = snapshot;
        if (current != null) {
            for (Entry entry : current.entries) {
                if (entry.format.equals(format)) {
                    list.add(new TopDeckSummary(entry));
                }
            }
        }
        return list;
    }

    /** The full deck behind a listed finisher, for the import preview. */
    public ImportedDeck deck(String id) throws DeckImportException {
        Snapshot current = enabled ? snapshot : null;
        if (current != null) {
            for (Entry e : current.entries) {
                if (e.id.equals(id)) {
                    return toDeck(e);
                }
            }
        }
        throw new DeckImportException(DeckImportException.Reason.NOT_FOUND, "That deck is no longer listed");
    }

    static ImportedDeck toDeck(Entry e) {
        ImportedDeck deck = new ImportedDeck("mtgo", e.id, e.eventUrl);
        deck.setName(e.event + ", " + ordinal(e.rank) + " place");
        deck.setAuthor(e.player);
        deck.setFormat(SourceSupport.deckType(e.format));
        deck.setDescription("Magic Online " + e.event + ", " + e.date + ": " + ordinal(e.rank) + " place, " + e.wins + "-" + e.losses
                + " in the rounds. From mtgo.com/decklists.");
        for (Card card : e.cards) {
            // MTGO set codes don't always match XMage's, so cards are matched by name alone
            deck.add(card.side ? ImportedDeck.Section.SIDE : ImportedDeck.Section.MAIN, card.name, null, null, card.qty);
        }
        return deck;
    }

    static String ordinal(int n) {
        int tens = n % 100;
        String suffix = tens >= 11 && tens <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    private static int count(Snapshot snapshot, String format) {
        int count = 0;
        if (snapshot != null) {
            for (Entry entry : snapshot.entries) {
                if (entry.format.equals(format)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void refreshIfDue() {
        if (!enabled) {
            return;
        }
        long now = clock.getAsLong();
        Snapshot current = snapshot;
        boolean due = current == null || now - current.updatedAt >= REFRESH_MILLIS;
        if (!due || now < nextAttempt || !refreshing.compareAndSet(false, true)) {
            return;
        }
        nextAttempt = now + RETRY_MILLIS;
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException e) {
            refreshing.set(false);
            throw e;
        }
    }

    /** Reads the index and the newest events of each format, and keeps the result. Runs on the refresh thread. */
    void refresh() {
        try {
            List<MtgoResultsSource.EventRef> index = MtgoResultsSource.parseIndex(fetcher.get(URI.create(MtgoResultsSource.INDEX_URL), "text/html"));
            Map<String, List<MtgoResultsSource.EventRef>> byFormat = new HashMap<>();
            for (MtgoResultsSource.EventRef event : index) {
                if (FORMATS.containsKey(event.format) && MtgoResultsSource.isTopEvent(event)) {
                    byFormat.computeIfAbsent(event.format, key -> new ArrayList<>()).add(event);
                }
            }
            Snapshot old = snapshot;
            Snapshot fresh = new Snapshot();
            fresh.updatedAt = clock.getAsLong();
            int events = 0;
            int failed = 0;
            for (String format : FORMATS.keySet()) {
                List<Entry> entries = new ArrayList<>();
                List<MtgoResultsSource.EventRef> candidates = byFormat.getOrDefault(format, Collections.emptyList());
                candidates.sort((a, b) -> {
                    int byDate = b.date.compareTo(a.date);
                    return byDate != 0 ? byDate : Long.compare(number(b.eventId), number(a.eventId));
                });
                int used = 0;
                for (MtgoResultsSource.EventRef event : candidates) {
                    if (used >= EVENTS_PER_FORMAT) {
                        break;
                    }
                    pause.accept(PAUSE_MILLIS);
                    try {
                        List<MtgoResultsSource.Finisher> finishers = MtgoResultsSource.parseEvent(fetcher.get(URI.create(event.url()), "text/html"), TOP);
                        events++;
                        if (finishers.isEmpty()) {
                            continue; // not finished yet
                        }
                        used++;
                        for (MtgoResultsSource.Finisher finisher : finishers) {
                            entries.add(entry(format, event, finisher));
                        }
                    } catch (DeckImportException | RuntimeException e) {
                        failed++;
                    }
                }
                if (entries.isEmpty() && old != null) {
                    // nothing new for this format: keep what we had rather than showing an empty list
                    for (Entry kept : old.entries) {
                        if (kept.format.equals(format)) {
                            entries.add(kept);
                        }
                    }
                }
                fresh.entries.addAll(entries);
            }
            if (fresh.entries.isEmpty()) {
                throw new DeckImportException(DeckImportException.Reason.SITE_CHANGED, "no finished events with decks were found");
            }
            snapshot = fresh;
            save(fresh);
            logger.info("Top decks refreshed: " + fresh.entries.size() + " decks from " + events + " events (" + failed + " events failed)");
        } catch (DeckImportException | RuntimeException e) {
            // counts and reasons only, never page contents or player names
            logger.warn("Top decks refresh failed, keeping the old list: "
                    + (e instanceof DeckImportException ? ((DeckImportException) e).getReason().wireName() : e.getClass().getSimpleName()));
        } finally {
            refreshing.set(false);
        }
    }

    private static Entry entry(String format, MtgoResultsSource.EventRef event, MtgoResultsSource.Finisher finisher) {
        Entry e = new Entry();
        e.id = format + "-" + event.eventId + "-" + finisher.rank;
        e.format = format;
        e.event = event.name;
        e.eventUrl = event.url();
        e.date = event.date;
        e.rank = finisher.rank;
        e.player = finisher.player;
        e.wins = finisher.wins;
        e.losses = finisher.losses;
        e.colours = finisher.colours;
        e.cards = finisher.cards;
        return e;
    }

    private static long number(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Snapshot load() {
        try {
            if (Files.isRegularFile(file)) {
                Snapshot loaded = gson.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Snapshot.class);
                if (loaded != null && loaded.entries != null) {
                    return loaded;
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Top decks file couldn't be read, starting without it: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private void save(Snapshot toSave) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, gson.toJson(toSave).getBytes(StandardCharsets.UTF_8));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Top decks couldn't be saved: " + e.getClass().getSimpleName());
        }
    }
}
