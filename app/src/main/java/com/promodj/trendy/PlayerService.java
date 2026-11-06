package com.promodj.trendy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновое воспроизведение: MediaPlayer + MediaSession + foreground-уведомление.
 */
public class PlayerService extends Service {

    private static final String TAG = "TrendyPlayer";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 4242;
    /** Ограничение обложки для Binder: Notification/MediaMetadata идут в SystemUI. */
    private static final int ARTWORK_MAX = 512;
    /** Повторы одного и того же сетевого URL перед переходом к следующему треку. */
    private static final int MAX_NETWORK_RETRIES = 2;
    private static final long NETWORK_RETRY_DELAY_MS = 1200L;
    /** PlaybackState для системных элементов обновляем примерно раз в секунду. */
    private static final long SESSION_UPDATE_INTERVAL_MS = 1000L;
    /** Позицию последней сессии сохраняем не чаще раза в 5 секунд. */
    private static final long SESSION_SAVE_INTERVAL_MS = 5000L;

    private static final int FOCUS_NONE = 0;
    private static final int FOCUS_GRANTED = 1;
    private static final int FOCUS_LOST_TRANSIENT = 2;
    private static final int FOCUS_DUCKED = 3;

    public static final String ACTION_PLAY_QUEUE = "com.promodj.trendy.action.PLAY_QUEUE";
    public static final String ACTION_TOGGLE = "com.promodj.trendy.action.TOGGLE";
    public static final String ACTION_PAUSE = "com.promodj.trendy.action.PAUSE";
    public static final String ACTION_RESUME = "com.promodj.trendy.action.RESUME";
    public static final String ACTION_NEXT = "com.promodj.trendy.action.NEXT";
    public static final String ACTION_PREV = "com.promodj.trendy.action.PREV";
    public static final String ACTION_SEEK = "com.promodj.trendy.action.SEEK";
    public static final String ACTION_STOP = "com.promodj.trendy.action.STOP";

    public static final String EXTRA_QUEUE = "queue";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_SEEK_MS = "seek_ms";

    public static final int STATE_IDLE = 0;
    public static final int STATE_BUFFERING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_ERROR = 4;
    public static final int STATE_ENDED = 5;

    public interface Listener {
        void onPlaybackStateChanged();
    }

    public class LocalBinder extends Binder {
        public PlayerService getService() {
            return PlayerService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();

    private final ArrayList<Track> queue = new ArrayList<>();
    private int index = -1;
    private Track current;
    private int state = STATE_IDLE;
    private boolean playingFromCache;
    private volatile int playSerial;
    private int consecutiveErrors;
    private int networkRetries;
    private String lastError = "";
    private int errorSerial;
    private int focusState = FOCUS_NONE;
    private boolean ducked;

    private MediaPlayer player;
    private boolean playerPrepared;
    private int pendingSeekMs = -1;
    private MediaSession session;
    private AudioManager audioManager;
    private Object focusRequest;
    private boolean resumeOnFocusGain;
    private boolean foreground;
    private volatile boolean destroyed;
    private Bitmap currentArtwork;
    private NotificationManager notificationManager;
    private long lastSessionSave;

    /**
     * Пока играет музыка, позиция в MediaSession должна «идти»: системный
     * пульт и Android Auto читают её из PlaybackState, а не из MediaPlayer.
     */
    private final Runnable sessionTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (state == STATE_PLAYING) {
                updatePlaybackState();
                if (System.currentTimeMillis() - lastSessionSave
                        >= SESSION_SAVE_INTERVAL_MS) {
                    persistSession();
                }
                main.postDelayed(this, SESSION_UPDATE_INTERVAL_MS);
            }
        }
    };

    public static void play(Context context, ArrayList<Track> tracks, int position) {
        Intent intent = new Intent(context, PlayerService.class);
        intent.setAction(ACTION_PLAY_QUEUE);
        intent.putExtra(EXTRA_QUEUE, (Serializable) tracks);
        intent.putExtra(EXTRA_INDEX, position);
        context.startService(intent);
    }

    public static void command(Context context, String action) {
        Intent intent = new Intent(context, PlayerService.class);
        intent.setAction(action);
        context.startService(intent);
    }

