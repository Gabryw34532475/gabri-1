package com.annunci.volo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Elenco degli annunci salvati (compagnia, fase di volo, file audio). */
public class Library {

    public static class Track {
        public String id;
        public String title;
        public String company;
        public String phase;
        public String file;

        public Track(String id, String title, String company, String phase, String file) {
            this.id = id;
            this.title = title;
            this.company = company;
            this.phase = phase;
            this.file = file;
        }
    }

    public static final String[] PHASES = {
            "Imbarco", "Sicurezza", "Rullaggio", "Decollo", "Crociera",
            "Turbolenza", "Discesa", "Atterraggio", "Sbarco", "Altro"
    };

    private final Context ctx;
    private final SharedPreferences sp;
    private final List<Track> tracks = new ArrayList<>();

    public Library(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE);
        load();
    }

    public File tracksDir() {
        File d = new File(ctx.getFilesDir(), "tracks");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public File fileOf(Track t) {
        return new File(tracksDir(), t.file);
    }

    private void load() {
        tracks.clear();
        try {
            JSONArray a = new JSONArray(sp.getString("tracks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                tracks.add(new Track(
                        o.getString("id"),
                        o.getString("title"),
                        o.getString("company"),
                        o.getString("phase"),
                        o.getString("file")));
            }
        } catch (Exception e) {
            // elenco rovinato: si riparte vuoto
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Track t : tracks) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", t.title);
                o.put("company", t.company);
                o.put("phase", t.phase);
                o.put("file", t.file);
                a.put(o);
            }
        } catch (Exception e) {
            return;
        }
        sp.edit().putString("tracks", a.toString()).apply();
    }

    public synchronized void add(Track t) {
        tracks.add(t);
        save();
    }

    public synchronized void update(Track t) {
        for (Track x : tracks) {
            if (x.id.equals(t.id)) {
                x.title = t.title;
                x.company = t.company;
                x.phase = t.phase;
            }
        }
        save();
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (t.id.equals(id)) {
                fileOf(t).delete();
                tracks.remove(i);
                break;
            }
        }
        save();
    }

    public synchronized Track byId(String id) {
        for (Track t : tracks) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    public synchronized List<String> companies() {
        TreeSet<String> s = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Track t : tracks) {
            s.add(t.company);
        }
        return new ArrayList<>(s);
    }

    private static int phaseIndex(String p) {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i].equals(p)) {
                return i;
            }
        }
        return PHASES.length;
    }

    /** company o phase null = nessun filtro. */
    public synchronized List<Track> filter(String company, String phase) {
        List<Track> r = new ArrayList<>();
        for (Track t : tracks) {
            if (company != null && !t.company.equalsIgnoreCase(company)) {
                continue;
            }
            if (phase != null && !t.phase.equals(phase)) {
                continue;
            }
            r.add(t);
        }
        Collections.sort(r, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                int c = phaseIndex(a.phase) - phaseIndex(b.phase);
                if (c != 0) {
                    return c;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return r;
    }
}
