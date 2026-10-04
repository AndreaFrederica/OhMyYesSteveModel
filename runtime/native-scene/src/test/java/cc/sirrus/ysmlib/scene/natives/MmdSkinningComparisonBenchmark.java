package cc.sirrus.ysmlib.scene.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.physics.natives.NativePhysicsProvider;
import java.nio.file.*;
import java.util.*;

/** Follow-up for noisy/regressing cases: warmed modes interleaved in deterministic random order. */
public final class MmdSkinningComparisonBenchmark {
    private static volatile Object sink;
    private static double percentile(long[] values,double q){return values[(int)Math.ceil(values.length*q)-1]/1e6;}
    public static void main(String[] args)throws Exception {
        var root=Path.of(System.getProperty("ysm.mmd.benchmark.corpus","D:/Projects/ysm/vrc-mmd"));List<Path> models,motions;
        try(var files=Files.walk(root)){var all=files.filter(Files::isRegularFile).sorted().toList();models=new ArrayList<>(all.stream().filter(p->p.toString().endsWith(".pmx")).toList());motions=all.stream().filter(p->p.toString().endsWith(".vmd")).toList();}
        String extra=System.getProperty("ysm.mmd.benchmark.extraCorpus","");
        if(!extra.isBlank())try(var files=Files.walk(Path.of(extra))){models.addAll(files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().equals("vertin.pmx")).toList());}
        AnimationClip clip=null;for(var path:motions){var vmd=new VmdReader().read(new ByteData(Files.readAllBytes(path)),ReadLimits.DEFAULT);if(!vmd.bones().isEmpty()){clip=new VmdAnimation().compile(vmd).clip();break;}}
        if(clip==null)throw new IllegalArgumentException("No motion");
        var physics=new NativePhysicsProvider(Path.of(System.getProperty("ysm.native.physics.library")));var library=Path.of(System.getProperty("ysm.native.skinning.library"));
        var modes=List.<DeformationProvider>of(new JavaDeformationProvider(),new NativeDeformationProvider(library,false),new NativeDeformationProvider(library,true));
        int warmup=120,samples=180;var rows=new ArrayList<String>();rows.add("model,physics,mode,instances,warmup,samples,p50Ms,p95Ms,p99Ms");
        var random=new Random(20261004);
        for(var path:models){var model=new PmxReader().read(new ByteData(Files.readAllBytes(path)),ReadLimits.DEFAULT);
            for(boolean enabled:new boolean[]{false,true}){
                var players=new ArrayList<MmdPlayer>();try{
                    for(var mode:modes)players.add(MmdPlayer.packed(model,clip,physics,MmdPlayback.Settings.preview().withPhysics(enabled),Map.of(),mode));
                    for(int i=1;i<=warmup;i++)for(var player:players)sink=player.seek(i/60d);
                    var times=new long[3][samples];var order=new ArrayList<>(List.of(0,1,2));
                    for(int i=0;i<samples;i++){Collections.shuffle(order,random);double time=(warmup+i+1)/60d;
                        for(int mode:order){long start=System.nanoTime();sink=players.get(mode).seek(time);times[mode][i]=System.nanoTime()-start;}}
                    for(int mode=0;mode<3;mode++){Arrays.sort(times[mode]);String row=String.format(Locale.ROOT,"\"%s\",%s,%s,1,%d,%d,%.4f,%.4f,%.4f",path.getFileName(),enabled,modes.get(mode).id(),warmup,samples,percentile(times[mode],.5),percentile(times[mode],.95),percentile(times[mode],.99));rows.add(row);System.out.println(row);}
                }finally{for(var player:players)player.close();}
            }
        }
        Path output=Path.of(args[0]);Files.createDirectories(output.getParent());Files.write(output,rows);
    }
}
