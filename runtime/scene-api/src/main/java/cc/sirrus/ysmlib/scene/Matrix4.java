package cc.sirrus.ysmlib.scene;

/** Immutable column-major matrix, preserving source bind matrices. */
public final class Matrix4 {
  public static final Matrix4 IDENTITY=new Matrix4(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1);
  private final FloatData elements;
  public Matrix4(float... values) {
    if(values.length!=16) throw new IllegalArgumentException("Matrix requires 16 components");
    elements=new FloatData(values);
  }
  public float get(int column,int row) {
    if(column<0 || column>=4 || row<0 || row>=4) throw new IndexOutOfBoundsException();
    return elements.get(column*4+row);
  }
  public float[] copy() { return elements.copy(); }
  public Matrix4 multiply(Matrix4 right) {
    float[] out=new float[16];
    for(int column=0;column<4;column++) for(int row=0;row<4;row++)
      for(int k=0;k<4;k++) out[column*4+row]+=get(k,row)*right.get(column,k);
    return new Matrix4(out);
  }
  public Vec3 transformPoint(Vec3 p) {
    if(!isAffine()) throw new IllegalStateException("Point transform requires an affine matrix");
    float x=p.x(),y=p.y(),z=p.z();
    return new Vec3(get(0,0)*x+get(1,0)*y+get(2,0)*z+get(3,0),
        get(0,1)*x+get(1,1)*y+get(2,1)*z+get(3,1),get(0,2)*x+get(1,2)*y+get(2,2)*z+get(3,2));
  }
  public boolean isAffine() { return get(0,3)==0 && get(1,3)==0 && get(2,3)==0 && get(3,3)==1; }
  public Vec3 transformDirection(Vec3 p) {
    return new Vec3(get(0,0)*p.x()+get(1,0)*p.y()+get(2,0)*p.z(),
        get(0,1)*p.x()+get(1,1)*p.y()+get(2,1)*p.z(),get(0,2)*p.x()+get(1,2)*p.y()+get(2,2)*p.z());
  }
  public Matrix4 inverse() {
    double[][] a=new double[4][8];
    for(int r=0;r<4;r++) { for(int c=0;c<4;c++) a[r][c]=get(c,r);a[r][r+4]=1; }
    for(int c=0;c<4;c++) {
      int pivot=c;
      for(int r=c+1;r<4;r++) if(Math.abs(a[r][c])>Math.abs(a[pivot][c])) pivot=r;
      if(a[pivot][c]==0) throw new IllegalArgumentException("Singular matrix");
      double[] swap=a[c];a[c]=a[pivot];a[pivot]=swap;
      double divisor=a[c][c];for(int k=0;k<8;k++) a[c][k]/=divisor;
      for(int r=0;r<4;r++) if(r!=c) { double factor=a[r][c];for(int k=0;k<8;k++) a[r][k]-=factor*a[c][k]; }
    }
    float[] out=new float[16];for(int r=0;r<4;r++) for(int c=0;c<4;c++) out[c*4+r]=(float)a[r][c+4];
    return new Matrix4(out);
  }
  @Override public boolean equals(Object other) { return other instanceof Matrix4 m && elements.equals(m.elements); }
  @Override public int hashCode() { return elements.hashCode(); }
}
