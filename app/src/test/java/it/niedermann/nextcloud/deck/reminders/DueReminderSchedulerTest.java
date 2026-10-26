package it.niedermann.nextcloud.deck.reminders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.Instant;

import it.niedermann.nextcloud.deck.model.Card;
import it.niedermann.nextcloud.deck.model.enums.DBStatus;

@RunWith(RobolectricTestRunner.class)
public class DueReminderSchedulerTest {

    @Test
    public void overdueCardKeepsPersistedSnoozeAcrossReschedule() {
        final long now = 1_800_000_000_000L;
        final Card card = cardDueAt(now - 60_000L);
        final long snoozedUntil = now + 5 * 60_000L;

        assertEquals(snoozedUntil, DueReminderScheduler.nextTriggerAtMillis(card, snoozedUntil, now));
    }

    @Test
    public void schedulingOverdueCardUsesPersistedSnoozeAlarm() {
        final var context = ApplicationProvider.<android.content.Context>getApplicationContext();
        final long now = System.currentTimeMillis();
        final long snoozedUntil = now + 5 * 60_000L;
        final long cardId = 8_765_433L;
        final Card card = cardDueAt(now - 60_000L);
        card.setLocalId(cardId);
        DueReminderScheduler.persistSnoozedUntil(context, cardId, snoozedUntil);

        try {
            DueReminderScheduler.scheduleCard(context, card);

            final AlarmManager alarmManager = (AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE);
            final ShadowAlarmManager shadowAlarmManager = Shadows.shadowOf(alarmManager);
            assertEquals(1, shadowAlarmManager.getScheduledAlarms().size());
            assertEquals(snoozedUntil, shadowAlarmManager.peekNextScheduledAlarm().getTriggerAtMs());
        } finally {
            DueReminderScheduler.cancelCard(context, cardId);
        }
    }

    @Test
    public void futureDueDateTakesPrecedenceAndExpiredSnoozeIsIgnored() {
        final long now = 1_800_000_000_000L;
        final Card futureCard = cardDueAt(now + 60_000L);
        final Card overdueCard = cardDueAt(now - 60_000L);

        assertEquals(now + 60_000L, DueReminderScheduler.nextTriggerAtMillis(futureCard, now + 5 * 60_000L, now));
        assertEquals(0L, DueReminderScheduler.nextTriggerAtMillis(overdueCard, now, now));
    }

    @Test
    public void completedArchivedAndDeletedCardsCannotBeRescheduled() {
        final long now = 1_800_000_000_000L;
        final Card done = cardDueAt(now - 60_000L);
        done.setDone(Instant.ofEpochMilli(now - 1L));
        final Card archived = cardDueAt(now - 60_000L);
        archived.setArchived(true);
        final Card deleted = cardDueAt(now - 60_000L);
        deleted.setStatusEnum(DBStatus.LOCAL_DELETED);

        assertEquals(0L, DueReminderScheduler.nextTriggerAtMillis(done, now + 1L, now));
        assertEquals(0L, DueReminderScheduler.nextTriggerAtMillis(archived, now + 1L, now));
        assertEquals(0L, DueReminderScheduler.nextTriggerAtMillis(deleted, now + 1L, now));
    }

    @Test
    public void snoozePersistenceCanBeClearedWhenCardIsInvalidated() {
        final var context = ApplicationProvider.<android.content.Context>getApplicationContext();
        final long cardId = 8_765_432L;
        final long snoozedUntil = 1_800_000_000_000L;
        try {
            DueReminderScheduler.persistSnoozedUntil(context, cardId, snoozedUntil);
            assertEquals(snoozedUntil, DueReminderScheduler.getSnoozedUntil(context, cardId));

            DueReminderScheduler.clearSnooze(context, cardId);
            assertEquals(0L, DueReminderScheduler.getSnoozedUntil(context, cardId));
            assertEquals(0L, DueReminderScheduler.nextTriggerAtMillis(cardDueAt(snoozedUntil), 0L, snoozedUntil));
        } finally {
            DueReminderScheduler.clearSnooze(context, cardId);
        }
    }

    @Test
    public void dueCardIsEligibleForReminder() {
        assertTrue(DueReminderScheduler.shouldNotify(cardDueAt(1_800_000_000_000L)));
    }

    private static Card cardDueAt(long dueAtMillis) {
        final Card card = new Card();
        card.setLocalId(42L);
        card.setDueDate(Instant.ofEpochMilli(dueAtMillis));
        return card;
    }
}
