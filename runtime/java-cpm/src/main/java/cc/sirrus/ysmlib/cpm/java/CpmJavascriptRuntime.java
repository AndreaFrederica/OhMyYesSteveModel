package cc.sirrus.ysmlib.cpm.java;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.*;

/** Sandboxed CPM animation.js bridge. Only explicit model/entity proxies are visible to JS. */
public final class CpmJavascriptRuntime implements AutoCloseable {
    public static final int MAX_SOURCE_CHARS = 1_000_000;
    // The 1.16 interpreter executes sizeable author scripts (some are 10k+
    // characters) in the fallback JVM runtime.  Keep a finite guard while
    // allowing ordinary scripts to finish under interpreter-only Graal.
    public static final long MAX_EXECUTION_MILLIS = 250;
    private final Context context;
    private final Map<String,Bone> bones;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"cpm-script");t.setDaemon(true);return t;});
    private final Value init, update, tick;
    private boolean closed;

    public CpmJavascriptRuntime(String script, Map<String,Bone> bones) {
        if (script == null || script.length() > MAX_SOURCE_CHARS) throw new IllegalArgumentException("CPM script is too large");
        this.bones = new LinkedHashMap<>(Objects.requireNonNull(bones));
        context = Context.newBuilder("js").allowHostAccess(HostAccess.NONE).allowHostClassLookup(s -> false)
                .allowIO(false).allowNativeAccess(false).allowCreateThread(false).build();
        try {
            context.eval(Source.newBuilder("js", script, "cpm-animation.js").cached(false).buildLiteral());
            var bindings=context.getBindings("js"); init=member(bindings,"init"); update=member(bindings,"update"); tick=member(bindings,"tick");
        } catch (RuntimeException error) { context.close(true); throw error; }
    }
    public void init(double age) { init(age, Map.of()); }
    public void init(double age, Map<String,Double> inputs) { call(init,age,inputs); }
    /** Render phase; CPM invokes update for every pose evaluation. */
    public void update(double age, Map<String,Double> inputs) { call(update,age,inputs); }
    public void tick(double age) { tick(age, Map.of()); }
    public void tick(double age, Map<String,Double> inputs) { call(tick,age,inputs); }
    /** Host adapter supplies registry names without exposing Minecraft classes to Lib. */
    private static Value member(Value object, String name) {
        return object.hasMember(name) ? object.getMember(name) : null;
    }
    private void call(Value fn,double age, Map<String,Double> inputs) {
        ensureOpen();if(fn==null||!fn.canExecute())return;
        var safeInputs = inputs == null ? Map.<String,Double>of() : Map.copyOf(inputs);
        var entityValues = new LinkedHashMap<String,Object>();
        entityValues.put("getAge", (ProxyExecutable)args->age);
        entityValues.put("getInput", (ProxyExecutable)args->args.length == 1
                ? safeInputs.getOrDefault(args[0].asString(), 0d) : 0d);
        for (var key : List.of("getHealth", "getMaxHealth", "getFoodLevel", "getSaturationLevel", "getHurtTime", "getHeadYaw", "getHeadPitch",
                "getBodyYaw", "getLimbSwing", "getLimbSwingAmount", "getAnimPosition", "getAnimSpeed",
                "getPartial", "getScale")) {
            entityValues.put(key, (ProxyExecutable)args -> safeInputs.getOrDefault(inputKey(key), 0d));
        }
        entityValues.put("getPose", (ProxyExecutable)args -> poseName(safeInputs));
        for (var method : List.of("isAlive", "isBurning", "isGlowing", "isInLava", "isInvisible", "isSneaking",
                "isCrouching", "isRiding", "isSprinting", "isHurt", "isClimbing", "isOnGround", "isWet", "isInWater", "isFirstPerson", "isCustomKeyDown")) {
            entityValues.put(method, (ProxyExecutable)args -> booleanInput(safeInputs, inputKey(method, args)));
        }
        for (var method : List.of("getSwingProgress", "getMotionX", "getMotionY", "getMotionZ", "getPosY")) {
            entityValues.put(method, (ProxyExecutable)args -> safeInputs.getOrDefault(inputKey(method), 0d));
        }
        entityValues.put("getRightHandItem", (ProxyExecutable)args -> itemProxy(safeInputs, "right_hand_empty"));
        entityValues.put("getLeftHandItem", (ProxyExecutable)args -> itemProxy(safeInputs, "left_hand_empty"));
        entityValues.put("getMainHandItem", (ProxyExecutable)args -> itemProxy(safeInputs, "main_hand_empty"));
        entityValues.put("getOffHandItem", (ProxyExecutable)args -> itemProxy(safeInputs, "off_hand_empty"));
        entityValues.put("getHelmetItem", (ProxyExecutable)args -> itemProxy(safeInputs, "helmet_empty"));
        entityValues.put("getChestplateItem", (ProxyExecutable)args -> itemProxy(safeInputs, "chestplate_empty"));
        entityValues.put("getLeggingsItem", (ProxyExecutable)args -> itemProxy(safeInputs, "leggings_empty"));
        entityValues.put("getBootsItem", (ProxyExecutable)args -> itemProxy(safeInputs, "boots_empty"));
        entityValues.put("getInventoryItem", (ProxyExecutable)args -> itemProxy(safeInputs, "inventory_" + (args.length == 1 ? args[0].asInt() : -1) + "_empty"));
        var entity=ProxyObject.fromMap(entityValues);
        var modelValues = new LinkedHashMap<String,Object>();
        // ScriptModel.isFirstPerson() is separate from ScriptEntity in the
        // upstream API (it reflects the renderer pass, not entity state).
        modelValues.put("isFirstPerson", (ProxyExecutable) args -> booleanInput(safeInputs, "is_first_person"));
        modelValues.put("setTexture", (ProxyExecutable) args -> null);
        modelValues.put("getParticle", (ProxyExecutable) args -> particleProxy());
        modelValues.put("getBone",(ProxyExecutable)args->{
            if(args.length!=1)return null;
            var key=args[0].asString();
            // CPM's renderer exposes built-in equipment bones even when a
            // model does not use that slot.  A no-op virtual bone preserves
            // upstream scripts such as setScale(builtin_item_right...) while
            // the host simply ignores it when no converted bone exists.
            return boneProxy(bones.computeIfAbsent(key, ignored -> new Bone()));
        });
        var model=ProxyObject.fromMap(modelValues);
        Future<?> task=executor.submit(()->fn.execute(entity,model));
        try { task.get(MAX_EXECUTION_MILLIS,TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { task.cancel(true); close(); throw new IllegalStateException("CPM script execution timed out",timeout); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); close(); throw new IllegalStateException("CPM script interrupted",interrupted); }
        catch (ExecutionException failed) {
            var cause = failed.getCause();
            throw new IllegalArgumentException("CPM script failed: "
                    + (cause == null ? "unknown error" : String.valueOf(cause.getMessage())), cause);
        }
    }
    private static ProxyObject boneProxy(Bone bone) {
        if(bone==null)return null;
        var values=new LinkedHashMap<String,Object>();
        values.put("getPositionX",(ProxyExecutable)a->bone.position[0]);
        values.put("getPositionY",(ProxyExecutable)a->bone.position[1]);
        values.put("getPositionZ",(ProxyExecutable)a->bone.position[2]);
        values.put("getRotationX",(ProxyExecutable)a->bone.rotation[0]);
        values.put("getRotationY",(ProxyExecutable)a->bone.rotation[1]);
        values.put("getRotationZ",(ProxyExecutable)a->bone.rotation[2]);
        values.put("getScaleX",(ProxyExecutable)a->bone.scale[0]);
        values.put("getScaleY",(ProxyExecutable)a->bone.scale[1]);
        values.put("getScaleZ",(ProxyExecutable)a->bone.scale[2]);
        values.put("isVisible",(ProxyExecutable)a->bone.visible);
        values.put("setPositionX",(ProxyExecutable)a->{bone.position[0]=a[0].asDouble();return null;});
        values.put("setPositionY",(ProxyExecutable)a->{bone.position[1]=a[0].asDouble();return null;});
        values.put("setPositionZ",(ProxyExecutable)a->{bone.position[2]=a[0].asDouble();return null;});
        values.put("setPosition",(ProxyExecutable)a->{for(int i=0;i<3;i++)bone.position[i]=a[i].asDouble();return null;});
        values.put("setRotationX",(ProxyExecutable)a->{bone.rotation[0]=a[0].asDouble();return null;});
        values.put("setRotationY",(ProxyExecutable)a->{bone.rotation[1]=a[0].asDouble();return null;});
        values.put("setRotationZ",(ProxyExecutable)a->{bone.rotation[2]=a[0].asDouble();return null;});
        values.put("setRotation",(ProxyExecutable)a->{for(int i=0;i<3;i++)bone.rotation[i]=a[i].asDouble();return null;});
        values.put("addRotation",(ProxyExecutable)a->{for(int i=0;i<3;i++)bone.rotation[i]+=a[i].asDouble();return null;});
        values.put("addPosition",(ProxyExecutable)a->{for(int i=0;i<3;i++)bone.position[i]+=a[i].asDouble();return null;});
        // CPM exposes both setScale(value) and setScale(x, y, z).
        values.put("setScale",(ProxyExecutable)a->{
            if (a.length == 1) {
                double value = a[0].asDouble();
                Arrays.fill(bone.scale, value);
            } else {
                for (int i=0;i<3 && i<a.length;i++) bone.scale[i]=a[i].asDouble();
            }
            return null;
        });
        values.put("setScaleX",(ProxyExecutable)a->{bone.scale[0]=a[0].asDouble();return null;});
        values.put("setScaleY",(ProxyExecutable)a->{bone.scale[1]=a[0].asDouble();return null;});
        values.put("setScaleZ",(ProxyExecutable)a->{bone.scale[2]=a[0].asDouble();return null;});
        values.put("setVisible",(ProxyExecutable)a->{bone.visible=a[0].asBoolean();return null;});
        values.put("setColor",(ProxyExecutable)a->{for(int i=0;i<Math.min(3,a.length);i++)bone.color[i]=a[i].asDouble();if(a.length>3)bone.alpha=a[3].asDouble();return null;});
        values.put("getColorR",(ProxyExecutable)a->bone.color[0]);
        values.put("getColorG",(ProxyExecutable)a->bone.color[1]);
        values.put("getColorB",(ProxyExecutable)a->bone.color[2]);
        values.put("getColorA",(ProxyExecutable)a->bone.alpha);
        values.put("setColorR",(ProxyExecutable)a->{bone.color[0]=a[0].asDouble();return null;});
        values.put("setColorG",(ProxyExecutable)a->{bone.color[1]=a[0].asDouble();return null;});
        values.put("setColorB",(ProxyExecutable)a->{bone.color[2]=a[0].asDouble();return null;});
        values.put("setAlpha",(ProxyExecutable)a->{bone.alpha=a[0].asDouble();return null;});
        values.put("setColorA",(ProxyExecutable)a->{bone.alpha=a[0].asDouble();return null;});
        values.put("setEmissive",(ProxyExecutable)a->{bone.emissive=a[0].asBoolean();return null;});
        // CPM's physicalize(frequency, damping, gravity, friction, collision)
        // is retained as per-bone state.  The provider performs the actual
        // deterministic integration after the script has authored its pose.
        values.put("physicalize",(ProxyExecutable)a->{
            if (a.length >= 5) for (int i=0;i<5;i++) bone.physics[i]=a[i].asDouble();
            bone.physicsEnabled=true; return null;
        });
        return ProxyObject.fromMap(values);
    }
    private static String inputKey(String method) { return inputKey(method, new Value[0]); }
    private static String inputKey(String method, Value[] args) {
        return switch (method) {
            case "getMaxHealth" -> "max_health"; case "getFoodLevel" -> "food_level";
            case "getSaturationLevel" -> "saturation_level"; case "getHurtTime" -> "hurt_time"; case "getHeadYaw" -> "head_yaw";
            case "getHeadPitch" -> "head_pitch"; case "getBodyYaw" -> "body_yaw";
            case "getLimbSwing" -> "limb_swing"; case "getLimbSwingAmount" -> "limb_swing_amount";
            case "getAnimPosition" -> "anim_position"; case "getAnimSpeed" -> "anim_speed";
            case "getPartial" -> "partial"; case "isAlive" -> "is_alive"; case "isBurning" -> "is_burning";
            case "isGlowing" -> "is_glowing"; case "isInLava" -> "is_in_lava"; case "isInvisible" -> "is_invisible";
            case "isSneaking" -> "is_sneaking"; case "isCrouching" -> "is_crouching";
            case "isRiding" -> "is_riding"; case "isSprinting" -> "is_sprinting";
            case "isHurt" -> "is_hurt"; case "isClimbing" -> "is_climbing";
            case "isOnGround" -> "is_on_ground"; case "isWet" -> "is_wet";
            case "isInWater" -> "is_in_water"; case "isFirstPerson" -> "is_first_person";
            case "isCustomKeyDown" -> "custom_key_down_" + (args.length == 1 ? args[0].asInt() : 0); case "getSwingProgress" -> "swing_progress";
            case "getMotionX" -> "motion_x"; case "getMotionY" -> "motion_y";
            case "getMotionZ" -> "motion_z"; case "getPosY" -> "pos_y";
            default -> method.substring(3).toLowerCase(Locale.ROOT);
        };
    }
    private static boolean booleanInput(Map<String,Double> inputs, String key) {
        return inputs.getOrDefault(key, 0d) != 0d;
    }
    private static String poseName(Map<String,Double> inputs) {
        if (booleanInput(inputs, "pose_sleeping")) return "sleeping";
        if (booleanInput(inputs, "pose_swimming")) return "swimming";
        if (booleanInput(inputs, "pose_fall_flying")) return "elytra_flying";
        if (booleanInput(inputs, "is_crouching")) return "crouching";
        if (booleanInput(inputs, "pose_spin_attack")) return "trident_attacking";
        return "standing";
    }
    private static ProxyObject emptyItem(Map<String,Double> inputs, String key) {
        return itemProxy(inputs, key);
    }
    private static ProxyObject itemProxy(Map<String,Double> inputs, String key) {
        boolean empty = inputs.getOrDefault(key, 1d) != 0d;
        String idKey = switch (key) {
            case "left_hand_empty" -> "left_hand_item_id";
            case "right_hand_empty" -> "right_hand_item_id";
            case "main_hand_empty" -> "main_hand_item_id";
            case "off_hand_empty" -> "off_hand_item_id";
            default -> key.endsWith("_empty") ? key.substring(0, key.length() - "_empty".length()) + "_id" : key + "_id";
        };
        return ProxyObject.fromMap(Map.of("isEmpty", (ProxyExecutable)a -> empty,
                "getId", (ProxyExecutable)a -> inputs.getOrDefault(idKey, 0d),
                "getItem", (ProxyExecutable)a -> cc.sirrus.ysmlib.cpm.CpmItemNames.lookup(Math.round(inputs.getOrDefault(idKey, 0d)))));
    }
    private static ProxyObject particleProxy() {
        var density = new double[]{0d};
        return ProxyObject.fromMap(Map.of("setDensity", (ProxyExecutable)a -> {
                    if (a.length > 0) density[0] = a[0].asDouble(); return null;
                }, "getDensity", (ProxyExecutable)a -> density[0]));
    }
    public Map<String,Bone> snapshot(){var out=new LinkedHashMap<String,Bone>();bones.forEach((k,v)->out.put(k,v.copy()));return Map.copyOf(out);}
    private void ensureOpen(){if(closed)throw new IllegalStateException("CPM script runtime is closed");}
    @Override public void close(){if(!closed){closed=true;executor.shutdownNow();context.close(true);}}
    public static final class Bone {
        private final double[] position={0,0,0},rotation={0,0,0},scale={1,1,1},color={1,1,1};
        private boolean visible=true,emissive; private double alpha=1;
        /**
         * Remake geometry supplies a rest rotation for every bone.  CPM's
         * script API edits the current absolute rotation, so a setter must
         * start from that authored value rather than from zero. Position is
         * seeded with ModelBone's authored local position so scripts observe
         * the same absolute value as CPM; the host subtracts this rest value
         * when it emits a render pose.
         */
        Bone(double positionX, double positionY, double positionZ,
             double rotationX, double rotationY, double rotationZ) {
            position[0] = positionX;
            position[1] = positionY;
            position[2] = positionZ;
            rotation[0] = rotationX;
            rotation[1] = rotationY;
            rotation[2] = rotationZ;
        }
        public Bone() {}
        public double[] position(){return position.clone();}public double[] rotation(){return rotation.clone();}public double[] scale(){return scale.clone();}public boolean visible(){return visible;}
        public double[] color(){return color.clone();}public double alpha(){return alpha;}public boolean emissive(){return emissive;}
        private final double[] physics={0,0,0,0,0}; private boolean physicsEnabled;
        public double[] physics(){return physics.clone();} public boolean physicsEnabled(){return physicsEnabled;}
        /** Refreshes a virtual host bone from the live renderer pose before a
         * script update.  The same object is deliberately shared by aliases
         * such as head/Head so getBone() observes the host and writes back to
         * it instead of creating an isolated script-only bone. */
        void setHostPose(double[] position, double[] rotation, double[] scale,
                         boolean visible, double[] color, double alpha, boolean emissive) {
            System.arraycopy(position, 0, this.position, 0, 3);
            System.arraycopy(rotation, 0, this.rotation, 0, 3);
            System.arraycopy(scale, 0, this.scale, 0, 3);
            System.arraycopy(color, 0, this.color, 0, 3);
            this.visible = visible; this.alpha = alpha; this.emissive = emissive;
        }
        private Bone copy(){var b=new Bone();System.arraycopy(position,0,b.position,0,3);System.arraycopy(rotation,0,b.rotation,0,3);System.arraycopy(scale,0,b.scale,0,3);System.arraycopy(color,0,b.color,0,3);System.arraycopy(physics,0,b.physics,0,5);b.physicsEnabled=physicsEnabled;b.visible=visible;b.alpha=alpha;b.emissive=emissive;return b;}
    }
}
