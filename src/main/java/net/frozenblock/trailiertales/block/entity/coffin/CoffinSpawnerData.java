package net.frozenblock.trailiertales.block.entity.coffin;

import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.util.random.WeightedEntry.Wrapper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CoffinSpawnerData {
	public static MapCodec<CoffinSpawnerData> MAP_CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance.group(
				SpawnData.LIST_CODEC.lenientOptionalFieldOf("spawn_potentials", SimpleWeightedRandomList.empty()).forGetter(data -> data.spawnPotentials),
				Codec.INT.listOf().lenientOptionalFieldOf("souls_to_spawn", new IntArrayList()).forGetter(data -> data.soulsToSpawn),
				UUIDUtil.CODEC_SET.lenientOptionalFieldOf("detected_players", Sets.newHashSet()).forGetter(data -> data.detectedPlayers),
				UUIDUtil.CODEC_SET.lenientOptionalFieldOf("current_mobs", Sets.newHashSet()).forGetter(data -> data.currentMobs),
				UUIDUtil.CODEC_SET.lenientOptionalFieldOf("current_apparitions", Sets.newHashSet()).forGetter(data -> data.currentApparitions),
				Codec.LONG.lenientOptionalFieldOf("power_cooldown_ends_at", 0L).forGetter(data -> data.powerCooldownEndsAt),
				Codec.LONG.lenientOptionalFieldOf("next_mob_spawns_at", 0L).forGetter(data -> data.nextMobSpawnsAt),
				Codec.intRange(0, Integer.MAX_VALUE).lenientOptionalFieldOf("total_mobs_spawned", 0).forGetter(data -> data.totalMobsSpawned),
				Codec.LONG.lenientOptionalFieldOf("next_apparition_spawns_at", 0L).forGetter(data -> data.nextApparitionSpawnsAt),
				Codec.intRange(0, Integer.MAX_VALUE).lenientOptionalFieldOf("total_apparitions_spawned", 0).forGetter(data -> data.totalApparitionsSpawned),
				Codec.intRange(0, Integer.MAX_VALUE).lenientOptionalFieldOf("power", 0).forGetter(data -> data.power),
				SpawnData.CODEC.lenientOptionalFieldOf("spawn_data").forGetter(data -> data.nextSpawnData),
				Codec.BOOL.lenientOptionalFieldOf("within_catacombs", false).forGetter(data -> data.withinCatacombs),
				Codec.intRange(0, 15).lenientOptionalFieldOf("max_active_light_level", 10).forGetter(data -> data.maxActiveLightLevel)
			)
			.apply(instance, CoffinSpawnerData::new)
	);

	protected final IntArrayList soulsToSpawn = new IntArrayList();
	protected final Set<UUID> detectedPlayers = new HashSet<>();
	protected final Set<UUID> currentMobs = new HashSet<>();
	protected final Set<UUID> currentApparitions = new HashSet<>();
	protected long powerCooldownEndsAt;
	protected long nextMobSpawnsAt;
	protected int totalMobsSpawned;
	protected long nextApparitionSpawnsAt;
	protected int totalApparitionsSpawned;
	protected int power;
	protected Optional<SpawnData> nextSpawnData;
	protected boolean withinCatacombs;
	protected int maxActiveLightLevel;
	private SimpleWeightedRandomList<SpawnData> spawnPotentials;

	public CoffinSpawnerData() {
		this(
			SimpleWeightedRandomList.empty(),
			new IntArrayList(),
			Collections.emptySet(),
			Collections.emptySet(),
			Collections.emptySet(),
			0L,
			0L,
			0,
			0L,
			0,
			0,
			Optional.empty(),
			false,
			7
		);
	}

	public CoffinSpawnerData(
		SimpleWeightedRandomList<SpawnData> spawnPotentials,
		List<Integer> soulsToSpawn,
		Set<UUID> detectedPlayers,
		Set<UUID> currentMobs,
		Set<UUID> currentApparitions,
		long powerCooldownEndsAt,
		long nextMobSpawnsAt,
		int totalMobsSpawned,
		long nextApparitionSpawnsAt,
		int totalApparitionsSpawned,
		int power,
		Optional<SpawnData> nextSpawnData,
		boolean withinCatacombs,
		int maxActiveLightLevel
	) {
		this.spawnPotentials = spawnPotentials;
		this.soulsToSpawn.addAll(soulsToSpawn);
		this.detectedPlayers.addAll(detectedPlayers);
		this.currentMobs.addAll(currentMobs);
		this.currentApparitions.addAll(currentApparitions);
		this.powerCooldownEndsAt = powerCooldownEndsAt;
		this.nextMobSpawnsAt = nextMobSpawnsAt;
		this.totalMobsSpawned = totalMobsSpawned;
		this.nextApparitionSpawnsAt = nextApparitionSpawnsAt;
		this.totalApparitionsSpawned = totalApparitionsSpawned;
		this.power = power;
		this.nextSpawnData = nextSpawnData;
		this.withinCatacombs = withinCatacombs;
		this.maxActiveLightLevel = maxActiveLightLevel;
	}

	public void reset() {
		this.totalMobsSpawned = 0;
		this.power = 0;
		this.nextMobSpawnsAt = 0L;
		this.powerCooldownEndsAt = 0L;
		this.currentMobs.clear();
		this.currentApparitions.clear();
	}

	public boolean hasMobToSpawn(Level level, RandomSource random, BlockPos pos) {
		boolean hasNextSpawnData = this.getOrCreateNextSpawnData(level, random, pos).getEntityToSpawn().contains("id", 8);
		return hasNextSpawnData || !this.spawnPotentials().isEmpty();
	}

	public boolean hasFinishedSpawningAllMobs(@NotNull CoffinSpawnerConfig config, int players) {
		return this.totalMobsSpawned >= config.calculateTargetTotalMobs(players);
	}

	public boolean haveAllCurrentMobsDied() {
		return this.currentMobs.isEmpty();
	}

	public boolean trackingEntity(@NotNull Entity entity) {
		return this.currentMobs.contains(entity.getUUID());
	}

	public int getPower() {
		return this.power;
	}

	public boolean isReadyToSpawnNextMob(@NotNull ServerLevel level, CoffinSpawnerConfig config, int players, boolean blocked) {
		return this.isPreparingToSpawnNextMob(level, config, players, 0) && !blocked;
	}

	public boolean isPreparingToSpawnNextMob(@NotNull ServerLevel level, CoffinSpawnerConfig config, int players, int timeAhead) {
		return level.getGameTime() + timeAhead >= this.nextMobSpawnsAt && this.detectedAnyPlayers() && this.currentMobs.size() < config.calculateTargetSimultaneousMobs(players);
	}

	public int countAdditionalPlayers() {
		return Math.max(0, this.detectedPlayers.size() - 1);
	}

	public boolean detectedAnyPlayers() {
		return !this.detectedPlayers.isEmpty();
	}

	public UUID randomPlayerUUID(RandomSource random) {
		return Util.getRandom(this.detectedPlayers.stream().toList(), random);
	}

	public Optional<Player> getClosestDetectedPlayer(Level level, Vec3 origin) {
		if (this.detectedAnyPlayers()) {
			AtomicReference<Double> closestDistance = new AtomicReference<>(Double.MAX_VALUE);
			AtomicReference<Optional<Player>> closestPlayer = new AtomicReference<>(Optional.empty());
			this.detectedPlayers.forEach(uuid -> {
				Player player = level.getPlayerByUUID(uuid);
				if (player != null && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(player)) {
					double distanceTo = player.distanceToSqr(origin);
					if (distanceTo < closestDistance.get()) {
						closestDistance.set(distanceTo);
						closestPlayer.set(Optional.of(player));
					}
				}
			});
			return closestPlayer.get();
		}
		return Optional.empty();
	}

	public void tryDetectPlayers(@NotNull ServerLevel world, @NotNull BlockPos pos, CoffinSpawner coffinSpawner) {
		boolean isSecondForPos = (pos.asLong() + world.getGameTime()) % 20L == 0L;
		if (isSecondForPos) {
			List<UUID> list = coffinSpawner.getPlayerDetector()
				.detect(world, coffinSpawner.getEntitySelector(), pos, coffinSpawner.getRequiredPlayerRange(), this.withinCatacombs);

			if (this.detectedPlayers.addAll(list)) {
				world.levelEvent(LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER, pos, this.detectedPlayers.size());
			}

			this.detectedPlayers.removeIf(uuid -> !list.contains(uuid));
		}
	}

	public boolean isPowerCooldownFinished(@NotNull ServerLevel level) {
		return level.getGameTime() >= this.powerCooldownEndsAt;
	}

	public void setEntityId(EntityType<?> type, @Nullable Level world, RandomSource random, BlockPos pos) {
		this.getOrCreateNextSpawnData(world, random, pos).getEntityToSpawn().putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
	}

	public SimpleWeightedRandomList<SpawnData> spawnPotentials() {
		return this.spawnPotentials;
	}

	@NotNull SpawnData getOrCreateNextSpawnData(@Nullable Level world, RandomSource random, BlockPos pos) {
		if (this.nextSpawnData.isEmpty()) {
			this.setNextSpawnData(world, pos, this.spawnPotentials.getRandom(random).map(Wrapper::data).orElseGet(SpawnData::new));
		}
		return this.nextSpawnData.get();
	}

	protected void setNextSpawnData(@Nullable Level world, BlockPos pos, SpawnData spawnEntry) {
		this.nextSpawnData = Optional.ofNullable(spawnEntry);
	}

	private static long lowResolutionPosition(@NotNull ServerLevel world, @NotNull BlockPos pos) {
		BlockPos blockPos = new BlockPos(Mth.floor((float)pos.getX() / 30F), Mth.floor((float)pos.getY() / 20F), Mth.floor((float)pos.getZ() / 30F));
		return world.getSeed() + blockPos.asLong();
	}
}
