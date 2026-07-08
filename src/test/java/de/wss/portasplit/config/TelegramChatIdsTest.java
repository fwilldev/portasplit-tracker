package de.wss.portasplit.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the (single or multi-recipient) {@code app.telegram.chat-id} value. */
class TelegramChatIdsTest {

    private static AppProperties.Telegram withChatId(String chatId) {
        return new AppProperties.Telegram(true, "token", chatId, "https://api.telegram.org");
    }

    @Test
    void singleId() {
        assertEquals(List.of("987654321"), withChatId("987654321").chatIds());
    }

    @Test
    void commaSeparatedList() {
        assertEquals(List.of("111", "222", "333"), withChatId("111,222,333").chatIds());
    }

    @Test
    void mixedSeparatorsAndWhitespaceAreTolerated() {
        assertEquals(List.of("111", "222", "333"), withChatId(" 111 , 222; 333\n").chatIds());
    }

    @Test
    void duplicatesAreCollapsed() {
        assertEquals(List.of("111", "222"), withChatId("111,222,111").chatIds());
    }

    @Test
    void blankOrNullYieldsEmpty() {
        assertTrue(withChatId("").chatIds().isEmpty());
        assertTrue(withChatId("   ").chatIds().isEmpty());
        assertTrue(withChatId(null).chatIds().isEmpty());
    }
}
