package com.promodj.trendy;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Список избранных треков: нажатие играет, «Убрать» убирает из избранного. */
public class FavoritesAdapter extends BaseAdapter {

    interface Listener {
        void onPlay(Track track);

        void onRemove(Track track);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<Track> items = new ArrayList<>();

    FavoritesAdapter(Context context, Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    void setItems(List<Track> tracks) {
        items.clear();
        if (tracks != null) {
            items.addAll(tracks);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Track getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_saved, parent, false);
            holder = new ViewHolder();
            holder.cover = (RoundedImageView) convertView.findViewById(R.id.savedCover);
            holder.title = (TextView) convertView.findViewById(R.id.savedTitle);
            holder.subtitle = (TextView) convertView.findViewById(R.id.savedSubtitle);
            holder.remove = (TextView) convertView.findViewById(R.id.savedDelete);
            holder.remove.setText(R.string.favorites_remove);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final Track track = items.get(position);
        holder.title.setText(track.title);
        holder.subtitle.setText(subtitle(track));
        ImageLoader.get(context).load(track.coverUrl, holder.cover);
        convertView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onPlay(track);
                }
            }
        });
        holder.remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onRemove(track);
                }
            }
        });
        return convertView;
    }

    private String subtitle(Track track) {
        StringBuilder text = new StringBuilder();
        if (track.artist != null && track.artist.length() > 0) {
            text.append(track.artist);
        } else if (track.kindLabel().length() > 0) {
            text.append(track.kindLabel());
        }
        if (track.durationSec > 0) {
            if (text.length() > 0) {
                text.append(" · ");
            }
            text.append(track.durationLabel());
        }
        return text.toString();
    }

    private static final class ViewHolder {
        RoundedImageView cover;
        TextView title;
        TextView subtitle;
        TextView remove;
    }
}
