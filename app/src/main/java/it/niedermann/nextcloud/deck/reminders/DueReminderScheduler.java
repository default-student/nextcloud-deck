package it.niedermann.nextcloud.deck.reminders;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

import it.niedermann.nextcloud.deck.DeckLog;
import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.database.DataBaseAdapter;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.model.Board;
import it.niedermann.nextcloud.deck.model.Card;
import it.niedermann.nextcloud.deck.model.Stack;
import it.niedermann.nextcloud.deck.model.enums.DBStatus;
import it.niedermann.nextcloud.deck.model.full.FullCard;
import it.niedermann.nextcloud.deck.ui.card.EditActivity;
import it.niedermann.nextcloud.deck.util.ExecutorServiceProvider;

public final class DueReminderScheduler {

    private static final int SNOOZE_MINUTES = 5;
    private static final String SNOOZE_SHARED_PREFERENCE_PREFIX = "local_due_reminder_snoozed_until_";
    static final String CHANNEL_ID = "due_reminders";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    private DueReminderScheduler() {
    }

    public static boolean isEnabled(@NonNull Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getBoolean(context.getString(R.string.pref_key_local_due_reminders), true);
    }

    public static void setEnabledAndReconcile(@NonNull Context context, boolean enabled) {
        final Context appContext = context.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
                .putBoolean(appContext.getString(R.string.pref_key_local_due_reminders), enabled)
                .apply();

        ExecutorServiceProvider.getLinkedBlockingQueueExecutor().submit(() -> {
            try {
                reconcileAll(appContext);
            } catch (RuntimeException exception) {
                DeckLog.logError(exception);
            }
        });
    }

    private static synchronized void reconcileAll(@NonNull Context context) {
        if (isEnabled(context)) {
            rescheduleAll(context);
        } else {
            cancelAll(context);
        }
    }

    public static synchronized void rescheduleAll(@NonNull Context context) {
        if (!isEnabled(context)) {
            cancelAll(context);
            return;
        }

        final var dataBaseAdapter = new DataBaseAdapter(context.getApplicationContext());
        final List<FullCard> cards = dataBaseAdapter.getUpcomingFullCardsDirectly();
        for (FullCard fullCard : cards) {
            scheduleCard(context, fullCard.getCard());
        }
    }

    public static synchronized void cancelAll(@NonNull Context context) {
        final var dataBaseAdapter = new DataBaseAdapter(context.getApplicationContext());
        final List<FullCard> cards = dataBaseAdapter.getUpcomingFullCardsDirectly();
        for (FullCard fullCard : cards) {
            cancelAlarm(context, fullCard.getCard().getLocalId());
        }
    }

    public static synchronized void scheduleCard(@NonNull Context context, @Nullable Card card) {
        if (card == null || card.getLocalId() == null) {
            return;
        }

        if (!isEnabled(context)) {
            cancelAlarm(context, card.getLocalId());
            return;
        }

        if (!shouldNotify(card)) {
            cancelCard(context, card.getLocalId());
            return;
        }

        final long nowMillis = Instant.now().toEpochMilli();
        final long snoozedUntilMillis = getSnoozedUntil(context, card.getLocalId());
        final long nextTriggerMillis = nextTriggerAtMillis(card, snoozedUntilMillis, nowMillis);
        if (nextTriggerMillis <= 0L) {
            clearSnooze(context, card.getLocalId());
            cancelAlarm(context, card.getLocalId());
            return;
        }

        if (card.getDueDate().toEpochMilli() > nowMillis) {
            clearSnooze(context, card.getLocalId());
        }
        cancelAlarm(context, card.getLocalId());
        scheduleAt(context, card.getLocalId(), nextTriggerMillis);
        DeckLog.info("Scheduled due reminder for card", card.getLocalId(), "at", Instant.ofEpochMilli(nextTriggerMillis));
    }

    public static synchronized void cancelCard(@NonNull Context context, @Nullable Long cardLocalId) {
        if (cardLocalId == null || cardLocalId <= 0L) {
            return;
        }

        clearSnooze(context, cardLocalId);
        cancelAlarm(context, cardLocalId);
    }

