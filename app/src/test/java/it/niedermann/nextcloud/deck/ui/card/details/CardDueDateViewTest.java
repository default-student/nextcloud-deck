package it.niedermann.nextcloud.deck.ui.card.details;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

public class CardDueDateViewTest {

    @Test
    public void todaySuggestionsExcludeTimesThatHaveAlreadyPassed() {
        assertEquals(Arrays.asList(LocalTime.of(15, 0), LocalTime.of(21, 0)),
                CardDueDateView.getSuggestedTimesForToday(LocalTime.of(14, 30)));
        assertEquals(Collections.emptyList(),
                CardDueDateView.getSuggestedTimesForToday(LocalTime.of(21, 1)));
    }

    @Test
    public void suggestedWallClockTimeUsesZoneRulesForDaylightSavingGaps() {
        final ZoneId berlin = ZoneId.of("Europe/Berlin");
        final Instant instant = CardDueDateView.toSuggestedInstant(
                LocalDate.of(2026, 3, 29),
                LocalTime.of(2, 0),
                berlin
        );

        assertEquals(LocalTime.of(3, 0), instant.atZone(berlin).toLocalTime());
        assertTrue(instant.isAfter(Instant.parse("2026-03-29T00:59:59Z")));
    }
}
