package com.nasmount.mobile;

import org.junit.Test;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Arrays;
import static org.junit.Assert.*;

public class QrCameraDecoderTest {
    @Test public void cameraQrDecodesAcrossRotationAngles() throws Exception {
        String uri=TvPairingCodec.pairingUri("192.168.1.22",25482,
                TvPairingCodec.newKey());
        BitMatrix qr=new QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,400,400);
        int w=400,h=400;
        byte[] luminance=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)
            luminance[y*w+x]=(byte)(qr.get(x,y)?0:255);
        for(int angle=0;angle<4;angle++){
            assertEquals(uri,QrCameraDecoder.decode(luminance,w,h));
            byte[] rotated=new byte[w*h];
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)
                rotated[x*h+(h-1-y)]=luminance[y*w+x];
            luminance=rotated;
        }
    }
    @Test public void blankOrInvalidImageNeverPairs() {
        byte[] white=new byte[640*480];
        Arrays.fill(white,(byte)255);
        assertNull(QrCameraDecoder.decode(white,640,480));
        assertNull(QrCameraDecoder.decode(new byte[10],100,100));
    }
}
