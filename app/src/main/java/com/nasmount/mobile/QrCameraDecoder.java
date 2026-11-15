package com.nasmount.mobile;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** QR detection on grayscale camera frames (all four sensor orientations). */
final class QrCameraDecoder {
    private QrCameraDecoder(){}

    static String decode(byte[] luminance,int width,int height){
        if(luminance==null||width<32||height<32||
                width>1600||height>1600||
                (long)width*height!=luminance.length)return null;
        QRCodeReader scanner=new QRCodeReader();
        for(int turn=0;turn<4;turn++){
            try{
                PlanarYUVLuminanceSource source=new PlanarYUVLuminanceSource(
                        luminance,width,height,0,0,width,height,false);
                Result result=scanner.decode(
                        new BinaryBitmap(new HybridBinarizer(source)));
                return result.getText();
            }catch(Exception ignored){
                scanner.reset();
            }
            byte[] rotated=new byte[luminance.length];
            for(int y=0;y<height;y++)
                for(int x=0;x<width;x++)
                    rotated[x*height+(height-1-y)]=luminance[y*width+x];
            luminance=rotated;
            int tmp=width;width=height;height=tmp;
        }
        return null;
    }
}
