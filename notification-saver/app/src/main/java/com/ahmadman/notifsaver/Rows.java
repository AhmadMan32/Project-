package com.ahmadman.notifsaver;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ahmadman.notifsaver.core.Capture;
import com.ahmadman.notifsaver.core.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** List adapters: apps / conversations, notification entries, and chat bubbles. */
final class Rows {

    private Rows() {
    }

    /** Who wrote it, for chat messages in groups (null when it is obvious). */
    static String shownSender(Capture c, String self) {
        if (!c.message || c.sender == null || c.sender.equals(c.conversation) || c.sender.equals(self)) {
            return null;
        }
        return c.sender;
    }

    static String headline(Capture c, String self) {
        String main = c.message ? c.conversation : c.title != null ? c.title : c.conversation;
        String s = shownSender(c, self);
        return s != null && main != null ? main + " · " + s : main != null ? main : s;
    }

    /** "dd.MM.yyyy HH:mm:ss Sender: text" lines for sharing a conversation. */
    static String transcript(List<Capture> rows, TimeZone tz) {
        java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
        d.setTimeZone(tz);
        StringBuilder sb = new StringBuilder();
        for (Capture c : rows) {
            sb.append(d.format(new java.util.Date(c.time))).append(' ').append(Formats.time(c.time, tz)).append(' ');
            String who = c.sender != null ? c.sender : c.title;
            if (who != null) {
                sb.append(who).append(": ");
            }
            sb.append(c.body()).append('\n');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------

    static final class GroupAdapter extends BaseAdapter {
        private final Context context;
        private final boolean apps;
        private final String self;
        private List<Db.Group> items = new ArrayList<>();
        long now = System.currentTimeMillis();

        GroupAdapter(Context c, boolean apps) {
            this.context = c;
            this.apps = apps;
            this.self = c.getString(R.string.self_sender);
        }

        void set(List<Db.Group> list) {
            items = list;
            now = System.currentTimeMillis();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Db.Group getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).last.id;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_group, parent, false);
            }
            Db.Group g = items.get(position);
            Capture c = g.last;
            ImageView icon = v.findViewById(R.id.icon);
            String name;
            String preview;
            if (apps) {
                name = g.app != null ? g.app : g.pkg;
                Icons.into(icon, g.pkg, name);
                String head = headline(c, self);
                preview = head != null ? head + ": " + c.body() : c.body();
            } else {
                name = g.conversation != null && !g.conversation.isEmpty() ? g.conversation : g.app;
                icon.setTag(R.id.icon, null);
                icon.setImageDrawable(new Ui.Letter(context, name));
                String s = shownSender(c, self);
                if (c.message && self.equals(c.sender)) {
                    s = self;
                }
                preview = s != null ? s + ": " + c.body() : c.body();
            }
            if (c.recovered || c.deleted) {
                preview = "🚫 " + preview;
            }
            ((TextView) v.findViewById(R.id.name)).setText(name);
            ((TextView) v.findViewById(R.id.preview)).setText(preview);
            ((TextView) v.findViewById(R.id.when)).setText(Formats.shortWhen(c.time, now, TimeZone.getDefault(),
                    Locale.getDefault(), context.getString(R.string.yesterday)));
            ((TextView) v.findViewById(R.id.count)).setText(Ui.number(g.count));
            return v;
        }
    }

    // -------------------------------------------------------------------------------------

    /** Day header (String) or entry (Capture). */
    abstract static class DayAdapter extends BaseAdapter {
        final Context context;
        final String self;
        final List<Object> items = new ArrayList<>();
        List<Capture> rows = new ArrayList<>();

        DayAdapter(Context c) {
            this.context = c;
            this.self = c.getString(R.string.self_sender);
        }

        void set(List<Capture> list) {
            rows = list;
            items.clear();
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            long lastDay = Long.MIN_VALUE;
            for (Capture c : list) {
                long day = Formats.dayDiff(c.time, now, tz);
                if (day != lastDay) {
                    items.add(Formats.dayHeader(c.time, now, tz, Locale.getDefault(), context.getString(R.string.today),
                            context.getString(R.string.yesterday)));
                    lastDay = day;
                }
                items.add(c);
            }
            notifyDataSetChanged();
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
        public long getItemId(int position) {
            Object o = items.get(position);
            return o instanceof Capture ? ((Capture) o).id : -position - 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return items.get(position) instanceof Capture;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        View day(View v, ViewGroup parent, String text) {
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_day, parent, false);
            }
            ((TextView) v.findViewById(R.id.day)).setText(text);
            return v;
        }
    }

    /** Timeline, search results and deleted messages: newest first. */
    static final class EntryAdapter extends DayAdapter {

        EntryAdapter(Context c) {
            super(c);
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof Capture ? 1 : 0;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            Object o = items.get(position);
            if (!(o instanceof Capture)) {
                return day(v, parent, (String) o);
            }
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_entry, parent, false);
            }
            Capture c = (Capture) o;
            Icons.into(v.findViewById(R.id.icon), c.pkg, c.app);
            String app = c.app != null ? c.app : c.pkg;
            ((TextView) v.findViewById(R.id.app)).setText(c.sub != null ? app + " · " + c.sub : app);
            ((TextView) v.findViewById(R.id.when)).setText(Formats.time(c.time, TimeZone.getDefault()));
            Ui.text(v.findViewById(R.id.title), headline(c, self));
            Ui.text(v.findViewById(R.id.text), c.body());
            TextView badge = v.findViewById(R.id.badge);
            Ui.text(badge, c.recovered ? context.getString(R.string.badge_recovered)
                    : c.deleted ? context.getString(R.string.badge_marker) : null);
            return v;
        }
    }

    /** One conversation: oldest first, chat bubbles. */
    static final class ThreadAdapter extends DayAdapter {

        ThreadAdapter(Context c) {
            super(c);
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object o = items.get(position);
            if (!(o instanceof Capture)) {
                return 0;
            }
            return ((Capture) o).deleted ? 2 : 1;
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            Object o = items.get(position);
            if (!(o instanceof Capture)) {
                return day(v, parent, (String) o);
            }
            Capture c = (Capture) o;
            String time = Formats.time(c.time, TimeZone.getDefault());
            if (c.deleted) {
                if (v == null) {
                    v = LayoutInflater.from(context).inflate(R.layout.item_system, parent, false);
                }
                String who = c.sender != null && !c.sender.equals(self) ? c.sender : null;
                String msg = who != null ? context.getString(R.string.system_deleted_by, who)
                        : context.getString(R.string.system_deleted);
                ((TextView) v.findViewById(R.id.text)).setText("🚫 " + msg + " · " + time);
                return v;
            }
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.item_bubble, parent, false);
            }
            boolean out = c.message && self.equals(c.sender);
            int wide = Ui.dp(context, 12);
            int narrow = Ui.dp(context, 56);
            View row = v.findViewById(R.id.row);
            row.setPadding(out ? narrow : wide, row.getPaddingTop(), out ? wide : narrow, row.getPaddingBottom());
            LinearLayout bubble = v.findViewById(R.id.bubble);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            lp.gravity = out ? Gravity.END : Gravity.START;
            bubble.setLayoutParams(lp);
            bubble.setBackgroundResource(c.recovered ? R.drawable.bg_bubble_deleted
                    : out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
            TextView sender = v.findViewById(R.id.sender);
            String s = shownSender(c, self);
            if (s == null && !c.message && c.title != null && !c.title.equals(c.conversation)) {
                s = c.title;
            }
            Ui.text(sender, s);
            if (s != null) {
                sender.setTextColor(Ui.colorFor(context, s));
            }
            TextView text = v.findViewById(R.id.text);
            text.setText(c.body());
            text.setTypeface(null, Typeface.NORMAL);
            Ui.text(v.findViewById(R.id.badge), c.recovered ? "🚫 " + context.getString(R.string.badge_recovered) : null);
            ((TextView) v.findViewById(R.id.when)).setText(time);
            return v;
        }
    }
}
