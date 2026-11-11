package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;
public class PersonalWakeFeaturesTest {
 @Test public void silenceFinite(){float[] f=PersonalWakeFeatures.features(new short[24000]);assertEquals(9536,f.length);for(float x:f)assertEquals((float)Math.log(Math.ulp(1.0)),x,1e-5f);}
 @Test public void scores(){float[] a=new float[2048],b=new float[2048];a[0]=1;b[1]=1;assertEquals(1,PersonalWakeFeatures.score(a,a),1e-7);assertEquals(.5,PersonalWakeFeatures.score(a,b),1e-7);}
 @Test(expected=IllegalArgumentException.class)public void quietRejected(){PersonalWakeFeatures.window(new short[48000]);}
 @Test(expected=IllegalArgumentException.class)public void clippedRejected(){short[] p=new short[48000];java.util.Arrays.fill(p,Short.MIN_VALUE);PersonalWakeFeatures.window(p);}
 @Test(expected=IllegalArgumentException.class)public void nanRejected(){float[] a=new float[2048];a[0]=Float.NaN;PersonalWakeFeatures.normalize(a);}
 @Test public void crop(){short[] p=new short[48000];for(int i=20000;i<30000;i++)p[i]=1000;short[] w=PersonalWakeFeatures.window(p);assertEquals(24000,w.length);long s=0;for(short x:w)s+=x;assertEquals(10000000L,s);}
}
