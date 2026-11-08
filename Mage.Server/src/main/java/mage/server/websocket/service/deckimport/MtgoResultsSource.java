package mage.server.websocket.service.deckimport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web client bridge: reads Magic Online tournament results from mtgo.com/decklists. Pure parsing, no network:
 * {@link TopDecksService} fetches the pages and keeps the result.
 * <p>
 * The index page lists a month of events as {@code /decklist/{format}-{type}-{yyyy}-{mm}-{dd}{id}} links. An event page
 * carries its results as JSON in {@code window.MTGO.decklists.data = {...}}: the decks, the final ranks and the records.
 */
final class MtgoResultsSource {

    static final String HOST = "www.mtgo.com";
    static final String INDEX_URL = "https://www.mtgo.com/decklists";

    /** One event on the index page. */
    static final class EventRef {
        final String path;
        final String name;
        final String format;
        final String type;
        final String date;
        final String eventId;

        EventRef(String path, String name, String format, String type, String date, String eventId) {
            this.path = path;
            this.name = name;
            this.format = format;
            this.type = type;
            this.date = date;
            this.eventId = eventId;
        }

        String url() {
            return "https://www.mtgo.com" + path;
        }
    }

    /** One finisher of an event, with the deck they played. */
    static final class Finisher {
        final int rank;
        final String player;
        final int wins;
        final int losses;
        final String colours;
        final List<TopDecksService.Card> cards;

        Finisher(int rank, String player, int wins, int losses, String colours, List<TopDecksService.Card> cards) {
            this.rank = rank;
            this.player = player;
            this.wins = wins;
            this.losses = losses;
            this.colours = colours;
            this.cards = cards;
        }
    }

    private static final Pattern EVENT_PATH = Pattern.compile("^/decklist/([a-z0-9-]+?)-((?:19|20)\\d{2})-(\\d{2})-(\\d{2})(\\d+)$");
    private static final String DATA_MARKER = "window.MTGO.decklists.data";
    private static final Map<String, String> COLOUR_LETTERS = new HashMap<>();

    static {
        COLOUR_LETTERS.put("COLOR_WHITE", "W");
        COLOUR_LETTERS.put("COLOR_BLUE", "U");
        COLOUR_LETTERS.put("COLOR_BLACK", "B");
        COLOUR_LETTERS.put("COLOR_RED", "R");
        COLOUR_LETTERS.put("COLOR_GREEN", "G");
    }

    private MtgoResultsSource() {
    }

    /**
     * The events on an index page, in page order. Links that aren't events are skipped; a page with none at all is
     * the site changing under us.
     */
    static List<EventRef> parseIndex(String html) throws DeckImportException {
        if (html == null) {
            throw changed("the decklists page was empty");
        }
        Document doc = Jsoup.parse(html);
        List<EventRef> events = new ArrayList<>();
        for (Element link : doc.select("a[href^=/decklist/]")) {
            String path = link.attr("href");
            Matcher match = EVENT_PATH.matcher(path);
            if (!match.matches()) {
                continue;
            }
            String slug = match.group(1);
            int dash = slug.indexOf('-');
            if (dash <= 0) {
                continue;
            }
            Element title = link.selectFirst("h3");
            String name = title != null ? title.text().trim() : slug;
            events.add(new EventRef(path, name, slug.substring(0, dash), slug.substring(dash + 1),
                    match.group(2) + "-" + match.group(3) + "-" + match.group(4), match.group(5)));
        }
        if (events.isEmpty()) {
            throw changed("the decklists page has no events");
        }
        return events;
    }

    /** Whether an event is one whose top finishers we list: Challenges, Showcases and Qualifiers, not Leagues. */
    static boolean isTopEvent(EventRef event) {
        return event.type.contains("challenge") || event.type.contains("showcase") || event.type.contains("qualifier");
    }

