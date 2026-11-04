package com.ahmadman.notifsaver;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.notifsaver.core.Capture;

import java.util.List;
import java.util.TimeZone;

/** One app's chats, one chat, everything, search results or deleted messages. Updates live. */
public class BrowseActivity extends BaseActivity implements Store.Listener {

    static final String EXTRA_MODE = "mode";
    static final String EXTRA_PKG = "pkg";
    static final String EXTRA_CONV = "conv";
    static final int APP = 1;
    static final int THREAD = 2;
    static final int ALL = 3;
    static final int SEARCH = 4;
    static final int DELETED = 5;

    private static final int PAGE = 200;

    static Intent app(Context c, String pkg) {
        return new Intent(c, BrowseActivity.class).putExtra(EXTRA_MODE, APP).putExtra(EXTRA_PKG, pkg);
    }

    static Intent thread(Context c, String pkg, String conv) {
        return new Intent(c, BrowseActivity.class).putExtra(EXTRA_MODE, THREAD).putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_CONV, conv == null ? "" : conv);
    }

    static Intent of(Context c, int mode) {
        return new Intent(c, BrowseActivity.class).putExtra(EXTRA_MODE, mode);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int mode;
    private String pkg;
    private String conv;
    /** APP mode: 0 = chats, 1 = all entries. */
    private int tab;
    private int limit = PAGE;
    private int generation;
    private boolean loadingMore;
    private boolean olderRequested;
    private String query = "";

    private ListView list;
    private TextView empty;
    private Rows.GroupAdapter groups;
    private Rows.EntryAdapter entries;
    private Rows.ThreadAdapter thread;
    private final Runnable searchLater = this::reload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);
        mode = getIntent().getIntExtra(EXTRA_MODE, ALL);
        pkg = getIntent().getStringExtra(EXTRA_PKG);
        conv = getIntent().getStringExtra(EXTRA_CONV);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        groups = new Rows.GroupAdapter(this, false);
        entries = new Rows.EntryAdapter(this);
        thread = new Rows.ThreadAdapter(this);

        Store store = Store.get(this);
        switch (mode) {
            case APP:
                Ui.topBar(this, store.label(pkg), null, true);
                Ui.action(this, R.id.action1, R.drawable.ic_more, R.string.more, this::appMenu);
                setupTabs();
                break;
            case THREAD:
                Ui.topBar(this, conv.isEmpty() ? store.label(pkg) : conv, store.label(pkg), true);
                Ui.action(this, R.id.action1, R.drawable.ic_more, R.string.more, this::threadMenu);
                list.setStackFromBottom(true);
                list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
                break;
            case SEARCH:
                Ui.topBar(this, getString(R.string.search_title), null, true);
                setupSearch();
                break;
            case DELETED:
                Ui.topBar(this, getString(R.string.deleted_messages), getString(R.string.deleted_subtitle), true);
                break;
            default:
                Ui.topBar(this, getString(R.string.all_notifications), null, true);
                break;
        }
        list.setOnItemClickListener((p, v, position, id) -> onClick(p.getItemAtPosition(position)));
        list.setOnItemLongClickListener((p, v, position, id) -> onLongClick(p.getItemAtPosition(position)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total == 0 || loadingMore) {
                    return;
                }
                if (mode == THREAD) {
                    if (first == 0 && thread.rows.size() >= limit) {
                        loadingMore = true;
                        olderRequested = true;
                        limit += PAGE;
                        reload();
                    }
                } else if (first + visible >= total - 5 && entries.rows.size() >= limit && list.getAdapter() == entries) {
                    loadingMore = true;
                    limit += PAGE;
                    reload();
                }
            }
        });
        attachAdapter();
    }

    private void setupTabs() {
        LinearLayout box = findViewById(R.id.tabs);
        box.setVisibility(View.VISIBLE);
        int[] labels = {R.string.tab_chats, R.string.tab_all};
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_segment, box, false);
            t.setText(labels[i]);
            final int index = i;
            t.setOnClickListener(v -> {
                tab = index;
                for (int k = 0; k < views.length; k++) {
                    views[k].setSelected(k == index);
                }
                limit = PAGE;
                attachAdapter();
                reload();
            });
            t.setSelected(i == tab);
            views[i] = t;
            box.addView(t);
        }
    }

    private void setupSearch() {
        EditText q = findViewById(R.id.query);
        q.setVisibility(View.VISIBLE);
        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                limit = PAGE;
                handler.removeCallbacks(searchLater);
                handler.postDelayed(searchLater, 250);
            }
        });
        q.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
            return true;
        });
        q.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void attachAdapter() {
        if (mode == THREAD) {
            list.setAdapter(thread);
        } else if (mode == APP && tab == 0) {
            list.setAdapter(groups);
        } else {
            list.setAdapter(entries);
        }
    }

    @Override
    protected void onUnlockedResume() {
        Store.get(this).addListener(this);
        reload();
    }

    @Override
    protected void onPause() {
        Store.get(this).removeListener(this);
        super.onPause();
    }

    @Override
    public void onSaved() {
        reload();
    }

    // -------------------------------------------------------------------------------------

    private void reload() {
        final int gen = ++generation;
        final int m = mode;
        final int t = tab;
        final int lim = limit;
        final String q = query;
        final Db db = Store.get(this).db;
        Store.get(this).reader.execute(() -> {
            Object result;
            if (m == APP && t == 0) {
                result = db.conversations(pkg);
            } else if (m == APP) {
                result = db.timeline(pkg, lim, 0);
            } else if (m == THREAD) {
                result = db.thread(pkg, conv, lim);
            } else if (m == SEARCH) {
                result = q.isEmpty() ? null : db.search(q, lim, 0);
            } else if (m == DELETED) {
                result = db.deletedMessages(lim);
            } else {
                result = db.timeline(null, lim, 0);
            }
            final Object r = result;
            runOnUiThread(() -> {
                if (gen == generation && !isFinishing() && !isDestroyed()) {
                    show(r);
                }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private void show(Object result) {
        loadingMore = false;
        if (mode == APP && tab == 0) {
            List<Db.Group> g = (List<Db.Group>) result;
            groups.set(g);
            ((TextView) findViewById(R.id.subtitle)).setVisibility(View.GONE);
            empty(g.isEmpty(), R.string.empty_list);
            return;
        }
        if (mode == THREAD) {
            int oldCount = thread.getCount();
            int first = list.getFirstVisiblePosition();
            View top = list.getChildAt(0);
            int offset = top == null ? 0 : top.getTop();
            List<Capture> rows = (List<Capture>) result;
            thread.set(rows);
            if (olderRequested) {
                olderRequested = false;
                list.setSelectionFromTop(first + thread.getCount() - oldCount, offset);
            }
            empty(rows.isEmpty(), R.string.empty_list);
            return;
        }
        if (mode == SEARCH && result == null) {
            entries.set(new java.util.ArrayList<>());
            empty(true, R.string.search_start);
            return;
        }
        List<Capture> rows = (List<Capture>) result;
        entries.set(rows);
        empty(rows.isEmpty(), mode == SEARCH ? R.string.search_none : mode == DELETED ? R.string.empty_deleted
                : R.string.empty_list);
    }

    private void empty(boolean isEmpty, int text) {
        empty.setText(text);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void onClick(Object item) {
        if (item instanceof Db.Group) {
            Db.Group g = (Db.Group) item;
            startActivity(thread(this, g.pkg, g.conversation));
        } else if (item instanceof Capture) {
            Capture c = (Capture) item;
            Dialogs.entry(this, c, mode != THREAD);
        }
    }

    private boolean onLongClick(Object item) {
        if (item instanceof Db.Group) {
            Db.Group g = (Db.Group) item;
            String name = g.conversation != null && !g.conversation.isEmpty() ? g.conversation : g.app;
            Dialogs.confirm(this, getString(R.string.confirm_delete_chat, name), R.string.delete,
                    () -> Store.get(this).write(() -> Store.get(this).db.deleteConversation(g.pkg, g.conversation)));
            return true;
        }
        if (item instanceof Capture) {
            Dialogs.entry(this, (Capture) item, mode != THREAD);
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------------------

    private void appMenu(View anchor) {
        Prefs prefs = Prefs.get(this);
        boolean on = prefs.isOn(pkg);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, on ? R.string.stop_recording_app : R.string.start_recording_app);
        menu.getMenu().add(0, 2, 1, R.string.delete_app_records);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                prefs.setOn(pkg, !on);
                Toast.makeText(this, on ? R.string.app_stopped : R.string.app_started, Toast.LENGTH_SHORT).show();
            } else {
                Dialogs.confirm(this, getString(R.string.confirm_delete_app, Store.get(this).label(pkg)), R.string.delete,
                        () -> {
                            Store.get(this).write(() -> Store.get(this).db.deleteApp(pkg));
                            finish();
                        });
            }
            return true;
        });
        menu.show();
    }

    private void threadMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.share_chat);
        menu.getMenu().add(0, 2, 1, R.string.delete_chat);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                Dialogs.share(this, Rows.transcript(thread.rows, TimeZone.getDefault()));
            } else {
                Dialogs.confirm(this, getString(R.string.confirm_delete_chat, conv.isEmpty() ? Store.get(this).label(pkg)
                        : conv), R.string.delete, () -> {
                            Store.get(this).write(() -> Store.get(this).db.deleteConversation(pkg, conv));
                            finish();
                        });
            }
            return true;
        });
        menu.show();
    }
}
