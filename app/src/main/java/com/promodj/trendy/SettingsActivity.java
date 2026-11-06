package com.promodj.trendy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Настройки: сохранённая музыка, место на устройстве и очистка кэша. */
public class SettingsActivity extends Activity {

    /** Страница поддержки разработчика на CloudTips. */
    private static final String DONATE_URL = "https://pay.cloudtips.ru/p/5320281d";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ListView list;
    private TextView storageInfo;
    private TextView tempInfo;
    private TextView freeInfo;
    private TextView emptyState;
    private TextView clearTempButton;
    private TextView deleteAllButton;
    private SavedAdapter adapter;

    private volatile long loadSerial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        setContentView(R.layout.activity_settings);

        list = (ListView) findViewById(R.id.settingsList);
        storageInfo = (TextView) findViewById(R.id.settingsStorage);
        tempInfo = (TextView) findViewById(R.id.settingsTemp);
        freeInfo = (TextView) findViewById(R.id.settingsFree);
        emptyState = (TextView) findViewById(R.id.settingsEmpty);
        clearTempButton = (TextView) findViewById(R.id.settingsClearTemp);
        deleteAllButton = (TextView) findViewById(R.id.settingsDeleteAll);

        findViewById(R.id.settingsBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        adapter = new SavedAdapter(this, new SavedAdapter.Listener() {
            @Override
            public void onPlay(AudioCache.Saved saved) {
                playSaved(saved);
            }

            @Override
            public void onDelete(AudioCache.Saved saved) {
                deleteOne(saved);
            }
        });
        list.setAdapter(adapter);

        clearTempButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmClearTemp();
            }
        });
        deleteAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDeleteAll();
            }
        });
        findViewById(R.id.settingsDonate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openDonatePage();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void reload() {
        final long serial = ++loadSerial;
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<AudioCache.Saved> saved = AudioCache.listSaved(app);
                final long savedBytes = AudioCache.totalSavedBytes(app);
                final long tempBytes = AudioCache.tempBytes(app);
                final long freeBytes = getFilesDir().getUsableSpace();
                if (serial != loadSerial) {
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (serial != loadSerial || isFinishing()) {
                            return;
                        }
                        apply(saved, savedBytes, tempBytes, freeBytes);
                    }
                });
            }
        });
    }

    private void apply(List<AudioCache.Saved> saved, long savedBytes,
                       long tempBytes, long freeBytes) {
        adapter.setItems(saved);
        storageInfo.setText(getString(R.string.settings_saved_summary,
                saved.size(), Ui.pluralTracks(saved.size()), Ui.formatSize(savedBytes)));
        tempInfo.setText(getString(R.string.settings_temp_cache, Ui.formatSize(tempBytes)));
        freeInfo.setText(getString(R.string.settings_free_space, Ui.formatSize(freeBytes)));

        boolean hasSaved = !saved.isEmpty();
        emptyState.setVisibility(hasSaved ? View.GONE : View.VISIBLE);
        list.setVisibility(hasSaved ? View.VISIBLE : View.GONE);

        deleteAllButton.setEnabled(hasSaved);
        deleteAllButton.setAlpha(hasSaved ? 1f : 0.45f);
        clearTempButton.setEnabled(tempBytes > 0);
        clearTempButton.setAlpha(tempBytes > 0 ? 1f : 0.45f);
    }

    private void playSaved(AudioCache.Saved saved) {
        if (saved == null || saved.track == null) {
            return;
        }
        ArrayList<Track> queue = new ArrayList<>();
        queue.add(saved.track);
        PlayerService.play(this, queue, 0);
        Toast.makeText(this,
                getString(R.string.settings_playing, saved.track.title),
                Toast.LENGTH_SHORT).show();
    }

    private void openDonatePage() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.settings_donate_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteOne(final AudioCache.Saved saved) {
        if (saved == null) {
            return;
        }
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                AudioCache.delete(app, saved.track.id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        Toast.makeText(SettingsActivity.this,
                                R.string.settings_deleted, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                });
            }
        });
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_delete_all_title)
                .setMessage(R.string.settings_delete_all_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteAll();
                    }
                })
                .show();
    }

    private void deleteAll() {
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final int removed = AudioCache.clearSaved(app);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        Toast.makeText(SettingsActivity.this,
                                removed > 0 ? R.string.settings_deleted
                                        : R.string.settings_nothing_to_delete,
                                Toast.LENGTH_SHORT).show();
                        reload();
                    }
                });
            }
        });
    }

    private void confirmClearTemp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_temp_title)
                .setMessage(R.string.settings_clear_temp_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearTemp();
                    }
                })
                .show();
    }

    private void clearTemp() {
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                AudioCache.clearTemp(app);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        Toast.makeText(SettingsActivity.this,
                                R.string.settings_deleted, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                });
            }
        });
    }
}
