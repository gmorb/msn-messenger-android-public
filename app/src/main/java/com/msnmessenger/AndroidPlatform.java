package com.msnmessenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.msnmessenger.imp.Importer;
import com.msnmessenger.imp.NativeCab;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Android services for the installer importer: Bitmap PNG I/O, native WMA decoder and cabinet extractor. */
final class AndroidPlatform implements Importer.Platform {

    @Override
    public int[] decodePng(byte[] png, int[] wh) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inPremultiplied = false;
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length, o);
        if (b == null) throw new IOException("Cannot read an image in the installer");
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        wh[0] = w;
        wh[1] = h;
        return px;
    }

    @Override
    public void writePng(File out, int[] argb, int w, int h) throws IOException {
        Bitmap b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IOException("Cannot save " + out.getName());
        } finally {
            b.recycle();
        }
    }

    @Override
    public int wmaToWav(File wma, File wav) {
        return WmaDecoder.toWav(wma, wav);
    }

    @Override
    public int extractFromExe(File exe, String name, File out) {
        return NativeCab.extractFile(exe, name, out);
    }
}
