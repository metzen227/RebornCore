/*
 * This file is part of TechReborn, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020 TechReborn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package reborncore.common.chunkloading;

import net.minecraft.class_5318;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import reborncore.common.network.ClientBoundPackets;
import reborncore.common.util.NBTSerializable;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

//This does not do the actual chunk loading, just keeps track of what chunks the chunk loader has loaded
public class ChunkLoaderManager extends PersistentState {

	private static final ChunkTicketType<ChunkPos> CHUNK_LOADER = ChunkTicketType.create("reborncore:chunk_loader", Comparator.comparingLong(ChunkPos::toLong));
	private static final String KEY = "reborncore_chunk_loader";

	public ChunkLoaderManager() {
		super(KEY);
	}

	public static ChunkLoaderManager get(World world){
		ServerWorld serverWorld = (ServerWorld) world;
		return serverWorld.getPersistentStateManager().getOrCreate(ChunkLoaderManager::new, KEY);
	}

	private final List<LoadedChunk> loadedChunks = new ArrayList<>();

	@Override
	public void fromTag(CompoundTag tag) {
		loadedChunks.clear();
		ListTag listTag = tag.getList("loadedchunks", tag.getType());

		loadedChunks.addAll(listTag.stream()
				.map(tag1 -> (CompoundTag) tag1)
				.map(LoadedChunk::new)
				.collect(Collectors.toList())
		);
	}

	@Override
	public CompoundTag toTag(CompoundTag tag) {
		ListTag listTag = new ListTag();

		listTag.addAll(loadedChunks.stream().map(LoadedChunk::write).collect(Collectors.toList()));
		tag.put("loadedchunks", listTag);

		return tag;
	}

	public Optional<LoadedChunk> getLoadedChunk(World world, ChunkPos chunkPos, BlockPos chunkLoader){
		return loadedChunks.stream()
			.filter(loadedChunk -> loadedChunk.getWorld().equals(getWorldName(world)))
			.filter(loadedChunk -> loadedChunk.getChunk().equals(chunkPos))
			.filter(loadedChunk -> loadedChunk.getChunkLoader().equals(chunkLoader))
			.findFirst();
	}

	public Optional<LoadedChunk> getLoadedChunk(World world, ChunkPos chunkPos){
		return loadedChunks.stream()
			.filter(loadedChunk -> loadedChunk.getWorld().equals(getWorldName(world)))
			.filter(loadedChunk -> loadedChunk.getChunk().equals(chunkPos))
			.findFirst();
	}

	public List<LoadedChunk> getLoadedChunks(World world, BlockPos chunkloader){
		return loadedChunks.stream()
			.filter(loadedChunk -> loadedChunk.getWorld().equals(getWorldName(world)))
			.filter(loadedChunk -> loadedChunk.getChunkLoader().equals(chunkloader))
			.collect(Collectors.toList());
	}

	public boolean isChunkLoaded(World world, ChunkPos chunkPos, BlockPos chunkLoader){
		return getLoadedChunk(world, chunkPos, chunkLoader).isPresent();
	}

	public boolean isChunkLoaded(World world, ChunkPos chunkPos){
		return getLoadedChunk(world, chunkPos).isPresent();
	}


	public void loadChunk(World world, ChunkPos chunkPos, BlockPos chunkLoader, String player){
		Validate.isTrue(!isChunkLoaded(world, chunkPos, chunkLoader), "chunk is already loaded");
		LoadedChunk loadedChunk = new LoadedChunk(chunkPos, getWorldName(world), player, chunkLoader);
		loadedChunks.add(loadedChunk);

		final ServerChunkManager serverChunkManager = ((ServerWorld) world).getChunkManager();
		serverChunkManager.addTicket(ChunkLoaderManager.CHUNK_LOADER, loadedChunk.getChunk(), 31, loadedChunk.getChunk());

		markDirty();
	}

	public void unloadChunkLoader(World world, BlockPos chunkLoader){
		getLoadedChunks(world, chunkLoader).forEach(loadedChunk -> unloadChunk(world, loadedChunk.getChunk(), chunkLoader));
	}

	public void unloadChunk(World world, ChunkPos chunkPos, BlockPos chunkLoader){
		Optional<LoadedChunk> optionalLoadedChunk = getLoadedChunk(world, chunkPos, chunkLoader);
		Validate.isTrue(optionalLoadedChunk.isPresent(), "chunk is not loaded");

		LoadedChunk loadedChunk = optionalLoadedChunk.get();

		loadedChunks.remove(loadedChunk);

		if(!isChunkLoaded(world, loadedChunk.getChunk())){
			final ServerChunkManager serverChunkManager = ((ServerWorld) world).getChunkManager();
			serverChunkManager.removeTicket(ChunkLoaderManager.CHUNK_LOADER, loadedChunk.getChunk(), 31, loadedChunk.getChunk());
		}
		markDirty();
	}

	public static Identifier getWorldName(World world){
		return world.method_28380().method_29116().getId(world.getDimension());
	}

	public static RegistryKey<DimensionType> getDimensionRegistryKey(World world){
		return RegistryKey.of(Registry.DIMENSION_TYPE_KEY, getWorldName(world));
	}

	public void syncChunkLoaderToClient(ServerPlayerEntity serverPlayerEntity, BlockPos chunkLoader){
		syncToClient(serverPlayerEntity, loadedChunks.stream().filter(loadedChunk -> loadedChunk.getChunkLoader().equals(chunkLoader)).collect(Collectors.toList()));
	}

	public void syncAllToClient(ServerPlayerEntity serverPlayerEntity) {
		syncToClient(serverPlayerEntity, loadedChunks);
	}

	public void clearClient(ServerPlayerEntity serverPlayerEntity) {
		syncToClient(serverPlayerEntity, Collections.emptyList());
	}

	public void syncToClient(ServerPlayerEntity serverPlayerEntity, List<LoadedChunk> chunks){
		serverPlayerEntity.networkHandler.sendPacket(
			ClientBoundPackets.createPacketSyncLoadedChunks(chunks)
		);
	}

	public static class LoadedChunk implements NBTSerializable {
		private ChunkPos chunk;
		private Identifier world;
		private String player;
		private BlockPos chunkLoader;

		public LoadedChunk(ChunkPos chunk, Identifier world, String player, BlockPos chunkLoader) {
			this.chunk = chunk;
			this.world = world;
			this.player = player;
			this.chunkLoader = chunkLoader;
			Validate.isTrue(!StringUtils.isBlank(player), "Player cannot be blank");
		}

		public LoadedChunk(CompoundTag tag) {
			read(tag);
		}

		@Override
		public @Nonnull CompoundTag write() {
			CompoundTag tag = new CompoundTag();
			tag.putLong("chunk", chunk.toLong());
			tag.putString("world", world.toString());
			tag.putString("player", player);
			tag.putLong("chunkLoader", chunkLoader.asLong());
			return tag;
		}

		@Override
		public void read(@Nonnull CompoundTag tag) {
			chunk = new ChunkPos(tag.getLong("chunk"));
			world = new Identifier(tag.getString("world"));
			player = tag.getString("player");
			chunkLoader = BlockPos.fromLong(tag.getLong("chunkLoader"));
		}

		public ChunkPos getChunk() {
			return chunk;
		}

		public Identifier getWorld() {
			return world;
		}

		public String getPlayer() {
			return player;
		}

		public BlockPos getChunkLoader() {
			return chunkLoader;
		}
	}
}
