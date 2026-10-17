package cc.sirrus.ysmlib.cpm.java;

import java.util.*;

/** Small deterministic evaluator for the legacy CustomModel expression language. */
public final class CpmExpressionEvaluator {
    private CpmExpressionEvaluator() {}
    public static double evaluate(String expression, Map<String,Double> variables) {
        if (expression == null || expression.isBlank()) return 0;
        var parser = new Parser(expression, variables == null ? Map.of() : variables);
        double value = parser.expression();
        if (!parser.end()) throw new IllegalArgumentException("Trailing CPM expression input");
        return value;
    }
    public static boolean evaluateBoolean(String expression, Map<String,Double> variables) { return evaluate(expression, variables) != 0; }

    private static final class Parser {
        final String text; final Map<String,Double> vars; int at;
        Parser(String text, Map<String,Double> vars) { this.text=text;this.vars=vars; }
        boolean end() { skip(); return at==text.length(); }
        double expression() { return or(); }
        double or() { double a=and(); while(match("||")) { double rhs=and(); a=(truth(a)||truth(rhs))?1:0; } return a; }
        double and() { double a=compare(); while(match("&&")) { double rhs=compare(); a=(truth(a)&&truth(rhs))?1:0; } return a; }
        double compare() { double a=add(); for (;;) { if(match("=="))a=eq(a,add())?1:0; else if(match("!="))a=eq(a,add())?0:1; else if(match(">=")){double b=add();a=a>=b-1e-6?1:0;} else if(match("<=")){double b=add();a=a<=b+1e-6?1:0;} else if(match(">")){double b=add();a=a>b+1e-6?1:0;} else if(match("<")){double b=add();a=a<b-1e-6?1:0;} else return a; } }
        double add() { double a=mul(); for (;;) { if(match("+"))a+=mul(); else if(match("-"))a-=mul(); else return a; } }
        double mul() { double a=unary(); for (;;) { if(match("*"))a*=unary(); else if(match("/"))a/=unary(); else if(match("%"))a%=unary(); else return a; } }
        double unary() { if(match("+"))return unary(); if(match("-"))return -unary(); if(match("!"))return truth(unary())?0:1; return primary(); }
        double primary() {
            skip(); if(match("(")){double v=expression();require(")");return v;}
            if(at<text.length() && (Character.isDigit(text.charAt(at))||text.charAt(at)=='.')) return number();
            String name=identifier(); if(name.isEmpty()) throw new IllegalArgumentException("Invalid CPM expression near "+at);
            if(match("(")){if(name.equalsIgnoreCase("if")) return lazyIf(); var args=new ArrayList<Double>();if(!match(")")){do args.add(expression());while(match(","));require(")");}return function(name,args);}
            if(name.equalsIgnoreCase("true"))return 1;if(name.equalsIgnoreCase("false"))return 0;
            if (name.equalsIgnoreCase("pi") || name.equalsIgnoreCase("math.pi")) return Math.PI;
            var value = vars.get(name);
            if (value != null) return value;
            // CustomModel uses tv/tvl/var namespaces interchangeably for
            // values supplied by the host.  Preserve the qualified lookup,
            // then fall back to the unqualified key used by YSM inputs.
            int dot = name.indexOf('.');
            if (dot >= 0) {
                value = vars.get(name.substring(dot + 1));
                if (value != null) return value;
            }
            value = vars.get("tvl." + name);
            return value == null ? 0d : value;
        }
        double number(){int s=at;while(at<text.length()&&(Character.isDigit(text.charAt(at))||".eE+-".indexOf(text.charAt(at))>=0)){if((text.charAt(at)=='+'||text.charAt(at)=='-')&&at>s&&(text.charAt(at-1)!='e'&&text.charAt(at-1)!='E'))break;at++;}return Double.parseDouble(text.substring(s,at));}
        String identifier(){skip();int s=at;while(at<text.length()&&(Character.isLetterOrDigit(text.charAt(at))||text.charAt(at)=='_'||text.charAt(at)=='.'))at++;return text.substring(s,at);}
        double function(String n,List<Double>a){String k=n.toLowerCase(Locale.ROOT);if(k.startsWith("math."))k=k.substring(5);return switch(k){case "pi"->Math.PI;case "sin"->Math.sin(one(k,a));case "cos"->Math.cos(one(k,a));case "tan"->Math.tan(one(k,a));case "asin"->Math.asin(one(k,a));case "acos"->Math.acos(one(k,a));case "atan"->Math.atan(one(k,a));case "atan2"->Math.atan2(two(k,a,0),two(k,a,1));case "abs"->Math.abs(one(k,a));case "floor"->Math.floor(one(k,a));case "ceil"->Math.ceil(one(k,a));case "round"->Math.round(one(k,a));case "sqrt"->Math.sqrt(one(k,a));case "exp"->Math.exp(one(k,a));case "log"->Math.log(one(k,a));case "pow"->Math.pow(two(k,a,0),two(k,a,1));case "min"->a.stream().min(Double::compare).orElse(0d);case "max"->a.stream().max(Double::compare).orElse(0d);case "clamp"->three(k,a);case "lerp"->a.size()==3?a.get(0)+(a.get(1)-a.get(0))*a.get(2):0;case "fmod"->floorMod(k,a);case "mod"->two(k,a,0)%two(k,a,1);case "torad"->one(k,a)*Math.PI/180d;case "todeg"->one(k,a)*180d/Math.PI;case "frac" -> {double v=one(k,a);yield v-Math.floor(v);}case "signum"->Math.signum(one(k,a));case "between"->a.size()==3&&a.get(0)>=a.get(1)-1e-6&&a.get(0)<=a.get(2)+1e-6?1d:0d;case "equals"->a.size()==3&&Math.abs(a.get(0)-a.get(1))<=a.get(2)?1d:0d;case "in" -> {if(a.size()<2)yield 0d;double v=a.get(0);yield a.subList(1,a.size()).stream().anyMatch(x->Math.abs(x-v)<1e-6)?1d:0d;}case "random"->Math.random();case "time"->vars.getOrDefault("time",0d);case "if"->conditional(a);default->0;};}
        double conditional(List<Double>a){for(int i=0;i+1<a.size();i+=2)if(truth(a.get(i)))return a.get(i+1);return a.isEmpty()?0:a.get(a.size()-1);}
        /** CPM evaluates only the selected if() branch. Split raw arguments
         * first so malformed or stateful dead branches are never executed. */
        double lazyIf(){
            var parts=new ArrayList<String>(); int start=at, depth=0;
            for(;at<text.length();at++) { char c=text.charAt(at);
                if(c=='(') depth++; else if(c==')') { if(depth==0){ parts.add(text.substring(start,at)); at++; break; } depth--; }
                else if(c==',' && depth==0){ parts.add(text.substring(start,at)); start=at+1; }
            }
            if(parts.isEmpty()) return 0;
            for(int i=0;i+1<parts.size();i+=2) if(truth(evaluate(parts.get(i),vars))) return evaluate(parts.get(i+1),vars);
            return parts.size()%2==1 ? evaluate(parts.get(parts.size()-1),vars) : 0;
        }
        double one(String n,List<Double>a){if(a.size()!=1)throw new IllegalArgumentException(n+" expects one argument");return a.get(0);}
        double two(String n,List<Double>a,int i){if(a.size()!=2)throw new IllegalArgumentException(n+" expects two arguments");return a.get(i);}
        double three(String n,List<Double>a){if(a.size()!=3)throw new IllegalArgumentException(n+" expects three arguments");return Math.max(a.get(1),Math.min(a.get(2),a.get(0)));}
        double floorMod(String n,List<Double>a){double x=two(n,a,0),y=two(n,a,1);return y==0?Double.NaN:x-y*Math.floor(x/y);}
        boolean match(String s){skip();if(text.startsWith(s,at)){at+=s.length();return true;}return false;}void require(String s){if(!match(s))throw new IllegalArgumentException("Expected "+s);}
        void skip(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}static boolean truth(double v){return v!=0&&!Double.isNaN(v);}static boolean eq(double a,double b){return Math.abs(a-b)<1e-6;}
    }
}
