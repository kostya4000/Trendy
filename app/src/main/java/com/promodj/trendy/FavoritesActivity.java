package com.promodj.trendy;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Избранное: список треков, добавленных с экрана плеера или из ленты. */
public class FavoritesActivity extends Activity {

    private ListView list;
    private TextView summary;
    private TextView emptyState;
    private FavoritesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.darkSystemBars(this);
        setContentView(R.layout.activity_favorites);

        list = (ListView) findViewById(R.id.favoritesList);
        summary = (TextView) findViewById(R.id.favoritesSummary);
        emptyState = (TextView) findViewById(R.id.favoritesEmpty);

        findViewById(R.id.favoritesBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        adapter = new FavoritesAdapter(this, new FavoritesAdapter.Listener() {
            @Override
            public void onPlay(Track track) {
                play(track);
            }

            @Override
            public void onRemove(Track track) {
                remove(track);
            }
        });
        list.setAdapter(adapter);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<Track> favorites = Favorites.all(this);
        adapter.setItems(favorites);
        summary.setText(getString(R.string.favorites_summary,
                favorites.size(), Ui.pluralTracks(favorites.size())));
        boolean hasItems = !favorites.isEmpty();
        list.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(hasItems ? View.GONE : View.VISIBLE);
    }

    private void play(Track track) {
        if (track == null) {
            return;
        }
        ArrayList<Track> queue = new ArrayList<>(Favorites.all(this));
        int position = 0;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id == track.id) {
                position = i;
                break;
            }
        }
        if (queue.isEmpty()) {
            queue.add(track);
        }
        PlayerService.play(this, queue, position);
        Toast.makeText(this, getString(R.string.settings_playing, track.title),
                Toast.LENGTH_SHORT).show();
    }

    private void remove(Track track) {
        if (track == null) {
            return;
        }
        Favorites.remove(this, track.id);
        reload();
        Toast.makeText(this, R.string.favorite_removed, Toast.LENGTH_SHORT).show();
    }
}