    public static void seek(Context context, int milliseconds) {
        Intent intent = new Intent(context, PlayerService.class);
        intent.setAction(ACTION_SEEK);
        intent.putExtra(EXTRA_SEEK_MS, milliseconds);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        setupMediaSession();
        restoreSession();
        try {
            registerReceiver(noisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        } catch (Exception error) {
            Log.w(TAG, "noisy receiver not registered", error);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_PLAY_QUEUE.equals(action)) {
            Serializable extra = intent.getSerializableExtra(EXTRA_QUEUE);
            if (extra instanceof ArrayList) {
                //noinspection unchecked
                ArrayList<Track> incoming = (ArrayList<Track>) extra;
                if (!incoming.isEmpty()) {
                    queue.clear();
                    queue.addAll(incoming);
                    consecutiveErrors = 0;
                    int position = intent.getIntExtra(EXTRA_INDEX, 0);
                    if (position < 0 || position >= queue.size()) {
                        position = 0;
                    }
                    playAt(position);
                }
            }
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_RESUME.equals(action)) {
            resume();
        } else if (ACTION_NEXT.equals(action)) {
            next(true);
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getIntExtra(EXTRA_SEEK_MS, 0));
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        // Сервис может умереть в любой момент — фиксируем позицию сразу.
        saveSessionBeforeDestroy();
        destroyed = true;
        playSerial++;
        main.removeCallbacks(sessionTicker);
        releasePlayer();
        try {
            unregisterReceiver(noisyReceiver);
        } catch (Exception ignored) {
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        abandonAudioFocus();
        stopForeground(true);
        foreground = false;
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        resolver.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- playback

    /**
     * Возвращает последнюю сессию: очередь, трек и позицию. Воспроизведение
     * не запускаем — приложение не должно зазвучать само при старте, но
     * мини-плеер сразу показывает, где остановились, и play продолжит
     * с этого места.
     */
    private void restoreSession() {
        PlaybackCache.Snapshot snapshot = PlaybackCache.load(this);
        if (snapshot == null || snapshot.queue.isEmpty()) {
            return;
        }
        queue.clear();
        queue.addAll(snapshot.queue);
        index = snapshot.index;
        current = queue.get(index);
        pendingSeekMs = snapshot.positionMs;
        state = STATE_PAUSED;
        updateSessionMetadata(current, null);
        updatePlaybackState();
        if (session != null) {
            // Без уведомления не стоит висеть в системных медиа-контролах.
            session.setActive(false);
        }
    }

    private void persistSession() {
        persistSession(getPosition());
    }

    private void persistSession(int positionMs) {
        if (current == null || queue.isEmpty() || index < 0 || index >= queue.size()) {
            return;
        }
        lastSessionSave = System.currentTimeMillis();
        PlaybackCache.saveAsync(this, queue, index, Math.max(0, positionMs));
    }

    /** Синхронный снимок на случай, если процесс вот-вот завершится. */
    private void saveSessionBeforeDestroy() {
        if (current == null || queue.isEmpty() || index < 0 || index >= queue.size()) {
            return;
        }
        int position = (state == STATE_PLAYING || state == STATE_BUFFERING
                || state == STATE_PAUSED) ? getPosition() : 0;
        PlaybackCache.saveNow(this, queue, index, Math.max(0, position));
    }

    private void playAt(int position) {
        playAt(position, -1);
    }

    private void playAt(int position, int startMs) {
        if (destroyed || position < 0 || position >= queue.size()) {
            return;
        }
        index = position;
        current = queue.get(position);
        pendingSeekMs = startMs;
        currentArtwork = null;
        lastError = "";
        networkRetries = 0;
        int serial = ++playSerial;
        releasePlayer();
        // Запоминаем, что играет сейчас, ещё до загрузки источника.
        persistSession(startMs >= 0 ? startMs : 0);

        // Без аудиофокуса не начинаем: иначе музыка зазвучит поверх
        // навигатора или звонка.
        if (!requestAudioFocus()) {
            focusState = FOCUS_NONE;
            lastError = getString(R.string.audio_focus_denied);
            errorSerial++;
            state = STATE_ERROR;
            if (session != null) {
                session.setActive(true);
            }
            updateSessionMetadata(current, null);
            updatePlaybackState();
            if (foreground) {
                // Не оставляем в уведомлении предыдущий трек.
                updateNotification();
            }
            notifyListeners();
            return;
        }

        setState(STATE_BUFFERING);
        if (session != null) {
            session.setActive(true);
        }
        updateSessionMetadata(current, null);
        loadArtwork(current);

        final Track track = current;
        if (AudioCache.isCached(this, track.id)) {
            playingFromCache = true;
            preparePlayer(AudioCache.file(this, track.id).getAbsolutePath(),
                    track, true, true, serial);
        } else {
            playingFromCache = false;
            resolveAndPlay(track, serial);
        }
    }

    /** Разворачивает prelisten-редирект и готовит сетевой источник. */
    private void resolveAndPlay(final Track track, final int serial) {
        resolver.execute(new Runnable() {
            @Override
            public void run() {
                // Быстрые переключения треков не должны копить устаревшие HEAD-запросы.
                if (serial != playSerial || destroyed) {
                    return;
                }
                final String resolved = Http.resolveStream(track.streamUrl);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (serial != playSerial) {
                            return;
                        }
                        playingFromCache = false;
                        preparePlayer(resolved, track, false, false, serial);
                    }
                });
            }
        });
    }