    /**
     * The top finishers (final ranks 1 to {@code top}) of one event, best first. An event that hasn't finished
     * has no final ranks and gives an empty list.
     */
    static List<Finisher> parseEvent(String html, int top) throws DeckImportException {
        JsonObject data = eventData(html);
        Map<String, JsonObject> decksByPlayer = new HashMap<>();
        for (JsonElement entry : SourceSupport.array(data, "decklists")) {
            if (entry.isJsonObject()) {
                decksByPlayer.put(SourceSupport.string(entry.getAsJsonObject(), "loginid"), entry.getAsJsonObject());
            }
        }
        Map<String, JsonObject> records = new HashMap<>();
        for (JsonElement entry : SourceSupport.array(data, "winloss")) {
            if (entry.isJsonObject()) {
                records.put(SourceSupport.string(entry.getAsJsonObject(), "loginid"), entry.getAsJsonObject());
            }
        }

        Map<Integer, Finisher> byRank = new HashMap<>();
        for (JsonElement entry : SourceSupport.array(data, "final_rank")) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject row = entry.getAsJsonObject();
            int rank = SourceSupport.integer(row, "rank", 0);
            String loginId = SourceSupport.string(row, "loginid");
            JsonObject deck = decksByPlayer.get(loginId);
            if (rank < 1 || rank > top || deck == null || byRank.containsKey(rank)) {
                continue;
            }
            List<TopDecksService.Card> cards = new ArrayList<>();
            String colours = readCards(deck, cards);
            if (cards.isEmpty()) {
                continue;
            }
            JsonObject record = records.get(loginId);
            byRank.put(rank, new Finisher(rank, SourceSupport.string(deck, "player"),
                    SourceSupport.integer(record, "wins", 0), SourceSupport.integer(record, "losses", 0), colours, cards));
        }
        List<Finisher> finishers = new ArrayList<>(byRank.values());
        finishers.sort((a, b) -> Integer.compare(a.rank, b.rank));
        return finishers;
    }

    private static JsonObject eventData(String html) throws DeckImportException {
        int marker = html == null ? -1 : html.indexOf(DATA_MARKER);
        int start = marker < 0 ? -1 : html.indexOf('{', marker);
        if (start < 0) {
            throw changed("an event page has no results data");
        }
        try (JsonReader reader = new JsonReader(new StringReader(html.substring(start)))) {
            reader.setLenient(true);
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw changed("an event page's results aren't an object");
            }
            return element.getAsJsonObject();
        } catch (JsonSyntaxException | IOException e) {
            throw new DeckImportException(DeckImportException.Reason.SITE_CHANGED, "mtgo.com sent event results we can't read", e);
        }
    }

    /** Fills {@code cards} from a player's deck and returns the colours of its spells, in WUBRG order. */
    private static String readCards(JsonObject deck, List<TopDecksService.Card> cards) {
        boolean[] seen = new boolean[5];
        String order = "WUBRG";
        for (String key : new String[]{"main_deck", "sideboard_deck"}) {
            JsonArray rows = SourceSupport.array(deck, key);
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                JsonObject attributes = SourceSupport.object(row, "card_attributes");
                String name = SourceSupport.cardName(SourceSupport.string(attributes, "card_name"));
                int quantity = parseInt(SourceSupport.string(row, "qty"));
                if (name == null || name.isEmpty() || quantity <= 0) {
                    continue;
                }
                cards.add(new TopDecksService.Card(name, quantity, key.equals("sideboard_deck") || "true".equals(SourceSupport.string(row, "sideboard"))));
                if (key.equals("main_deck")) {
                    for (JsonElement colour : SourceSupport.array(attributes, "colors")) {
                        String letter = colour.isJsonPrimitive() ? COLOUR_LETTERS.get(colour.getAsString()) : null;
                        if (letter != null) {
                            seen[order.indexOf(letter)] = true;
                        }
                    }
                }
            }
        }
        StringBuilder colours = new StringBuilder();
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                colours.append(order.charAt(i));
            }
        }
        return colours.toString();
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static DeckImportException changed(String what) {
        return new DeckImportException(DeckImportException.Reason.SITE_CHANGED, "mtgo.com changed: " + what);
    }
}
