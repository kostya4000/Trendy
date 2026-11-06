package com.promodj.trendy;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Список сохранённых треков на экране настроек. */
public class SavedAdapter extends BaseAdapter implements Ui.RowAction {

    interface Listener {
        void onPlay(AudioCache.Saved saved);

        void onDelete(AudioCache.Saved saved);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<AudioCache.Saved> items = new ArrayList<>();
    private final Drawable actionRing;
    private int actionPosition = -1;

    SavedAdapter(Context context, Listener listener) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
        this.actionRing = context.getDrawable(R.drawable.bg_focus_ring_solid);
    }

    void setItems(List<AudioCache.Saved> saved) {
        items.clear();
        if (saved != null) {
            items.addAll(saved);
        }
        notifyDataSetChanged();
    }

    AudioCache.Saved getSaved(int position) {
        return items.get(position);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public int getActionPosition() {
        return actionPosition;
    }

    @Override
    public void setActionPosition(int position) {
        if (actionPosition == position) {
            return;
        }
        actionPosition = position;
        notifyDataSetChanged();
    }

    @Override
    public void performAction(int position) {
        if (position < 0 || position >= items.size() || listener == null) {
            return;
        }
        listener.onDelete(items.get(position));
    }

    @Override
    public void performPrimary(int position) {
        if (position < 0 || position >= items.size() || listener == null) {
            return;
        }
        listener.onPlay(items.get(position));
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).track.id;
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
            holder.delete = (TextView) convertView.findViewById(R.id.savedDelete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        final AudioCache.Saved saved = items.get(position);
        Track track = saved.track;
        holder.title.setText(track.title);
        holder.subtitle.setText(subtitle(saved));
        ImageLoader.get(context).load(track.coverUrl, holder.cover);
        holder.delete.setForeground(position == actionPosition ? actionRing : null);
        holder.delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) {
                    listener.onDelete(saved);
                }
            }
        });
        return convertView;
    }

    private String subtitle(AudioCache.Saved saved) {
        Track track = saved.track;
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
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(Ui.formatSize(saved.bytes));
        return text.toString();
    }

    private static final class ViewHolder {
        RoundedImageView cover;
        TextView title;
        TextView subtitle;
        TextView delete;
    }
}
