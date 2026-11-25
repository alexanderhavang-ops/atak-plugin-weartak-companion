package com.weartak.atak.weartak_companion.plugin;

import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

public class VoiceSetupSoundTest {
    private static final String ORIGIN = "https://tak.example";
    private static final String CARD = ORIGIN + "/api/onboarding/cards/test_card_link_012345678";
    private static final String SETUP = ORIGIN + "/api/onboarding/cards/watch-voice#" +
            "0123456789012345678901234567890123456789012";

    @Test public void requestsTicketFromTheCardOrigin() {
        URI endpoint = VoiceSetupSound.ticketEndpoint(CARD + "?lang=en");
        assertEquals(CARD + "/watch-voice/ticket", endpoint.toString());
        assertEquals(SETUP, VoiceSetupSound.validateSetupLink(endpoint, SETUP));
    }

    @Test public void rejectsOtherCardPathsAndInsecureOrigins() {
        for (String invalid : new String[]{CARD.replace("https:", "http:"),
                CARD.replace("tak.example", "user@tak.example"), CARD.replace("tak.example", "tak.example:8443"),
                CARD + "/packages/atak/qr.txt", CARD + "#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> VoiceSetupSound.ticketEndpoint(invalid));
        }
    }

    @Test public void rejectsCrossOriginOrMalformedSetupLinks() {
        URI endpoint = VoiceSetupSound.ticketEndpoint(CARD);
        for (String invalid : new String[]{SETUP.replace("tak.example", "other.example"),
                SETUP.replace("#", "?ticket="), SETUP + "x", SETUP.replace("/watch-voice#", "/other#")}) {
            assertThrows(IllegalArgumentException.class, () -> VoiceSetupSound.validateSetupLink(endpoint, invalid));
        }
    }
}
