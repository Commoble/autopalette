package commoble.autopalette;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.ResourcePackFileNotFoundException;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.metadata.pack.PackMetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

public class AutopalettePack extends AbstractPackResources implements PreparableReloadListener
{
	public static final Logger LOGGER = LogManager.getLogger();
	public static final Gson GSON = new Gson();
	public static final String DIRECTORY = "autotextures";
	public static final String TEXTURE_DIRECTORY = "textures/";
	public static final Set<String> NAMESPACES = ImmutableSet.of(Autopalette.MODID);
	public static final List<ResourceLocation> NO_RESOURCES = Collections.emptyList();
	// reuse a JRL to parse jsons, hehehe
	public static final SimpleJsonResourceReloadListener JSON_HELPER = new SimpleJsonResourceReloadListener(GSON, DIRECTORY)
	{
		// noop, we just use the JRL to prepare jsonelements for us
		@Override
		protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager manager, ProfilerFiller profiler)
		{}
	};
	
	private final PackMetadataSection packInfo;
	private Map<ResourceLocation, Callable<InputStream>> resources = new HashMap<>();

	public AutopalettePack()
	{
		super(new File("autopalette_virtual_pack"));
		this.packInfo = new PackMetadataSection(Component.translatable("autopalette.pack_description"), 6);;
	}
	
	// resource loading stuff
	
	@Override
	public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier stage, ResourceManager manager,
		ProfilerFiller workerProfiler, ProfilerFiller mainProfiler,
		Executor workerExecutor, Executor mainExecutor)
	{
		// we need to ensure generated textures are ready before the texture stitchers load them
		// unfortunately, the texture stitchers run during the worker thread phase
		// so we need to generate our textures on the main thread, before the worker phase starts
		// (we are here)

		this.gatherTextureData(manager, mainProfiler);
		
			// prepare = worker thread stuff
		return CompletableFuture.supplyAsync(() -> null, workerExecutor)
			// wait for off-thread phase to conclude
			.thenCompose(stage::wait)
			// then do stuff on main thread again
			.thenAcceptAsync((noResult) -> {}, mainExecutor);
	}
	
	// resource loading helpers
	
	protected void gatherTextureData(ResourceManager manager, ProfilerFiller profiler)
	{
		LOGGER.info("Starting autopalette texture generation");
		// get all available packs (even unselected ones)
		Minecraft minecraft = Minecraft.getInstance();
		PackRepository packList = minecraft.getResourcePackRepository();
		Map<String, Pack> selectedPacks = packList.getSelectedPacks()
			.stream()
			.collect(Collectors.toMap(Pack::getId, info->info));
		Map<String, Pack> unselectedPacks = minecraft.getResourcePackRepository()
			.getAvailablePacks()
			.stream()
			.filter(info -> !selectedPacks.containsKey(info.getId()))
			.collect(Collectors.toMap(Pack::getId, info->info));
		
		// for each palette override, we want to
			// load the specified texture from the given available pack
			// if that was successful, use the palette override to generate a new texture
		Map<ResourceLocation, Callable<InputStream>> resourceStreams = new HashMap<>();
		
		// parse raw jsons from resource packs
		Map<ResourceLocation, JsonElement> rawJsons = JSON_HELPER.prepare(manager, profiler);
		rawJsons.forEach((id,json) -> PaletteOverride.CODEC.parse(JsonOps.INSTANCE, json)
			.resultOrPartial(LOGGER::error) // bad data -> log it
			.flatMap(result -> generateImage(id,result,selectedPacks,unselectedPacks))
			.ifPresent(pair->
			{
				NativeImage image = pair.getFirst();
				ResourceLocation textureID = makeTextureID(id);
				resourceStreams.put(textureID, () -> new ByteArrayInputStream(image.asByteArray()));
				// if the original texture had metadata, we'll need to provide that later
				pair.getSecond().ifPresent(metadataGetter -> resourceStreams.put(getMetadataLocation(textureID), metadataGetter));
			}));
		
		this.resources = resourceStreams;
		LOGGER.info("Concluded autopalette texture generation");
	}
	
	public static ResourceLocation makeTextureID(ResourceLocation jsonID)
	{
		return new ResourceLocation(jsonID.getNamespace(), TEXTURE_DIRECTORY+jsonID.getPath()+".png");
	}
	
	// from FallbackResourceManager
	public static ResourceLocation getMetadataLocation(ResourceLocation id)
	{
		return new ResourceLocation(id.getNamespace(), id.getPath() + ".mcmeta");
	}
	
	public static Optional<Pair<NativeImage, Optional<Callable<InputStream>>>> generateImage(ResourceLocation overrideID, PaletteOverride override, Map<String,Pack> selectedPacks, Map<String,Pack> unselectedPacks)
	{
		ResourceLocation parentTextureID = override.getParentTextureID();
		String parentPackID = override.getParentPack();
		Pack info = override.getPackInfo(selectedPacks, unselectedPacks);
		if (info == null)
		{
			// if we can't find the pack, don't do anything
			// if the pack doesn't exist at all, we should probably warn the user
			if (!selectedPacks.containsKey(parentPackID) && !unselectedPacks.containsKey(parentPackID))
			{
				LOGGER.error("Cannot override texture {} in pack {} specified by override {}: pack does not exist", parentTextureID, parentPackID, overrideID);
				LOGGER.error("Available selected packs: {}", selectedPacks.keySet());
				LOGGER.error("Available unselected packs: {}", unselectedPacks.keySet());
			}
			return Optional.empty();
		}
		
		// closing open packs might mess with zipfile packs, so let's not do that
		// (autopalette textures that load from standalone resource packs should always require that the pack be selected)
		PackResources pack = info.open();
		if (pack == null)
		{
			LOGGER.error("Cannot override texture {} in pack {} specified by override {}: pack cannot be opened", parentTextureID, parentPackID, overrideID);
			return Optional.empty();
		}
		ResourceLocation parentFile = makeTextureID(parentTextureID);
		try(InputStream inputStream = pack.getResource(PackType.CLIENT_RESOURCES, parentFile))
		{
			// use the palette map to generate a new texture
			NativeImage image = NativeImage.read(inputStream);
			NativeImage transformedImage = override.transformImage(image);
			// check if the original texture had metadata -- we'll need to provide that from the virtual pack if it exists
			ResourceLocation metadata = getMetadataLocation(parentFile);
			Optional<Callable<InputStream>> metadataLookup = Optional.empty();
			if (pack.hasResource(PackType.CLIENT_RESOURCES, metadata))
			{
				BufferedReader bufferedReader = null;
				JsonObject metadataJson = null;
				// read the metadata json from IO now so we don't trip over other IO readers later
				try (InputStream metadataStream = pack.getResource(PackType.CLIENT_RESOURCES, metadata))
				{
					bufferedReader = new BufferedReader(new InputStreamReader(metadataStream, StandardCharsets.UTF_8));
					metadataJson = GsonHelper.parse(bufferedReader);
				} finally {
		               IOUtils.closeQuietly(bufferedReader);
	            }
				if (metadataJson != null)
				{
					JsonObject metaDataJsonForLambda = metadataJson; // closure variables must be final or effectively final
					// we'll need to provide the json in InputStream format
					metadataLookup = Optional.of(() -> new ByteArrayInputStream(metaDataJsonForLambda.toString().getBytes()));
				}
			}
			return Optional.of(Pair.of(transformedImage, metadataLookup));
		}
		catch (IOException e)
		{
			LOGGER.error("Cannot override texture {} in pack {} specified by override {}: error getting texture", parentTextureID, parentPackID, overrideID);
			e.printStackTrace();
			return Optional.empty();
		}
	}
	
	// resource pack stuff

	@Override
	public String getName()
	{
		return "Autopalette Textures";
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException
	{
		return serializer instanceof PackMetadataSectionSerializer
			? (T)this.packInfo
			: null;
	}

	@Override
	protected boolean hasResource(String filename)
	{
		// only called by hasResource(type, RL) in this class, which we override here
		return false;
	}
	
	@Override
    public InputStream getRootResource(String fileName) throws IOException
    {
        // the root resource getter is mostly just used for getting the pack.png image
		// we don't have one of those at the moment (if our resource pack shows up on the pack list, we could consider having one)
        throw new ResourcePackFileNotFoundException(this.file, fileName);
    }

	@Override
	protected InputStream getResource(String filename) throws IOException
	{
		// only called by getMetadataSection, getResource(type,RL), and getRootResource(string) in this class,
		// which we override to ignore this
		throw new ResourcePackFileNotFoundException(this.file, filename);
	}

	@Override
	public void close()
	{
	}

	@Override
	public Set<String> getNamespaces(PackType packType)
	{
		// the namespaces present in the pack are added to the resource manager using this method when the pack is added
		// this is used to find resources later, so it's very important
		// unfortunately, when resources reload, this is called BEFORE reload listeners fire!
		// seems like the best thing we can do for now is to require that all textures use the "autopalette" namespace
		return NAMESPACES;
	}

	@Override
	public boolean hasResource(PackType type, ResourceLocation id)
	{
		return type == PackType.CLIENT_RESOURCES && (this.resources.containsKey(id));
	}

	@Override
	public InputStream getResource(PackType type, ResourceLocation id) throws IOException
	{
		// this will be called by the texture stitcher on the main thread after resources are loaded,
		// so textures will need to be ready and retrievable by then
		
		if (this.resources.containsKey(id))
		{
			Callable<InputStream> streamGetter = this.resources.get(id);
			if (streamGetter == null)
			{
				throw this.makeFileNotFoundException(type, id);
			}
			
			try
			{
				return streamGetter.call();
			}
			catch (Exception e)
			{
				e.printStackTrace();
				throw this.makeFileNotFoundException(type, id);
			}
		}
		else
		{
			throw this.makeFileNotFoundException(type, id);
		}
	}
	
	public ResourcePackFileNotFoundException makeFileNotFoundException(PackType type, ResourceLocation id)
	{
		// from ResourcePack
		String path = String.format("%s/%s/%s", type.getDirectory(), id.getNamespace(), id.getPath());
		return new ResourcePackFileNotFoundException(this.file, path);
	}

	@Override
	public Collection<ResourceLocation> getResources(PackType packType, String namespace, String id, Predicate<ResourceLocation> filter)
	{
		// getResources isn't called by anything related to texture stitching;
		// it IS called by some reload listeners on worker threads to load resources, we should make sure we don't break anything
		return NO_RESOURCES;
	}

}
