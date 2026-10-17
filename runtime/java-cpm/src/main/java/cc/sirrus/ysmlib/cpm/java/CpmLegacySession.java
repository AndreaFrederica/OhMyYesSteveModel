package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Stateful legacy tickVars/variables session. One instance belongs to one model/preview. */
public final class CpmLegacySession {
    private final CpmDocument.Legacy source;
    private final Map<String,Double> values = new LinkedHashMap<>();
    private final Map<String,Double> previous = new LinkedHashMap<>();
    private final Map<String,Boolean> bools = new LinkedHashMap<>();
    public CpmLegacySession(CpmDocument.Legacy source) {
        this.source=Objects.requireNonNull(source);
        var tick=object(source.source().fields().get("tickVars"));
        if(tick!=null) for(var e:tick.fields().entrySet()) {
            var a=array(e.getValue()); if(a==null||a.values().size()<2) continue;
            boolean bool=a.values().get(0) instanceof CpmValue.StringValue s
                    && (s.value().equalsIgnoreCase("boolean") || s.value().equalsIgnoreCase("bool"));
            if(bool) bools.put(e.getKey(), truth(a.values().get(1),false)); else values.put(e.getKey(),number(a.values().get(1),0,values));
        }
    }
    public Map<String,Double> tick(Map<String,Double> inputs) {
        var context=new LinkedHashMap<String,Double>();if(inputs!=null)context.putAll(inputs);values.forEach(context::put);bools.forEach((k,v)->context.put(k,v?1d:0d));
        values.forEach((k,v)->{ context.put("tvl."+k,previous.getOrDefault(k,v)); context.put("tvc."+k,v); context.put("tvp."+k,v); });
        bools.forEach((k,v)->{ double current=v?1d:0d; context.put("tvl."+k,previous.getOrDefault(k,current)); context.put("tvc."+k,current); context.put("tvp."+k,current); });
        var vars=object(source.source().fields().get("variables"));if(vars!=null) for(var e:vars.fields().entrySet()) context.put(e.getKey(),number(e.getValue(),0,context));
        var tick=object(source.source().fields().get("tickVars"));if(tick!=null) for(var e:tick.fields().entrySet()) {var a=array(e.getValue());if(a==null||a.values().size()<3)continue;double old=bools.containsKey(e.getKey())?(bools.get(e.getKey())?1d:0d):values.getOrDefault(e.getKey(),0d);previous.put(e.getKey(),old);var v=number(a.values().get(2),0,context);if(bools.containsKey(e.getKey())) { bools.put(e.getKey(),v!=0); context.put(e.getKey(),v!=0?1d:0d); } else { values.put(e.getKey(),v); context.put(e.getKey(),v); } context.put("tvl."+e.getKey(),old); context.put("tvc."+e.getKey(),v); context.put("tvp."+e.getKey(),v); }
        values.forEach(context::put);bools.forEach((k,v)->context.put(k,v?1d:0d));return Map.copyOf(context);
    }
    public Map<String,Double> values(){var out=new LinkedHashMap<>(values);bools.forEach((k,v)->out.put(k,v?1d:0d));return Map.copyOf(out);}
    private static CpmValue.ObjectValue object(CpmValue v){return v instanceof CpmValue.ObjectValue o?o:null;}
    private static CpmValue.ArrayValue array(CpmValue v){return v instanceof CpmValue.ArrayValue a?a:null;}
    private static double number(CpmValue v,double fallback,Map<String,Double> c){if(v instanceof CpmValue.NumberValue n)return Double.parseDouble(n.value());if(v instanceof CpmValue.BooleanValue b)return b.value()?1:0;if(v instanceof CpmValue.StringValue s)try{return CpmExpressionEvaluator.evaluate(s.value(),c);}catch(RuntimeException ignored){}return fallback;}
    private static boolean truth(CpmValue v,boolean fallback){if(v instanceof CpmValue.BooleanValue b)return b.value();if(v instanceof CpmValue.NumberValue n)return Double.parseDouble(n.value())!=0;if(v instanceof CpmValue.StringValue s)return Boolean.parseBoolean(s.value());return fallback;}
}
