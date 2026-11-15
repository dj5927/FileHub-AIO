package com.nasmount.mobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.EnumMap;

final class TvQrCode {
    private TvQrCode() {}
    static Bitmap bitmap(String value,int size) throws WriterException {
        EnumMap<EncodeHintType,Object> options=new EnumMap<>(EncodeHintType.class);
        options.put(EncodeHintType.MARGIN,2);
        BitMatrix pixels=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,
                size,size,options);
        Bitmap image=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            image.setPixel(x,y,pixels.get(x,y)?Color.BLACK:Color.WHITE);
        return image;
    }
}
