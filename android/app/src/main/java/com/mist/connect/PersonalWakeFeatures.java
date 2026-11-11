package com.mist.connect;
/** Fixed 16kHz/1.5s log-filterbank frontend; no audio persistence or network. */
public final class PersonalWakeFeatures {
 public static final int SAMPLES=24000, DIM=2048;
 public static float[] features(short[] pcm) {
  if(pcm.length!=SAMPLES) throw new IllegalArgumentException("length");
  int[] bins=new int[66]; double top=2595*Math.log10(1+8000.0/700);
  for(int i=0;i<66;i++) bins[i]=(int)Math.floor(513*(700*(Math.pow(10,top*i/65/2595)-1))/16000);
  float[] out=new float[149*64];
  for(int t=0;t<149;t++) {
   double[] re=new double[512],im=new double[512];
   for(int i=0;i<400;i++) { int p=t*160+i; if(p<pcm.length) re[i]=pcm[p]/32768.0; }
   fft(re,im);
   for(int m=0;m<64;m++) {
    double sum=0;
    for(int k=bins[m];k<bins[m+1];k++) sum+=(re[k]*re[k]+im[k]*im[k])/512*(k-bins[m])/(double)(bins[m+1]-bins[m]);
    for(int k=bins[m+1];k<bins[m+2];k++) sum+=(re[k]*re[k]+im[k]*im[k])/512*(bins[m+2]-k)/(double)(bins[m+2]-bins[m+1]);
    out[t*64+m]=(float)Math.log(sum==0?Math.ulp(1.0):sum);
   }
  }
  return out;
 }
 private static void fft(double[] r,double[] im) {
  for(int i=1,j=0;i<512;i++) {int b=256;for(; (j&b)!=0;b>>=1)j^=b;j^=b;if(i<j){double x=r[i];r[i]=r[j];r[j]=x;}}
  for(int n=2;n<=512;n<<=1) {double ar=Math.cos(-2*Math.PI/n),ai=Math.sin(-2*Math.PI/n);
   for(int k=0;k<512;k+=n){double wr=1,wi=0;for(int t=0;t<n/2;t++){
    int u=k+t,v=u+n/2;double vr=r[v]*wr-im[v]*wi,vi=r[v]*wi+im[v]*wr;
    r[v]=r[u]-vr;im[v]=im[u]-vi;r[u]+=vr;im[u]+=vi;double nw=wr*ar-wi*ai;wi=wr*ai+wi*ar;wr=nw;
   }}
  }
 }
 public static float[] normalize(float[] v) {
  if(v.length!=DIM) throw new IllegalArgumentException("dimension");
  double n=0;for(float f:v){if(!Float.isFinite(f))throw new IllegalArgumentException("nonfinite");n+=f*f;}
  if(n<1e-12)throw new IllegalArgumentException("zero vector");
  float[] out=v.clone();for(int i=0;i<v.length;i++)out[i]/=Math.sqrt(n);return out;
 }
 public static double score(float[] a,float[] b) {
  a=normalize(a);b=normalize(b);double s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return Math.max(0,Math.min(1,(s+1)/2));
 }
 /** Reject quiet/clipped enrollment and crop around energy, not random offset. */
 public static short[] window(short[] pcm) {
  if(pcm.length<SAMPLES)throw new IllegalArgumentException("녹음 길이가 부족합니다");
  long sum=0,best=-1;int clips=0,start=0;
  for(short p:pcm)if(Math.abs((int)p)>=32760)clips++;
  if(clips>pcm.length/100)throw new IllegalArgumentException("음성이 너무 큽니다. 조금 떨어져 다시 녹음해 주세요");
  for(int i=0;i<pcm.length;i++){
   sum+=(long)pcm[i]*pcm[i];if(i>=SAMPLES)sum-=(long)pcm[i-SAMPLES]*pcm[i-SAMPLES];
   if(i>=SAMPLES-1&&sum>best){best=sum;start=i-SAMPLES+1;}
  }
  if(Math.sqrt(best/(double)SAMPLES)<180)throw new IllegalArgumentException("음성이 너무 작습니다");
  return java.util.Arrays.copyOfRange(pcm,start,start+SAMPLES);
 }
}
