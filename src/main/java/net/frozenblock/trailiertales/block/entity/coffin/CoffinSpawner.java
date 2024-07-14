package net.frozenblock.trailiertales.block.entity.coffin;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Optional;
import java.util.UUID;
import net.frozenblock.trailiertales.block.CoffinBlock;
import net.frozenblock.trailiertales.block.entity.coffin.impl.EntityCoffinData;
import net.frozenblock.trailiertales.block.entity.coffin.impl.EntityCoffinInterface;
import net.frozenblock.trailiertales.block.impl.CoffinPart;
import net.frozenblock.trailiertales.entity.Apparition;
import net.frozenblock.trailiertales.registry.RegisterEntities;
import net.frozenblock.trailiertales.registry.RegisterParticles;
import net.frozenblock.trailiertales.registry.RegisterSounds;
import net.frozenblock.trailiertales.worldgen.structure.CatacombsGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.entity.trialspawner.PlayerDetector;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CoffinSpawner {
	private static final int PLAYER_TRACKING_DISTANCE = 48;
	private static final int MAX_MOB_TRACKING_DISTANCE = 64;
	private static final int MAX_MOB_TRACKING_DISTANCE_SQR = Mth.square(MAX_MOB_TRACKING_DISTANCE);
	public static final PlayerDetector IN_CATACOMBS_NO_CREATIVE_PLAYERS = (world, entitySelector, pos, d, bl) -> entitySelector.getPlayers(
			world, player -> player.blockPosition().closerThan(pos, d) && !player.isCreative() && !player.isSpectator()
		)
		.stream()
		.filter(player -> !bl || isInCatacombsBounds(player.blockPosition(), world.structureManager()))
		.map(Entity::getUUID)
		.toList();
	private final CoffinSpawnerConfig normalConfig;
	private final CoffinSpawnerConfig irritatedConfig;
	private final CoffinSpawnerConfig aggressiveConfig;
	private final CoffinSpawnerData data;
	private final int requiredPlayerRange;
	private final int powerCooldownLength;
	private final CoffinSpawner.StateAccessor stateAccessor;
	private final PlayerDetector.EntitySelector entitySelector;
	private boolean overridePeacefulAndMobSpawnRule;
	private final UUID uuid;
	private boolean attemptingToSpawnMob;

	public Codec<CoffinSpawner> codec() {
		return RecordCodecBuilder.create(
			instance -> instance.group(
					CoffinSpawnerConfig.CODEC.optionalFieldOf("normal_config", CoffinSpawnerConfig.DEFAULT).forGetter(CoffinSpawner::getNormalConfig),
					CoffinSpawnerConfig.CODEC.optionalFieldOf("irritated_config", CoffinSpawnerConfig.IRRITATED).forGetter(CoffinSpawner::getIrritatedConfig),
					CoffinSpawnerConfig.CODEC.optionalFieldOf("aggressive_config", CoffinSpawnerConfig.AGGRESSIVE).forGetter(CoffinSpawner::getAggressiveConfig),
					CoffinSpawnerData.MAP_CODEC.forGetter(CoffinSpawner::getData),
					Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("power_cooldown_length", 12000).forGetter(CoffinSpawner::getPowerCooldownLength),
					Codec.intRange(1, PLAYER_TRACKING_DISTANCE).optionalFieldOf("required_player_range", PLAYER_TRACKING_DISTANCE).forGetter(CoffinSpawner::getRequiredPlayerRange),
					Codec.STRING.optionalFieldOf("uuid", UUID.randomUUID().toString()).forGetter(CoffinSpawner::getStringUUID),
					Codec.BOOL.optionalFieldOf("attempting_to_spawn_mob", false).forGetter(CoffinSpawner::isAttemptingToSpawnMob)
				)
				.apply(
					instance,
					(config, config2, config3, data, powerCooldownLength, integer, uuid, attemptingSpawn) -> new CoffinSpawner(
						config, config2, config3, data, powerCooldownLength, integer, uuid, attemptingSpawn, this.stateAccessor, this.entitySelector
					)
				)
		);
	}

	public @NotNull CompoundTag getUpdateTag() {
		CompoundTag compoundTag = new CompoundTag();
		compoundTag.putBoolean("attempting_to_spawn_mob", this.attemptingToSpawnMob);
		return compoundTag;
	}

	public CoffinSpawner(CoffinSpawner.StateAccessor coffin, PlayerDetector.EntitySelector playerDetectionSelector) {
		this(
			CoffinSpawnerConfig.DEFAULT,
			CoffinSpawnerConfig.IRRITATED,
			CoffinSpawnerConfig.AGGRESSIVE,
			new CoffinSpawnerData(),
			12000,
			PLAYER_TRACKING_DISTANCE,
			UUID.randomUUID().toString(),
			false,
			coffin,
			playerDetectionSelector
		);
	}

	public CoffinSpawner(
		CoffinSpawnerConfig normalConfig,
		CoffinSpawnerConfig irritatedConfig,
		CoffinSpawnerConfig aggressiveConfig,
		CoffinSpawnerData data,
		int powerCooldownLength,
		int requiredPlayerRange,
		String uuid,
		boolean attemptingToSpawnMob,
		CoffinSpawner.StateAccessor coffin,
		PlayerDetector.EntitySelector playerDetectionSelector
	) {
		this.normalConfig = normalConfig;
		this.irritatedConfig = irritatedConfig;
		this.aggressiveConfig = aggressiveConfig;
		this.data = data;
		this.powerCooldownLength = powerCooldownLength;
		this.requiredPlayerRange = requiredPlayerRange;
		this.uuid = UUID.fromString(uuid);
		this.attemptingToSpawnMob = attemptingToSpawnMob;
		this.stateAccessor = coffin;
		this.entitySelector = playerDetectionSelector;
	}

	public CoffinSpawnerConfig getConfig() {
		return switch (this.getState()) {
			case AGGRESSIVE -> this.aggressiveConfig;
			case INACTIVE -> this.irritatedConfig;
			default -> this.normalConfig;
		};
	}

	@VisibleForTesting
	public CoffinSpawnerConfig getNormalConfig() {
		return this.normalConfig;
	}

	@VisibleForTesting
	public CoffinSpawnerConfig getIrritatedConfig() {
		return this.irritatedConfig;
	}

	@VisibleForTesting
	public CoffinSpawnerConfig getAggressiveConfig() {
		return this.aggressiveConfig;
	}

	public CoffinSpawnerData getData() {
		return this.data;
	}

	public int getPowerCooldownLength() {
		return this.powerCooldownLength;
	}

	public int getRequiredPlayerRange() {
		return this.requiredPlayerRange;
	}

	public UUID getUUID() {
		return this.uuid;
	}

	public String getStringUUID() {
		return this.uuid.toString();
	}

	public boolean isAttemptingToSpawnMob() {
		return this.attemptingToSpawnMob;
	}

	public void addPower(int i, @NotNull Level level) {
		this.data.power += i;
		this.data.powerCooldownEndsAt = level.getGameTime() + this.powerCooldownLength;
	}

	public void addSoulParticle(int delayUntilSpawn) {
		this.data.soulsToSpawn.add(delayUntilSpawn);
	}

	public CoffinSpawnerState getState() {
		return this.stateAccessor.getState();
	}

	public void setState(Level level, CoffinSpawnerState state) {
		this.stateAccessor.setState(level, state);
	}

	public void markUpdated() {
		this.stateAccessor.markUpdated();
	}

	public PlayerDetector getPlayerDetector() {
		return this.data.withinCatacombs ? IN_CATACOMBS_NO_CREATIVE_PLAYERS : PlayerDetector.NO_CREATIVE_PLAYERS;
	}

	public PlayerDetector.EntitySelector getEntitySelector() {
		return this.entitySelector;
	}

	public boolean canSpawnInLevel(Level level) {
		if (this.overridePeacefulAndMobSpawnRule) {
			return true;
		} else {
			return level.getDifficulty() != Difficulty.PEACEFUL && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
		}
	}

	public Optional<UUID> spawnMob(@NotNull ServerLevel level, BlockPos pos) {
		RandomSource randomSource = level.getRandom();
		SpawnData spawnData = this.data.getOrCreateNextSpawnData(level, level.getRandom(), pos);
		CompoundTag compoundTag = spawnData.entityToSpawn();
		ListTag listTag = compoundTag.getList("Pos", 6);
		Optional<EntityType<?>> optional = EntityType.by(compoundTag);
		if (optional.isEmpty()) {
			return Optional.empty();
		} else {
			int i = listTag.size();
			double d = i >= 1
				? listTag.getDouble(0)
				: (double)pos.getX() + (randomSource.nextDouble() - randomSource.nextDouble()) * (double)this.getConfig().spawnRange() + 0.5;
			double e = i >= 2 ? listTag.getDouble(1) : (double)(pos.getY() + randomSource.nextInt(3) - 1);
			double f = i >= 3
				? listTag.getDouble(2)
				: (double)pos.getZ() + (randomSource.nextDouble() - randomSource.nextDouble()) * (double)this.getConfig().spawnRange() + 0.5;
			if (!level.noCollision(optional.get().getSpawnAABB(d, e, f))) {
				return Optional.empty();
			} else {
				Vec3 vec3 = new Vec3(d, e, f);
				if (!inLineOfSight(level, pos.getCenter(), vec3)) {
					return Optional.empty();
				} else {
					BlockPos blockPos = BlockPos.containing(vec3);
					if (!SpawnPlacements.checkSpawnRules(optional.get(), level, MobSpawnType.TRIAL_SPAWNER, blockPos, level.getRandom())) {
						return Optional.empty();
					} else {
						if (spawnData.getCustomSpawnRules().isPresent()) {
							SpawnData.CustomSpawnRules customSpawnRules = spawnData.getCustomSpawnRules().get();
							if (!customSpawnRules.isValidPosition(blockPos, level)) {
								return Optional.empty();
							}
						}

						int lightAtPos = level.getRawBrightness(blockPos, 0);
						int lightToleranceDifference = Math.max(this.data.maxActiveLightLevel, lightAtPos) - this.data.maxActiveLightLevel;
						if (lightToleranceDifference > 0 && randomSource.nextInt(lightToleranceDifference * 25) > 0) {
							return Optional.empty();
						}

						Entity entity = EntityType.loadEntityRecursive(compoundTag, level, entityx -> {
							entityx.moveTo(d, e, f, randomSource.nextFloat() * 360F, 0F);
							return entityx;
						});
						if (entity == null) {
							return Optional.empty();
						} else {
							if (entity instanceof Mob mob) {
								if (!mob.checkSpawnObstruction(level)) {
									return Optional.empty();
								}

								boolean bl = spawnData.getEntityToSpawn().size() == 1 && spawnData.getEntityToSpawn().contains("id", 8);
								if (bl) {
									mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.TRIAL_SPAWNER, null);
								}

								spawnData.getEquipment().ifPresent(mob::equip);
							}

							if (!level.tryAddFreshEntityWithPassengers(entity)) {
								return Optional.empty();
							} else {
								level.playSound(
									null,
									entity,
									RegisterSounds.COFFIN_SPAWN_MOB,
									SoundSource.BLOCKS,
									1F,
									(randomSource.nextFloat() - randomSource.nextFloat()) * 0.2F + 1F
								);
								if (entity instanceof Mob mob) {
									mob.spawnAnim();
								}
								level.gameEvent(entity, GameEvent.ENTITY_PLACE, blockPos);
								this.appendCoffinSpawnAttributes(entity, level, pos);
								return Optional.of(entity.getUUID());
							}
						}
					}
				}
			}
		}
	}

	public boolean canSpawnApparition(Level level) {
		CoffinSpawnerData data = this.getData();
		return this.getConfig().spawnsApparitions()
			&& data.currentApparitions.isEmpty()
			&& data.detectedAnyPlayers()
			&& level.getGameTime() > data.nextApparitionSpawnsAt
			&& level.getRandom().nextFloat() < 0.0005F;
	}

	public void spawnApparition(@NotNull ServerLevel level, @NotNull BlockPos pos) {
		Apparition apparition = RegisterEntities.APPARITION.create(level, null, pos, MobSpawnType.TRIAL_SPAWNER, true, false);
		if (apparition != null) {
			if (level.addFreshEntity(apparition)) {
				apparition.hiddenTicks = 100;
				this.appendCoffinSpawnAttributes(apparition, level, pos);
				this.data.nextApparitionSpawnsAt = level.getGameTime() + 80L;
				this.data.currentApparitions.add(apparition.getUUID());
			}
		}
	}

	public void appendCoffinSpawnAttributes(Entity entity, Level level, BlockPos pos) {
		if (entity instanceof Mob mob) {
			mob.getAttributes().getInstance(Attributes.FOLLOW_RANGE)
				.addPermanentModifier(new AttributeModifier(CoffinBlock.ATTRIBUTE_COFFIN_FOLLOW_RANGE, 24D, AttributeModifier.Operation.ADD_VALUE));
			Optional<Player> closestDetectedPlayer = this.data.getClosestDetectedPlayer(level, entity.position());
			closestDetectedPlayer.ifPresent(mob::setTarget);
		}
		if (entity instanceof EntityCoffinInterface entityInterface) {
			entityInterface.trailierTales$setCoffinData(
				new EntityCoffinData(pos, this.uuid)
			);
		}
		if (entity instanceof Apparition apparition) {
			apparition.getBrain().setMemory(MemoryModuleType.HOME, new GlobalPos(level.dimension(), pos));
		}
	}

	public void updateAttemptingToSpawn(@NotNull ServerLevel level, BlockPos pos, @Nullable Direction direction) {
		boolean isAttempting = this.isAttemptingToSpawnMob(level, pos, direction);
		if (isAttempting != this.attemptingToSpawnMob) {
			this.attemptingToSpawnMob = isAttempting;
			this.markUpdated();
		}
	}

	public boolean isAttemptingToSpawnMob(@NotNull ServerLevel level, BlockPos pos, @Nullable Direction direction) {
		int additionalPlayers = this.data.countAdditionalPlayers();
		boolean isPreparing = this.data.isPreparingToSpawnNextMob(level, this.getConfig(), additionalPlayers, 45);
		boolean finishedSpawningMobs = this.data.hasFinishedSpawningAllMobs(this.getConfig(), additionalPlayers);
		boolean canSpawnInLevel = this.canSpawnInLevel(level) && this.getState().isCapableOfSpawning();
		return isPreparing && !finishedSpawningMobs && canSpawnInLevel;
	}

	public void tickServer(ServerLevel world, BlockPos pos, CoffinPart part, boolean ominous) {
		if (part == CoffinPart.HEAD || world.isClientSide) {
			return;
		}

		Direction direction = CoffinBlock.getCoffinOrientation(world, pos);
		if (direction != null) {
			if (!this.data.soulsToSpawn.isEmpty()) {
				boolean isNegativeDirection = direction.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
				boolean isOppositeX = isNegativeDirection && direction.getAxis() == Direction.Axis.X;
				boolean isOppositeZ = isNegativeDirection && direction.getAxis() == Direction.Axis.Z;
				IntArrayList newList = new IntArrayList();
				this.data.soulsToSpawn.forEach(spawnTime -> {
					if (spawnTime <= 0) {
						double stepX = direction.getStepX();
						double stepZ = direction.getStepZ();
						double relativeX = isOppositeX ? 0D : stepX == 0D ? 0.5D : stepX;
						double relativeZ = isOppositeZ ? 0D : stepZ == 0D ? 0.5D : stepZ;
						double xOffset = Math.abs(stepX * 0.35D);
						double zOffset = Math.abs(stepZ * 0.35D);
						world.sendParticles(
							RegisterParticles.COFFIN_SOUL_ENTER,
							pos.getX() + relativeX,
							pos.getY() + 0.95D,
							pos.getZ() + relativeZ,
						4,
							xOffset,
							0D,
							zOffset,
							0D
						);
						this.addPower(1, world);
					} else {
						newList.add(spawnTime - 1);
					}
				});
				this.data.soulsToSpawn.clear();
				this.data.soulsToSpawn.addAll(newList);
			}
		}

		this.data.currentMobs.removeIf(uiid -> {
			Entity entity = world.getEntity(uiid);
			boolean shouldUntrack = shouldMobBeUntracked(world, pos, entity);
			if (shouldUntrack) {
				CoffinBlock.onCoffinUntrack(entity);
			}
			return shouldUntrack;
		});

		this.data.currentApparitions.removeIf(uiid -> {
			Entity entity = world.getEntity(uiid);
			boolean shouldUntrack = shouldMobBeUntracked(world, pos, entity);
			if (shouldUntrack) {
				CoffinBlock.onCoffinUntrack(entity);
				this.data.nextApparitionSpawnsAt = world.getGameTime() + 800L;
			}
			return shouldUntrack;
		});

		CoffinSpawnerState currentState = this.getState();
		if (!this.canSpawnInLevel(world)) {
			if (currentState.isCapableOfSpawning()) {
				this.setState(world, CoffinSpawnerState.INACTIVE);
			}
		} else {
			CoffinSpawnerState nextState = currentState.tickAndGetNext(pos, this, world);
			if (nextState != currentState) {
				this.setState(world, nextState);
			}
		}
		this.updateAttemptingToSpawn(world, pos, direction);
	}

	private static boolean shouldMobBeUntracked(@NotNull ServerLevel level, BlockPos pos, UUID uuid) {
		return shouldMobBeUntracked(level, pos, level.getEntity(uuid));
	}

	private static boolean shouldMobBeUntracked(@NotNull ServerLevel level, BlockPos pos, Entity entity) {
		return entity == null
			|| !entity.level().dimension().equals(level.dimension())
			|| entity.blockPosition().distSqr(pos) > (double)MAX_MOB_TRACKING_DISTANCE_SQR
			|| entity.isRemoved();
	}

	private static boolean inLineOfSight(@NotNull Level level, Vec3 spawnerPos, Vec3 mobPos) {
		BlockHitResult blockHitResult = level.clip(new ClipContext(mobPos, spawnerPos, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return !(blockHitResult.getBlockPos().equals(BlockPos.containing(spawnerPos)) || blockHitResult.getType() == HitResult.Type.MISS);
	}

	public static void addSpawnParticles(Level world, BlockPos pos, RandomSource random, SimpleParticleType simpleParticleType) {
		for(int i = 0; i < 20; ++i) {
			double d = (double)pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 2D;
			double e = (double)pos.getY() + 0.5D + (random.nextDouble() - 0.5D) * 2D;
			double f = (double)pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 2D;
			world.addParticle(ParticleTypes.SMOKE, d, e, f, 0D, 0D, 0D);
			world.addParticle(simpleParticleType, d, e, f, 0D, 0D, 0D);
		}
	}

	public static void addDetectPlayerParticles(Level world, BlockPos pos, RandomSource random, int detectedPlayers, ParticleOptions particleOptions) {
		for(int i = 0; i < 30 + Math.min(detectedPlayers, 10) * 5; ++i) {
			double d = (double)(2F * random.nextFloat() - 1F) * 0.65;
			double e = (double)(2F * random.nextFloat() - 1F) * 0.65;
			double f = (double)pos.getX() + 0.5 + d;
			double g = (double)pos.getY() + 0.1 + (double)random.nextFloat() * 0.8;
			double h = (double)pos.getZ() + 0.5 + e;
			world.addParticle(particleOptions, f, g, h, 0.0, 0.0, 0.0);
		}
	}

	public static boolean isInCatacombsBounds(BlockPos pos, @NotNull StructureManager structureManager) {
		Structure structure = structureManager.registryAccess().registryOrThrow(Registries.STRUCTURE).get(CatacombsGenerator.CATACOMBS_KEY);
		return structure != null && structureManager.structureHasPieceAt(pos, structureManager.getStructureAt(pos, structure));
	}

	@VisibleForTesting
	public void overridePeacefulAndMobSpawnRule() {
		this.overridePeacefulAndMobSpawnRule = true;
	}

	public interface StateAccessor {
		void setState(Level level, CoffinSpawnerState state);

		CoffinSpawnerState getState();

		void markUpdated();
	}
}
