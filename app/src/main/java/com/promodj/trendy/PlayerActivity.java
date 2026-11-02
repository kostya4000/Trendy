package com.promodj.trendy;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class PlayerActivity extends Activity implements PlayerService.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());

    private PlayerService service;
    private boolean bound;
    private boolean bindRequested;
    private long shownTrackId = -1L;
    private boolean userSeeking;

    private ImageView background;
    private RoundedImageView cover;
    private TextView title;
    private TextView artist;
    private SeekBar seekBar;
    private TextView timeCurrent;
    private TextView timeTotal;
    private ImageButton playButton;
    private TextView status;
    private TextView downloadButton;
    private TextView cacheButton;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (!isFinishing()) {
                main.postDelayed(this, 250);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bindRequested) {
                return;
            }
            service = ((PlayerService.LocalBinder) binder).getService();
            service.addListener(PlayerActivity.this);
            bound = true;
            if (service.getCurrent() == null) {
                finish();
                return;
            }
            refresh(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        setContentView(R.layout.activity_player);

        background = (ImageView) findViewById(R.id.playerBackground);
        cover = (RoundedImageView) findViewById(R.id.cover);
        title = (TextView) findViewById(R.id.title);
        artist = (TextView) findViewById(R.id.artist);
        seekBar = (SeekBar) findViewById(R.id.seek);
        timeCurrent = (TextView) findViewById(R.id.timeCurrent);
        timeTotal = (TextView) findViewById(R.id.timeTotal);
        playButton = (ImageButton) findViewById(R.id.play);
        status = (TextView) findViewById(R.id.status);
        downloadButton = (TextView) findViewById(R.id.download);
        cacheButton = (TextView) findViewById(R.id.cache);

        cover.setCornerRadius(Ui.dp(this, 22));
        resizeCover();

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareCurrent();
            }
        });
        findViewById(R.id.prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service != null) {
                    service.previous();
                }
            }
        });
        findViewById(R.id.next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service != null) {
                    service.next(true);
                }
            }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (service != null) {
                    service.toggle();
                }
            }
        });
        findViewById(R.id.openSite).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openOnSite();
            }
        });
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                downloadCurrent();
            }
        });
        cacheButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cacheCurrent();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || service == null) {
                    return;
                }
                int duration = service.getDuration();
                timeCurrent.setText(Ui.formatTime((int) (duration * (progress / 1000.0)) / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (service != null) {
                    int duration = service.getDuration();
                    if (duration > 0) {
                        service.seekTo((int) (duration * (bar.getProgress() / 1000.0)));
                    }
                }
            }
        });

        // На ТВ и автомобильных магнитолах управление идёт с пульта/кнопок.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            playButton.requestFocus();
        }
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.removeCallbacks(ticker);
        main.post(ticker);
        if (service != null) {
            refresh(false);
            updateProgress();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
    public void onPlaybackStateChanged() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refresh(false);
            }
        });
    }

    private void resizeCover() {
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        int heightDp = Math.round(getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        int sizeDp;
        if (widthDp > heightDp) {
            // Ландшафт (ТВ, магнитола): обложка живёт в левой колонке.
            sizeDp = Math.min((int) (widthDp * 0.34), heightDp - 120);
            sizeDp = Math.max(120, Math.min(sizeDp, 420));
        } else {
            sizeDp = Math.min(widthDp - 96, heightDp - 370);
            sizeDp = Math.max(120, Math.min(sizeDp, 300));
        }
        int size = Ui.dp(this, sizeDp);
        android.view.ViewGroup.LayoutParams params = cover.getLayoutParams();
        params.width = size;
        params.height = size;
        cover.setLayoutParams(params);
    }

    private void refresh(boolean force) {
        if (service == null) {
            return;
        }
        Track track = service.getCurrent();
        if (track == null) {
            finish();
            return;
        }
        boolean changed = force || track.id != shownTrackId;
        if (changed) {
            shownTrackId = track.id;
            title.setText(track.title);
            artist.setText(track.artist.length() > 0
                    ? track.artist : track.kindLabel());
            cover.setImageResource(R.drawable.ic_cover_placeholder);
            background.setImageDrawable(null);
            String coverUrl = track.coverLargeUrl != null && track.coverLargeUrl.length() > 0
                    ? track.coverLargeUrl : track.coverUrl;
            if (coverUrl != null && coverUrl.length() > 0) {
                ImageLoader.get(this).load(coverUrl, new ImageLoader.Callback() {
                    @Override
                    public void onImage(Bitmap bitmap) {
                        if (bitmap != null && service != null && service.getCurrent() != null
                                && service.getCurrent().id == shownTrackId) {
                            cover.setImageBitmap(bitmap);
                            background.setImageBitmap(bitmap);
                        }
                    }
                });
            }
            seekBar.setProgress(0);
            timeCurrent.setText("0:00");
        }

        updateCacheButton(track);

        int state = service.getState();
        boolean pauseIcon = state == PlayerService.STATE_PLAYING
                || state == PlayerService.STATE_BUFFERING;
        playButton.setImageResource(pauseIcon
                ? R.drawable.ic_pause_white : R.drawable.ic_play_white);
        playButton.setContentDescription(getString(
                pauseIcon ? R.string.pause : R.string.play));

        int duration = service.getDuration();
        timeTotal.setText(Ui.formatTime(duration / 1000));
        seekBar.setEnabled(duration > 0);

        if (state == PlayerService.STATE_ERROR) {
            status.setText(service.getLastError());
            status.setVisibility(View.VISIBLE);
        } else if (state == PlayerService.STATE_BUFFERING) {
            status.setText(R.string.loading);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        updateProgress();
    }

    private void updateProgress() {
        if (service == null) {
            return;
        }
        int duration = service.getDuration();
        int position = service.getPosition();
        if (duration > 0) {
            timeTotal.setText(Ui.formatTime(duration / 1000));
            if (!userSeeking) {
                seekBar.setProgress((int) Math.min(1000L,
                        Math.round(position * 1000.0 / duration)));
                timeCurrent.setText(Ui.formatTime(position / 1000));
            }
        }
    }

    private void shareCurrent() {
        if (service == null || service.getCurrent() == null) {
            return;
        }
        Track track = service.getCurrent();
        String url = track.pageUrl != null && track.pageUrl.length() > 0
                ? track.pageUrl : "https://promodj.com/trendy";
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, track.title);
        intent.putExtra(Intent.EXTRA_TEXT, track.artist.length() > 0
                ? track.artist + " — " + track.title + "\n" + url : track.title + "\n" + url);
        startActivity(Intent.createChooser(intent, getString(R.string.share)));
    }

    private void openOnSite() {
        if (service == null || service.getCurrent() == null) {
            return;
        }
        String url = service.getCurrent().pageUrl;
        if (url == null || url.length() == 0) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(this, R.string.link_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateCacheButton(Track track) {
        if (cacheButton == null || track == null) {
            return;
        }
        boolean cached = AudioCache.isCached(this, track.id);
        boolean downloading = AudioCache.isDownloading(track.id);
        if (cached) {
            cacheButton.setText(R.string.cached);
            cacheButton.setAlpha(0.72f);
        } else if (downloading) {
            cacheButton.setText(R.string.caching);
            cacheButton.setAlpha(1f);
        } else {
            cacheButton.setText(R.string.cache);
            cacheButton.setAlpha(1f);
        }
        cacheButton.setContentDescription(getString(
                cached ? R.string.cached : R.string.cache));
    }

    private void cacheCurrent() {
        if (service == null || service.getCurrent() == null) {
            return;
        }
        final Track track = service.getCurrent();
        if (AudioCache.isCached(this, track.id)) {
            Toast.makeText(this, R.string.cache_already, Toast.LENGTH_SHORT).show();
            return;
        }
        if (AudioCache.isDownloading(track.id)) {
            Toast.makeText(this, R.string.cache_downloading, Toast.LENGTH_SHORT).show();
            return;
        }
        if ((track.streamUrl == null || track.streamUrl.length() == 0)
                && (track.downloadUrl == null || track.downloadUrl.length() == 0)) {
            Toast.makeText(this, R.string.cache_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean started = AudioCache.download(this, track, new AudioCache.Listener() {
            @Override
            public void onCacheFinished(final long trackId, final boolean success) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (service != null && service.getCurrent() != null
                                && service.getCurrent().id == trackId) {
                            refresh(false);
                        }
                        Toast.makeText(PlayerActivity.this,
                                success ? R.string.cache_done : R.string.cache_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        if (started) {
            Toast.makeText(this, R.string.cache_started, Toast.LENGTH_SHORT).show();
        }
        refresh(false);
    }

    private void downloadCurrent() {
        if (service == null || service.getCurrent() == null) {
            return;
        }
        Track track = service.getCurrent();
        if (track.downloadUrl == null || track.downloadUrl.length() == 0) {
            Toast.makeText(this, R.string.download_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) {
                Toast.makeText(this, R.string.download_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            String name = Ui.safeFileName(track.artist.length() > 0
                    ? track.artist + " - " + track.title : track.title) + ".mp3";
            DownloadManager.Request request = new DownloadManager.Request(
                    Uri.parse(track.downloadUrl));
            request.setTitle(track.title);
            request.setDescription(track.artist.length() > 0
                    ? track.artist : getString(R.string.notification_album));
            request.setMimeType("audio/mpeg");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                request.setDestinationInExternalFilesDir(
                        this, Environment.DIRECTORY_MUSIC, name);
            }
            manager.enqueue(request);
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, R.string.download_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
