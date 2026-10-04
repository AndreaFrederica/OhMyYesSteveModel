package com.elfmcys.ysm.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.elfmcys.ysm.YesSteveModel;
import org.lwjgl.opengl.*;
import java.io.IOException;

/** Reusable render-owner scratch space for linear scene compositing into Minecraft's numeric sRGB RGBA8 target. */
public final class SceneDisplayBuffer implements AutoCloseable {
    /** Explicit host attachment identities; shader-pack MRTs and multisample targets need their own adapter. */
    public record Destination(int framebuffer,int color,int depth,int width,int height,boolean stencil) {
        public Destination {
            if(framebuffer<=0 || color<=0 || depth<=0 || width<1 || height<1) throw new IllegalArgumentException("Invalid scene display destination");
        }
    }
    private final long maxBytes;
    private int framebuffer,color,depth,program,vao,width,height,depthFormat;
    private boolean drawing,closed;
    public SceneDisplayBuffer(long maxBytes) {
        if(maxBytes<=0) throw new IllegalArgumentException("Invalid scene compositing budget");this.maxBytes=maxBytes;
    }

    /** On a source/draw exception, the destination color/depth/stencil are left untouched. */
    public void draw(Destination destination,Runnable linearDraw) throws IOException {
        RenderSystem.assertOnRenderThread();
        if(closed || drawing) throw new IllegalStateException("Scene display buffer is closed or already borrowed");
        if(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)!=destination.framebuffer())
            throw new IllegalArgumentException("Scene destination is not the current host framebuffer");
        // GL errors are sticky. A shader pack, GUI renderer, or another mod may
        // have left an unrelated error behind; it must not be attributed to
        // this pass. Errors generated below are still checked at each boundary.
        int inheritedErrors = drainErrors();
        if (inheritedErrors > 0) {
            YesSteveModel.LOGGER.debug("Discarded {} inherited GL error(s) before scene compositing", inheritedErrors);
        }
        drawing=true;
        try(var saved=new State()) {
            validate(destination);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,destination.depth());
            int format=GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            ensureStorage(destination,format);
            copyState();
            int mask=GL11C.GL_DEPTH_BUFFER_BIT|(destination.stencil()?GL11C.GL_STENCIL_BUFFER_BIT:0);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,destination.framebuffer());
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,framebuffer);
            GL30C.glBlitFramebuffer(0,0,width,height,0,0,width,height,mask,GL11C.GL_NEAREST);
            transfer(destination.color(),true);
            check("scene display decode");
            saved.restoreRaster();
            saved.restoreBindings();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);
            linearDraw.run();
            check("scene linear drawing");
            copyState();
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,destination.framebuffer());
            // The original channel mask applies to the final commit, as it would to a direct draw.
            saved.restoreColorMask();
            transfer(color,false);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,framebuffer);
            GL30C.glBlitFramebuffer(0,0,width,height,0,0,width,height,mask,GL11C.GL_NEAREST);
            check("scene display commit");
        } finally { drawing=false; }
    }
    private void validate(Destination destination) {
        if(GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0)!=GL30C.GL_COLOR_ATTACHMENT0)
            throw new IllegalArgumentException("Scene display adapter requires one numeric color target");
        for(int i=1;i<GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);i++)
            if(GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0+i)!=GL11C.GL_NONE)
                throw new IllegalArgumentException("Scene display adapter cannot composite a multiple-render-target pass");
        if(GL11C.glGetInteger(GL13C.GL_SAMPLES)>1)
            throw new IllegalArgumentException("Scene display adapter requires a non-multisampled destination");
        if(GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalArgumentException("Incomplete scene display destination");
        attachment(GL30C.GL_COLOR_ATTACHMENT0,destination.color());attachment(GL30C.GL_DEPTH_ATTACHMENT,destination.depth());
        if(destination.stencil()) attachment(GL30C.GL_STENCIL_ATTACHMENT,destination.depth());
        else if(GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_STENCIL_ATTACHMENT,GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11C.GL_NONE)
            throw new IllegalArgumentException("Unspecified scene stencil attachment");
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        for(int texture:new int[]{destination.color(),destination.depth()}) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);
            if(GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_WIDTH)!=destination.width()
                    || GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_HEIGHT)!=destination.height())
                throw new IllegalArgumentException("Scene display attachment dimensions differ");
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,destination.color());
        if(GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_INTERNAL_FORMAT)!=GL11C.GL_RGBA8)
            throw new IllegalArgumentException("Scene display adapter requires numeric RGBA8; use an explicit adapter for another working space");
    }
    private static void attachment(int point,int texture) {
        if(GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11C.GL_TEXTURE
                || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)!=texture
                || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL)!=0)
            throw new IllegalArgumentException("Scene display destination attachment changed");
    }
    private void ensureStorage(Destination destination,int format) throws IOException {
        boolean stencil=format==GL30C.GL_DEPTH24_STENCIL8 || format==GL30C.GL_DEPTH32F_STENCIL8;
        if(stencil!=destination.stencil()) throw new IllegalArgumentException("Scene depth/stencil format mismatch");
        long bytes=(long)destination.width()*destination.height()*(16+(format==GL30C.GL_DEPTH32F_STENCIL8?8:4));
        if(bytes>maxBytes) throw new IllegalArgumentException("Scene compositing buffer budget exceeded");
        if(program==0) initializeProgram();
        if(width==destination.width() && height==destination.height() && depthFormat==format) return;
        int newColor=0,newDepth=0,newFramebuffer=0;
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,0);
            newColor=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,newColor);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_RGBA32F,destination.width(),destination.height(),0,GL11C.GL_RGBA,GL11C.GL_FLOAT,0L);
            parameters();
            newDepth=GL11C.glGenTextures();GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,newDepth);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,format,destination.width(),destination.height(),0,
                    stencil?GL30C.GL_DEPTH_STENCIL:GL11C.GL_DEPTH_COMPONENT,
                    format==GL30C.GL_DEPTH32F_STENCIL8?GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV:stencil?GL30C.GL_UNSIGNED_INT_24_8:GL11C.GL_FLOAT,0L);
            parameters();
            newFramebuffer=GL30C.glGenFramebuffers();GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,newFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,newColor,0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,stencil?GL30C.GL_DEPTH_STENCIL_ATTACHMENT:GL30C.GL_DEPTH_ATTACHMENT,GL11C.GL_TEXTURE_2D,newDepth,0);
            if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Incomplete scene compositing buffer");
            check("scene compositing allocation");
        } catch(RuntimeException|Error failure) {
            if(newFramebuffer!=0) GL30C.glDeleteFramebuffers(newFramebuffer);
            if(newColor!=0) GL11C.glDeleteTextures(newColor);if(newDepth!=0) GL11C.glDeleteTextures(newDepth);throw failure;
        }
        releaseStorage();framebuffer=newFramebuffer;color=newColor;depth=newDepth;
        width=destination.width();height=destination.height();depthFormat=format;
    }
    private static void parameters() {
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MAG_FILTER,GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LEVEL,0);
    }
    private void initializeProgram() throws IOException {
        int vertex=0,fragment=0,candidate=0,candidateVao=0;
        try {
            vertex=compile(GL20C.GL_VERTEX_SHADER,"""
                    #version 150
                    void main(){vec2 p=gl_VertexID==0?vec2(-1,-1):gl_VertexID==1?vec2(3,-1):vec2(-1,3);gl_Position=vec4(p,0,1);}
                    """);
            fragment=compile(GL20C.GL_FRAGMENT_SHADER,"""
                    #version 150
                    uniform sampler2D Source;
                    uniform bool Decode;
                    out vec4 Color;
                    void main(){vec4 c=texelFetch(Source,ivec2(gl_FragCoord.xy),0);
                        for(int i=0;i<3;i++) c[i]=Decode?(c[i]<=0.04045?c[i]/12.92:pow((c[i]+0.055)/1.055,2.4)):
                            (c[i]<=0.0031308?12.92*c[i]:1.055*pow(c[i],1.0/2.4)-0.055);
                        Color=c;}
                    """);
            candidate=GL20C.glCreateProgram();GL20C.glAttachShader(candidate,vertex);GL20C.glAttachShader(candidate,fragment);
            GL30C.glBindFragDataLocation(candidate,0,"Color");GL20C.glLinkProgram(candidate);
            if(GL20C.glGetProgrami(candidate,GL20C.GL_LINK_STATUS)==0) throw new IOException(GL20C.glGetProgramInfoLog(candidate));
            candidateVao=GL30C.glGenVertexArrays();check("scene display shader creation");program=candidate;vao=candidateVao;
        } catch(Exception|Error failure) {
            if(candidate!=0) GL20C.glDeleteProgram(candidate);if(candidateVao!=0) GL30C.glDeleteVertexArrays(candidateVao);throw failure;
        } finally { if(vertex!=0) GL20C.glDeleteShader(vertex);if(fragment!=0) GL20C.glDeleteShader(fragment); }
    }
    private static int compile(int type,String source) throws IOException {
        int shader=GL20C.glCreateShader(type);GL20C.glShaderSource(shader,source);GL20C.glCompileShader(shader);
        if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==0) {
            String error=GL20C.glGetShaderInfoLog(shader);GL20C.glDeleteShader(shader);throw new IOException(error);
        }return shader;
    }
    private static void copyState() {
        for(int cap:new int[]{GL11C.GL_BLEND,GL11C.GL_CULL_FACE,GL11C.GL_DEPTH_TEST,GL11C.GL_STENCIL_TEST,GL11C.GL_SCISSOR_TEST,
                GL11C.GL_COLOR_LOGIC_OP,GL11C.GL_DITHER,GL30C.GL_RASTERIZER_DISCARD,GL30C.GL_FRAMEBUFFER_SRGB,
                GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE,GL13C.GL_SAMPLE_COVERAGE,GL11C.GL_POLYGON_OFFSET_FILL}) GL11C.glDisable(cap);
        GL11C.glDepthMask(false);GL11C.glColorMask(true,true,true,true);SceneRasterState.forceFillIfSupported();
        for(int i=0;i<GL11C.glGetInteger(GL30C.GL_MAX_CLIP_DISTANCES);i++) GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0+i);
    }
    private void transfer(int source,boolean decode) {
        GL11C.glViewport(0,0,width,height);GL20C.glUseProgram(program);GL30C.glBindVertexArray(vao);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,source);
        if(State.samplers()) GL33C.glBindSampler(0,0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"Source"),0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program,"Decode"),decode?1:0);GL11C.glDrawArrays(GL11C.GL_TRIANGLES,0,3);
    }
    private static void check(String operation) {
        int error=GL11C.glGetError();if(error!=GL11C.GL_NO_ERROR) throw new IllegalStateException(operation+": GL "+error);
    }
    private static int drainErrors() {
        int count = 0;
        // Bound the cleanup so a broken host cannot turn a render frame into an
        // unbounded loop. The first operation after this point is checked normally.
        while (count < 32 && GL11C.glGetError() != GL11C.GL_NO_ERROR) count++;
        return count;
    }
    private void releaseStorage() {
        if(framebuffer!=0) GL30C.glDeleteFramebuffers(framebuffer);
        if(color!=0) GL11C.glDeleteTextures(color);if(depth!=0) GL11C.glDeleteTextures(depth);
        framebuffer=color=depth=width=height=depthFormat=0;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;if(drawing) throw new IllegalStateException("Scene display buffer is borrowed");
        closed=true;releaseStorage();if(program!=0) GL20C.glDeleteProgram(program);if(vao!=0) GL30C.glDeleteVertexArrays(vao);program=vao=0;
    }
    private static final class State implements AutoCloseable {
        private final SceneRasterState raster=new SceneRasterState();
        private final int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),draw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int program=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),vao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        private final int unit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),unpack=GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int texture,sampler;
        private final int[] viewport=new int[4];
        private final byte[] colorMask=new byte[4];
        private final int[] caps={GL11C.GL_STENCIL_TEST,GL11C.GL_SCISSOR_TEST,GL11C.GL_COLOR_LOGIC_OP,GL11C.GL_DITHER,GL30C.GL_RASTERIZER_DISCARD,GL13C.GL_SAMPLE_COVERAGE};
        private final boolean[] enabled=new boolean[caps.length];
        private final boolean[] clips=new boolean[GL11C.glGetInteger(GL30C.GL_MAX_CLIP_DISTANCES)];
        State() {
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
            var mask=java.nio.ByteBuffer.allocateDirect(4);GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK,mask);mask.get(colorMask);
            for(int i=0;i<caps.length;i++) enabled[i]=GL11C.glIsEnabled(caps[i]);
            for(int i=0;i<clips.length;i++) clips[i]=GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0+i);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);texture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            sampler=samplers()?GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING):0;GL13C.glActiveTexture(unit);
        }
        static boolean samplers() { return GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects; }
        void restoreColorMask() { GL11C.glColorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0); }
        void restoreRaster() {
            raster.close();restoreColorMask();for(int i=0;i<caps.length;i++) SceneRasterState.enabled(caps[i],enabled[i]);
            for(int i=0;i<clips.length;i++) SceneRasterState.enabled(GL30C.GL_CLIP_DISTANCE0+i,clips[i]);
            GL11C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
        void restoreBindings() {
            GL20C.glUseProgram(program);GL30C.glBindVertexArray(vao);GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,unpack);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);
            if(samplers()) GL33C.glBindSampler(0,sampler);GL13C.glActiveTexture(unit);
        }
        @Override public void close() {
            restoreRaster();GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,draw);
            restoreBindings();
        }
    }
}
