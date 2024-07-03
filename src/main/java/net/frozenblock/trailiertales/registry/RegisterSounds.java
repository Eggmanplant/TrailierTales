package net.frozenblock.trailiertales.registry;

import net.frozenblock.trailiertales.TrailierConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import org.jetbrains.annotations.NotNull;

public class RegisterSounds {

	public static final SoundEvent COFFIN_BREAK = register("block.coffin.break");
	public static final SoundEvent COFFIN_STEP = register("block.coffin.step");
	public static final SoundEvent COFFIN_PLACE = register("block.coffin.place");
	public static final SoundEvent COFFIN_HIT = register("block.coffin.hit");
	public static final SoundEvent COFFIN_FALL = register("block.coffin.fall");
	public static final SoundEvent COFFIN_SPAWN_MOB = register("block.coffin.spawn_mob");
	public static final SoundType COFFIN = new SoundType(
		1F,
		1F,
		COFFIN_BREAK,
		COFFIN_STEP,
		COFFIN_PLACE,
		COFFIN_HIT,
		COFFIN_FALL
	);

	public static final SoundEvent SUSPICIOUS_DIRT_BREAK = register("block.suspicious_dirt.break");
	public static final SoundEvent SUSPICIOUS_DIRT_STEP = register("block.suspicious_dirt.step");
	public static final SoundEvent SUSPICIOUS_DIRT_PLACE = register("block.suspicious_dirt.place");
	public static final SoundEvent SUSPICIOUS_DIRT_HIT = register("block.suspicious_dirt.hit");
	public static final SoundEvent SUSPICIOUS_DIRT_FALL = register("block.suspicious_dirt.fall");

	public static final SoundEvent BRUSH_DIRT = register("item.brush.brushing.dirt");
	public static final SoundEvent BRUSH_DIRT_COMPLETED = register("item.brush.brushing.dirt.complete");

	public static final SoundEvent SUSPICIOUS_CLAY_BREAK = register("block.suspicious_clay.break");
	public static final SoundEvent SUSPICIOUS_CLAY_STEP = register("block.suspicious_clay.step");
	public static final SoundEvent SUSPICIOUS_CLAY_PLACE = register("block.suspicious_clay.place");
	public static final SoundEvent SUSPICIOUS_CLAY_HIT = register("block.suspicious_clay.hit");
	public static final SoundEvent SUSPICIOUS_CLAY_FALL = register("block.suspicious_clay.fall");

	public static final SoundEvent BRUSH_CLAY = register("item.brush.brushing.clay");
	public static final SoundEvent BRUSH_CLAY_COMPLETED = register("item.brush.brushing.clay.complete");

	@NotNull
	private static SoundEvent register(@NotNull String string) {
		ResourceLocation resourceLocation = TrailierConstants.id(string);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, resourceLocation, SoundEvent.createVariableRangeEvent(resourceLocation));
	}

	public static void init() {}
}
