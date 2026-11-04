package com.tvhome.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LauncherActivity extends Activity {

    static class AppEntry {
        String pkg, label;
        Drawable banner;
        boolean isIcon;
        Intent intent;
        int color = 0xFF888888;
    }

    private int W, H, cols, margin, tileW, imgW, imgH, rowH, padTop;
    private float dp;
    private Typeface outfit;
    private SharedPreferences prefs;

    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> libraryApps = new ArrayList<>();
    private FrameLayout root;
    private RecyclerView grid;
    private TileAdapter adapter;
    private View glass, glow;
    private TextView bigName, greeting, wifiPill;
    private final TextView[] pills = new TextView[3];
    private PopupWindow popup;
    private PopupWindow managePopup;
    private int lastPos = 0, pendingFocus = -1;
    private GradientDrawable glowDrawable;

    // ---------------------------------------------------------------- setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        dp = dm.density;
        cols = Math.max(4, Math.min(7, getResources().getInteger(R.integer.columns)));
        outfit = getResources().getFont(R.font.outfit);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        lastPos = prefs.getInt("last", 0);

        margin = (int) (W * 0.06f);
        tileW = (W - 2 * margin) / cols;
        int pad = px(8);
        imgW = tileW - 2 * pad;
        imgH = imgW * 9 / 16;
        rowH = pad + imgH + px(34);
        padTop = H - rowH - px(44);

        loadApps();
        buildUi();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void style(TextView t, float sp, int wght, int color) {
        t.setTypeface(outfit);
        t.setFontVariationSettings("'wght' " + wght);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, AppEntry> map = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || map.containsKey(p)) continue;
            AppEntry e = new AppEntry();
            e.pkg = p;
            e.label = ri.loadLabel(pm).toString();
            Drawable bn = ri.activityInfo.loadBanner(pm);
            e.isIcon = bn == null;
            e.banner = bn != null ? bn : ri.loadIcon(pm);
            e.intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(p, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.color = sampleColor(e.banner);
            map.put(p, e);
        }
        dockApps.clear();
        String savedDock = prefs.getString("pinned_dock_packages", null);
        String[] dockPkgList;
        if (savedDock != null) {
            dockPkgList = savedDock.split(",");
        } else {
            dockPkgList = getResources().getStringArray(R.array.dock_apps);
        }

        List<String> validPinnedPkgs = new ArrayList<>();
        for (String p : dockPkgList) {
            p = p.trim();
            if (p.isEmpty()) continue;
            AppEntry e = map.remove(p);
            if (e != null) {
                dockApps.add(e);
                validPinnedPkgs.add(p);
            }
        }

        // If there were stale entries removed from a saved list, persist clean state
        if (savedDock != null && validPinnedPkgs.size() != dockPkgList.length) {
            persistPinnedPackages(validPinnedPkgs);
        }

        libraryApps.clear();
        libraryApps.addAll(map.values());
        Collections.sort(libraryApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry c) {
                return a.label.compareToIgnoreCase(c.label);
            }
        });

        apps.clear();
        apps.addAll(dockApps);
        apps.addAll(libraryApps);
    }

    private void persistPinnedPackages(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(pkgs.get(i));
        }
        prefs.edit().putString("pinned_dock_packages", sb.toString()).apply();
    }

    private void savePinnedApps() {
        List<String> pkgs = new ArrayList<>();
        for (AppEntry e : dockApps) {
            pkgs.add(e.pkg);
        }
        persistPinnedPackages(pkgs);
    }

    private void updateAndPreserveFocus(String targetPkg) {
        apps.clear();
        apps.addAll(dockApps);
        apps.addAll(libraryApps);
        adapter.notifyDataSetChanged();
        savePinnedApps();

        int targetIndex = 0;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).pkg.equals(targetPkg)) {
                targetIndex = i;
                break;
            }
        }
        final int finalTarget = targetIndex;
        grid.post(new Runnable() {
            @Override
            public void run() {
                focusPos(finalTarget);
            }
        });
    }

    private void swapDockApps(int i1, int i2) {
        if (i1 < 0 || i1 >= dockApps.size() || i2 < 0 || i2 >= dockApps.size()) return;
        AppEntry e1 = dockApps.get(i1);
        AppEntry e2 = dockApps.get(i2);
        dockApps.set(i1, e2);
        dockApps.set(i2, e1);
        updateAndPreserveFocus(e1.pkg);
    }

    private void unpinApp(AppEntry entry) {
        if (!dockApps.remove(entry)) return;
        libraryApps.add(entry);
        Collections.sort(libraryApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry c) {
                return a.label.compareToIgnoreCase(c.label);
            }
        });
        updateAndPreserveFocus(entry.pkg);
    }

    private void pinApp(AppEntry entry) {
        if (!libraryApps.remove(entry)) return;
        dockApps.add(entry);
        updateAndPreserveFocus(entry.pkg);
    }

    /** Average colour of a banner, brightened so the glow is visible on black. */
    private int sampleColor(Drawable src) {
        try {
            Drawable d = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
            Bitmap bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 8, 8);
            d.draw(new Canvas(bm));
            long r = 0, g = 0, bl = 0;
            int n = 0;
            for (int x = 0; x < 8; x++)
                for (int y = 0; y < 8; y++) {
                    int c = bm.getPixel(x, y);
                    if (Color.alpha(c) < 40) continue;
                    r += Color.red(c);
                    g += Color.green(c);
                    bl += Color.blue(c);
                    n++;
                }
            bm.recycle();
            if (n == 0) return 0xFF888888;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[2] = Math.max(hsv[2], 0.75f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF888888;
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{getColor(R.color.bg_dark_gradient_top), getColor(R.color.bg_dark_gradient_bottom)}
        );
        root.setBackground(bg);
        setContentView(root);

        // ambient glow (below everything else)
        glow = new View(this);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(px(380));
        glowDrawable.setColors(new int[]{0x55888888, 0x22888888, 0x00000000});
        glow.setBackground(glowDrawable);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(px(760), px(440));
        glow.setLayoutParams(glp);
        glow.setAlpha(0f);
        root.addView(glow);

        // grid (first row = dock)
        grid = new RecyclerView(this);
        GridLayoutManager lm = new GridLayoutManager(this, cols);
        grid.setLayoutManager(lm);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(margin, padTop, margin, px(80));
        grid.setItemViewCacheSize(cols * 2);
        grid.addItemDecoration(new DockBand());
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                View f = rv.getFocusedChild();
                if (f != null) placeGlass(f, false);
                updateAmbient();
                tryPendingFocus();
            }
        });
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        // glass highlight
        glass = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(px(18));
        gd.setColor(getColor(R.color.glass_frame_fill));
        gd.setStroke(px(2), getColor(R.color.glass_frame_stroke_inner));
        glass.setBackground(gd);
        int gw = (int) (imgW * 1.10f) + px(12);
        int gh = (int) (imgH * 1.10f) + px(12);
        glass.setLayoutParams(new FrameLayout.LayoutParams(gw, gh));
        glass.setAlpha(0f);
        root.addView(glass);

        // focused dock app name
        bigName = new TextView(this);
        style(bigName, 38, 300, Color.WHITE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2);
        nlp.leftMargin = margin;
        nlp.topMargin = padTop - px(84);
        bigName.setLayoutParams(nlp);
        bigName.setAlpha(0f);
        root.addView(bigName);

        // top-left: greeting, clock, date
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.leftMargin = margin;
        tlp.topMargin = px(28);
        tl.setLayoutParams(tlp);
        greeting = new TextView(this);
        style(greeting, 17, 350, 0xB3FFFFFF);
        greeting.setLetterSpacing(0.04f);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 74, 250, Color.WHITE);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 17, 300, 0xB3FFFFFF);
        tl.addView(greeting);
        tl.addView(clock);
        tl.addView(date);
        root.addView(tl);

        // top-right pills
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.rightMargin = margin;
        blp.topMargin = px(32);
        bar.setLayoutParams(blp);
        String[] names = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView p = new TextView(this);
            style(p, 16, 400, Color.WHITE);
            p.setText(names[i]);
            p.setPadding(px(20), px(10), px(20), px(10));
            p.setGravity(Gravity.CENTER);
            p.setFocusable(true);
            p.setClickable(true);
            setPillBg(p, false);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            plp.leftMargin = px(12);
            p.setLayoutParams(plp);
            p.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    setPillBg((TextView) v, has);
                    if (has) hideAmbient();
                }
            });
            p.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int code, KeyEvent ev) {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    switch (code) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            if (idx > 0) pills[idx - 1].requestFocus();
                            return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            if (idx < 2) pills[idx + 1].requestFocus();
                            return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            focusPos(Math.min(lastPos, cols - 1));
                            return true;
                    }
                    return false;
                }
            });
            p.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPill(idx, v);
                }
            });
            pills[i] = p;
            bar.addView(p);
        }
        wifiPill = pills[1];
        root.addView(bar);
    }

    private void setPillBg(TextView p, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(22));
        g.setColor(getColor(focused ? R.color.pill_focus_bg : R.color.pill_normal_bg));
        g.setStroke(focused ? px(2) : px(1), getColor(focused ? R.color.pill_stroke_focus : R.color.pill_stroke_normal));
        p.setBackground(g);
        p.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(160).start();
    }

    // --------------------------------------------------------------- pills

    private void onPill(int idx, View v) {
        try {
            if (idx == 0) showInputs(v);
            else if (idx == 1) startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private void showInputs(View anchor) {
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(8), px(8), px(8), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(20));
        bg.setColor(0xEE222224);
        bg.setStroke(px(1), 0x44FFFFFF);
        box.setBackground(bg);

        List<TvInputInfo> list = tim != null ? tim.getTvInputList() : new ArrayList<TvInputInfo>();
        final List<TextView> items = new ArrayList<>();
        for (final TvInputInfo info : list) {
            boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER;
            if (!info.isPassthroughInput() && !tuner) continue;
            CharSequence label = info.loadCustomLabel(this);
            if (label == null || label.length() == 0) label = info.loadLabel(this);
            if (tuner) label = "Live TV";
            final TextView t = new TextView(this);
            style(t, 17, 400, Color.WHITE);
            t.setText(label);
            t.setPadding(px(22), px(10), px(40), px(10));
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackgroundColor(Color.TRANSPARENT);
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(px(14));
                    g.setColor(has ? 0x44FFFFFF : 0x00000000);
                    v.setBackground(g);
                }
            });
            final boolean isTuner = tuner;
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (popup != null) popup.dismiss();
                    switchInput(info, isTuner);
                }
            });
            items.add(t);
            box.addView(t);
        }
        if (items.isEmpty()) {
            TextView t = new TextView(this);
            style(t, 16, 300, 0xBBFFFFFF);
            t.setText("No inputs found");
            t.setPadding(px(22), px(10), px(22), px(10));
            box.addView(t);
        }
        popup = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(null);
        popup.showAsDropDown(anchor, 0, px(8));
        if (!items.isEmpty()) items.get(0).requestFocus();
    }

    private void switchInput(TvInputInfo info, boolean tuner) {
        try {
            if (tuner) {
                Intent li = getPackageManager().getLeanbackLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
                if (li != null) {
                    startActivity(li);
                    return;
                }
            }
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
        updateWifi();
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (!grid.hasFocus() && !pillHasFocus()) focusPos(Math.min(lastPos, Math.max(0, apps.size() - 1)));
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (popup != null) popup.dismiss();
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
            lastPos = Math.min(lastPos, cols - 1);
            focusPos(lastPos);
        }
    }

    @Override
    public void onBackPressed() {
        if (managePopup != null && managePopup.isShowing()) {
            managePopup.dismiss();
            managePopup = null;
            return;
        }
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            popup = null;
            return;
        }
        // A launcher never exits on Back
    }

    private void showAppManagementDialog(final AppEntry entry, final int pos, final boolean isDock, View anchor) {
        if (managePopup != null && managePopup.isShowing()) {
            managePopup.dismiss();
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(10), px(10), px(10), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(20));
        bg.setColor(0xF01A1D24);
        bg.setStroke(px(1), getColor(R.color.dock_band_stroke));
        box.setBackground(bg);

        TextView title = new TextView(this);
        style(title, 15, 600, Color.WHITE);
        title.setText(entry.label);
        title.setPadding(px(20), px(8), px(20), px(10));
        box.addView(title);

        List<TextView> actionButtons = new ArrayList<>();

        if (isDock) {
            if (pos > 0) {
                TextView btnLeft = createActionButton("Move Left", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        managePopup.dismiss();
                        swapDockApps(pos, pos - 1);
                    }
                });
                actionButtons.add(btnLeft);
                box.addView(btnLeft);
            }
            if (pos < dockApps.size() - 1) {
                TextView btnRight = createActionButton("Move Right", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        managePopup.dismiss();
                        swapDockApps(pos, pos + 1);
                    }
                });
                actionButtons.add(btnRight);
                box.addView(btnRight);
            }
            TextView btnUnpin = createActionButton("Unpin from Dock", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    managePopup.dismiss();
                    unpinApp(entry);
                }
            });
            actionButtons.add(btnUnpin);
            box.addView(btnUnpin);
        } else {
            TextView btnPin = createActionButton("Pin to Dock", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    managePopup.dismiss();
                    pinApp(entry);
                }
            });
            actionButtons.add(btnPin);
            box.addView(btnPin);
        }

        managePopup = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        managePopup.setBackgroundDrawable(null);
        managePopup.showAsDropDown(anchor, 0, -px(30));
        if (!actionButtons.isEmpty()) {
            actionButtons.get(0).requestFocus();
        }
    }

    private TextView createActionButton(String text, View.OnClickListener onClickListener) {
        TextView t = new TextView(this);
        style(t, 15, 400, Color.WHITE);
        t.setText(text);
        t.setPadding(px(20), px(8), px(30), px(8));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundColor(Color.TRANSPARENT);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(px(12));
                g.setColor(has ? getColor(R.color.pill_focus_bg) : Color.TRANSPARENT);
                if (has) g.setStroke(px(1), getColor(R.color.pill_stroke_focus));
                v.setBackground(g);
            }
        });
        t.setOnClickListener(onClickListener);
        return t;
    }

    private boolean pillHasFocus() {
        for (TextView p : pills) if (p.hasFocus()) return true;
        return false;
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String s = wi != null ? wi.getSSID() : null;
            if (s != null && !s.contains("unknown ssid") && !s.equals("0x")) {
                name = s.replace("\"", "");
            }
        } catch (Exception ignored) {
        }
        wifiPill.setText(name);
    }

    // ------------------------------------------------------ focus / ambient

    private void focusPos(int p) {
        if (p < 0 || p >= apps.size()) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            vh.itemView.requestFocus();
        } else {
            pendingFocus = p;
            grid.scrollToPosition(p);
            grid.post(new Runnable() {
                @Override
                public void run() {
                    tryPendingFocus();
                }
            });
        }
    }

    private void tryPendingFocus() {
        if (pendingFocus < 0) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pendingFocus);
        if (vh != null) {
            pendingFocus = -1;
            vh.itemView.requestFocus();
        }
    }

    private int focusedTilePos = -1;

    private void onTileFocused(int pos, TileHolder h) {
        lastPos = pos;
        focusedTilePos = pos;
        prefs.edit().putInt("last", pos).apply();
        AppEntry e = apps.get(pos);

        // Magnify neighbouring tiles in the dock
        animateDockNeighbours(pos);

        if (pos < dockApps.size()) {
            int off = grid.computeVerticalScrollOffset();
            if (off > 0) grid.smoothScrollBy(0, -off);
            bigName.setText(e.label);
            int baseColor = e.color & 0x00FFFFFF;
            glowDrawable.setColors(new int[]{0x55000000 | baseColor, 0x22000000 | baseColor, 0x00000000});
            float cx = margin + (pos % cols) * tileW + tileW / 2f;
            glow.animate()
                    .x(cx - px(380))
                    .y(padTop - px(290))
                    .alpha(1f)
                    .setDuration(280)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            bigName.animate().alpha(1f).setDuration(180).start();
        } else {
            hideAmbient();
        }
        placeGlass(h.itemView, true);
    }

    private void animateDockNeighbours(int focusedPos) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int childPos = grid.getChildAdapterPosition(child);
            if (childPos < 0) continue;
            TileHolder holder = (TileHolder) grid.getChildViewHolder(child);
            if (holder == null) continue;

            if (childPos == focusedPos) {
                holder.img.animate().scaleX(1.10f).scaleY(1.10f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else if (focusedPos < dockApps.size() && (childPos == focusedPos - 1 || childPos == focusedPos + 1) && childPos < dockApps.size()) {
                holder.img.animate().scaleX(1.03f).scaleY(1.03f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                holder.img.animate().scaleX(1.00f).scaleY(1.00f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }
    }

    private void hideAmbient() {
        glow.animate().alpha(0f).setDuration(150).start();
        bigName.animate().alpha(0f).setDuration(150).start();
    }

    private void updateAmbient() {
        // fade the name and glow out as the dock row scrolls away
        int off = grid.computeVerticalScrollOffset();
        if (off > px(20)) {
            if (bigName.getAlpha() > 0f) hideAmbient();
        }
    }

    private void placeGlass(View tile, boolean animate) {
        TileHolder h = (TileHolder) grid.getChildViewHolder(tile);
        if (h == null) return;
        ImageView img = h.img;
        int[] a = new int[2], r = new int[2];
        img.getLocationOnScreen(a);
        root.getLocationOnScreen(r);
        float cx = a[0] - r[0] + img.getWidth() * img.getScaleX() / 2f;
        float cy = a[1] - r[1] + img.getHeight() * img.getScaleY() / 2f;
        float x = cx - glass.getLayoutParams().width / 2f;
        float y = cy - glass.getLayoutParams().height / 2f;
        glass.animate().cancel();
        if (animate && glass.getAlpha() > 0f) {
            glass.animate().x(x).y(y).alpha(1f).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(1.1f)).start();
        } else {
            glass.setX(x);
            glass.setY(y);
            glass.setAlpha(1f);
        }
    }

    // ---------------------------------------------------------- dock band

    private class DockBand extends RecyclerView.ItemDecoration {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DockBand() {
            fillPaint.setColor(getColor(R.color.dock_band_bg));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(px(1.5f));
            strokePaint.setColor(getColor(R.color.dock_band_stroke));
            sheenPaint.setStyle(Paint.Style.STROKE);
            sheenPaint.setStrokeWidth(px(1));
            sheenPaint.setColor(getColor(R.color.dock_sheen));
        }

        @Override
        public void onDraw(Canvas c, RecyclerView parent, RecyclerView.State state) {
            if (dockApps.isEmpty()) return;
            int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
            int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE;

            for (int i = 0; i < parent.getChildCount(); i++) {
                View v = parent.getChildAt(i);
                int pos = parent.getChildAdapterPosition(v);
                if (pos >= 0 && pos < dockApps.size()) {
                    top = Math.min(top, v.getTop());
                    bottom = Math.max(bottom, v.getBottom());
                    left = Math.min(left, v.getLeft());
                    right = Math.max(right, v.getRight());
                }
            }
            if (top == Integer.MAX_VALUE || left == Integer.MAX_VALUE) return;

            RectF r = new RectF(left - px(10), top - px(6), right + px(10), bottom + px(2));
            c.drawRoundRect(r, px(26), px(26), fillPaint);
            c.drawRoundRect(r, px(26), px(26), strokePaint);
            // subtle specular line along the top inner curve
            c.drawLine(r.left + px(26), r.top + px(1), r.right - px(26), r.top + px(1), sheenPaint);
        }
    }

    // ------------------------------------------------------------ adapter

    private static class TileHolder extends RecyclerView.ViewHolder {
        ImageView img;
        TextView label;

        TileHolder(View v, ImageView i, TextView l) {
            super(v);
            img = i;
            label = l;
        }
    }

    private class TileAdapter extends RecyclerView.Adapter<TileHolder> {
        @Override
        public TileHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout tile = new LinearLayout(LauncherActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setLayoutParams(new RecyclerView.LayoutParams(tileW, rowH));
            tile.setPadding(px(8), px(8), px(8), 0);
            tile.setFocusable(true);
            tile.setClickable(true);

            ImageView img = new ImageView(LauncherActivity.this);
            img.setLayoutParams(new LinearLayout.LayoutParams(imgW, imgH));
            tile.addView(img);

            TextView label = new TextView(LauncherActivity.this);
            style(label, 15, 400, Color.WHITE);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(imgW, px(26));
            llp.topMargin = px(8);
            label.setLayoutParams(llp);
            tile.addView(label);

            final TileHolder h = new TileHolder(tile, img, label);
            tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0) return;
                    h.label.setVisibility((has && pos >= dockApps.size()) ? View.VISIBLE : View.INVISIBLE);
                    if (has) onTileFocused(pos, h);
                    else if (!grid.hasFocus() && !pillHasFocus()) {
                        glass.animate().alpha(0f).setDuration(120).start();
                        animateDockNeighbours(-1);
                    }
                }
            });
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0 || pos >= apps.size()) return;
                    try {
                        startActivity(apps.get(pos).intent);
                    } catch (Exception ignored) {
                    }
                }
            });
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0 || pos >= apps.size()) return false;
                    showAppManagementDialog(apps.get(pos), pos, pos < dockApps.size(), v);
                    return true;
                }
            });
            tile.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int code, KeyEvent ev) {
                    if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int pos = h.getBindingAdapterPosition();
                    int n = apps.size();
                    if (code == KeyEvent.KEYCODE_MENU) {
                        if (pos >= 0 && pos < n) {
                            showAppManagementDialog(apps.get(pos), pos, pos < dockApps.size(), v);
                            return true;
                        }
                    }
                    switch (code) {
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            if (pos % cols != 0) focusPos(pos - 1);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            if (pos % cols != cols - 1 && pos < n - 1) focusPos(pos + 1);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_UP:
                            if (pos < cols) pills[0].requestFocus();
                            else focusPos(pos - cols);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            int t = pos + cols;
                            if (t >= n) t = (pos / cols < (n - 1) / cols) ? n - 1 : -1;
                            if (t >= 0) focusPos(t);
                            return true;
                    }
                    return false;
                }
            });
            return h;
        }

        @Override
        public void onBindViewHolder(TileHolder h, int pos) {
            AppEntry e = apps.get(pos);
            h.img.setImageDrawable(e.banner);
            h.img.setScaleX(1f);
            h.img.setScaleY(1f);
            if (e.isIcon) {
                h.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setCornerRadius(px(16));
                cardBg.setColor(getColor(R.color.card_icon_bg));
                cardBg.setStroke(px(1), getColor(R.color.card_icon_stroke));
                h.img.setBackground(cardBg);
                h.img.setPadding(px(26), px(12), px(26), px(12));
            } else {
                h.img.setScaleType(ImageView.ScaleType.FIT_XY);
                GradientDrawable bannerBg = new GradientDrawable();
                bannerBg.setCornerRadius(px(14));
                bannerBg.setColor(Color.TRANSPARENT);
                h.img.setBackground(bannerBg);
                h.img.setClipToOutline(true);
                h.img.setPadding(0, 0, 0, 0);
            }
            h.label.setText(e.label);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
