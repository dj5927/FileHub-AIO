package com.nasmount.mobile;

/** A host paint notification is not evidence that a texture buffer was
 * presented. Do not expose a newly attached surface until both agree. */
final class FlutterFrameGate {
    private long epoch;
    private boolean snapshotReady,bufferReady;
    long next(){snapshotReady=false;bufferReady=false;return ++epoch;}
    long epoch(){return epoch;}
    boolean ready(long value){
        if(value!=epoch)return false;
        if(!snapshotReady)bufferReady=false;
        snapshotReady=true;return true;
    }
    void buffer(){bufferReady=true;}
    void lost(){bufferReady=false;}
    boolean canReveal(){return snapshotReady&&bufferReady;}
}
