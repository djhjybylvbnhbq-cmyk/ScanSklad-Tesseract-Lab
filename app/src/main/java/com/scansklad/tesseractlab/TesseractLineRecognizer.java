package com.scansklad.tesseractlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.SystemClock;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Dedicated off-line OCR for an already cropped single warehouse-address line. */
public final class TesseractLineRecognizer implements AutoCloseable {
    public static final class Recognition {
        public final String text;
        public final int confidence; // Estimated confidence (0..100), NOT proof of correctness.
        public final long millis;

        public Recognition(String text, int confidence, long millis) {
            this.text = text;
            this.confidence = confidence;
            this.millis = millis;
        }
    }

    private final TessBaseAPI tess;

    public TesseractLineRecognizer(Context context) throws IOException {
        File folder = new File(context.getFilesDir(), "tesseract/tessdata");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Не удалось создать каталог OCR: " + folder);
        }
        copyAsset(context, folder, "rus.traineddata");
        copyAsset(context, folder, "eng.traineddata");
        TessBaseAPI instance = new TessBaseAPI();
        try {
            File parent = folder.getParentFile();
            if (parent == null || !instance.init(parent.getAbsolutePath(), "rus+eng")) {
                throw new IOException("Не удалось инициализировать Tesseract: проверьте rus и eng модели");
            }
            instance.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE);
        } catch (IOException failure) {
            instance.recycle();
            throw failure;
        } catch (RuntimeException | Error failure) {
            instance.recycle();
            throw failure;
        }
        tess = instance;
    }

    private static void copyAsset(Context context, File folder, String fileName) throws IOException {
        File dst = new File(folder, fileName);
        if (dst.isFile() && dst.length() > 100_000) return;
        File tmp = new File(folder, fileName + ".tmp");
        try (InputStream in = context.getAssets().open("tessdata/" + fileName);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        if (tmp.length() < 100_000) throw new IOException("OCR модель не загружена: " + fileName);
        if (dst.exists() && !dst.delete()) throw new IOException("Не удалось заменить " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("Не удалось сохранить " + dst);
    }

    /** Must be called from the SAME dedicated background thread that initialized Tesseract. */
    public Recognition recognize(Bitmap frame) {
        long start = SystemClock.elapsedRealtime();
        Bitmap processed = preprocess(frame);
        try {
            tess.setImage(processed);
            String text = tess.getUTF8Text();
            int confidence = tess.meanConfidence();
            return new Recognition(text == null ? "" : text.replace('\n', ' ').trim(),
                    Math.max(0, Math.min(100, confidence)), SystemClock.elapsedRealtime() - start);
        } finally {
            tess.clear();
            processed.recycle();
        }
    }

    /** Upscale small type and make a grayscale copy; no dictionary or address guessing. */
    private static Bitmap preprocess(Bitmap bitmap) {
        int width = bitmap.getWidth();
        // Keep memory bounded; small labels benefit from 1.5–2x interpolation.
        float scale = width < 900 ? Math.min(2f, 1100f / Math.max(1, width)) : 1f;
        int targetW = Math.max(1, Math.round(width * scale));
        int targetH = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        ColorMatrix grayscale = new ColorMatrix();
        grayscale.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(grayscale));
        canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, targetW, targetH), paint);
        return output;
    }

    @Override public void close() {
        tess.recycle();
    }
}
