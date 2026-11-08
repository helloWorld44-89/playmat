package mage.server.websocket.service.deckimport;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Magic Online top decks against pages recorded from mtgo.com/decklists (2026-11-08, trimmed and with player names
 * replaced), in src/test/resources/deckimport/mtgo. No network: a fake fetcher serves the recordings.
 */
public class TopDecksTest {

    private static final long DAY = TopDecksService.REFRESH_MILLIS;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static String page(String name) {
        return DeckImportSourcesTest.fixture("mtgo/" + name);
    }

    /** Serves the index page and the same recorded event for every event link, and counts the requests. */
    private static final class FakeSite implements Fetcher {
        final AtomicInteger requests = new AtomicInteger();
        final List<String> urls = new ArrayList<>();
        boolean down;
        String event = page("event-legacy-challenge-16.html");

        @Override
        public String get(URI uri, String accept) throws DeckImportException {
            requests.incrementAndGet();
            urls.add(uri.toString());
            if (down) {
                throw new DeckImportException(DeckImportException.Reason.UNAVAILABLE, "mtgo.com couldn't be reached");
            }
            return uri.getPath().equals("/decklists") ? page("index.html") : event;
        }
    }

    private TopDecksService service(FakeSite site, AtomicLong clock, Path file, boolean enabled) {
        return new TopDecksService(site, clock::get, Runnable::run, millis -> {
        }, file, enabled);
    }

    private TopDecksService service(FakeSite site, AtomicLong clock) {
        return service(site, clock, folder.getRoot().toPath().resolve("topdecks.json"), true);
    }

    // parsing

    @Test
    public void indexListsEventsAndTellsLeaguesFromChallenges() throws Exception {
        List<MtgoResultsSource.EventRef> events = MtgoResultsSource.parseIndex(page("index.html"));
        Assert.assertEquals(15, events.size());
        MtgoResultsSource.EventRef legacy = events.stream().filter(e -> e.path.contains("legacy-challenge-16-2026-11-08")).findFirst().get();
        Assert.assertEquals("legacy", legacy.format);
        Assert.assertEquals("challenge-16", legacy.type);
        Assert.assertEquals("2026-11-08", legacy.date);
        Assert.assertEquals("12857693", legacy.eventId);
        Assert.assertEquals("Legacy Challenge 16", legacy.name);
        Assert.assertEquals("https://www.mtgo.com/decklist/legacy-challenge-16-2026-11-0812857693", legacy.url());
        Assert.assertTrue(MtgoResultsSource.isTopEvent(legacy));
        MtgoResultsSource.EventRef showcase = events.stream().filter(e -> e.type.contains("showcase")).findFirst().get();
        Assert.assertTrue(MtgoResultsSource.isTopEvent(showcase));
        MtgoResultsSource.EventRef league = events.stream().filter(e -> e.type.equals("league")).findFirst().get();
        Assert.assertFalse(MtgoResultsSource.isTopEvent(league));
    }

    @Test
    public void indexWithoutEventsIsTheSiteChanging() {
        Assert.assertEquals(DeckImportException.Reason.SITE_CHANGED, reasonOf(() -> MtgoResultsSource.parseIndex("<html><body>Maintenance</body></html>")));
    }

    @Test
    public void eventGivesTheTopEightWithDecksAndRecords() throws Exception {
        List<MtgoResultsSource.Finisher> top = MtgoResultsSource.parseEvent(page("event-legacy-challenge-16.html"), 8);
        Assert.assertEquals(8, top.size());
        for (int i = 0; i < top.size(); i++) {
            Assert.assertEquals(i + 1, top.get(i).rank);
            Assert.assertTrue(top.get(i).player.startsWith("Player"));
        }
        MtgoResultsSource.Finisher winner = top.get(0);
        int main = 0;
        int side = 0;
        for (TopDecksService.Card card : winner.cards) {
            if (card.side) {
                side += card.qty;
            } else {
                main += card.qty;
            }
        }
        Assert.assertTrue("a legacy main deck has at least 60 cards, not " + main, main >= 60);
        Assert.assertTrue(side > 0 && side <= 15);
        Assert.assertTrue(winner.colours.matches("[WUBRG]*"));
        Assert.assertTrue(winner.wins > 0);
    }

    @Test
    public void onlyAsManyFinishersAsAskedFor() throws Exception {
        Assert.assertEquals(3, MtgoResultsSource.parseEvent(page("event-legacy-challenge-16.html"), 3).size());
    }

    @Test
    public void eventWithoutFinalRanksIsNotFinished() throws Exception {
        String running = page("event-legacy-challenge-16.html").replaceAll("\"final_rank\": ?\\[.*?\\]\\s*,", "\"final_rank\": [],");
        Assert.assertTrue(MtgoResultsSource.parseEvent(running, 8).isEmpty());
    }

    @Test
    public void eventPageWithoutResultsDataIsTheSiteChanging() {
        Assert.assertEquals(DeckImportException.Reason.SITE_CHANGED, reasonOf(() -> MtgoResultsSource.parseEvent("<html>nothing here</html>", 8)));
        Assert.assertEquals(DeckImportException.Reason.SITE_CHANGED, reasonOf(() -> MtgoResultsSource.parseEvent("window.MTGO.decklists.data = {oops", 8)));
    }

    // the service

