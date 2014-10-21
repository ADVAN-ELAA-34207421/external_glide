package com.bumptech.glide.load.resource.gif;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.bumptech.glide.Glide;
import com.bumptech.glide.gifdecoder.GifDecoder;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.NullEncoder;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.UUID;

class GifFrameManager {
    /** 60fps is {@value #MIN_FRAME_DELAY}ms per frame. */
    private static final long MIN_FRAME_DELAY = 1000 / 60;
    private final GifFrameModelLoader frameLoader;
    private final GifFrameResourceDecoder frameResourceDecoder;
    private final GifDecoder decoder;
    private final Handler mainHandler;
    private final Context context;
    private final Encoder<GifDecoder> sourceEncoder;
    private final Transformation<Bitmap>[] transformation;
    private final int targetWidth;
    private final int targetHeight;
    private final FrameSignature signature;
    private DelayTarget current;
    private DelayTarget next;

    public interface FrameCallback {
        void onFrameRead(int index);
    }

    public GifFrameManager(Context context, GifDecoder decoder, Transformation<Bitmap> transformation, int targetWidth,
            int targetHeight) {
        this(context, Glide.get(context).getBitmapPool(), decoder, new Handler(Looper.getMainLooper()), transformation,
                targetWidth, targetHeight);
    }

    @SuppressWarnings("unchecked")
    public GifFrameManager(Context context, BitmapPool bitmapPool, GifDecoder decoder, Handler mainHandler,
            Transformation<Bitmap> transformation, int targetWidth, int targetHeight) {
        if (transformation == null) {
            throw new NullPointerException("Transformation must not be null");
        }

        this.context = context;
        this.frameResourceDecoder = new GifFrameResourceDecoder(bitmapPool);
        this.decoder = decoder;
        this.mainHandler = mainHandler;
        this.transformation = new Transformation[] {transformation};
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.frameLoader = new GifFrameModelLoader();
        this.sourceEncoder = NullEncoder.get();
        this.signature = new FrameSignature();
    }

    Transformation<Bitmap> getTransformation() {
        return transformation[0];
    }

    public void getNextFrame(FrameCallback cb) {
        decoder.advance();

        long targetTime = SystemClock.uptimeMillis() + Math.max(MIN_FRAME_DELAY, decoder.getNextDelay());
        next = new DelayTarget(cb, targetTime);
        next.setFrameIndex(decoder.getCurrentFrameIndex());

        // Use an incrementing signature to make sure we never hit an active resource that matches one of our frames.
        signature.increment();
        Glide.with(context)
                .using(frameLoader, GifDecoder.class)
                .load(decoder)
                .as(Bitmap.class)
                .signature(signature)
                .sourceEncoder(sourceEncoder)
                .decoder(frameResourceDecoder)
                .transform(transformation)
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(next);
    }

    public Bitmap getCurrentFrame() {
        return current != null ? current.resource : null;
    }

    public void clear() {
        if (current != null) {
            mainHandler.removeCallbacks(current);
            Glide.clear(current);
            current = null;
        }
        if (next != null) {
            mainHandler.removeCallbacks(next);
            Glide.clear(next);
            next = null;
        }

        decoder.resetFrameIndex();
    }

    class DelayTarget extends SimpleTarget<Bitmap> implements Runnable {
        private final FrameCallback cb;
        private final long targetTime;
        private Bitmap resource;
        private int index;

        public DelayTarget(FrameCallback cb, long targetTime) {
            super(targetWidth, targetHeight);
            this.cb = cb;
            this.targetTime = targetTime;
        }

        public void setFrameIndex(int index) {
            this.index = index;
        }

        @Override
        public void onResourceReady(final Bitmap resource, GlideAnimation<? super Bitmap> glideAnimation) {
            this.resource = resource;
            mainHandler.postAtTime(this, targetTime);
        }

        @Override
        public void run() {
            cb.onFrameRead(index);
            if (current != null) {
                Glide.clear(current);
            }
            current = this;
        }

        @Override
        public void onLoadCleared(Drawable placeholder) {
            resource = null;
        }
    }

    private static class FrameSignature implements Key {
        private final UUID uuid;
        private int id;

        public FrameSignature() {
            this.uuid = UUID.randomUUID();
        }

        public void increment() {
            id++;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof FrameSignature) {
                FrameSignature other = (FrameSignature) o;
                return other.uuid.equals(uuid) && id == other.id;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = uuid.hashCode();
            result = 31 * result + id;
            return result;
        }

        @Override
        public void updateDiskCacheKey(MessageDigest messageDigest) throws UnsupportedEncodingException {
            throw new UnsupportedOperationException("Not implemented");
        }
    }
}
