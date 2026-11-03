package com.keita.graphmemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private GraphStore store;
    private GraphView graphView;
    private TextView status;
    private GraphStore.Node selected = null;

    private static final int MODE_NONE = 0, MODE_CONNECT = 1, MODE_COMBINE = 2;
    private int mode = MODE_NONE;
    private GraphStore.Node connectFrom = null;
    private List<GraphStore.Node> combineList = new ArrayList<>();

    private long lastTapNodeId = -1;
    private long lastTapTime = 0;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Button plainButton(String s) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(Color.BLACK); b.setBackgroundColor(Color.WHITE);
        b.setTextSize(13); b.setPadding(0, 0, 0, 0);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new GraphStore();
        store.load(this);

        // 初回だけ見本の生成元を1つ置く（消せば再起動しても復活しない）
        if (!getSharedPreferences("graph", MODE_PRIVATE).contains("seeded_v1")) {
            if (store.nodes.isEmpty()) {
                store.nodes.add(new GraphStore.Node(System.currentTimeMillis(), "生成元",
                        "ダブルタップでタイトルと本文を編集できます。\n選んで「子」で枝を生やし、「接続」で他の丸へつなぎ、「結合」で2つの丸から新しい丸を作れます。",
                        250, 350, true));
                store.save(this);
            }
            getSharedPreferences("graph", MODE_PRIVATE).edit().putBoolean("seeded_v1", true).apply();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.WHITE);

        Button genBtn = plainButton("＋生成元");
        genBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addGenerator(); }
        });
        Button childBtn = plainButton("＋子");
        childBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addChild(); }
        });
        Button connectBtn = plainButton("接続");
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startConnect(); }
        });
        Button combineBtn = plainButton("結合");
        combineBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startCombine(); }
        });
        Button delBtn = plainButton("削除");
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { deleteSelected(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(genBtn, bp); bar.addView(childBtn, bp); bar.addView(connectBtn, bp);
        bar.addView(combineBtn, bp); bar.addView(delBtn, bp);
        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(Color.BLACK); status.setTextSize(12);
        status.setBackgroundColor(Color.WHITE);
        status.setPadding(dp(8), dp(2), dp(8), dp(2));
        root.addView(status);

        graphView = new GraphView();
        root.addView(graphView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateStatus();
    }

    private void updateStatus() {
        if (mode == MODE_CONNECT) {
            status.setText("接続モード: 接続先の丸をタップ（元: " + connectFrom.title + "）");
        } else if (mode == MODE_COMBINE) {
            status.setText("結合モード: 丸を2つタップ（" + combineList.size() + "/2）");
        } else if (selected != null) {
            status.setText("選択中: " + selected.title + (selected.generator ? "（生成元）" : ""));
        } else {
            status.setText("タップ=選択 / ダブルタップ=本文編集 / ドラッグ=移動 / 背景ドラッグ=スクロール / ピンチ=拡大縮小");
        }
    }

    private void refresh() { graphView.invalidate(); updateStatus(); }

    // ---------- 操作 ----------

    private float[] viewCenterWorld() {
        int w = graphView.getWidth(), h = graphView.getHeight();
        if (w == 0) return new float[]{250, 350};
        return new float[]{(w / 2f - graphView.ox) / graphView.scale, (h / 2f - graphView.oy) / graphView.scale};
    }

    private float nextGeneratorHue() {
        int gen = 0;
        for (GraphStore.Node n : store.nodes) if (n.generator) gen++;
        return (200f + gen * 28f) % 360f;
    }

    private static float mixHue(float h1, float h2) {
        double a1 = Math.toRadians(h1), a2 = Math.toRadians(h2);
        double x = Math.cos(a1) + Math.cos(a2), y = Math.sin(a1) + Math.sin(a2);
        float h = (float) Math.toDegrees(Math.atan2(y, x));
        return h < 0 ? h + 360f : h;
    }

    private void addGenerator() {
        float[] c = viewCenterWorld();
        float off = (store.nodes.size() % 5) * 30;
        GraphStore.Node n = new GraphStore.Node(System.currentTimeMillis(), "", "", c[0] + off, c[1] + off, true,
                1.0f, nextGeneratorHue());
        showEditDialog(n, true, null);
    }

    private void addChild() {
        if (selected == null) { Toast.makeText(this, "先に丸をタップで選んでください", Toast.LENGTH_SHORT).show(); return; }
        final GraphStore.Node parent = selected;
        // 子は親の色から少しだけずらす（枝を伸ばすほど色が少しずつ移る）
        GraphStore.Node n = new GraphStore.Node(System.currentTimeMillis(), "", "",
                parent.x + 190, parent.y + 150, false, parent.size, (parent.hue + 18f) % 360f);
        showEditDialog(n, true, new Runnable() {
            @Override public void run() { store.edges.add(new GraphStore.Edge(parent.id, lastAddedId)); }
        });
    }

    private long lastAddedId = -1;

    private void startConnect() {
        if (selected == null) { Toast.makeText(this, "先に接続元の丸を選んでください", Toast.LENGTH_SHORT).show(); return; }
        mode = MODE_CONNECT; connectFrom = selected;
        updateStatus();
    }

    private void startCombine() {
        mode = MODE_COMBINE; combineList = new ArrayList<>();
        updateStatus();
        Toast.makeText(this, "結合する丸を2つタップしてください", Toast.LENGTH_SHORT).show();
    }

    private void deleteSelected() {
        if (selected == null) { Toast.makeText(this, "先に丸を選んでください", Toast.LENGTH_SHORT).show(); return; }
        store.removeNode(selected.id);
        selected = null; mode = MODE_NONE;
        store.save(this); refresh();
        Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show();
    }

    private void handleTap(GraphStore.Node node) {
        if (node == null) {
            selected = null; refresh(); return;
        }
        if (mode == MODE_CONNECT) {
            if (node.id == connectFrom.id) { Toast.makeText(this, "自分自身には接続できません", Toast.LENGTH_SHORT).show(); return; }
            if (store.hasEdge(connectFrom.id, node.id)) {
                Toast.makeText(this, "すでに接続されています", Toast.LENGTH_SHORT).show();
            } else {
                store.edges.add(new GraphStore.Edge(connectFrom.id, node.id));
                store.save(this);
                Toast.makeText(this, "接続しました", Toast.LENGTH_SHORT).show();
            }
            mode = MODE_NONE; selected = node; refresh(); return;
        }
        if (mode == MODE_COMBINE) {
            if (!combineList.contains(node)) combineList.add(node);
            if (combineList.size() >= 2) {
                final GraphStore.Node a = combineList.get(0), b = combineList.get(1);
                mode = MODE_NONE; combineList = new ArrayList<>();
                String t = a.title + "＋" + b.title;
                String body = "";
                if (!a.body.isEmpty()) body += a.body;
                if (!b.body.isEmpty()) { if (!body.isEmpty()) body += "\n\n"; body += b.body; }
                // 結合した丸の色は2つの色の中間、大きさは2つの平均
                GraphStore.Node n = new GraphStore.Node(System.currentTimeMillis(), t, body,
                        (a.x + b.x) / 2f, (a.y + b.y) / 2f + 170, false,
                        (a.size + b.size) / 2f, mixHue(a.hue, b.hue));
                showEditDialog(n, true, new Runnable() {
                    @Override public void run() {
                        store.edges.add(new GraphStore.Edge(a.id, lastAddedId));
                        store.edges.add(new GraphStore.Edge(b.id, lastAddedId));
                    }
                });
            }
            refresh(); return;
        }
        // 通常: 選択 & ダブルタップ判定
        long now = System.currentTimeMillis();
        boolean doubleTap = (node.id == lastTapNodeId && now - lastTapTime < 350);
        lastTapNodeId = node.id; lastTapTime = now;
        selected = node;
        if (doubleTap) {
            lastTapNodeId = -1;
            showEditDialog(node, false, null);
        }
        refresh();
    }

    // ---------- 編集ダイアログ ----------

    private void showEditDialog(final GraphStore.Node node, final boolean isNew, final Runnable afterAdd) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        final EditText titleEdit = new EditText(this);
        titleEdit.setHint("タイトル（円の上に表示）");
        titleEdit.setSingleLine(true);
        titleEdit.setText(node.title);
        box.addView(titleEdit);

        final EditText bodyEdit = new EditText(this);
        bodyEdit.setHint("本文");
        bodyEdit.setText(node.body);
        bodyEdit.setMinLines(6);
        bodyEdit.setGravity(android.view.Gravity.TOP);
        box.addView(bodyEdit);

        final CheckBox genCheck = new CheckBox(this);
        genCheck.setText("生成元（太い枠の丸）");
        genCheck.setChecked(node.generator);
        box.addView(genCheck);

        final TextView sizeLabel = new TextView(this);
        sizeLabel.setTextColor(Color.BLACK);
        box.addView(sizeLabel);
        final SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMin(50); sizeBar.setMax(250);
        sizeBar.setProgress(Math.round(node.size * 100));
        sizeLabel.setText("大きさ: " + sizeBar.getProgress() + "%");
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                sizeLabel.setText("大きさ: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(sizeBar);

        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String t = titleEdit.getText().toString().trim();
                        if (t.isEmpty()) t = "無題";
                        node.title = t;
                        node.body = bodyEdit.getText().toString();
                        node.generator = genCheck.isChecked();
                        node.size = sizeBar.getProgress() / 100f;
                        if (isNew) {
                            store.nodes.add(node);
                            lastAddedId = node.id;
                            if (afterAdd != null) afterAdd.run();
                            selected = node;
                        }
                        store.save(MainActivity.this);
                        refresh();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---------- キャンバス ----------

    class GraphView extends View {
        float ox = 0, oy = 0, scale;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ScaleGestureDetector scaler;
        private GraphStore.Node dragNode = null;
        private float downX, downY, lastX, lastY;
        private boolean moved = false;

        GraphView() {
            super(MainActivity.this);
            setBackgroundColor(Color.WHITE);
            scale = getResources().getDisplayMetrics().density;
            scaler = new ScaleGestureDetector(MainActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector det) {
                    float min = getResources().getDisplayMetrics().density * 0.3f;
                    float max = getResources().getDisplayMetrics().density * 3.5f;
                    scale = Math.max(min, Math.min(max, scale * det.getScaleFactor()));
                    invalidate();
                    return true;
                }
            });
        }

        private float sx(GraphStore.Node n) { return n.x * scale + ox; }
        private float sy(GraphStore.Node n) { return n.y * scale + oy; }
        private float sr(GraphStore.Node n) { return 56 * scale * n.size; }

        private GraphStore.Node hit(float x, float y) {
            for (int i = store.nodes.size() - 1; i >= 0; i--) {
                GraphStore.Node n = store.nodes.get(i);
                float dx = x - sx(n), dy = y - sy(n);
                float r = sr(n);
                if (dx * dx + dy * dy <= r * r) return n;
            }
            return null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 矢印
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            for (GraphStore.Edge e : store.edges) {
                GraphStore.Node a = store.find(e.fromId), b = store.find(e.toId);
                if (a == null || b == null) continue;
                float x1 = sx(a), y1 = sy(a), x2 = sx(b), y2 = sy(b);
                float dx = x2 - x1, dy = y2 - y1;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1) continue;
                float ux = dx / len, uy = dy / len;
                float sxp = x1 + ux * sr(a), syp = y1 + uy * sr(a);
                float exp = x2 - ux * sr(b), eyp = y2 - uy * sr(b);
                paint.setStrokeWidth(3);
                canvas.drawLine(sxp, syp, exp, eyp, paint);
                // 矢じり
                double ang = Math.atan2(eyp - syp, exp - sxp);
                float ah = sr(b) * 0.28f;
                for (double da : new double[]{Math.PI * 0.85, -Math.PI * 0.85}) {
                    canvas.drawLine(exp, eyp,
                            exp + (float) (ah * Math.cos(ang + da)),
                            eyp + (float) (ah * Math.sin(ang + da)), paint);
                }
            }
            // 丸（半透明の放射グラデーション: 重なった部分は下の色と混ざってグラデーションになる）
            for (GraphStore.Node n : store.nodes) {
                float x = sx(n), y = sy(n), r = sr(n);
                int center = Color.HSVToColor(185, new float[]{n.hue, 0.16f, 1.0f});
                int edge = Color.HSVToColor(165, new float[]{n.hue, 0.52f, 0.96f});
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new RadialGradient(x, y, r, center, edge, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, r, paint);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.BLACK);
                paint.setStrokeWidth(n.generator ? 7 : (n == selected ? 5 : 2));
                canvas.drawCircle(x, y, r, paint);
                if (n == selected) {
                    paint.setStrokeWidth(2);
                    canvas.drawCircle(x, y, r + 8, paint);
                }
                // タイトル（最大3行）
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.BLACK);
                paint.setTextSize(15 * scale);
                paint.setTextAlign(Paint.Align.LEFT);
                List<String> lines = wrap(n.title, r * 1.55f);
                float lh = 17 * scale;
                float ty = y - (lines.size() - 1) * lh / 2f + 5 * scale;
                for (String line : lines) {
                    float tw = paint.measureText(line);
                    canvas.drawText(line, x - tw / 2f, ty, paint);
                    ty += lh;
                }
            }
        }

        private List<String> wrap(String text, float maxWidth) {
            List<String> out = new ArrayList<>();
            String rest = text;
            while (!rest.isEmpty() && out.size() < 3) {
                int cnt = paint.breakText(rest, true, maxWidth, null);
                if (cnt <= 0) cnt = 1;
                if (cnt >= rest.length()) { out.add(rest); rest = ""; }
                else if (out.size() == 2) { out.add(rest.substring(0, cnt) + "…"); rest = ""; }
                else { out.add(rest.substring(0, cnt)); rest = rest.substring(cnt); }
            }
            if (out.isEmpty()) out.add("");
            return out;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            scaler.onTouchEvent(ev);
            if (scaler.isInProgress() || ev.getPointerCount() > 1) { dragNode = null; return true; }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = ev.getX(); downY = lastY = ev.getY();
                    dragNode = hit(downX, downY);
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - lastX, dy = ev.getY() - lastY;
                    if (Math.abs(ev.getX() - downX) + Math.abs(ev.getY() - downY) > 12) moved = true;
                    if (moved) {
                        if (dragNode != null) {
                            dragNode.x += dx / scale; dragNode.y += dy / scale;
                        } else { ox += dx; oy += dy; }
                        invalidate();
                    }
                    lastX = ev.getX(); lastY = ev.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        handleTap(hit(ev.getX(), ev.getY()));
                    } else if (dragNode != null) {
                        store.save(MainActivity.this);
                    }
                    dragNode = null;
                    return true;
            }
            return true;
        }
    }
}
