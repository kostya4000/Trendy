package com.promodj.trendy;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class TrackAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Track> items = new ArrayList<>();
    private long currentId = -1L;
    private boolean playing;

    public TrackAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Track> tracks) {
        items.clear();
        if (tracks != null) {
            items.addAll(tracks);
        }
        notifyDataSetChanged();
    }

    public List<Track> getItems() {
        return items;
    }

    public void setPlaybackState(long trackId, boolean isPlaying) {
        currentId = trackId;
        playing = isPlaying;
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
            convertView = inflater.inflate(R.layout.item_track, parent, false);
            holder = new ViewHolder();
            holder.cover = (RoundedImageView) convertView.findViewById(R.id.cover);
            holder.title = (TextView) convertView.findViewById(R.id.title);
            holder.subtitle = (TextView) convertView.findViewById(R.id.subtitle);
            holder.duration = (TextView) convertView.findViewById(R.id.duration);
            holder.equalizer = (ImageView) convertView.findViewById(R.id.equalizer);
            holder.cached = (ImageView) convertView.findViewById(R.id.cached);
            holder.favorite = (ImageView) convertView.findViewById(R.id.favorite);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Track track = items.get(position);
        boolean current = track.id == currentId;
        holder.title.setText(track.title);
        holder.subtitle.setText(track.subtitle());
        holder.duration.setText(track.durationLabel());
        holder.title.setTextColor(current
                ? context.getColor(R.color.accent) : context.getColor(R.color.text));
        holder.equalizer.setVisibility(current && playing ? View.VISIBLE : View.INVISIBLE);
        holder.cached.setVisibility(AudioCache.isCached(context, track.id)
                ? View.VISIBLE : View.GONE);
        holder.favorite.setVisibility(Favorites.isFavorite(context, track.id)
                ? View.VISIBLE : View.GONE);
        holder.cover.setAlpha(current ? 1f : 0.94f);
        convertView.setBackgroundResource(
                current ? R.drawable.bg_row_current : R.drawable.bg_row);
        ImageLoader.get(context).load(track.coverUrl, holder.cover);
        return convertView;
    }

    private static final class ViewHolder {
        RoundedImageView cover;
        TextView title;
        TextView subtitle;
        TextView duration;
        ImageView equalizer;
        ImageView cached;
        ImageView favorite;
    }
}
