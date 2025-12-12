package com.example.facedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
public class YuvToRgbConverter {
    private final RenderScript rs;
    private final ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private Allocation inAlloc;
    private Allocation outAlloc;

    public YuvToRgbConverter(Context context) {
        RenderScript tmpRs = null;
        ScriptIntrinsicYuvToRGB tmpIntrinsic = null;
        try {
            tmpRs = RenderScript.create(context);
            tmpIntrinsic = ScriptIntrinsicYuvToRGB.create(tmpRs, Element.U8_4(tmpRs));
        } catch (Throwable t) {
            // 如果这里失败，会在 ImageUtil.init 捕获并降级
            if (tmpIntrinsic != null) try { tmpIntrinsic.destroy(); } catch (Throwable ignored) {}
            if (tmpRs != null) try { tmpRs.destroy(); } catch (Throwable ignored) {}
            throw t;
        }
        this.rs = tmpRs;
        this.yuvToRgbIntrinsic = tmpIntrinsic;
    }

    public synchronized void yuvToRgb(@NonNull Image image, @NonNull Bitmap output) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            final Image.Plane[] planes = image.getPlanes();

            int yuvBytesSize = planes[0].getBuffer().remaining() +
                    planes[1].getBuffer().remaining() +
                    planes[2].getBuffer().remaining();
            if (inAlloc == null || inAlloc.getBytesSize() < yuvBytesSize) {
                Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(yuvBytesSize);
                inAlloc = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
            }

            if (outAlloc == null || outAlloc.getType().getX() != width || outAlloc.getType().getY() != height) {
                Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
                outAlloc = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
            }

            byte[] yuvBytes = new byte[yuvBytesSize];
            int offset = 0;
            for (int i = 0; i < planes.length; ++i) {
                ByteBuffer buffer = planes[i].getBuffer();
                int remaining = buffer.remaining();
                buffer.get(yuvBytes, offset, remaining);
                offset += remaining;
            }

            inAlloc.copyFrom(yuvBytes);
            yuvToRgbIntrinsic.setInput(inAlloc);
            yuvToRgbIntrinsic.forEach(outAlloc);
            outAlloc.copyTo(output);
        } catch (Throwable t) {
            throw t; // 让调用方（ImageUtil）捕获并回退
        }
    }

    public void destroy() {
        try { if (inAlloc != null) inAlloc.destroy(); } catch (Throwable ignored) {}
        try { if (outAlloc != null) outAlloc.destroy(); } catch (Throwable ignored) {}
        try { if (yuvToRgbIntrinsic != null) yuvToRgbIntrinsic.destroy(); } catch (Throwable ignored) {}
        try { if (rs != null) rs.destroy(); } catch (Throwable ignored) {}
    }
}