    @Test
    public void firstRequestRefreshesOnceAndListsNewestEventsFirst() throws Exception {
        FakeSite site = new FakeSite();
        TopDecksService service = service(site, new AtomicLong(1_000));

        TopDecksService.TopDecksOverview overview = service.overview();

        Assert.assertTrue(site.urls.get(0).equals("https://www.mtgo.com/decklists"));
        // 1 index page and at most 3 events for each of the 6 formats
        Assert.assertTrue("requests: " + site.requests.get(), site.requests.get() > 1 && site.requests.get() <= 1 + 3 * 6);
        List<TopDecksService.TopDeckSummary> legacy = service.decks("legacy");
        Assert.assertEquals(24, legacy.size());
        Assert.assertTrue(site.urls.contains("https://www.mtgo.com/decklist/legacy-challenge-16-2026-11-0812857693"));
        Assert.assertNotNull(overview);
    }

    @Test
    public void nothingIsReadAgainWithinADay() throws Exception {
        FakeSite site = new FakeSite();
        AtomicLong clock = new AtomicLong(1_000);
        TopDecksService service = service(site, clock);
        service.overview();
        int afterFirst = site.requests.get();

        clock.addAndGet(DAY - 1);
        service.overview();
        service.decks("modern");
        service.deck(service.decks("modern").get(0).getId());

        Assert.assertEquals(afterFirst, site.requests.get());

        clock.addAndGet(2);
        service.overview();
        Assert.assertTrue("a day later it reads again", site.requests.get() > afterFirst);
    }

    @Test
    public void aRestartReadsTheSavedCopyInsteadOfTheSite() throws Exception {
        FakeSite site = new FakeSite();
        AtomicLong clock = new AtomicLong(1_000);
        Path file = folder.getRoot().toPath().resolve("saved").resolve("topdecks.json");
        service(site, clock, file, true).overview();
        int afterFirst = site.requests.get();

        TopDecksService restarted = service(site, clock, file, true);
        clock.addAndGet(60_000);

        Assert.assertEquals(24, restarted.decks("legacy").size());
        Assert.assertEquals(afterFirst, site.requests.get());
    }

    @Test
    public void whenTheSiteIsDownTheOldListStaysAndTheNextTryWaitsAnHour() throws Exception {
        FakeSite site = new FakeSite();
        AtomicLong clock = new AtomicLong(1_000);
        TopDecksService service = service(site, clock);
        service.overview();

        clock.addAndGet(DAY + 1);
        site.down = true;
        int before = site.requests.get();
        service.overview();
        Assert.assertEquals("one try, which fails at the index", before + 1, site.requests.get());
        Assert.assertEquals("the old list is still served", 24, service.decks("legacy").size());

        clock.addAndGet(30 * 60_000L);
        service.overview();
        Assert.assertEquals("no second try within the hour", before + 1, site.requests.get());

        clock.addAndGet(31 * 60_000L);
        site.down = false;
        service.overview();
        Assert.assertTrue(site.requests.get() > before + 1);
    }

    @Test
    public void aSiteThatChangedLeavesNothingListedButDoesNotBreak() throws Exception {
        FakeSite site = new FakeSite();
        site.event = "<html>new layout</html>";
        TopDecksService service = service(site, new AtomicLong(1_000));

        TopDecksService.TopDecksOverview overview = service.overview();

        Assert.assertTrue(overview.deckCount() == 0);
        Assert.assertTrue(service.decks("modern").isEmpty());
    }

    @Test
    public void listedDeckBecomesAnImportedDeckWithoutSetCodes() throws Exception {
        FakeSite site = new FakeSite();
        TopDecksService service = service(site, new AtomicLong(1_000));
        service.overview();

        TopDecksService.TopDeckSummary first = service.decks("legacy").get(0);
        ImportedDeck deck = service.deck(first.getId());

        Assert.assertEquals("mtgo", deck.getSite());
        Assert.assertEquals("Constructed - Legacy", deck.getFormat());
        Assert.assertTrue(deck.getName().contains("1st place"));
        Assert.assertTrue(deck.getUrl().startsWith("https://www.mtgo.com/decklist/legacy-challenge"));
        Assert.assertTrue(deck.getMain().stream().mapToInt(c -> c.getAmount()).sum() >= 60);
        Assert.assertTrue(deck.getMain().stream().allMatch(c -> c.getSetCode() == null || c.getSetCode().isEmpty()));
        Assert.assertEquals(DeckImportException.Reason.NOT_FOUND, reasonOf(() -> service.deck("legacy-1-1")));
    }

    @Test
    public void unknownFormatIsUnsupported() {
        TopDecksService service = service(new FakeSite(), new AtomicLong(1_000));
        Assert.assertEquals(DeckImportException.Reason.UNSUPPORTED, reasonOf(() -> service.decks("commander")));
    }

    @Test
    public void switchedOffNeverReadsTheSite() {
        FakeSite site = new FakeSite();
        TopDecksService service = service(site, new AtomicLong(1_000), folder.getRoot().toPath().resolve("off.json"), false);

        TopDecksService.TopDecksOverview overview = service.overview();

        Assert.assertEquals(0, site.requests.get());
        Assert.assertTrue(overview.formatCount() == 0);
        Assert.assertEquals(DeckImportException.Reason.UNSUPPORTED, reasonOf(() -> service.decks("modern")));
    }

    @Test
    public void ordinals() {
        Assert.assertEquals("1st", TopDecksService.ordinal(1));
        Assert.assertEquals("2nd", TopDecksService.ordinal(2));
        Assert.assertEquals("3rd", TopDecksService.ordinal(3));
        Assert.assertEquals("8th", TopDecksService.ordinal(8));
        Assert.assertEquals("11th", TopDecksService.ordinal(11));
    }

    private static DeckImportException.Reason reasonOf(DeckImportSourcesTest.ThrowingRunnable action) {
        try {
            action.run();
        } catch (DeckImportException e) {
            return e.getReason();
        }
        Assert.fail("expected a DeckImportException");
        return null;
    }
}