    private void preparePlayer(final String url, final Track track, final boolean fallbackUsed,
                               final boolean fromCache, final int serial) {
        if (destroyed || serial != playSerial) {
            return;
        }
        releasePlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mediaPlayer) {
                    if (mediaPlayer != player || serial != playSerial) {
                        return;
                    }
                    playerPrepared = true;
                    consecutiveErrors = 0;
                    networkRetries = 0;
                    if (pendingSeekMs >= 0) {
                        mediaPlayer.seekTo(pendingSeekMs);
                        pendingSeekMs = -1;
                    }
                    applyVolume();
                    mediaPlayer.start();
                    setState(STATE_PLAYING);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {
                    if (mediaPlayer != player) {
                        return;
                    }
                    // Не освобождаем MediaPlayer изнутри его callback.
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || serial != playSerial
                                    || state != STATE_PLAYING) {
                                return;
                            }
                            if (index < queue.size() - 1) {
                                playAt(index + 1);
                            } else {
                                endQueue();
                            }
                        }
                    });
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                    if (mediaPlayer != player) {
                        return true;
                    }
                    // Обработку и повторные попытки делаем вне callback плеера.
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || serial != playSerial) {
                                return;
                            }
                            handleSourceError(track, url, fallbackUsed, fromCache);
                        }
                    });
                    return true;
                }
            });
            mp.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override
                public void onBufferingUpdate(MediaPlayer mediaPlayer, int percent) {
                    // Прогресс буферизации не показываем отдельно; состояние уже есть.
                }
            });
            mp.prepareAsync();
        } catch (Exception error) {
            Log.w(TAG, "prepare failed", error);
            if (destroyed || serial != playSerial) {
                return;
            }
            handleSourceError(track, url, fallbackUsed, fromCache);
        }
    }

    /**
     * Единая точка обработки сбоя источника: битый кэш → сеть, запасной URL,
     * затем ограниченные повторы с задержкой.
     */
    private void handleSourceError(final Track track, final String url,
                                   final boolean fallbackUsed, final boolean fromCache) {
        if (destroyed) {
            return;
        }
        if (fromCache) {
            // Сохранённый пользователем файл не удаляем: чистим только
            // временную копию и пробуем сеть.
            AudioCache.deleteTemp(this, track.id);
            playingFromCache = false;
            releasePlayer();
            resolveAndPlay(track, playSerial);
            return;
        }
        String fallback = track.streamUrl == null ? "" : track.streamUrl;
        if (!fallbackUsed && url != null && fallback.length() > 0
                && !url.equals(fallback)) {
            preparePlayer(fallback, track, true, false, playSerial);
            return;
        }
        if (networkRetries < MAX_NETWORK_RETRIES) {
            networkRetries++;
            setState(STATE_BUFFERING);
            final int serial = playSerial;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && serial == playSerial) {
                        preparePlayer(url, track, fallbackUsed, false, serial);
                    }
                }
            }, NETWORK_RETRY_DELAY_MS);
            return;
        }
        handlePlaybackError();
    }

    private void handlePlaybackError() {
        if (destroyed) {
            return;
        }
        consecutiveErrors++;
        errorSerial++;
        networkRetries = 0;
        lastError = getString(R.string.error_playback);
        boolean canAdvance = queue.size() > 1 && consecutiveErrors <= 2
                && index < queue.size() - 1;
        releasePlayer();
        if (canAdvance) {
            setState(STATE_ERROR);
            final int serial = playSerial;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && state == STATE_ERROR && serial == playSerial) {
                        next(false);
                    }
                }
            }, 1800);
        } else {
            // Терминальная ошибка: ресурсы и фокус больше не нужны.
            ducked = false;
            abandonAudioFocus();
            setState(STATE_ERROR);
        }
    }

    private void endQueue() {
        ducked = false;
        resumeOnFocusGain = false;
        applyVolume();
        abandonAudioFocus();
        setState(STATE_ENDED);
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        playerPrepared = false;
        if (old != null) {
            try {
                old.reset();
            } catch (Exception ignored) {
            }
            try {
                old.release();
            } catch (Exception ignored) {
            }
        }
    }

    public void toggle() {
        if (state == STATE_PLAYING || state == STATE_BUFFERING) {
            pause();
        } else if (state == STATE_PAUSED) {
            resume();
        } else if (current != null && index >= 0) {
            playAt(index);
        }
    }

    public void pause() {
        pauseInternal(true);
    }

    private void pauseInternal(boolean abandonFocus) {
        if (state == STATE_BUFFERING) {
            playSerial++;
            releasePlayer();
        } else if (player != null && playerPrepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (Exception ignored) {
            }
        }
        if (state != STATE_IDLE) {
            setState(STATE_PAUSED);
        }
        if (abandonFocus) {
            // Пользователь сам поставил паузу — после возврата фокуса
            // возобновлять не нужно.
            resumeOnFocusGain = false;
            abandonAudioFocus();
        }
    }

    public void resume() {
        if (current == null) {
            return;
        }
        if (!requestAudioFocus()) {
            lastError = getString(R.string.audio_focus_denied);
            errorSerial++;
            setState(STATE_ERROR);
            return;
        }
        startPlayback();
    }

    /** Запуск с уже полученным аудиофокусом (в т.ч. после AUDIOFOCUS_GAIN). */
    private void startPlayback() {
        if (current == null) {
            return;
        }
        if (state == STATE_ENDED || state == STATE_ERROR
                || player == null || !playerPrepared) {
            consecutiveErrors = 0;
            // Позиция могла остаться от восстановленной сессии.
            playAt(index < 0 ? 0 : index, pendingSeekMs);
            return;
        }
        try {
            applyVolume();
            player.start();
            setState(STATE_PLAYING);
        } catch (Exception error) {
            handlePlaybackError();
        }
    }

    public void next(boolean fromUser) {
        if (queue.isEmpty()) {
            return;
        }
        if (fromUser) {
            consecutiveErrors = 0;
        }
        int nextIndex = index + 1;
        if (nextIndex >= queue.size()) {
            if (fromUser) {
                nextIndex = 0;
            } else {
                endQueue();
                return;
            }
        }
        playAt(nextIndex);
    }

    public void previous() {
        if (queue.isEmpty()) {
            return;
        }
        if (playerPrepared && getPosition() > 4000) {
            seekTo(0);
            return;
        }
        int prevIndex = index - 1;
        if (prevIndex < 0) {
            prevIndex = queue.size() - 1;
        }
        playAt(prevIndex);
    }

    public void seekTo(int milliseconds) {
        if (milliseconds < 0) {
            milliseconds = 0;
        }
        if (player != null && playerPrepared) {
            try {
                player.seekTo(milliseconds);
            } catch (Exception ignored) {
            }
        } else {
            pendingSeekMs = milliseconds;
        }
        persistSession(milliseconds);
        updatePlaybackState();
        notifyListeners();
    }

    public void stopPlayback() {
        playSerial++;
        main.removeCallbacks(sessionTicker);
        releasePlayer();
        queue.clear();
        index = -1;
        current = null;
        currentArtwork = null;
        playingFromCache = false;
        ducked = false;
        resumeOnFocusGain = false;
        lastError = "";
        state = STATE_IDLE;
        if (session != null) {
            session.setActive(false);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0f)
                    .build());
        }
        abandonAudioFocus();
        stopForeground(true);
        foreground = false;
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        PlaybackCache.clear(this);
        notifyListeners();
        stopSelf();
    }

    // ---------------------------------------------------------------- state

    private void setState(int newState) {
        state = newState;
        updatePlaybackState();
        updateNotification();
        // Пока играет — обновляем позицию в MediaSession раз в секунду.
        // На паузе/ошибке периодические обновления прекращаются.
        main.removeCallbacks(sessionTicker);
        if (state == STATE_PLAYING) {
            main.postDelayed(sessionTicker, SESSION_UPDATE_INTERVAL_MS);
        }
        // Пауза — самый важный момент для сохранения позиции; после конца
        // очереди трек при следующем запуске начнётся сначала.
        if (state == STATE_PAUSED) {
            persistSession();
        } else if (state == STATE_ENDED) {
            persistSession(0);
        } else if (state == STATE_ERROR) {
            // Если сеть подвела до подготовки, не теряем восстановленную позицию.
            persistSession(pendingSeekMs >= 0 ? pendingSeekMs : 0);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onPlaybackStateChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public List<Track> getQueue() {
        return new ArrayList<>(queue);
    }

    public int getIndex() {
        return index;
    }

    public Track getCurrent() {
        return current;
    }

    public int getState() {
        return state;
    }

    public boolean isPlaying() {
        return state == STATE_PLAYING;
    }

    public boolean isPlayingFromCache() {
        return playingFromCache;
    }

    public int getPosition() {
        if (player != null && playerPrepared) {
            try {
                return Math.max(0, player.getCurrentPosition());
            } catch (Exception ignored) {
            }
        }
        // Позиция из восстановленной сессии — до первого prepare.
        if (pendingSeekMs >= 0) {
            return pendingSeekMs;
        }
        return 0;
    }

    public int getDuration() {
        if (player != null && playerPrepared) {
            try {
                int value = player.getDuration();
                if (value > 0) {
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        return current != null ? current.durationSec * 1000 : 0;
    }

    public int getErrorSerial() {
        return errorSerial;
    }

    public String getLastError() {
        return lastError;
    }

    // ---------------------------------------------------------------- session

    private void setupMediaSession() {
        session = new MediaSession(this, "PromoDJ Trendy");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stopPlayback();
            }

            @Override
            public void onSkipToNext() {
                next(true);
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }
        });
        session.setSessionActivity(playerActivityIntent());
        session.setActive(true);
        updatePlaybackState();
    }

    private PendingIntent playerActivityIntent() {
        Intent intent = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 100, intent, flags);
    }

    private void updatePlaybackState() {
        if (session == null) {
            return;
        }
        int sessionState;
        switch (state) {
            case STATE_BUFFERING:
                sessionState = PlaybackState.STATE_BUFFERING;
                break;
            case STATE_PLAYING:
                sessionState = PlaybackState.STATE_PLAYING;
                break;
            case STATE_PAUSED:
                sessionState = PlaybackState.STATE_PAUSED;
                break;
            case STATE_ERROR:
                sessionState = PlaybackState.STATE_ERROR;
                break;
            case STATE_ENDED:
                sessionState = PlaybackState.STATE_STOPPED;
                break;
            default:
                sessionState = PlaybackState.STATE_NONE;
                break;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(sessionState, getPosition(),
                        state == STATE_PLAYING ? 1f : 0f);
        if (lastError.length() > 0 && state == STATE_ERROR) {
            builder.setErrorMessage(lastError);
        }
        session.setPlaybackState(builder.build());
    }

    private void updateSessionMetadata(Track track, Bitmap artwork) {
        if (session == null || track == null) {
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        track.artist.length() > 0 ? track.artist : "PromoDJ")
                .putString(MediaMetadata.METADATA_KEY_ALBUM,
                        getString(R.string.notification_album))
                .putLong(MediaMetadata.METADATA_KEY_DURATION,
                        track.durationSec > 0 ? track.durationSec * 1000L : 0L);
        if (artwork != null) {
            // Только один ключ: два одинаковых Bitmap удваивают размер Binder-транзакции.
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        session.setMetadata(builder.build());
    }

    private Bitmap scaleArtwork(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        if (width <= 0 || height <= 0 || longest <= ARTWORK_MAX) {
            return source;
        }
        float ratio = (float) ARTWORK_MAX / longest;
        int scaledWidth = Math.max(1, Math.round(width * ratio));
        int scaledHeight = Math.max(1, Math.round(height * ratio));
        try {
            return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        } catch (Exception ignored) {
            return source;
        }
    }

    private void loadArtwork(final Track track) {
        if (track.coverUrl == null || track.coverUrl.length() == 0) {
            return;
        }
        ImageLoader.get(this).load(track.coverUrl, new ImageLoader.Callback() {
            @Override
            public void onImage(Bitmap bitmap) {
                if (destroyed || bitmap == null || current == null || current.id != track.id) {
                    return;
                }
                currentArtwork = scaleArtwork(bitmap);
                updateSessionMetadata(track, currentArtwork);
                updateNotification();
            }
        });
    }

    // ---------------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void updateNotification() {
        if (destroyed || current == null || session == null) {
            return;
        }
        PendingIntent contentIntent = playerActivityIntent();

        PendingIntent previousIntent = serviceIntent(ACTION_PREV, 1);
        PendingIntent toggleIntent = serviceIntent(
                isPlaying() || state == STATE_BUFFERING ? ACTION_PAUSE : ACTION_RESUME, 2);
        PendingIntent nextIntent = serviceIntent(ACTION_NEXT, 3);
        PendingIntent stopIntent = serviceIntent(ACTION_STOP, 4);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW);
        }
        builder.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(current.title)
                .setContentText(current.artist.length() > 0
                        ? current.artist : getString(R.string.notification_album))
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .setColor(0xFFFF2D46)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(isPlaying() || state == STATE_BUFFERING)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_prev_white, getString(R.string.prev), previousIntent).build())
                .addAction(new Notification.Action.Builder(
                        isPlaying() || state == STATE_BUFFERING
                                ? R.drawable.ic_pause_white : R.drawable.ic_play_white,
                        isPlaying() || state == STATE_BUFFERING
                                ? getString(R.string.pause) : getString(R.string.play),
                        toggleIntent).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_next_white, getString(R.string.next), nextIntent).build())
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stop_white, getString(R.string.stop), stopIntent).build());
        if (currentArtwork != null) {
            builder.setLargeIcon(currentArtwork);
        }

        Notification notification = builder.build();
        try {
            if (!foreground) {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
                foreground = true;
            } else if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception error) {
            Log.w(TAG, "startForeground failed", error);
        }
    }

    // ---------------------------------------------------------------- audio focus

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            focusState = FOCUS_GRANTED;
            return true;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(focusListener)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            result = audioManager.requestAudioFocus(
                    (android.media.AudioFocusRequest) focusRequest);
        } else {
            //noinspection deprecation
            result = audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusState = FOCUS_GRANTED;
            return true;
        }
        focusState = FOCUS_NONE;
        return false;
    }

    private void abandonAudioFocus() {
        focusState = FOCUS_NONE;
        ducked = false;
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(
                        (android.media.AudioFocusRequest) focusRequest);
            }
        } else {
            //noinspection deprecation
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    private void applyVolume() {
        if (player == null) {
            return;
        }
        float volume = ducked ? 0.2f : 1f;
        try {
            player.setVolume(volume, volume);
        } catch (Exception ignored) {
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        focusState = FOCUS_NONE;
                        ducked = false;
                        resumeOnFocusGain = false;
                        if (state == STATE_PLAYING || state == STATE_BUFFERING) {
                            pauseInternal(false);
                        }
                        applyVolume();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        focusState = FOCUS_LOST_TRANSIENT;
                        ducked = false;
                        resumeOnFocusGain = state == STATE_PLAYING
                                || state == STATE_BUFFERING;
                        if (state == STATE_PLAYING || state == STATE_BUFFERING) {
                            pauseInternal(false);
                        }
                        applyVolume();
                    } else if (focusChange
                            == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        focusState = FOCUS_DUCKED;
                        ducked = true;
                        applyVolume();
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        focusState = FOCUS_GRANTED;
                        ducked = false;
                        applyVolume();
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false;
                            startPlayback();
                        }
                    }
                }
            };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };
}
