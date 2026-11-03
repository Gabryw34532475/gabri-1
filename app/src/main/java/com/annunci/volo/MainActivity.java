package com.annunci.volo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int BG = 0xFF0B1B2B;
    private static final int CARD = 0xFF13293D;
    private static final int ACCENT = 0xFFFFB400;
    private static final int TEXT = 0xFFEAF2F8;
    private static final int MUTED = 0xFF8FA6B8;
    private static final String ALL_C = "Tutte le compagnie";
    private static final String ALL_P = "Tutte le fasi";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private interface OnSave {
        void save(String title, String company, String phase);
    }

    private Library lib;
    private final List<Library.Track> shown = new ArrayList<>();
    private String selCompany = ALL_C;
    private String selPhase = ALL_P;
    private Spinner spCompany;
    private Spinner spPhase;
    private TrackAdapter adapter;
    private TextView nowTitle;
    private TextView nowSub;
    private ImageButton btnPlay;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateNowPlaying();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        lib = new Library(this);
        setContentView(buildUi());
        rebuildCompanySpinner();
        applyFilter();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(receiver, new IntentFilter(PlayerService.ACTION_STATE));
        updateNowPlaying();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(receiver);
        super.onStop();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // Intestazione
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        TextView title = new TextView(this);
        title.setText("Annunci di Volo");
        title.setTextColor(ACCENT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button add = new Button(this);
        add.setText("+ Aggiungi");
        add.setOnClickListener(v -> pickFiles());
        header.addView(add);
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Filtri
        adapter = new TrackAdapter();
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(dp(8), 0, dp(8), dp(4));
        spCompany = new Spinner(this);
        spPhase = new Spinner(this);
        filters.addView(spCompany, new LinearLayout.LayoutParams(0, WRAP, 1f));
        filters.addView(spPhase, new LinearLayout.LayoutParams(0, WRAP, 1f));
        root.addView(filters, new LinearLayout.LayoutParams(MATCH, WRAP));

        List<String> phases = new ArrayList<>();
        phases.add(ALL_P);
        for (String p : Library.PHASES) {
            phases.add(p);
        }
        ArrayAdapter<String> pa = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, phases);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPhase.setAdapter(pa);

        spCompany.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selCompany = (String) p.getItemAtPosition(pos);
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        spPhase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selPhase = (String) p.getItemAtPosition(pos);
                applyFilter();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // Lista
        FrameLayout listFrame = new FrameLayout(this);
        ListView lv = new ListView(this);
        lv.setDivider(new ColorDrawable(CARD));
        lv.setDividerHeight(dp(1));
        lv.setAdapter(adapter);
        TextView empty = new TextView(this);
        empty.setText("Nessun annuncio.\nTocca \"+ Aggiungi\" per importare i tuoi file mp3.");
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(32), dp(32), dp(32));
        listFrame.addView(lv, new FrameLayout.LayoutParams(MATCH, MATCH));
        listFrame.addView(empty, new FrameLayout.LayoutParams(MATCH, MATCH));
        lv.setEmptyView(empty);
        lv.setOnItemClickListener((p, v, pos, id) -> playFrom(pos));
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            showItemMenu(shown.get(pos));
            return true;
        });
        root.addView(listFrame, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Barra del lettore
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(16), dp(8), dp(8), dp(8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        nowTitle = new TextView(this);
        nowTitle.setTextColor(TEXT);
        nowTitle.setTextSize(15);
        nowTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowSub = new TextView(this);
        nowSub.setTextColor(MUTED);
        nowSub.setTextSize(12);
        nowSub.setSingleLine(true);
        nowSub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(nowTitle);
        texts.addView(nowSub);
        bar.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageButton prev = iconButton(android.R.drawable.ic_media_previous,
                v -> sendAction(PlayerService.ACTION_PREV));
        btnPlay = iconButton(android.R.drawable.ic_media_play, v -> onTogglePressed());
        ImageButton next = iconButton(android.R.drawable.ic_media_next,
                v -> sendAction(PlayerService.ACTION_NEXT));
        bar.addView(prev, new LinearLayout.LayoutParams(dp(52), dp(52)));
        bar.addView(btnPlay, new LinearLayout.LayoutParams(dp(52), dp(52)));
        bar.addView(next, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));

        return root;
    }

    private ImageButton iconButton(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setColorFilter(ACCENT);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setOnClickListener(l);
        return b;
    }

    private class TrackAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                TextView t1 = new TextView(MainActivity.this);
                t1.setTextSize(16);
                TextView t2 = new TextView(MainActivity.this);
                t2.setTextSize(12);
                t2.setTextColor(MUTED);
                row.addView(t1);
                row.addView(t2);
                row.setTag(new TextView[]{t1, t2});
            } else {
                row = (LinearLayout) cv;
            }
            TextView[] tv = (TextView[]) row.getTag();
            Library.Track t = shown.get(pos);
            boolean cur = t.id.equals(PlayerService.currentId);
            tv[0].setText(t.title);
            tv[0].setTextColor(cur ? ACCENT : TEXT);
            tv[1].setText(t.company + " · " + t.phase);
            row.setBackgroundColor(cur ? CARD : Color.TRANSPARENT);
            return row;
        }
    }

    // ------------------------------------------------------- dati e filtri

    private void rebuildCompanySpinner() {
        List<String> cs = new ArrayList<>();
        cs.add(ALL_C);
        cs.addAll(lib.companies());
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, cs);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCompany.setAdapter(a);
        int idx = cs.indexOf(selCompany);
        if (idx < 0) {
            idx = 0;
            selCompany = ALL_C;
        }
        spCompany.setSelection(idx);
    }

    private void applyFilter() {
        shown.clear();
        shown.addAll(lib.filter(
                selCompany.equals(ALL_C) ? null : selCompany,
                selPhase.equals(ALL_P) ? null : selPhase));
        adapter.notifyDataSetChanged();
    }

    private void updateNowPlaying() {
        if (PlayerService.currentId == null) {
            nowTitle.setText("Nessun annuncio in riproduzione");
            nowSub.setText("Tocca un annuncio nella lista");
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        } else {
            nowTitle.setText(PlayerService.currentTitle);
            nowSub.setText(PlayerService.currentSub);
            btnPlay.setImageResource(PlayerService.playing
                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ----------------------------------------------------------- riproduzione

    private void playFrom(int pos) {
        if (pos < 0 || pos >= shown.size()) {
            return;
        }
        String[] ids = new String[shown.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = shown.get(i).id;
        }
        Intent i = new Intent(this, PlayerService.class)
                .setAction(PlayerService.ACTION_PLAY_LIST)
                .putExtra("ids", ids)
                .putExtra("index", pos);
        startForegroundService(i);
    }

    private void sendAction(String action) {
        if (!PlayerService.running) {
            return;
        }
        startForegroundService(new Intent(this, PlayerService.class).setAction(action));
    }

    private void onTogglePressed() {
        if (PlayerService.running) {
            sendAction(PlayerService.ACTION_TOGGLE);
        } else if (!shown.isEmpty()) {
            playFrom(0);
        }
    }

    // ---------------------------------------------------- importa / modifica

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"audio/*", "application/ogg", "application/octet-stream"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) {
                uris.add(cd.getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        showTrackDialog("Aggiungi " + uris.size() + " file", false, "",
                selCompany.equals(ALL_C) ? "" : selCompany, null,
                (t, c, p) -> importFiles(uris, c, p));
    }

    private void importFiles(final List<Uri> uris, final String company, final String phase) {
        Toast.makeText(this, "Importo i file...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            for (Uri u : uris) {
                try {
                    String name = displayName(u);
                    String ext = ".mp3";
                    String base = name;
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        ext = name.substring(dot);
                        base = name.substring(0, dot);
                    }
                    String id = UUID.randomUUID().toString();
                    File f = new File(lib.tracksDir(), id + ext);
                    try (InputStream in = getContentResolver().openInputStream(u);
                         OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    lib.add(new Library.Track(id, base, company, phase, f.getName()));
                    ok++;
                } catch (Exception e) {
                    // file saltato
                }
            }
            final int done = ok;
            runOnUiThread(() -> {
                rebuildCompanySpinner();
                applyFilter();
                Toast.makeText(MainActivity.this,
                        done + " annunci aggiunti", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private String displayName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) {
                    n = c.getString(ix);
                }
            }
        } catch (Exception e) {
            // nome non disponibile
        }
        return n == null ? "annuncio.mp3" : n;
    }

    private void showItemMenu(final Library.Track t) {
        new AlertDialog.Builder(this)
                .setTitle(t.title)
                .setItems(new String[]{"Modifica", "Elimina"}, (d, which) -> {
                    if (which == 0) {
                        showTrackDialog("Modifica annuncio", true, t.title, t.company, t.phase,
                                (ti, c, p) -> {
                                    if (!ti.isEmpty()) {
                                        t.title = ti;
                                    }
                                    t.company = c;
                                    t.phase = p;
                                    lib.update(t);
                                    rebuildCompanySpinner();
                                    applyFilter();
                                });
                    } else {
                        if (t.id.equals(PlayerService.currentId)) {
                            sendAction(PlayerService.ACTION_STOP);
                        }
                        lib.remove(t.id);
                        rebuildCompanySpinner();
                        applyFilter();
                    }
                })
                .show();
    }

    private void showTrackDialog(String heading, boolean askTitle, String iTitle,
                                 String iCompany, String iPhase, final OnSave cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText etTitle = new EditText(this);
        etTitle.setHint("Nome dell'annuncio");
        etTitle.setText(iTitle);
        if (askTitle) {
            box.addView(etTitle);
        }

        final AutoCompleteTextView etCompany = new AutoCompleteTextView(this);
        etCompany.setHint("Compagnia (es. Ryanair)");
        etCompany.setText(iCompany, false);
        etCompany.setThreshold(1);
        etCompany.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, lib.companies()));
        box.addView(etCompany);

        final Spinner spP = new Spinner(this);
        spP.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, Library.PHASES));
        if (iPhase != null) {
            for (int i = 0; i < Library.PHASES.length; i++) {
                if (Library.PHASES[i].equals(iPhase)) {
                    spP.setSelection(i);
                }
            }
        }
        box.addView(spP);

        new AlertDialog.Builder(this)
                .setTitle(heading)
                .setView(box)
                .setPositiveButton("Salva", (d, w) -> {
                    String comp = etCompany.getText().toString().trim();
                    if (comp.isEmpty()) {
                        comp = "Generale";
                    }
                    String ttl = etTitle.getText().toString().trim();
                    cb.save(ttl, comp, (String) spP.getSelectedItem());
                })
                .setNegativeButton("Annulla", null)
                .show();
    }
}
