package com.ahmadman.kenarpanel;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ahmadman.kenarpanel.atv.Failure;
import com.ahmadman.kenarpanel.atv.Messages;
import com.ahmadman.kenarpanel.atv.Pairing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-time pairing with an Android TV. The TV is found on the Wi-Fi by itself (when there is
 * one, pairing starts right away); the only question is the 6-character code the TV shows.
 */
public class PairActivity extends Activity {

    /** Tests point this at a fake TV. */
    static int pairPortForTests = Messages.PAIRING_PORT;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String[]> tvs = new LinkedHashMap<>();
    private Prefs prefs;
    private AtvFinder finder;
    private Pairing pairing;
    private String pairingName;
    private String pairingHost;
    private int pairingPort;
    private boolean busy;
    private TextView status;
    private TextView detail;
    private View retry;
    private LinearLayout list;
    private View codeBox;
    private EditText code;

    private final Runnable autoPick = () -> {
        if (!busy && tvs.size() == 1) {
            String[] t = tvs.values().iterator().next();
            pair(t[0], t[1], Integer.parseInt(t[2]));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair);
        MainActivity.applyInsets(findViewById(R.id.root));
        prefs = new Prefs(this);
        status = findViewById(R.id.pair_status);
        detail = findViewById(R.id.pair_detail);
        retry = findViewById(R.id.pair_retry);
        retry.setOnClickListener(v -> pair(pairingName, pairingHost, pairingPort));
        list = findViewById(R.id.tv_list);
        codeBox = findViewById(R.id.code_box);
        code = findViewById(R.id.code);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        findViewById(R.id.back).setOnClickListener(v -> finish());
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() == 6) {
                    submit(); // no extra button press needed
                }
            }
        });
        findViewById(R.id.code_ok).setOnClickListener(v -> submit());
        findViewById(R.id.manual_ok).setOnClickListener(v -> {
            String ip = ((EditText) findViewById(R.id.manual_ip)).getText().toString().trim();
            if (!ip.isEmpty()) {
                pair(ip, ip, Messages.REMOTE_PORT);
            }
        });
        finder = new AtvFinder(this, this::found);
        status.setText(R.string.pair_searching);
        finder.start();
        main.postDelayed(autoPick, 2500);
        main.postDelayed(() -> {
            if (!busy && tvs.isEmpty()) {
                status.setText(R.string.pair_none_found);
            }
        }, 8000);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        finder.stop();
        Pairing p = pairing;
        pairing = null;
        if (p != null) {
            worker.execute(p::close);
        }
        worker.shutdown();
        super.onDestroy();
    }

    void found(String name, String host, int port) {
        if (tvs.containsKey(host)) {
            return;
        }
        tvs.put(host, new String[]{name, host, String.valueOf(port)});
        TextView row = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tv, list, false);
        row.setText(getString(R.string.pair_tv_row, name, host));
        row.setOnClickListener(v -> pair(name, host, port));
        list.addView(row);
        if (!busy) {
            status.setText(R.string.pair_choose);
        }
    }

    /** Step 1: the TV shows a code. */
    void pair(String name, String host, int port) {
        if (busy) {
            return;
        }
        busy = true;
        main.removeCallbacks(autoPick);
        pairingName = name;
        pairingHost = host;
        pairingPort = port;
        detail.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        status.setText(getString(R.string.pair_connecting, name));
        worker.execute(() -> {
            Pairing p = null;
            try {
                p = new Pairing(AtvIdentity.get(this));
                p.start(host, pairPortForTests, Build.MODEL, 8000);
                Pairing started = p;
                main.post(() -> {
                    if (isFinishing()) {
                        started.close();
                        return;
                    }
                    pairing = started;
                    status.setText(getString(R.string.pair_enter_code, name));
                    codeBox.setVisibility(View.VISIBLE);
                    list.setVisibility(View.GONE);
                    findViewById(R.id.manual_box).setVisibility(View.GONE);
                    code.requestFocus();
                    InputMethodManager imm = getSystemService(InputMethodManager.class);
                    if (imm != null) {
                        imm.showSoftInput(code, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            } catch (Exception e) {
                if (p != null) {
                    p.close();
                }
                main.post(() -> failed(name, e));
            }
        });
    }

    /** Step 2: the code from the TV screen. */
    void submit() {
        Pairing p = pairing;
        String c = Messages.normalizeCode(code.getText().toString());
        if (p == null || c == null) {
            return;
        }
        pairing = null;
        status.setText(R.string.pair_checking);
        worker.execute(() -> {
            try {
                String pin = p.finish(c);
                main.post(() -> {
                    prefs.atv(pairingName, pairingHost, pairingPort, pin);
                    prefs.tvMode("atv");
                    AtvRemote.get(this).pairedChanged();
                    OverlayService.settingsChanged();
                    status.setText(getString(R.string.pair_done, pairingName));
                    main.postDelayed(this::finish, 1200);
                });
            } catch (Pairing.WrongCodeException e) {
                main.post(() -> {
                    busy = false;
                    code.setText("");
                    status.setText(R.string.pair_wrong_code);
                    codeBox.setVisibility(View.GONE);
                    list.setVisibility(View.VISIBLE);
                    // start over: the TV shows a new code
                    pair(pairingName, pairingHost, pairingPort);
                });
            } catch (Exception e) {
                main.post(() -> {
                    codeBox.setVisibility(View.GONE);
                    list.setVisibility(View.VISIBLE);
                    failed(pairingName, e);
                });
            }
        });
    }

    /** Says what went wrong and what to do about it, with the technical cause underneath. */
    void failed(String name, Exception e) {
        busy = false;
        status.setText(getString(reason(Failure.of(e)), name));
        detail.setText(getString(R.string.pair_detail, Failure.detail(e)));
        detail.setVisibility(View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
    }

    static int reason(Failure.Kind kind) {
        switch (kind) {
            case UNREACHABLE:
                return R.string.pair_fail_unreachable;
            case CLOSED_PORT:
                return R.string.pair_fail_closed;
            case OLD_SERVICE:
                return R.string.pair_fail_old_service;
            case TLS:
                return R.string.pair_fail_tls;
            case TV_SAID_NO:
                return R.string.pair_fail_refused;
            default:
                return R.string.pair_fail_other;
        }
    }
}
