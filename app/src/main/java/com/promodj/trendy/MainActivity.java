package com.promodj.trendy;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements PlayerService.Listener {

    private static final String PREFS = "trendy";
    private static final int REQUEST_NOTIFICATIONS = 41;

    private static final String[] GENRE_KEYS = {
            "edm", "pop_rock", "hiphop_rnb_funk", "trance", "house_disco", "techno",
            "bass", "breaks_dnb", "hardcore", "ambient_jazz_downtempo", "experimental"
    };
    private static final String[] GENRE_NAMES = {
            "EDM", "Pop/Rock", "Hip-Hop", "Trance", "House", "Techno",
            "Bass", "Breaks/D&B", "Hardcore", "Ambient", "Experimental"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private TrackAdapter adapter;
    private ListView listView;
    private View emptyState;
    private ProgressBar progress;
    private TextView meta;
    private LinearLayout genreRow;
    private TextView[] genreChips;
    private TextView[] filterChips;

    private View miniPlayer;
    private RoundedImageView miniCover;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;
    private ProgressBar miniProgress;
    private ImageButton refreshButton;
    private TextView miniTimeCurrent;
    private TextView miniTimeTotal;

    /** Ландшафт: постоянный плеер слева, список справа. */
    private boolean panelMode;
    private SeekBar panelSeek;
    private boolean panelSeeking;

    private PlayerService service;
    private boolean bound;
    private boolean bindRequested;
    private String genre = "edm";
    private int filter;
    private volatile long loadSerial;
    private int seenErrorSerial = -1;
    private boolean initialFocusApplied;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bindRequested) {
                return;
            }
            PlayerService.LocalBinder local = (PlayerService.LocalBinder) binder;
            service = local.getService();
            service.addListener(MainActivity.this);
            bound = true;
            refreshMiniPlayer();
            updateHighlight();
            updateMiniProgress();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            refreshMiniPlayer();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateMiniProgress();
            if (!isFinishing()) {
                main.postDelayed(this, 500);
            }
        }
    };

    private final SeekBar.OnSeekBarChangeListener seekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!fromUser || service == null || miniTimeCurrent == null) {
                return;
            }
            int duration = service.getDuration();
            if (duration > 0) {
                int position = (int) (duration * (progress / 1000.0));
                miniTimeCurrent.setText(Ui.formatTime(position / 1000));
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
            panelSeeking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
            panelSeeking = false;
            if (service == null) {
                return;
            }
            int duration = service.getDuration();
            if (duration > 0) {
                service.seekTo((int) (duration * (bar.getProgress() / 1000.0)));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        genre = preferences.getString("genre", "edm");
        filter = preferences.getInt("filter", 0);
        if (indexOfGenre(genre) < 0) {
            genre = "edm";
        }
        if (filter < 0 || filter > 2) {
            filter = 0;
        }

        adapter = new TrackAdapter(this);
        listView = (ListView) findViewById(R.id.list);
        emptyState = findViewById(R.id.emptyState);
        progress = (ProgressBar) findViewById(R.id.progress);
        meta = (TextView) findViewById(R.id.meta);
        genreRow = (LinearLayout) findViewById(R.id.genreRow);
        refreshButton = (ImageButton) findViewById(R.id.refresh);

        miniPlayer = findViewById(R.id.miniPlayer);
        panelMode = findViewById(R.id.playerPanel) != null;
        miniCover = (RoundedImageView) findViewById(R.id.miniCover);
        miniTitle = (TextView) findViewById(R.id.miniTitle);
        miniArtist = (TextView) findViewById(R.id.miniArtist);
        miniPlay = (ImageButton) findViewById(R.id.miniPlay);
        miniProgress = (ProgressBar) findViewById(R.id.miniProgress);
        miniTimeCurrent = (TextView) findViewById(R.id.miniTimeCurrent);
        miniTimeTotal = (TextView) findViewById(R.id.miniTimeTotal);
        if (miniProgress instanceof SeekBar) {
            panelSeek = (SeekBar) miniProgress;
            panelSeek.setOnSeekBarChangeListener(seekListener);
        }

        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                List<Track> items = adapter.getItems();
                if (items.isEmpty()) {
                    return;
                }
                PlayerService.play(MainActivity.this, new ArrayList<>(items), position);
            }
        });

        findViewById(R.id.retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadFeed();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                view.animate().rotationBy(360f).setDuration(500).start();
                loadFeed();
            }
        });

        buildGenreChips();
        buildFilterChips();
        applyGenreStyles();
        applyFilterStyles();

        miniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service != null) {
                    service.toggle();
                } else {
                    PlayerService.command(MainActivity.this, PlayerService.ACTION_TOGGLE);
                }
            }
        });
        findViewById(R.id.miniNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service != null) {
                    service.next(true);
                } else {
                    PlayerService.command(MainActivity.this, PlayerService.ACTION_NEXT);
                }
            }
        });
        View miniPrev = findViewById(R.id.miniPrev);
        if (miniPrev != null) {
            miniPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (service != null) {
                        service.previous();
                    } else {
                        PlayerService.command(MainActivity.this, PlayerService.ACTION_PREV);
                    }
                }
            });
        }
        View miniInfo = findViewById(R.id.miniInfo);
        if (miniInfo != null) {
            miniInfo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openPlayer();
                }
            });
        }
        if (panelMode) {
            sizePanelCover();
        }

        loadFeed();
        requestNotificationPermission();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindRequested = true;
        bindService(new Intent(this, PlayerService.class), connection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
        refreshMiniPlayer();
        updateHighlight();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        bindRequested = false;
        if (service != null) {
            service.removeListener(this);
        }
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        bound = false;
        service = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onPlaybackStateChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshMiniPlayer();
                updateHighlight();
                updateMiniProgress();
                showPlaybackErrorIfNeeded();
            }
        });
    }

    // ---------------------------------------------------------------- feed

    private void loadFeed() {
        final String requestedGenre = genre;
        final int requestedFilter = filter;
        final long serial = ++loadSerial;

        TrackCache.Cached cached = TrackCache.load(this, requestedGenre, requestedFilter);
        if (cached != null && !cached.tracks.isEmpty()) {
            applyTracks(cached.tracks);
        } else {
            adapter.setItems(Collections.<Track>emptyList());
            emptyState.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }

        io.execute(new Runnable() {
            @Override
            public void run() {
                // Не тратим сеть на жанр, который пользователь уже переключил.
                if (serial != loadSerial) {
                    return;
                }
                try {
                    final List<Track> tracks = PromoDjApi.fetch(requestedGenre, requestedFilter);
                    TrackCache.save(MainActivity.this, requestedGenre, requestedFilter, tracks);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (serial != loadSerial) {
                                return;
                            }
                            progress.setVisibility(View.GONE);
                            applyTracks(tracks);
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (serial != loadSerial) {
                                return;
                            }
                            progress.setVisibility(View.GONE);
                            if (adapter.getCount() == 0) {
                                emptyState.setVisibility(View.VISIBLE);
                                meta.setText(R.string.error_network);
                                applyInitialFocus();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        R.string.error_network, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }
        });
    }

    private void applyTracks(List<Track> tracks) {
        adapter.setItems(tracks);
        emptyState.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        String filterName;
        if (filter == 1) {
            filterName = "миксы";
        } else if (filter == 2) {
            filterName = "треки";
        } else {
            filterName = "всё вместе";
        }
        meta.setText(tracks.size() + " " + Ui.pluralTracks(tracks.size())
                + " · " + GENRE_NAMES[indexOfGenre(genre)] + " · " + filterName);
        updateHighlight();
        applyInitialFocus();
    }

    private int indexOfGenre(String key) {
        for (int i = 0; i < GENRE_KEYS.length; i++) {
            if (GENRE_KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- chips

    private void buildGenreChips() {
        genreRow.removeAllViews();
        genreChips = new TextView[GENRE_KEYS.length];
        // На ТВ и в ландшафте чипы крупнее: выделение видно с дивана.
        int height = Ui.dp(this, panelMode ? 46 : 36);
        int horizontal = Ui.dp(this, panelMode ? 20 : 15);
        for (int i = 0; i < GENRE_KEYS.length; i++) {
            final int position = i;
            TextView chip = new TextView(this);
            chip.setText(GENRE_NAMES[i]);
            chip.setTextSize(panelMode ? 14f : 12f);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setBackgroundResource(R.drawable.bg_genre_chip);
            chip.setTextColor(getColorStateList(R.color.genre_chip_text));
            if (panelMode) {
                // Пультом: влево от жанров — к постоянному плееру.
                chip.setNextFocusLeftId(R.id.miniPlay);
            }
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setPadding(horizontal, 0, horizontal, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, height);
            params.setMarginEnd(Ui.dp(this, 7));
            chip.setLayoutParams(params);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (!genre.equals(GENRE_KEYS[position])) {
                        genre = GENRE_KEYS[position];
                        preferences.edit().putString("genre", genre).apply();
                        applyGenreStyles();
                        loadFeed();
                    }
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus) {
                        scrollChipIntoView(chip, false);
                        return;
                    }
                    // Фокус ушёл из строки жанров — возвращаем выбранный жанр в кадр,
                    // иначе на ТВ не видно, какой жанр включён.
                    genreRow.post(new Runnable() {
                        @Override
                        public void run() {
                            if (genreRow.findFocus() != null) {
                                return;
                            }
                            int index = indexOfGenre(genre);
                            if (index >= 0) {
                                scrollChipIntoView(genreChips[index], true);
                            }
                        }
                    });
                }
            });
            genreRow.addView(chip);
            genreChips[i] = chip;
        }
        final int selected = Math.max(0, indexOfGenre(genre));
        genreRow.post(new Runnable() {
            @Override
            public void run() {
                scrollChipIntoView(genreChips[selected], true);
            }
        });
    }

    /**
     * Прокручивает строку жанров ровно настолько, чтобы чип был виден.
     * center=true центрирует чип (возврат к выбранному жанру).
     */
    private void scrollChipIntoView(View chip, boolean center) {
        if (chip == null || !(genreRow.getParent() instanceof HorizontalScrollView)) {
            return;
        }
        HorizontalScrollView scroll = (HorizontalScrollView) genreRow.getParent();
        int width = scroll.getWidth();
        if (width <= 0 || chip.getWidth() <= 0) {
            return;
        }
        int padding = Ui.dp(this, 20);
        int x = scroll.getScrollX();
        int left = chip.getLeft();
        int right = chip.getRight();
        if (center) {
            scroll.smoothScrollTo(Math.max(0, left - (width - chip.getWidth()) / 2), 0);
        } else if (left - padding < x) {
            scroll.smoothScrollTo(Math.max(0, left - padding), 0);
        } else if (right + padding > x + width) {
            scroll.smoothScrollTo(Math.max(0, right + padding - width), 0);
        }
    }

    private void applyGenreStyles() {
        if (genreChips == null) {
            return;
        }
        for (int i = 0; i < genreChips.length; i++) {
            boolean selected = GENRE_KEYS[i].equals(genre);
            TextView chip = genreChips[i];
            // Фон и цвет текста — state-list в bg_genre_chip / genre_chip_text.
            chip.setSelected(selected);
            // Жирный шрифт дублирует цвет: на ТВ выделение видно и без оттенка.
            chip.setTypeface(Typeface.create("sans-serif",
                    selected ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    private void buildFilterChips() {
        filterChips = new TextView[]{
                (TextView) findViewById(R.id.filterAll),
                (TextView) findViewById(R.id.filterMixes),
                (TextView) findViewById(R.id.filterTracks)
        };
        for (int i = 0; i < filterChips.length; i++) {
            final int value = i;
            filterChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (filter != value) {
                        filter = value;
                        preferences.edit().putInt("filter", filter).apply();
                        applyFilterStyles();
                        loadFeed();
                    }
                }
            });
        }
    }

    private void applyFilterStyles() {
        if (filterChips == null) {
            return;
        }
        for (int i = 0; i < filterChips.length; i++) {
            boolean selected = filter == i;
            filterChips[i].setBackgroundResource(selected ? R.drawable.bg_filter_selected : 0);
            filterChips[i].setTextColor(getColor(selected
                    ? R.color.accent : R.color.text_dim));
        }
    }

    // ---------------------------------------------------------------- player

    private void refreshMiniPlayer() {
        Track track = service == null ? null : service.getCurrent();
        if (track == null) {
            if (panelMode) {
                showPanelPlaceholder();
            } else if (miniPlayer != null) {
                miniPlayer.setVisibility(View.GONE);
            }
            return;
        }
        if (miniPlayer != null) {
            miniPlayer.setVisibility(View.VISIBLE);
        }
        miniTitle.setText(track.title);
        miniArtist.setText(track.artist.length() > 0 ? track.artist : track.kindLabel());
        ImageLoader.get(this).load(track.coverUrl, miniCover);
        boolean pauseIcon = service.getState() == PlayerService.STATE_PLAYING
                || service.getState() == PlayerService.STATE_BUFFERING;
        miniPlay.setImageResource(pauseIcon
                ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        miniPlay.setContentDescription(getString(pauseIcon ? R.string.pause : R.string.play));
    }

    /** В ландшафте левая панель видна всегда, даже без трека. */
    private void showPanelPlaceholder() {
        if (miniTitle != null) {
            miniTitle.setText(R.string.nothing_playing);
        }
        if (miniArtist != null) {
            miniArtist.setText("");
        }
        if (miniCover != null) {
            miniCover.setImageResource(R.drawable.ic_cover_placeholder);
        }
        if (miniPlay != null) {
            miniPlay.setImageResource(R.drawable.ic_play_white);
            miniPlay.setContentDescription(getString(R.string.play));
        }
        if (miniProgress != null) {
            miniProgress.setProgress(0);
        }
        if (miniTimeCurrent != null) {
            miniTimeCurrent.setText(Ui.formatTime(0));
        }
        if (miniTimeTotal != null) {
            miniTimeTotal.setText(Ui.formatTime(0));
        }
    }

    /** Обложка панели — квадрат по меньшей стороне свободного места. */
    private void sizePanelCover() {
        final View box = findViewById(R.id.coverBox);
        if (box == null || miniCover == null) {
            return;
        }
        box.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || box.getWidth() == 0 || box.getHeight() == 0) {
                    return;
                }
                int size = Math.min(box.getWidth(), box.getHeight());
                size = Math.max(Ui.dp(MainActivity.this, 120), size);
                android.view.ViewGroup.LayoutParams params = miniCover.getLayoutParams();
                params.width = size;
                params.height = size;
                miniCover.setLayoutParams(params);
            }
        });
    }

    private void updateHighlight() {
        if (service == null || service.getCurrent() == null) {
            adapter.setPlaybackState(-1L, false);
        } else {
            adapter.setPlaybackState(service.getCurrent().id, service.isPlaying());
        }
    }

    private void updateMiniProgress() {
        if (service == null || service.getCurrent() == null) {
            return;
        }
        int duration = service.getDuration();
        int position = service.getPosition();
        if (miniProgress != null && !panelSeeking) {
            int value = duration > 0
                    ? (int) Math.min(1000L, Math.round(position * 1000.0 / duration)) : 0;
            miniProgress.setProgress(value);
            if (miniTimeCurrent != null) {
                miniTimeCurrent.setText(Ui.formatTime(position / 1000));
            }
        }
        if (miniTimeTotal != null) {
            miniTimeTotal.setText(Ui.formatTime(duration / 1000));
        }
    }

    private void openPlayer() {
        if (service != null && service.getCurrent() != null) {
            startActivity(new Intent(this, PlayerActivity.class));
        }
    }

    /** На ТВ/магнитоле без тачскрина сразу отдаём фокус списку. */
    private void applyInitialFocus() {
        if (initialFocusApplied
                || getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return;
        }
        View target = null;
        if (adapter.getCount() > 0) {
            target = listView;
        } else if (emptyState.getVisibility() == View.VISIBLE) {
            target = findViewById(R.id.retry);
        }
        if (target != null) {
            initialFocusApplied = true;
            target.requestFocus();
        }
    }

    private void showPlaybackErrorIfNeeded() {
        if (service == null) {
            return;
        }
        if (service.getState() == PlayerService.STATE_ERROR
                && service.getErrorSerial() != seenErrorSerial) {
            seenErrorSerial = service.getErrorSerial();
            String error = service.getLastError();
            Toast.makeText(this, error == null || error.length() == 0
                    ? getString(R.string.error_network) : error, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }
}
