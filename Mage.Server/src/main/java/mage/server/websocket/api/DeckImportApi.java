package mage.server.websocket.api;

import mage.server.websocket.rpc.RpcException;
import mage.server.websocket.rpc.RpcMethod;
import mage.server.websocket.service.deckimport.DeckImportException;

import java.util.Arrays;
import java.util.List;

import static mage.server.websocket.rpc.RpcParam.Type.STRING;
import static mage.server.websocket.rpc.RpcParam.of;

/**
 * Web client bridge: decks imported by link from deck websites (Archidekt, TCGplayer, MTGTop8, Scryfall, ManaBox).
 */
final class DeckImportApi {

    private DeckImportApi() {
    }

    static List<RpcMethod> methods(ApiContext ctx) {
        return Arrays.asList(
                RpcMethod.named("deckImportFromUrl")
                        .params(of("url", STRING))
                        .returns("ImportedDeck")
                        .doc("Read the deck behind a deck website link. Ten imports a minute per session; results are cached "
                                + "for five minutes. Fails with code -32005 and data {reason}: private, not_found, blocked, "
                                + "rate_limited, site_changed, unsupported, too_large, timeout or unavailable.")
                        .handler(call -> {
                            try {
                                return ctx.deckImport.importFromUrl(call.string(0), call.getConnection().getSessionId());
                            } catch (DeckImportException e) {
                                throw new RpcException(RpcException.DECK_IMPORT_FAILED, e.getMessage())
                                        .withReason(e.getReason().wireName());
                            }
                        }),

                RpcMethod.named("deckImportSources")
                        .publicAccess()
                        .returns("DeckSourceStatus[]")
                        .doc("Deck websites the server imports from by link, and whether each works right now (ok or degraded).")
                        .handler(call -> ctx.deckImport.sourceStatuses()),

                RpcMethod.named("topDecksOverview")
                        .returns("TopDecksOverview")
                        .doc("The formats that list top decks (the best finishers of recent Magic Online Challenges and Showcases), "
                                + "how many decks each has, and when the list was last read from mtgo.com. The server reads mtgo.com "
                                + "at most once a day and answers from its saved copy; enabled is false when the host switched the feature off.")
                        .handler(call -> ctx.topDecks.overview()),

                RpcMethod.named("topDecksList")
                        .params(of("format", STRING))
                        .returns("TopDeckSummary[]")
                        .doc("The listed top decks of one format (standard, pioneer, modern, legacy, vintage or pauper): newest event "
                                + "first, best finisher first. Fails with -32005 and reason unsupported for any other format.")
                        .handler(call -> {
                            try {
                                return ctx.topDecks.decks(call.string(0));
                            } catch (DeckImportException e) {
                                throw new RpcException(RpcException.DECK_IMPORT_FAILED, e.getMessage())
                                        .withReason(e.getReason().wireName());
                            }
                        }),

                RpcMethod.named("topDeckGet")
                        .params(of("id", STRING))
                        .returns("ImportedDeck")
                        .doc("The full deck behind a listed top deck id, in the same shape as deckImportFromUrl, for the import preview. "
                                + "Fails with -32005 and reason not_found when the list has moved on since the id was listed.")
                        .handler(call -> {
                            try {
                                return ctx.topDecks.deck(call.string(0));
                            } catch (DeckImportException e) {
                                throw new RpcException(RpcException.DECK_IMPORT_FAILED, e.getMessage())
                                        .withReason(e.getReason().wireName());
                            }
                        })
        );
    }
}
