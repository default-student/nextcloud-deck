package it.niedermann.nextcloud.deck.ui.settings;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.stream.Stream;

import it.niedermann.nextcloud.deck.DeckLog;
import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.model.Account;
import it.niedermann.nextcloud.deck.remote.SyncWorker;
import it.niedermann.nextcloud.deck.reminders.DueReminderScheduler;
import it.niedermann.nextcloud.deck.ui.theme.ThemedSwitchPreference;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String KEY_ACCOUNT = "account";
    private Account account;
    private PreferencesViewModel preferencesViewModel;
    private ThemedSwitchPreference wifiOnlyPref;
    private Preference pushNotificationPref;
    private ThemedSwitchPreference compactPref;
    private ThemedSwitchPreference coverImagesPref;
    private ThemedSwitchPreference compressImageAttachmentsPref;
    private ThemedSwitchPreference localDueRemindersPref;
    private Preference localDueRemindersExactAlarmPref;
    private Preference localDueRemindersFullScreenPref;
    private ThemedSwitchPreference debuggingPref;
    private ThemedSwitchPreference eTagPref;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        final var args = getArguments();
        if (args == null || !args.containsKey(KEY_ACCOUNT)) {
            throw new IllegalArgumentException(KEY_ACCOUNT + " must be provided");
        }

        account = (Account) args.getSerializable(KEY_ACCOUNT);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings, rootKey);

        preferencesViewModel = new ViewModelProvider(requireActivity()).get(PreferencesViewModel.class);

        wifiOnlyPref = findPreference(getString(R.string.pref_key_wifi_only));
        pushNotificationPref = findPreference(getString(R.string.pref_key_push_notifications));
        coverImagesPref = findPreference(getString(R.string.pref_key_cover_images));
        compactPref = findPreference(getString(R.string.pref_key_compact));
        compressImageAttachmentsPref = findPreference(getString(R.string.pref_key_compress_image_attachments));
        localDueRemindersPref = findPreference(getString(R.string.pref_key_local_due_reminders));
        localDueRemindersExactAlarmPref = findPreference(getString(R.string.pref_key_local_due_reminders_exact_alarm));
        localDueRemindersFullScreenPref = findPreference(getString(R.string.pref_key_local_due_reminders_full_screen));
        eTagPref = findPreference(getString(R.string.pref_key_etags));

        debuggingPref = findPreference(getString(R.string.pref_key_debugging));
        if (debuggingPref != null) {
            debuggingPref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
                DeckLog.enablePersistentLogs((Boolean) newValue);
                DeckLog.log("persistet debug logs:", newValue);
                return true;
            });
        } else {
            DeckLog.error("Could not find preference with key:", getString(R.string.pref_key_debugging));
        }

        final var backgroundSyncPref = findPreference(getString(R.string.pref_key_background_sync));
        if (backgroundSyncPref != null) {
            backgroundSyncPref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
                SyncWorker.update(requireContext().getApplicationContext(), (String) newValue);
                return true;
            });
        } else {
            DeckLog.error("Could not find preference with key", getString(R.string.pref_key_background_sync));
        }

        final var pushNotificationPref = findPreference(getString(R.string.pref_key_push_notifications));
        if (pushNotificationPref != null) {
            pushNotificationPref.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_faq_push_notifications))));
                return true;
            });
        } else {
            DeckLog.error("Could not find preference with key", getString(R.string.pref_key_push_notifications));
        }

        if (localDueRemindersPref != null) {
            localDueRemindersPref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    requestNotificationPermissionIfNeeded();
                }
                DueReminderScheduler.setEnabledAndReconcile(requireContext(), Boolean.TRUE.equals(newValue));
                updateReminderAccessPreferences(Boolean.TRUE.equals(newValue));
                return true;
            });
        } else {
            DeckLog.error("Could not find preference with key", getString(R.string.pref_key_local_due_reminders));
        }

        if (localDueRemindersExactAlarmPref != null) {
            localDueRemindersExactAlarmPref.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
            localDueRemindersExactAlarmPref.setOnPreferenceClickListener(preference -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(DueReminderScheduler.createExactAlarmSettingsIntent(requireContext()));
                }
                return true;
            });
        }

        if (localDueRemindersFullScreenPref != null) {
            final boolean supportsFullScreenAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
            localDueRemindersFullScreenPref.setVisible(supportsFullScreenAccess);
            localDueRemindersFullScreenPref.setOnPreferenceClickListener(preference -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    final Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            .setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                    startActivity(intent);
                }
                return true;
            });
        }
        updateReminderAccessPreferences();

        final var themePref = findPreference(getString(R.string.pref_key_dark_theme));
        if (themePref != null) {
            themePref.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
                preferencesViewModel.setAppTheme(Integer.parseInt((String) newValue));
                ActivityCompat.recreate(requireActivity());
                return true;
            });
        } else {
            DeckLog.error("Could not find preference with key:", getString(R.string.pref_key_dark_theme));
        }

        final var recoveryPref = findPreference(getString(R.string.pref_key_recovery));
        if (recoveryPref != null) {
            recoveryPref.setOnPreferenceClickListener(preference -> {
                startActivity(RecoveryActivity.createIntent(requireContext(), account));
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Stream.of(wifiOnlyPref, compactPref, coverImagesPref, compressImageAttachmentsPref, localDueRemindersPref, debuggingPref, eTagPref)
                .forEach(pref -> pref.applyTheme(account.getColor()));
    }

    @Override
    public void onResume() {
        super.onResume();
        updateReminderAccessPreferences();
    }

    private void updateReminderAccessPreferences() {
        updateReminderAccessPreferences(DueReminderScheduler.isEnabled(requireContext()));
    }

    private void updateReminderAccessPreferences(boolean remindersEnabled) {
        if (localDueRemindersExactAlarmPref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            localDueRemindersExactAlarmPref.setEnabled(remindersEnabled);
            localDueRemindersExactAlarmPref.setSummary(DueReminderScheduler.canScheduleExactAlarms(requireContext())
                    ? R.string.settings_local_due_reminders_exact_alarm_allowed
                    : R.string.settings_local_due_reminders_exact_alarm_denied);
        }

        if (localDueRemindersFullScreenPref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            localDueRemindersFullScreenPref.setEnabled(remindersEnabled);
            localDueRemindersFullScreenPref.setSummary(DueReminderScheduler.canUseFullScreenIntent(requireContext())
                    ? R.string.settings_local_due_reminders_full_screen_allowed
                    : R.string.settings_local_due_reminders_full_screen_denied);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @NonNull
    public static Fragment newInstance(@NonNull Account account) {
        final var fragment = new SettingsFragment();

        final var args = new Bundle();
        args.putSerializable(KEY_ACCOUNT, account);
        fragment.setArguments(args);

        return fragment;
    }
}
