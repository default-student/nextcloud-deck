package it.niedermann.nextcloud.deck.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import it.niedermann.nextcloud.deck.DeckLog;
import it.niedermann.nextcloud.deck.util.ExecutorServiceProvider;

public class DueReminderRescheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final var appContext = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();
        final String action = intent == null ? "unknown broadcast" : intent.getAction();
        try {
            ExecutorServiceProvider.getLinkedBlockingQueueExecutor().submit(() -> {
                try {
                    DeckLog.info("Rescheduling local due reminders after", action);
                    DueReminderScheduler.rescheduleAll(appContext);
                } catch (RuntimeException exception) {
                    DeckLog.logError(exception);
                } finally {
                    pendingResult.finish();
                }
            });
        } catch (RuntimeException exception) {
            pendingResult.finish();
            DeckLog.logError(exception);
        }
    }
}
