package com.elfmcys.ysm.client.animation.molang;

import com.elfmcys.ysm.accessor.IArrowExtraInfo;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.molang.functions.*;
import com.elfmcys.ysm.client.animation.molang.variable.FirstPersonModHideVariable;
import com.elfmcys.ysm.client.animation.molang.variable.LadderFacingVariable;
import com.elfmcys.ysm.client.animation.molang.variable.MoveInputVariable;
import com.elfmcys.ysm.client.animation.molang.variable.TextureNameVariable;
import com.elfmcys.ysm.client.compat.aquaacrobatics.AACompat;
import com.elfmcys.ysm.client.compat.backport.BackportCompat;
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.binding.ContextBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.util.ComponentUtils;
import com.elfmcys.ysm.util.EquipmentUtil;
import com.elfmcys.ysm.util.LazyValue;
import com.elfmcys.ysm.util.PersonView;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.item.EntityExpBottle;
import net.minecraft.entity.passive.EntityParrot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.*;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

public class YSMBinding extends ContextBinding {
    public static final LazyValue<YSMBinding> INSTANCE = new LazyValue<>(YSMBinding::new);

    private YSMBinding() {
        function("dump_equipped_item", new DumpEquippedItem());
        function("dump_relative_block", new DumpRelativeBlock());
        var("dump_mods", YSMBinding::dumpMods);
        entityVar("dump_effects", YSMBinding::dumpEffects);
        entityVar("dump_biome", YSMBinding::dumpBiome);

        function("mod_version", new ModVersion());
        function("equipped_enchantment_level", new EquippedEnchantmentLevel());
        function("effect_level", new EffectLevel());
        function("relative_block_name", new RelativeBlockName());
        function("relative_block_name_any", new RelativeBlockNameAny());

        function("bone_rot", new BoneRotation());
        function("bone_pos", new BonePosition());
        function("bone_scale", new BoneScale());
        function("bone_pivot_abs", new BoneAbsolutePivot());
        function("bone_color", new BoneRenderFunction.Color());
        function("bone_transparency", new BoneRenderFunction.Transparency());
        function("bone_glow", new BoneRenderFunction.Glow());

        var("head_yaw", ctx -> ctx.data().netHeadYaw);
        var("head_pitch", ctx -> ctx.data().headPitch);
        var("weather", ctx -> getWeather(ctx.level()));
        var("dimension_name", ctx -> ctx.level().provider.getDimensionType().getName());
        var("fps", ctx -> Minecraft.getDebugFPS());
        var("time_delta", ctx -> ctx.animatableEntity().getStateTracker().getRenderTickDelta() / 20);

        entityVar("ground_speed2", YSMBinding::getGroundSpeed2);

        entityVar("input_vertical", MoveInputVariable::getVertical);
        entityVar("input_horizontal", MoveInputVariable::getHorizontal);
        entityVar("person_view", PersonView::getPersonView);
        entityVar("rendering_in_paperdoll", ctx -> ctx.animationEvent().getRenderContext().paperDoll());
        entityVar("rendering_in_inventory", PersonView::isInInventory);
        entityVar("block_light", ctx -> ctx.level().getLightFor(EnumSkyBlock.BLOCK, new BlockPos(ctx.entity())));
        entityVar("sky_light", ctx -> ctx.level().getLightFor(EnumSkyBlock.SKY, new BlockPos(ctx.entity())));

        entityVar("is_passenger", ctx -> ctx.entity().isRiding());
        entityVar("is_sleep", ctx -> ctx.entity() instanceof EntityLivingBase living && living.isPlayerSleeping());
        entityVar("is_sneak", ctx -> ctx.entity().onGround && AACompat.isCrouchingPose(ctx.entity()));
        entityVar("biome_category", ctx -> getBiomeCategory(ctx.entity()));
        entityVar("is_open_air", ctx -> isOpenAir(ctx.entity()));
        entityVar("eye_in_water", ctx -> ctx.entity().isInsideOfMaterial(Material.WATER));
        entityVar("frozen_ticks", ctx -> 0);
        entityVar("air_supply", ctx -> ctx.entity().getAir());
        entityVar("delta_movement_length", YSMBinding::getDeltaMovementLength);

        livingEntityVar("has_helmet", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.HEAD));
        livingEntityVar("has_chest_plate", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.CHEST));
        livingEntityVar("has_leggings", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.LEGS));
        livingEntityVar("has_boots", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.FEET));
        livingEntityVar("has_mainhand", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.MAINHAND));
        livingEntityVar("has_offhand", ctx -> getSlotValue(ctx.entity(), EntityEquipmentSlot.OFFHAND));
        livingEntityVar("has_elytra", ctx -> !EquipmentUtil.getEquippedElytraItem(ctx.entity()).isEmpty());
        livingEntityVar("is_riptide", ctx -> BackportCompat.isAutoSpinAttack(ctx.entity()));
        livingEntityVar("armor_value", ctx -> ctx.entity().getTotalArmorValue());
        livingEntityVar("hurt_time", ctx -> ctx.entity().hurtTime);
        livingEntityVar("is_close_eyes", ctx -> getEyeCloseState(ctx.animationEvent(), ctx.entity()));
        livingEntityVar("on_ladder", ctx -> ctx.entity().isOnLadder());
        livingEntityVar("ladder_facing", new LadderFacingVariable());
        livingEntityVar("arrow_count", ctx -> ctx.entity().getArrowCountInEntity());
        livingEntityVar("stinger_count", ctx -> 0);
        livingEntityVar("entity_type", YSMBinding::getEntityType);
        livingEntityVar("is_player", ctx -> "player".equals(getEntityType(ctx)));
        livingEntityVar("is_maid", ctx -> "maid".equals(getEntityType(ctx)));
        // 为了兼容其他模组，只有玩家能返回这个值，其他都是满值（20）
        livingEntityVar("food_level", YSMBinding::getFoodLevel);

        livingEntityVar("xxa", YSMBinding::getXxa);
        livingEntityVar("yya", YSMBinding::getYya);
        livingEntityVar("zza", YSMBinding::getZza);

        livingEntityVar("mainhand_charged_crossbow", ctx -> isChargedCrossbow(ctx, EnumHand.MAIN_HAND));
        livingEntityVar("offhand_charged_crossbow", ctx -> isChargedCrossbow(ctx, EnumHand.OFF_HAND));
        livingEntityVar("is_fishing", YSMBinding::isFishing);

        livingEntityVar("swinging", ctx -> ctx.entity().isSwingInProgress);
        livingEntityVar("swing_time", ctx -> ctx.entity().swingProgressInt);
        livingEntityVar("swinging_arm", ctx -> {
            EnumHand hand = ctx.entity().swingingHand;
            return hand == EnumHand.MAIN_HAND ? 0 : 1;
        });
        livingEntityVar("attack_time", ctx -> ctx.entity().getSwingProgress(ctx.animationEvent().getPartialTick()));

        playerVar("texture_name", new TextureNameVariable());
        playerVar("first_person_mod_hide", new FirstPersonModHideVariable());
        playerVar("has_left_shoulder_parrot", ctx -> hasParrot(ctx.entity(), true));
        playerVar("has_right_shoulder_parrot", ctx -> hasParrot(ctx.entity(), false));
        playerVar("left_shoulder_parrot_variant", ctx -> getParrotVariant(ctx.entity(), true));
        playerVar("right_shoulder_parrot_variant", ctx -> getParrotVariant(ctx.entity(), false));

        playerVar("attack_damage", ctx -> ctx.entity().getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getAttributeValue());
        playerVar("attack_speed", ctx -> ctx.entity().getEntityAttribute(SharedMonsterAttributes.ATTACK_SPEED).getAttributeValue());
        playerVar("attack_knockback", ctx -> 0.0);
        playerVar("movement_speed", ctx -> ctx.entity().getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue());
        playerVar("knockback_resistance", ctx -> ctx.entity().getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).getAttributeValue());
        playerVar("luck", ctx -> ctx.entity().getEntityAttribute(SharedMonsterAttributes.LUCK).getAttributeValue());

        playerVar("block_reach", ctx -> ctx.entity().getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue());
        playerVar("entity_reach", ctx -> 0.0);
        playerVar("swim_speed", ctx -> ctx.entity().getEntityAttribute(EntityLivingBase.SWIM_SPEED).getAttributeValue());
        playerVar("entity_gravity", ctx -> 0.0);
        playerVar("step_height_addition", ctx -> 0.0);
        playerVar("nametag_distance", ctx -> 0.0);
        playerVar("in_shield_block_cooldown", YSMBinding::inShieldBlockCooldown);

        clientPlayerVar("elytra_rot_x", ctx -> Math.toDegrees(ctx.entity().rotateElytraX));
        clientPlayerVar("elytra_rot_y", ctx -> Math.toDegrees(ctx.entity().rotateElytraY));
        clientPlayerVar("elytra_rot_z", ctx -> Math.toDegrees(ctx.entity().rotateElytraZ));

        localPlayerVar("hit_target_id", YSMBinding::getHitId);
        localPlayerVar("hit_target_type", YSMBinding::getHitType);

        function("first_order", new FirstOrderFunction());
        function("second_order", new SecondOrderFunction());
        function("particle", new ParticleFunction(false));
        function("abs_particle", new ParticleFunction(true));
        function("perlin_noise", new PerlinNoiseFunction());
        function("play_sound", new SoundFunction.Play());
        function("stop_sound", new SoundFunction.Stop());
        function("stop_all_sounds", new SoundFunction.StopAll());
        function("keyboard", new InputCheck.Keyboard());
        function("mouse", new InputCheck.Mouse());
        function("sync", new Sync());
        function("defer", new Defer());

        projectileVar("projectile_owner", ctx -> ctx.createChild(getProjectileOwner(ctx.entity())));

        throwableItemProjectileVar("throwable_item", YSMBinding::getThrowableItem);

        fishingHookVar("hooked_in", YSMBinding::getHookedIn);
        fishingHookVar("is_biting", ctx -> ctx.entity().ticksCatchable > 0);

        abstractArrowVar("on_ground_time", ctx -> ((IArrowExtraInfo) ctx.entity()).inGroundTime());
        abstractArrowVar("in_ground", ctx -> ((IArrowExtraInfo) ctx.entity()).isInGround());
        abstractArrowVar("is_spectral_arrow", ctx -> ctx.entity() instanceof EntitySpectralArrow);
        abstractArrowVar("shoot_item_id", ctx -> ((IArrowExtraInfo) ctx.entity()).getShootItemId());

        // 模组联动
        CuriosCompat.addMolangBinding(this);
    }

    private static String getHitId(IContext<EntityPlayerSP> context) {
        RayTraceResult hitResult = Minecraft.getMinecraft().objectMouseOver;
        if (hitResult.typeOfHit == RayTraceResult.Type.BLOCK) {
            //noinspection ConstantValue
            if (hitResult.getBlockPos() == null) {
                return "";
            }
            WorldClient level = Minecraft.getMinecraft().world;
            if (level == null) {
                return "";
            }
            IBlockState blockState = level.getBlockState(hitResult.getBlockPos());
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(blockState.getBlock());
            if (id != null) {
                return id.toString();
            } else {
                return "";
            }
        }

        if (hitResult.typeOfHit == RayTraceResult.Type.ENTITY) {
            Entity entity = hitResult.entityHit;
            if (entity == null) {
                return "";
            }
            ResourceLocation id = EntityList.getKey(entity);
            if (id != null) {
                return id.toString();
            } else {
                return "";
            }
        }

        return "";
    }

    private static String getHitType(IContext<EntityPlayerSP> context) {
        RayTraceResult hitResult = Minecraft.getMinecraft().objectMouseOver;
        if (hitResult == null) {
            return StringUtils.EMPTY;
        }
        return switch (hitResult.typeOfHit) {
            case BLOCK -> "block";
            case ENTITY -> "entity";
            default -> StringUtils.EMPTY;
        };
    }

    private static String getHookedIn(IContext<EntityFishHook> ctx) {
        Entity hooked = ctx.entity().caughtEntity;
        if (hooked != null) {
            ResourceLocation id = EntityList.getKey(hooked);
            if (id != null) {
                return id.toString();
            }
        }
        return "";
    }

    // TODO：注册表
    private static String getThrowableItem(IContext<EntityThrowable> ctx) {
        Item item = switch (ctx.entity()) {
            case EntityEgg _ -> Items.EGG;
            case EntityEnderPearl _ -> Items.ENDER_PEARL;
            case EntityExpBottle _ -> Items.EXPERIENCE_BOTTLE;
            case EntityPotion _ -> Items.POTIONITEM;
            case EntitySnowball _ -> Items.SNOWBALL;
            default -> null;
        };
        if (item != null) {
            ResourceLocation itemId = item.getRegistryName();
            if (itemId != null) {
                return itemId.toString();
            }
        }
        return "";
    }

    @Nullable
    private static Entity getProjectileOwner(IProjectile projectile) {
        return switch (projectile) {
            case EntityArrow arrow -> arrow.shootingEntity;
            case EntityThrowable throwable -> throwable.getThrower();
            case EntityLlamaSpit llamaSpit -> llamaSpit.owner;
            default -> null;
        };
    }

    private static double getDeltaMovementLength(IContext<Entity> ctx) {
        Entity entity = ctx.entity();
        return Math.sqrt(entity.motionX * entity.motionX
                + entity.motionY * entity.motionY
                + entity.motionZ * entity.motionZ);
    }

    private static float getGroundSpeed2(IContext<Entity> ctx) {
        var stateStacker = ctx.animatableEntity().getStateTracker();
        var posDelta = stateStacker.getPositionDelta();
        return 20 * MathHelper.sqrt((float) ((posDelta.x * posDelta.x) + (posDelta.z * posDelta.z))) / stateStacker.getRenderTickDelta();
    }

    private static float getXxa(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().xxa();
        } else {
            return ctx.entity().moveStrafing;
        }
    }

    private static float getYya(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().yya();
        } else {
            return ctx.entity().moveVertical;
        }
    }

    private static float getZza(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().zza();
        } else {
            return ctx.entity().moveForward;
        }
    }

    private static boolean inShieldBlockCooldown(IContext<EntityPlayer> context) {
        if (context.animatableEntity() instanceof PlayerAnimatableCapability cap) {
            return cap.getStateTracker().inShieldBlockCooldown();
        } else {
            return false;
        }
    }

    private static boolean isFishing(IContext<EntityLivingBase> ctx) {
        EntityLivingBase entity = ctx.entity();
        if (entity instanceof EntityPlayer player) {
            return player.fishEntity != null;
        }
        return TlmClientCompat.isMaidFishing(entity);
    }

    private static boolean isChargedCrossbow(IContext<EntityLivingBase> ctx, EnumHand hand) {
        ItemStack itemInHand = ctx.entity().getHeldItem(hand);
        return BackportCompat.isCrossbowCharged(itemInHand);
    }

    private static String getEntityType(IContext<EntityLivingBase> ctx) {
        EntityLivingBase entity = ctx.entity();
        if (entity instanceof EntityPlayer) {
            return "player";
        }
        ResourceLocation key = EntityList.getKey(entity);
        if (key == null) {
            return StringUtils.EMPTY;
        }
        if ("touhou_little_maid".equals(key.getNamespace()) && "maid".equals(key.getPath())) {
            return "maid";
        }
        return key.toString();
    }

    private static Object getFoodLevel(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().foodLevel();
        } else if (ctx.entity() instanceof EntityPlayer player) {
            return player.getFoodStats().getFoodLevel();
        } else {
            return 20;
        }
    }

    private static boolean getEyeCloseState(AnimationEvent<?> animationEvent, EntityLivingBase player) {
        float remainder = (animationEvent.getRenderTicks() + Math.abs(player.getUniqueID().getLeastSignificantBits()) % 10) % 90;
        boolean isBlinkTime = 85 < remainder && remainder < 90;
        return player.isPlayerSleeping() || isBlinkTime;
    }

    private static boolean getSlotValue(EntityLivingBase entity, EntityEquipmentSlot slot) {
        return !EquipmentUtil.getEquippedItem(entity, slot).isEmpty();
    }

    private static int getWeather(WorldClient world) {
        if (world.isThundering()) {
            return 2;
        } else if (world.isRaining()) {
            return 1;
        }
        return 0;
    }

    @Deprecated
    private static String getBiomeCategory(Entity entity) {
        return null;
    }

    private static Object dumpMods(IContext<?> context) {
        if (!context.isDebugEnabled()) {
            return null;
        }

        Loader.instance().getModList().stream().filter(Objects::nonNull).sorted(Comparator.comparing(ModContainer::getName)).forEach(mod -> {
            context.debugPrint(new TextComponentString("Mod: display ").appendSibling(ComponentUtils.suggestOnClickText(mod.getName()))
                    .appendText("  id ").appendSibling(ComponentUtils.suggestOnClickText(mod.getModId())));
        });
        return null;
    }

    private static Object dumpEffects(IContext<Entity> context) {
        if (!context.isDebugEnabled()) {
            return null;
        }

        Collection<PotionEffect> effects;
        if (context.entity() instanceof EntityTippedArrow tippedArrow) {
            effects = tippedArrow.customPotionEffects;
        } else if (context.entity() instanceof EntityLivingBase living) {
            effects = living.getActivePotionEffects();
        } else {
            return null;
        }

        for (PotionEffect instance : effects) {
            ResourceLocation id = ForgeRegistries.POTIONS.getKey(instance.getPotion());
            if (id == null) {
                continue;
            }
            context.debugPrint(new TextComponentString("Effect: display ").appendSibling(ComponentUtils.suggestOnClickText(I18n.format(instance.getPotion().getName())))
                    .appendText("  name ").appendSibling(ComponentUtils.suggestOnClickText(id.toString()))
                    .appendText("  lv=").appendText(String.valueOf(instance.getAmplifier() + 1)));
        }

        return null;
    }

    private static Object dumpBiome(IContext<Entity> context) {
        if (!context.isDebugEnabled()) {
            return null;
        }

        Biome biome = context.entity().world.getBiome(new BlockPos(context.entity()));
        ResourceLocation id = biome.getRegistryName();
        if (id == null) {
            return null;
        }
        context.debugPrint(new TextComponentString("Display ").appendSibling(ComponentUtils.suggestOnClickText(biome.getBiomeName())));
        context.debugPrint(new TextComponentString("Name ").appendSibling(ComponentUtils.suggestOnClickText(id.toString())));
        for (BiomeDictionary.Type type : BiomeDictionary.getTypes(biome)) {
            context.debugPrint(new TextComponentString("Type ").appendSibling(ComponentUtils.suggestOnClickText(type.getName().toLowerCase(Locale.ENGLISH))));
        }
        return null;
    }

    private static boolean isOpenAir(Entity entity) {
        BlockPos blockpos = new BlockPos(entity);
        if (!entity.world.canSeeSky(blockpos)) {
            return false;
        }
        if (entity.world.getPrecipitationHeight(blockpos).getY() > blockpos.getY()) {
            return false;
        }
        return true;
    }

    // TODO：注册表
    private static String getParrotVariant(EntityPlayer player, boolean leftShoulder) {
        NBTTagCompound shoulderTag = leftShoulder ? player.getLeftShoulderEntity() : player.getRightShoulderEntity();
        if (EntityList.getClassFromName(shoulderTag.getString("id")) == EntityParrot.class) {
            return switch (shoulderTag.getInteger("Variant")) {
                case 0 -> "red_blue";
                case 1 -> "blue";
                case 2 -> "green";
                case 3 -> "yellow_blue";
                case 4 -> "grey";
                default -> "empty";
            };
        }
        return "empty";
    }

    private static boolean hasParrot(EntityPlayer player, boolean leftShoulder) {
        NBTTagCompound shoulderTag = leftShoulder ? player.getLeftShoulderEntity() : player.getRightShoulderEntity();
        return !shoulderTag.isEmpty();
    }
}
