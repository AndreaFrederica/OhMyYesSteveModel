package cc.sirrus.ysmlib.tools.scene;

import cc.sirrus.ysmlib.SceneAuthoring;
import cc.sirrus.ysmlib.scene.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.List;

/** Small bounded CPU geometry preview. Intentionally does not claim game material rendering. */
public final class ScenePreview {
    public record Point(int bone,double x,double y) {}
    public record Result(BufferedImage image,List<Point> bones,int triangles,int coveredPixels) {}
    private ScenePreview(){}
    public static Result render(ScenePackagePlayback.Frame frame,SceneModelProfile profile,List<SceneSkeleton.Bone> skeleton,
                                int width,int height,double yaw,double pitch,double zoom,int selected) {
        return render(frame,profile,skeleton,width,height,yaw,pitch,zoom,selected,null);
    }
    public static Result render(ScenePackagePlayback.Frame frame,SceneModelProfile profile,List<SceneSkeleton.Bone> skeleton,
                                int width,int height,double yaw,double pitch,double zoom,int selected,double[] referenceBounds) {
        if(width<32||height<32||width>2048||height>2048||!Double.isFinite(zoom)||zoom<=0)throw new IllegalArgumentException("Invalid preview size/zoom");
        var geometry=frame.thirdPerson();double[] bounds=referenceBounds==null?SceneAuthoring.bounds(geometry,profile):referenceBounds;
        var camera=new Camera(bounds,width,height,yaw,pitch,zoom);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();Arrays.fill(pixels,0x252c36);
        float[] depth=new float[pixels.length];Arrays.fill(depth,Float.POSITIVE_INFINITY);int triangles=0;
        for(var draw:geometry.draws())if(draw.visible())for(var primitive:draw.geometry().primitives()) {
            var positions=primitive.attributes().get("POSITION").values();int count=primitive.vertexCount();double[] points=new double[count*3];
            for(int i=0;i<count;i++) {
                int k=i*3;Vec3 source=draw.world().transformPoint(new Vec3(positions.get(k),positions.get(k+1),positions.get(k+2)));
                double[] p=camera.project(SceneAuthoring.position(source,geometry.coordinates(),profile));System.arraycopy(p,0,points,k,3);
            }
            int n=primitive.indices().size()==0?count:primitive.indices().size();int material=primitive.material();
            int base=Color.HSBtoRGB((float)((Math.max(0,material)*.137+.53)%1),.22f,.87f)&0xffffff;
            switch(primitive.topology()) {
                case TRIANGLES->{for(int i=0;i+2<n;i+=3){triangle(points,index(primitive,i),index(primitive,i+1),index(primitive,i+2),width,height,pixels,depth,base);triangles++;}}
                case TRIANGLE_STRIP->{for(int i=2;i<n;i++){triangle(points,index(primitive,i-2),index(primitive,i-1),index(primitive,i),width,height,pixels,depth,base);triangles++;}}
                case TRIANGLE_FAN->{for(int i=2;i<n;i++){triangle(points,index(primitive,0),index(primitive,i-1),index(primitive,i),width,height,pixels,depth,base);triangles++;}}
                case POINTS->{for(int i=0;i<n;i++){int k=index(primitive,i)*3,x=(int)points[k],y=(int)points[k+1];if(x>=0&&x<width&&y>=0&&y<height&&points[k+2]<depth[y*width+x]){pixels[y*width+x]=base;depth[y*width+x]=(float)points[k+2];}}}
                case LINES,LINE_STRIP,LINE_LOOP->{var g=image.createGraphics();g.setColor(new Color(base));int step=primitive.topology()==MeshAsset.Topology.LINES?2:1;for(int i=0;i+1<n;i+=step)line(g,points,index(primitive,i),index(primitive,i+1));if(primitive.topology()==MeshAsset.Topology.LINE_LOOP&&n>1)line(g,points,index(primitive,n-1),index(primitive,0));g.dispose();}
            }
        }
        int covered=0;for(int p:pixels)if(p!=0x252c36)covered++;
        var points=new ArrayList<Point>();var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        for(var bone:skeleton) {
            Vec3 p=bone.position();var details=frame.details();
            if(details instanceof ScenePackagePlayback.Mmd m)p=m.value().pose().bones().get(bone.index()).position();
            else if(details instanceof ScenePackagePlayback.Fbx f){var matrix=f.value().nodes().get(bone.index()).world();p=new Vec3((float)matrix.get(9),(float)matrix.get(10),(float)matrix.get(11));}
            else {var pose=details instanceof ScenePackagePlayback.Scene s?s.pose():details instanceof ScenePackagePlayback.Vrm v?v.value().pose():null;if(pose!=null)p=pose.globalMatrices().get(bone.index()).transformPoint(Vec3.ZERO);}
            var projected=camera.project(SceneAuthoring.position(p,geometry.coordinates(),profile));points.add(new Point(bone.index(),projected[0],projected[1]));
        }
        for(var bone:skeleton)if(bone.parent()>=0){var a=points.get(bone.index());var b=points.get(bone.parent());g.setColor(new Color(80,200,250,90));g.drawLine((int)a.x,(int)a.y,(int)b.x,(int)b.y);}
        for(var p:points){g.setColor(p.bone==selected?Color.YELLOW:new Color(100,230,250,120));int radius=p.bone==selected?5:2;g.fillOval((int)p.x-radius,(int)p.y-radius,radius*2+1,radius*2+1);}
        g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));g.drawString("Geometry + skeleton | physics OFF | simplified shading",12,20);
        g.drawString(String.format(Locale.ROOT,"Height %.3f m | %,d triangles",profile.placement().actualHeight(),triangles),12,height-12);g.dispose();
        return new Result(image,List.copyOf(points),triangles,covered);
    }
    private static int index(MeshAsset.Primitive p,int i){return p.indices().size()==0?i:p.indices().get(i);}
    private static void line(Graphics2D g,double[] p,int a,int b){g.drawLine((int)p[a*3],(int)p[a*3+1],(int)p[b*3],(int)p[b*3+1]);}
    private static void triangle(double[] p,int ai,int bi,int ci,int width,int height,int[] pixels,float[] depth,int base){
        int a=ai*3,b=bi*3,c=ci*3;double ax=p[a],ay=p[a+1],bx=p[b],by=p[b+1],cx=p[c],cy=p[c+1];
        double area=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);if(Math.abs(area)<1e-8)return;
        int minX=Math.max(0,(int)Math.floor(Math.min(ax,Math.min(bx,cx)))),maxX=Math.min(width-1,(int)Math.ceil(Math.max(ax,Math.max(bx,cx))));
        int minY=Math.max(0,(int)Math.floor(Math.min(ay,Math.min(by,cy)))),maxY=Math.min(height-1,(int)Math.ceil(Math.max(ay,Math.max(by,cy))));
        double ux=bx-ax,uy=by-ay,uz=p[b+2]-p[a+2],vx=cx-ax,vy=cy-ay,vz=p[c+2]-p[a+2];
        double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        double light=.3+.7*Math.abs((nx*.25-ny*.5+nz*.83)/Math.max(1e-9,length));
        int color=((int)(((base>>16)&255)*light)<<16)|((int)(((base>>8)&255)*light)<<8)|(int)((base&255)*light);
        for(int y=minY;y<=maxY;y++)for(int x=minX;x<=maxX;x++){
            double u=((bx-x-.5)*(cy-y-.5)-(by-y-.5)*(cx-x-.5))/area;
            double v=((cx-x-.5)*(ay-y-.5)-(cy-y-.5)*(ax-x-.5))/area,w=1-u-v;
            if(u<0||v<0||w<0)continue;float z=(float)(u*p[a+2]+v*p[b+2]+w*p[c+2]);int i=y*width+x;
            if(z<depth[i]){depth[i]=z;pixels[i]=color;}
        }
    }
    private static final class Camera {
        final double x,y,z,scale,cy,sy,cp,sp;final int width,height;
        Camera(double[] b,int width,int height,double yaw,double pitch,double zoom){this.width=width;this.height=height;x=(b[0]+b[3])/2;y=(b[1]+b[4])/2;z=(b[2]+b[5])/2;
            double dx=b[3]-b[0],dy=b[4]-b[1],dz=b[5]-b[2];scale=Math.min(width,height)*.8/Math.max(.01,Math.sqrt(dx*dx+dy*dy+dz*dz))*zoom;
            cy=Math.cos(Math.toRadians(yaw));sy=Math.sin(Math.toRadians(yaw));cp=Math.cos(Math.toRadians(pitch));sp=Math.sin(Math.toRadians(pitch));}
        double[] project(Vec3 p){double dx=p.x()-x,dy=p.y()-y,dz=p.z()-z,rx=cy*dx+sy*dz,rz=-sy*dx+cy*dz;
            return new double[]{width*.5+rx*scale,height*.5-(cp*dy-sp*rz)*scale,-(sp*dy+cp*rz)*scale};}
    }
}
