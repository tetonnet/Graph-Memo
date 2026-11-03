package com.keita.graphmemo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GraphStore {

    public static class Node {
        public long id;
        public String title;
        public String body;
        public float x, y;
        public boolean generator;
        public float size; // 基準半径に対する倍率 (0.5〜2.5)
        public float hue;  // 色相 (0〜360)
        Node(long id, String title, String body, float x, float y, boolean generator) {
            this(id, title, body, x, y, generator, 1.0f, 210f);
        }
        Node(long id, String title, String body, float x, float y, boolean generator, float size, float hue) {
            this.id = id; this.title = title; this.body = body;
            this.x = x; this.y = y; this.generator = generator;
            this.size = size; this.hue = hue;
        }
    }

    public static class Edge {
        public long fromId, toId;
        Edge(long fromId, long toId) { this.fromId = fromId; this.toId = toId; }
    }

    private static final String PREF = "graph";
    private static final String KEY_NODES = "nodes";
    private static final String KEY_EDGES = "edges";

    public List<Node> nodes = new ArrayList<>();
    public List<Edge> edges = new ArrayList<>();

    public Node find(long id) {
        for (Node n : nodes) if (n.id == id) return n;
        return null;
    }

    public void removeNode(long id) {
        List<Node> keptN = new ArrayList<>();
        for (Node n : nodes) if (n.id != id) keptN.add(n);
        nodes = keptN;
        List<Edge> keptE = new ArrayList<>();
        for (Edge e : edges) if (e.fromId != id && e.toId != id) keptE.add(e);
        edges = keptE;
    }

    public boolean hasEdge(long fromId, long toId) {
        for (Edge e : edges) if (e.fromId == fromId && e.toId == toId) return true;
        return false;
    }

    public void load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        nodes = new ArrayList<>(); edges = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_NODES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Node n = new Node(o.getLong("id"), o.optString("title", ""), o.optString("body", ""),
                        (float) o.optDouble("x", 200), (float) o.optDouble("y", 200), o.optBoolean("gen", false));
                n.size = (float) o.optDouble("size", 1.0);
                // v1.0のデータには色がないので、順番で少しずつ違う色を割り当てる
                n.hue = (float) o.optDouble("hue", (nodes.size() * 24) % 360);
                nodes.add(n);
            }
            JSONArray arr2 = new JSONArray(sp.getString(KEY_EDGES, "[]"));
            for (int i = 0; i < arr2.length(); i++) {
                JSONObject o = arr2.getJSONObject(i);
                edges.add(new Edge(o.getLong("from"), o.getLong("to")));
            }
        } catch (Exception ignored) {}
    }

    public void save(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (Node n : nodes) {
                JSONObject o = new JSONObject();
                o.put("id", n.id); o.put("title", n.title); o.put("body", n.body);
                o.put("x", n.x); o.put("y", n.y); o.put("gen", n.generator);
                o.put("size", n.size); o.put("hue", n.hue);
                arr.put(o);
            }
            JSONArray arr2 = new JSONArray();
            for (Edge e : edges) {
                JSONObject o = new JSONObject();
                o.put("from", e.fromId); o.put("to", e.toId);
                arr2.put(o);
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_NODES, arr.toString())
                    .putString(KEY_EDGES, arr2.toString())
                    .apply();
        } catch (Exception ignored) {}
    }
}