    private static void cancelAlarm(@NonNull Context context, @Nullable Long cardLocalId) {
        if (cardLocalId == null || cardLocalId <= 0L) {
            return;
        }

        final var alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(createReminderPendingIntent(context, cardLocalId));
        }
    }

    public static void showReminder(@NonNull Context context, long cardLocalId) {
        if (!isEnabled(context)) {
            return;
        }

        final var dataBaseAdapter = new DataBaseAdapter(context.getApplicationContext());
        final FullCard fullCard = dataBaseAdapter.getFullCardByLocalIdDirectly(cardLocalId);
        if (fullCard == null || !shouldNotify(fullCard.getCard())) {
            DeckLog.info("Skipping due reminder for stale card", cardLocalId);
            return;
        }

        final Account account = dataBaseAdapter.getAccountByIdDirectly(fullCard.getAccountId());
        final Board board = dataBaseAdapter.getBoardByLocalCardIdDirectly(cardLocalId);
        final Stack stack = dataBaseAdapter.getStackByLocalIdDirectly(fullCard.getCard().getStackId());
        if (account == null || board == null || stack == null) {
            DeckLog.warn("Skipping due reminder because account, board or stack could not be found for card", cardLocalId);
            return;
        }

        createNotificationChannel(context);

        final PendingIntent openCardPendingIntent = createOpenCardPendingIntent(context, account, board, cardLocalId);
        final PendingIntent fullScreenPendingIntent = createFullScreenPendingIntent(context, cardLocalId);
        final PendingIntent completePendingIntent = createCompletePendingIntent(context, cardLocalId);
        final PendingIntent snoozePendingIntent = createSnoozePendingIntent(context, cardLocalId);

        final String title = context.getString(R.string.local_due_reminder_notification_title, safeTitle(fullCard.getCard()));
        final String dueAt = fullCard.getCard().getDueDate()
                .atZone(ZoneId.systemDefault())
                .format(DATE_TIME_FORMATTER);
        final String text = context.getString(R.string.local_due_reminder_notification_text, board.getTitle(), stack.getTitle(), dueAt);

        final var notificationBuilder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications_24)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openCardPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .addAction(R.drawable.ic_outline_check_circle_24, context.getString(R.string.local_due_reminder_mark_complete), completePendingIntent)
                .addAction(R.drawable.ic_time_24, context.getString(R.string.local_due_reminder_snooze), snoozePendingIntent)
                .setAutoCancel(true);

        final var notification = notificationBuilder.build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(notificationId(cardLocalId), notification);
            DeckLog.info("Displayed due reminder for card", cardLocalId);
        } else {
            DeckLog.warn("Skipping due reminder notification because POST_NOTIFICATIONS is not granted.");
        }
    }

    public static synchronized void snooze(@NonNull Context context, long cardLocalId) {
        if (!isEnabled(context)) {
            return;
        }

        final var dataBaseAdapter = new DataBaseAdapter(context.getApplicationContext());
        final FullCard fullCard = dataBaseAdapter.getFullCardByLocalIdDirectly(cardLocalId);
        if (fullCard == null || !shouldNotify(fullCard.getCard())) {
            DeckLog.info("Skipping snooze for stale card", cardLocalId);
            return;
        }

        final long snoozedUntilMillis = Instant.now().plusSeconds(SNOOZE_MINUTES * 60L).toEpochMilli();
        persistSnoozedUntil(context, cardLocalId, snoozedUntilMillis);
        scheduleAt(context, cardLocalId, snoozedUntilMillis);
        NotificationManagerCompat.from(context).cancel(notificationId(cardLocalId));
        DeckLog.info("Snoozed due reminder for card", cardLocalId, "until", Instant.ofEpochMilli(snoozedUntilMillis));
    }

    public static synchronized void markComplete(@NonNull Context context, long cardLocalId) {
        final var dataBaseAdapter = new DataBaseAdapter(context.getApplicationContext());
        if (dataBaseAdapter.markCardDoneDirectly(cardLocalId, Instant.now())) {
            cancelCard(context, cardLocalId);
            NotificationManagerCompat.from(context).cancel(notificationId(cardLocalId));
            DeckLog.info("Marked due reminder card complete", cardLocalId);
        } else {
            DeckLog.info("Skipping complete action for stale or already completed card", cardLocalId);
        }
    }

    public static boolean canScheduleExactAlarms(@NonNull Context context) {
        final var alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && canScheduleExactAlarms(alarmManager);
    }

    @NonNull
    public static Intent createExactAlarmSettingsIntent(@NonNull Context context) {
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.getPackageName(), null));
    }

    private static boolean canScheduleExactAlarms(@NonNull AlarmManager alarmManager) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms();
    }

    public static boolean canUseFullScreenIntent(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }

        final var notificationManager = context.getSystemService(NotificationManager.class);
        return notificationManager != null && notificationManager.canUseFullScreenIntent();
    }

    private static void scheduleAt(@NonNull Context context, long cardLocalId, long triggerAtMillis) {
        final var alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            DeckLog.warn("Could not schedule due reminder because AlarmManager is unavailable.");
            return;
        }

        final PendingIntent pendingIntent = createReminderPendingIntent(context, cardLocalId);

        if (canScheduleExactAlarms(alarmManager)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                }
            } catch (SecurityException exception) {
                DeckLog.warn("Exact alarm access changed while scheduling; falling back to an inexact reminder for card", cardLocalId);
                scheduleInexact(alarmManager, triggerAtMillis, pendingIntent);
            }
        } else {
            scheduleInexact(alarmManager, triggerAtMillis, pendingIntent);
        }
    }

    private static void scheduleInexact(@NonNull AlarmManager alarmManager, long triggerAtMillis, @NonNull PendingIntent pendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    static long getSnoozedUntil(@NonNull Context context, long cardLocalId) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getLong(snoozePreferenceKey(cardLocalId), 0L);
    }

    static void persistSnoozedUntil(@NonNull Context context, long cardLocalId, long snoozedUntilMillis) {
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()).edit()
                .putLong(snoozePreferenceKey(cardLocalId), snoozedUntilMillis)
                .apply();
    }

    static void clearSnooze(@NonNull Context context, long cardLocalId) {
        PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext()).edit()
                .remove(snoozePreferenceKey(cardLocalId))
                .apply();
    }

    @NonNull
    private static String snoozePreferenceKey(long cardLocalId) {
        return SNOOZE_SHARED_PREFERENCE_PREFIX + cardLocalId;
    }

    static long nextTriggerAtMillis(@NonNull Card card, long snoozedUntilMillis, long nowMillis) {
        if (!shouldNotify(card)) {
            return 0L;
        }

        final long dueAtMillis = card.getDueDate().toEpochMilli();
        if (dueAtMillis > nowMillis) {
            return dueAtMillis;
        }

        return snoozedUntilMillis > nowMillis ? snoozedUntilMillis : 0L;
    }

    static boolean shouldNotify(@NonNull Card card) {
        return card.getDueDate() != null
                && card.getDone() == null
                && !card.isArchived()
                && card.getStatusEnum() != DBStatus.LOCAL_DELETED
                && (card.getDeletedAt() == null || card.getDeletedAt().toEpochMilli() == 0L);
    }

    private static void createNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final var channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.local_due_reminders_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(context.getString(R.string.local_due_reminders_channel_description));
        channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        final var notificationManager = context.getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    private static PendingIntent createReminderPendingIntent(@NonNull Context context, long cardLocalId) {
        return PendingIntent.getBroadcast(
                context,
                notificationId(cardLocalId),
                DueReminderReceiver.createIntent(context, cardLocalId),
                FLAG_IMMUTABLE | FLAG_CANCEL_CURRENT
        );
    }

    private static PendingIntent createCompletePendingIntent(@NonNull Context context, long cardLocalId) {
        return PendingIntent.getBroadcast(
                context,
                notificationId(cardLocalId) + 100_000,
                DueReminderReceiver.createIntent(context, DueReminderReceiver.ACTION_MARK_COMPLETE, cardLocalId),
                FLAG_IMMUTABLE | FLAG_CANCEL_CURRENT
        );
    }

    private static PendingIntent createSnoozePendingIntent(@NonNull Context context, long cardLocalId) {
        return PendingIntent.getBroadcast(
                context,
                notificationId(cardLocalId) + 300_000,
                DueReminderReceiver.createIntent(context, DueReminderReceiver.ACTION_SNOOZE, cardLocalId),
                FLAG_IMMUTABLE | FLAG_CANCEL_CURRENT
        );
    }

    private static PendingIntent createFullScreenPendingIntent(@NonNull Context context, long cardLocalId) {
        return PendingIntent.getActivity(
                context,
                notificationId(cardLocalId),
                DueReminderActivity.createIntent(context, cardLocalId),
                FLAG_IMMUTABLE | FLAG_CANCEL_CURRENT
        );
    }

    static PendingIntent createOpenCardPendingIntent(@NonNull Context context, @NonNull Account account, @NonNull Board board, long cardLocalId) {
        final Intent openCardIntent = EditActivity.createEditCardIntent(context, account, board.getLocalId(), cardLocalId);
        return PendingIntent.getActivity(
                context,
                notificationId(cardLocalId) + 200_000,
                openCardIntent,
                FLAG_IMMUTABLE | FLAG_CANCEL_CURRENT
        );
    }

    static int notificationId(long cardLocalId) {
        return (int) (20_000L + cardLocalId);
    }

    @NonNull
    private static String safeTitle(@NonNull Card card) {
        final String title = card.getTitle();
        return title == null || title.trim().isEmpty() ? "" : title;
    }
}
