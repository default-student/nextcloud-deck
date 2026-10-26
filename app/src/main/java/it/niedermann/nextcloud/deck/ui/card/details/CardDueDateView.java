package it.niedermann.nextcloud.deck.ui.card.details;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.chip.Chip;
import com.nextcloud.android.common.ui.theme.utils.ColorRole;
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog;
import com.wdullaer.materialdatetimepicker.time.TimePickerDialog;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.niedermann.nextcloud.deck.R;
import it.niedermann.nextcloud.deck.databinding.ViewCardDueDateBinding;
import it.niedermann.nextcloud.deck.model.ocs.Version;
import it.niedermann.nextcloud.deck.ui.theme.ThemeUtils;
import it.niedermann.nextcloud.deck.ui.theme.Themed;
import it.niedermann.nextcloud.deck.ui.theme.ThemedDatePickerDialog;
import it.niedermann.nextcloud.deck.ui.theme.ThemedTimePickerDialog;

public class CardDueDateView extends FrameLayout implements DatePickerDialog.OnDateSetListener, TimePickerDialog.OnTimeSetListener, Themed {

    private final ViewCardDueDateBinding binding;
    @Nullable
    private DueDateChangedListener dueDateChangedListener;
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy HH:mm");
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT);
    boolean supportsDone = false;
    @Nullable
    private Instant dueDate = null;
    @Nullable
    private Instant done = null;
    @Nullable
    @ColorInt
    private Integer color = null;
    private FragmentManager fragmentManager = null;
    @Nullable
    private LocalDate expandedQuickPickDate;
    private static final List<LocalTime> QUICK_PICK_TIMES = Arrays.asList(
            LocalTime.of(7, 0),
            LocalTime.of(9, 0),
            LocalTime.of(13, 0),
            LocalTime.of(15, 0),
            LocalTime.of(21, 0)
    );

    public CardDueDateView(Context context) {
        super(context);
        binding = ViewCardDueDateBinding.inflate(LayoutInflater.from(context), this, true);
    }

    public CardDueDateView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        binding = ViewCardDueDateBinding.inflate(LayoutInflater.from(context), this, true);
    }

    public CardDueDateView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        binding = ViewCardDueDateBinding.inflate(LayoutInflater.from(context), this, true);
    }

    /**
     * @noinspection unused
     */
    public CardDueDateView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        binding = ViewCardDueDateBinding.inflate(LayoutInflater.from(context), this, true);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != isEnabled()) {
            super.setEnabled(enabled);
            render();
        }
    }

    public void setDueDate(@NonNull FragmentManager fragmentManager, @NonNull Version version, @Nullable Instant dueDate, @Nullable Instant done) {
        this.fragmentManager = fragmentManager;
        this.supportsDone = version.supportsDone();
        this.dueDate = dueDate;
        this.done = done;
        render();
    }

    private void render() {
        setVisibilityState();
        setTextState();
        setInteraction();
        renderQuickPicks();
    }

    private void setVisibilityState() {
        if (done == null) {
            Stream.of(
                    binding.dueDateDateWrapper,
                    binding.dueDateTimeWrapper
            ).forEach(v -> v.setVisibility(View.VISIBLE));

            Stream.of(
                    binding.doneCheck,
                    binding.doneDueDate,
                    binding.doneDate,
                    binding.clearDone
            ).forEach(v -> v.setVisibility(View.GONE));

            binding.markAsDone.setVisibility(supportsDone ? View.VISIBLE : View.GONE);
            binding.clearDueDate.setVisibility(dueDate == null || !isEnabled() ? View.GONE : View.VISIBLE);
        } else {

            Stream.of(
                    binding.doneCheck,
                    binding.doneDate
            ).forEach(v -> v.setVisibility(View.VISIBLE));

            Stream.of(
                    binding.markAsDone,
                    binding.dueDateDateWrapper,
                    binding.dueDateTimeWrapper,
                    binding.clearDueDate
            ).forEach(v -> v.setVisibility(View.GONE));

            binding.clearDone.setVisibility(supportsDone ? View.VISIBLE : View.GONE);
            binding.doneDueDate.setVisibility(dueDate == null || !isEnabled() ? View.GONE : View.VISIBLE);
        }
    }

    private void renderQuickPicks() {
        binding.dueDateQuickPicks.removeAllViews();
        binding.dueDateQuickPickTimes.removeAllViews();

        if (done != null || !isEnabled()) {
            binding.dueDateQuickPicks.setVisibility(View.GONE);
            binding.dueDateQuickPickTimesLabel.setVisibility(View.GONE);
            binding.dueDateQuickPickTimes.setVisibility(View.GONE);
            return;
        }

        binding.dueDateQuickPicks.setVisibility(View.VISIBLE);
        binding.dueDateQuickPicks.addView(createQuickPickChip(
                R.string.due_date_quick_pick_15_minutes,
                v -> applySuggestedDueDate(Instant.now().plus(Duration.ofMinutes(15)))
        ));
        binding.dueDateQuickPicks.addView(createQuickPickChip(
                R.string.due_date_quick_pick_1_hour,
                v -> applySuggestedDueDate(Instant.now().plus(Duration.ofHours(1)))
        ));

        final ZoneId zone = ZoneId.systemDefault();
        final ZonedDateTime now = ZonedDateTime.now(zone);
        final LocalDate today = now.toLocalDate();
        final LocalDate tomorrow = today.plusDays(1);
        final List<LocalTime> todayTimes = getSuggestedTimesForToday(now.toLocalTime());
        if (!todayTimes.isEmpty()) {
            binding.dueDateQuickPicks.addView(createDayQuickPickChip(
                    R.string.due_date_quick_pick_today,
                    today,
                    R.string.due_date_quick_pick_times_today
            ));
        }
        binding.dueDateQuickPicks.addView(createDayQuickPickChip(
                R.string.due_date_quick_pick_tomorrow,
                tomorrow,
                R.string.due_date_quick_pick_times_tomorrow
        ));

        if (expandedQuickPickDate != null) {
            final boolean isToday = expandedQuickPickDate.equals(today);
            final List<LocalTime> times = isToday ? todayTimes : QUICK_PICK_TIMES;
            if (times.isEmpty()) {
                expandedQuickPickDate = null;
                binding.dueDateQuickPickTimesLabel.setVisibility(View.GONE);
                binding.dueDateQuickPickTimes.setVisibility(View.GONE);
                return;
            }

            binding.dueDateQuickPickTimesLabel.setText(isToday
                    ? R.string.due_date_quick_pick_times_today
                    : R.string.due_date_quick_pick_times_tomorrow);
            binding.dueDateQuickPickTimesLabel.setVisibility(View.VISIBLE);
            binding.dueDateQuickPickTimes.setVisibility(View.VISIBLE);
            final String dayLabel = getContext().getString(isToday
                    ? R.string.due_date_quick_pick_today
                    : R.string.due_date_quick_pick_tomorrow);
            for (LocalTime time : times) {
                final Instant selected = toSuggestedInstant(expandedQuickPickDate, time, zone);
                final String formattedTime = time.format(timeFormatter);
                final Chip chip = createQuickPickChip(formattedTime, v -> applySuggestedDueDate(selected));
                chip.setContentDescription(getContext().getString(
                        R.string.due_date_quick_pick_at_day_time,
                        dayLabel,
                        formattedTime
                ));
                binding.dueDateQuickPickTimes.addView(chip);
            }
        } else {
            binding.dueDateQuickPickTimesLabel.setVisibility(View.GONE);
            binding.dueDateQuickPickTimes.setVisibility(View.GONE);
        }
    }

    private Chip createDayQuickPickChip(int labelRes, @NonNull LocalDate date, int timesLabelRes) {
        final Chip chip = createQuickPickChip(labelRes, v -> {
            expandedQuickPickDate = date.equals(expandedQuickPickDate) ? null : date;
            renderQuickPicks();
        });
        chip.setCheckable(true);
        chip.setChecked(date.equals(expandedQuickPickDate));
        chip.setContentDescription(getContext().getString(
                R.string.due_date_quick_pick_show_times,
                getContext().getString(labelRes),
                getContext().getString(timesLabelRes)
        ));
        return chip;
    }

    private Chip createQuickPickChip(int labelRes, @NonNull OnClickListener listener) {
        return createQuickPickChip(getContext().getString(labelRes), listener);
    }

    private Chip createQuickPickChip(@NonNull String label, @NonNull OnClickListener listener) {
        final Chip chip = new Chip(getContext());
        chip.setText(label);
        chip.setCheckable(false);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setOnClickListener(listener);
        return chip;
    }

    private void applySuggestedDueDate(@NonNull Instant suggestedDueDate) {
        expandedQuickPickDate = null;
        dueDate = suggestedDueDate;
        if (dueDateChangedListener != null) {
            dueDateChangedListener.onDueDateChanged(suggestedDueDate);
        }
        render();
    }

    @NonNull
    static List<LocalTime> getSuggestedTimesForToday(@NonNull LocalTime now) {
        final List<LocalTime> suggestions = new ArrayList<>();
        for (LocalTime time : QUICK_PICK_TIMES) {
            if (time.isAfter(now)) {
                suggestions.add(time);
            }
        }
        return suggestions;
    }

    @NonNull
    static Instant toSuggestedInstant(@NonNull LocalDate date, @NonNull LocalTime time, @NonNull ZoneId zone) {
        return LocalDateTime.of(date, time).atZone(zone).toInstant();
    }

    private void setTextState() {
        if (done == null) {
            binding.doneDate.setText(null);
            binding.doneDueDate.setText(null);

            if (this.dueDate == null) {
                binding.dueDateDate.setText(null);
                binding.dueDateTime.setText(null);

            } else {
                final var dueDate = this.dueDate.atZone(ZoneId.systemDefault());
                binding.dueDateDate.setText(dueDate.format(dateFormatter));
                binding.dueDateTime.setText(dueDate.format(timeFormatter));
            }

        } else {
            binding.dueDateDate.setText(null);
            binding.dueDateTime.setText(null);

            binding.doneDate.setText(done.atZone(ZoneId.systemDefault()).format(dateTimeFormatter));

            if (this.dueDate == null) {
                binding.doneDueDate.setText(null);

            } else {
                final var dueDate = this.dueDate.atZone(ZoneId.systemDefault());
                binding.doneDueDate.setText(getContext().getString(R.string.label_due_at, dueDate.format(dateTimeFormatter)));
            }
        }
    }

    private void setInteraction() {
        final var enabled = isEnabled();

        binding.dueDateDate.setEnabled(enabled);
        binding.dueDateTime.setEnabled(enabled);

        if (enabled) {
            binding.clearDone.setOnClickListener(v -> {
                if (this.dueDateChangedListener != null) {
                    this.dueDateChangedListener.onDoneChanged(null);
                }
            });

            if (supportsDone) {
                binding.markAsDone.setOnClickListener(v -> {
                    if (this.dueDateChangedListener != null) {
                        this.dueDateChangedListener.onDoneChanged(Instant.now());
                    }
                });
            } else {
                binding.markAsDone.setOnClickListener(null);
            }

            binding.clearDueDate.setOnClickListener(v -> {
                if (this.dueDateChangedListener != null) {
                    this.dueDateChangedListener.onDueDateChanged(null);
                }
            });

            binding.dueDateDate.setOnClickListener(v -> {
                if (this.fragmentManager == null || this.color == null) {
                    return;
                }
                final LocalDate date;
                if (this.dueDate != null) {
                    date = this.dueDate.atZone(ZoneId.systemDefault()).toLocalDate();
                } else {
                    date = LocalDate.now();
                }

                ThemedDatePickerDialog.newInstance(this, date.getYear(), date.getMonthValue(), date.getDayOfMonth(), this.color)
                        .show(this.fragmentManager, ThemedDatePickerDialog.class.getCanonicalName());
            });

            binding.dueDateTime.setOnClickListener(v -> {
                if (this.fragmentManager == null || this.color == null) {
                    return;
                }
                final LocalTime time;
                if (this.dueDate != null) {
                    time = this.dueDate.atZone(ZoneId.systemDefault()).toLocalTime();
                } else {
                    time = LocalTime.now();
                }
                ThemedTimePickerDialog.newInstance(this, time.getHour(), time.getMinute(), true, this.color)
                        .show(this.fragmentManager, ThemedTimePickerDialog.class.getCanonicalName());
            });
        } else {
            Stream.of(
                    binding.clearDone,
                    binding.markAsDone,
                    binding.clearDueDate,
                    binding.dueDateDate,
                    binding.dueDateTime
            ).forEach(v -> v.setOnClickListener(null));
        }
    }

    @Override
    public void onDateSet(DatePickerDialog view, int year, int monthOfYear, int dayOfMonth) {
        int hourOfDay;
        int minute;

        final var selectedTime = binding.dueDateTime.getText();
        if (TextUtils.isEmpty(selectedTime)) {
            hourOfDay = 0;
            minute = 0;
        } else {
            assert this.dueDate != null; // Since selectedTime is not empty and is derived from dueDate, dueDate itself shouldn't be null here.
            final var oldTime = LocalTime.from(this.dueDate.atZone(ZoneId.systemDefault()));
            hourOfDay = oldTime.getHour();
            minute = oldTime.getMinute();
        }

        final var newDateTime = ZonedDateTime.of(
                LocalDate.of(year, monthOfYear + 1, dayOfMonth),
                LocalTime.of(hourOfDay, minute),
                ZoneId.systemDefault()
        );
        this.dueDate = newDateTime.toInstant();

        if (dueDateChangedListener != null) {
            dueDateChangedListener.onDueDateChanged(newDateTime.toInstant());
        }
    }

    @Override
    public void onTimeSet(TimePickerDialog view, int hourOfDay, int minute, int second) {
        final var oldDateTime = this.dueDate == null ? ZonedDateTime.now() : this.dueDate.atZone(ZoneId.systemDefault());
        final var newDateTime = oldDateTime.with(
                LocalTime.of(hourOfDay, minute)
        );

        if (dueDateChangedListener != null) {
            dueDateChangedListener.onDueDateChanged(newDateTime.toInstant());
        }
    }

    @Override
    public void applyTheme(int color) {
        this.color = color;
        final var utils = ThemeUtils.of(color, getContext());

        Stream.of(
                binding.dueDateDateWrapper,
                binding.dueDateTimeWrapper
        ).forEach(utils.material::colorTextInputLayout);

        Stream.of(
                binding.clearDone,
                binding.clearDueDate
        ).forEach(v -> utils.platform.colorImageView(v, ColorRole.SECONDARY));

        utils.platform.colorTextView(binding.doneDate, ColorRole.ON_SURFACE);
        utils.platform.colorTextView(binding.doneDueDate, ColorRole.ON_SURFACE_VARIANT);
        utils.material.colorMaterialButtonPrimaryTonal(binding.markAsDone);
    }

    public void setDueDateListener(@Nullable DueDateChangedListener dueDateChangedListener) {
        this.dueDateChangedListener = dueDateChangedListener;
    }

    public interface DueDateChangedListener {
        void onDueDateChanged(@Nullable Instant dueDate);

        void onDoneChanged(@Nullable Instant done);
    }
}
