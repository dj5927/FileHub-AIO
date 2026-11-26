package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class FlutterFrameGateTest {
    @Test public void newRouteNeedsItsOwnAcknowledgementAndActualBuffer(){
        FlutterFrameGate gate=new FlutterFrameGate();
        long first=gate.next();
        gate.buffer();
        assertFalse(gate.canReveal());
        assertTrue(gate.ready(first));
        assertFalse(gate.canReveal());
        gate.buffer();
        assertTrue(gate.canReveal());
        long second=gate.next();
        assertFalse(gate.ready(first));
        assertFalse(gate.canReveal());
        assertTrue(gate.ready(second));
        gate.buffer();
        assertTrue(gate.canReveal());
        gate.lost();
        assertFalse(gate.canReveal());
    }
    @Test public void repeatedSnapshotAcknowledgementDoesNotResetPresentedBuffer(){
        FlutterFrameGate gate=new FlutterFrameGate();
        long epoch=gate.next();
        gate.ready(epoch);gate.buffer();
        assertTrue(gate.ready(epoch));
        assertTrue(gate.canReveal());
    }
}
