package com.gosen.chinese;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** All annotations and review waves use indices into the exact displayed text. */
public class AnnotatedTextView extends TextView {
    public interface Listener { void onNote(AnnotationResolver.ResolvedNote note, RectF screenAnchor); }
    public interface UnannotatedTapListener { void onTextTap(int offset, RectF screenAnchor); }
    private List<AnnotationResolver.ResolvedNote> notes = new ArrayList<>();
    private List<AnnotationResolver.ResolvedNote> markCandidates = new ArrayList<>();
    private final List<AnnotationResolver.ResolvedNote> marked = new ArrayList<>();
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path activePath = new Path();
    private int activeStart = -1, activeEnd = -1;
    private int evidenceColor = 0x50D4B36B;
    private int tappedOffset;
    private String articleId;
    private StudyStore store;
    private UnannotatedTapListener unannotatedTapListener;
    private float downX, downY;
    private boolean dragged;
    private boolean textGesture;

    public AnnotatedTextView(Context context) {
        super(context);
        setMovementMethod(LinkMovementMethod.getInstance());
        setHighlightColor(Color.TRANSPARENT);
        wavePaint.setColor(Color.rgb(158, 126, 74));
        wavePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        wavePaint.setStyle(Paint.Style.STROKE);
    }

    public void bind(JSONObject article, StudyStore studyStore, boolean enableHints, Listener listener) {
        articleId = article.optString("id");
        store = studyStore;
        notes = enableHints ? CharacterAnnotationResolver.resolve(article) : new ArrayList<>();
        markCandidates = enableHints ? new ArrayList<>(AnnotationResolver.resolve(article)) : new ArrayList<>();
        java.util.Set<String> markKeys=new java.util.HashSet<>();
        for(AnnotationResolver.ResolvedNote note:markCandidates)markKeys.add(note.key(articleId));
        for(AnnotationResolver.ResolvedNote note:notes)if(markKeys.add(note.key(articleId)))markCandidates.add(note);
        SpannableString text = new SpannableString(article.optString("body"));
        for (AnnotationResolver.ResolvedNote note : notes) {
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    listener.onNote(note, anchorFor(note));
                }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setUnderlineText(false);
                }
            }, note.start, note.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        setText(text);
        refreshMarks();
    }

    public void refreshMarks() {
        marked.clear();
        if (store != null) for (AnnotationResolver.ResolvedNote note : markCandidates) {
            if (store.hasReview(note.key(articleId))) marked.add(note);
        }
        invalidate();
    }

    public void setOnUnannotatedTapListener(UnannotatedTapListener listener) { unannotatedTapListener = listener; }

    public void setAnnotationColors(int waveColor, int activeColor, int evidenceColor) {
        wavePaint.setColor(waveColor); activePaint.setColor(activeColor); this.evidenceColor = evidenceColor;
        invalidate();
    }

    public void setActiveRange(int start, int end) {
        activeStart = Math.max(0, Math.min(start, getText().length()));
        activeEnd = Math.max(activeStart, Math.min(end, getText().length()));
        if (activeStart == activeEnd) { activeStart = -1; activeEnd = -1; }
        invalidate();
    }

    public void clearActiveRange() { activeStart = -1; activeEnd = -1; invalidate(); }

    /** Range geometry shares the layout that draws and receives taps on the original text. */
    public RectF anchorForRange(int start, int end, int nearOffset) {
        Layout layout = getLayout();
        if (layout == null || start < 0 || end <= start || end > getText().length()) return new RectF();
        int offset = Math.max(start, Math.min(end - 1, nearOffset));
        int line = layout.getLineForOffset(offset);
        RectF bounds = selectionBounds(layout, Math.max(start, layout.getLineStart(line)), Math.min(end, layout.getLineEnd(line)));
        int[] location = new int[2]; getLocationOnScreen(location);
        return new RectF(location[0] + getTotalPaddingLeft() + bounds.left - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineTop(line) - getScrollY(),
                location[0] + getTotalPaddingLeft() + bounds.right - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineBottom(line) - getScrollY());
    }

    public void highlight(List<String> quotes) {
        SpannableString text = new SpannableString(getText());
        for (BackgroundColorSpan span : text.getSpans(0, text.length(), BackgroundColorSpan.class)) text.removeSpan(span);
        for (String quote : quotes) {
            int start = text.toString().indexOf(quote);
            if (start >= 0 && !quote.isEmpty()) text.setSpan(new BackgroundColorSpan(evidenceColor),
                    start, start + quote.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        setText(text);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tappedOffset = getOffsetForPosition(event.getX(), event.getY());
            downX = event.getX(); downY = event.getY(); dragged = false;
            textGesture = hitOffset(downX, downY) >= 0;
        } else if (action == MotionEvent.ACTION_MOVE) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) dragged = true;
        } else if (action == MotionEvent.ACTION_CANCEL) { dragged = true; textGesture = false; }
        if(action==MotionEvent.ACTION_UP&&!notes.isEmpty()
                &&(dragged||event.getEventTime()-event.getDownTime()>=ViewConfiguration.getLongPressTimeout())){
            MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);cancel.recycle();return false;
        }
        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP && textGesture && !dragged && unannotatedTapListener != null
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            int offset = hitOffset(event.getX(), event.getY());
            if (offset >= 0 && !hasClickableSpan(offset)) {
                unannotatedTapListener.onTextTap(offset, anchorAt(offset));
                handled = true;
            }
        }
        return handled || (unannotatedTapListener != null && textGesture);
    }

    private int hitOffset(float x, float y) {
        Layout layout = getLayout();
        if (layout == null || getText().length() == 0) return -1;
        float localX = x - getTotalPaddingLeft() + getScrollX();
        float localY = y - getTotalPaddingTop() + getScrollY();
        if (localY < 0 || localY >= layout.getHeight()) return -1;
        int line = layout.getLineForVertical((int) localY);
        if (localX < layout.getLineLeft(line) || localX > layout.getLineRight(line)) return -1;
        int offset = getOffsetForPosition(x, y);
        if (offset < 0 || offset >= getText().length() || Character.isWhitespace(getText().charAt(offset))) return -1;
        return offset;
    }

    private boolean hasClickableSpan(int offset) {
        if (!(getText() instanceof Spanned)) return false;
        Spanned text = (Spanned) getText();
        return text.getSpans(offset, Math.min(offset + 1, text.length()), ClickableSpan.class).length > 0;
    }

    private RectF anchorAt(int offset) {
        Layout layout = getLayout();
        int line = layout.getLineForOffset(offset);
        RectF bounds = selectionBounds(layout, offset, Math.min(offset + 1, getText().length()));
        int[] location = new int[2]; getLocationOnScreen(location);
        return new RectF(location[0] + getTotalPaddingLeft() + bounds.left - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineTop(line) - getScrollY(),
                location[0] + getTotalPaddingLeft() + bounds.right - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineBottom(line) - getScrollY());
    }

    private RectF anchorFor(AnnotationResolver.ResolvedNote note) {
        Layout layout = getLayout();
        if (layout == null) return new RectF();
        int offset = Math.max(note.start, Math.min(note.end - 1, tappedOffset));
        int line = layout.getLineForOffset(offset);
        int start = Math.max(note.start, layout.getLineStart(line));
        int end = Math.min(note.end, layout.getLineEnd(line));
        RectF selection = selectionBounds(layout, start, end);
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new RectF(location[0] + getTotalPaddingLeft() + selection.left - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineTop(line) - getScrollY(),
                location[0] + getTotalPaddingLeft() + selection.right - getScrollX(),
                location[1] + getTotalPaddingTop() + layout.getLineBottom(line) - getScrollY());
    }

    @Override protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null && activeStart >= 0 && activeEnd > activeStart && activeEnd <= getText().length()) {
            activePath.reset(); layout.getSelectionPath(activeStart, activeEnd, activePath);
            canvas.save(); canvas.translate(getTotalPaddingLeft() - getScrollX(), getTotalPaddingTop() - getScrollY());
            canvas.drawPath(activePath, activePaint); canvas.restore();
        }
        super.onDraw(canvas);
        if (layout == null) return;
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop() - getScrollY());
        for (AnnotationResolver.ResolvedNote note : marked) {
            int first = layout.getLineForOffset(note.start), last = layout.getLineForOffset(note.end - 1);
            for (int line = first; line <= last; line++) {
                int start = Math.max(note.start, layout.getLineStart(line));
                int end = Math.min(note.end, layout.getLineEnd(line));
                RectF selection = selectionBounds(layout, start, end);
                float left = selection.left, right = selection.right;
                float y = layout.getLineBaseline(line) + density * 4;
                wave.reset();
                wave.moveTo(left, y);
                for (float x = left; x < right; x += density) {
                    wave.lineTo(Math.min(x + density, right), y + (float)Math.sin((x - left) / (density * 1.5)) * density);
                }
                canvas.drawPath(wave, wavePaint);
            }
        }
        canvas.restore();
    }

    private RectF selectionBounds(Layout layout, int start, int end) {
        Path selection = new Path();
        layout.getSelectionPath(start, end, selection);
        RectF bounds = new RectF();
        selection.computeBounds(bounds, true);
        return bounds;
    }
}
