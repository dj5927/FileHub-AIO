package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PageCurlPhysicsTest {
    private float d(float p,float grab,float y){
        return PageCurlPhysics.distance(p,grab,0f,y);
    }
    @Test public void bottomGrabPullsBottomBeforeTop(){
        assertTrue(d(.48f,.96f,1f)>d(.48f,.96f,.5f));
        assertTrue(d(.48f,.96f,.5f)>d(.48f,.96f,0f));
        assertTrue(PageCurlPhysics.backWidth(1000,.48f,.96f,0f,1f)>
                PageCurlPhysics.backWidth(1000,.48f,.96f,0f,0f));
    }
    @Test public void topGrabMirrorsBottomCoupling(){
        assertEquals(d(.48f,.96f,1f),d(.48f,.04f,0f),.00001f);
        assertEquals(d(.48f,.96f,0f),d(.48f,.04f,1f),.00001f);
        assertTrue(d(.48f,.04f,0f)>d(.48f,.04f,1f));
    }
    @Test public void middleGrabPullsTopAndBottomEqually(){
        assertEquals(d(.50f,.50f,0f),d(.50f,.50f,1f),.00001f);
        assertTrue(d(.50f,.50f,.5f)>d(.50f,.50f,0f));
    }
    @Test public void diagonalDragMovesElasticAnchor(){
        assertTrue(PageCurlPhysics.activeGrab(.5f,-.7f)<.5f);
        assertTrue(PageCurlPhysics.activeGrab(.5f,.7f)>.5f);
        assertTrue(PageCurlPhysics.distance(.5f,.5f,-.7f,0f)>
                PageCurlPhysics.distance(.5f,.5f,.7f,0f));
    }
    @Test public void reverseIsExactHorizontalMirrorAtEveryHeight(){
        for(float grab:new float[]{0f,.22f,.5f,.94f,1f})
            for(float y:new float[]{0f,.19f,.55f,1f})
                for(float progress:new float[]{0f,.1f,.42f,.8f,1f}){
                    float right=PageCurlPhysics.edgeX(1000,progress,grab,
                            .3f,y,true);
                    float left=PageCurlPhysics.edgeX(1000,progress,grab,
                            .3f,y,false);
                    assertEquals(1000f,right+left,.001f);
                }
    }
    @Test public void endpointsMeetAndRestoreAcrossTheWholePage(){
        for(float y:new float[]{0f,.25f,.5f,.75f,1f}){
            assertEquals(0f,d(0f,.92f,y),.00001f);
            assertEquals(1f,d(1f,.92f,y),.00001f);
            assertEquals(0f,PageCurlPhysics.backWidth(
                    1000,0f,.92f,0f,y),.00001f);
            assertEquals(0f,PageCurlPhysics.backWidth(
                    1000,1f,.92f,0f,y),.00001f);
        }
    }
    @Test public void movingFingerBackUnfoldsAllEdgesContinuously(){
        for(float y:new float[]{0f,.5f,1f})
            assertTrue(d(.08f,.9f,y)<d(.38f,.9f,y));
    }
    @Test public void elasticFarEdgeNeverMovesBackwardWhileFingerAdvances(){
        for(float grab:new float[]{0f,.25f,.5f,.75f,1f}){
            for(float y:new float[]{0f,.25f,.5f,.75f,1f}){
                float previous=-1f;
                for(int step=0;step<=100;step++){
                    float distance=d(step/100f,grab,y);
                    assertTrue("Nonmonotonic fold at grab="+grab+
                            " y="+y+" p="+step,
                            distance+.00001f>=previous);
                    previous=distance;
                }
            }
        }
    }
}
