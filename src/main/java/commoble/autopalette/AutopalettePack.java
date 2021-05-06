package commoble.autopalette;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.resources.JsonReloadListener;
import net.minecraft.profiler.IProfiler;
import net.minecraft.resources.IFutureReloadListener;
import net.minecraft.resources.IResourceManager;
import net.minecraft.resources.IResourcePack;
import net.minecraft.resources.ResourcePack;
import net.minecraft.resources.ResourcePackFileNotFoundException;
import net.minecraft.resources.ResourcePackInfo;
import net.minecraft.resources.ResourcePackList;
import net.minecraft.resources.ResourcePackType;
import net.minecraft.resources.data.IMetadataSectionSerializer;
import net.minecraft.resources.data.PackMetadataSection;
import net.minecraft.resources.data.PackMetadataSectionSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TranslationTextComponent;

public class AutopalettePack extends ResourcePack implements IFutureReloadListener
{
	public static final Logger LOGGER = LogManager.getLogger();
	public static final Gson GSON = new Gson();
	public static final String DIRECTORY = "autotextures";
	public static final String TEXTURE_DIRECTORY = "textures/";
	// reuse a JRL to parse jsons, hehehe
	public static final JsonReloadListener JSON_HELPER = new JsonReloadListener(GSON, DIRECTORY)
	{
		// noop, we just use the JRL to prepare jsonelements for us
		@Override
		protected void apply(Map<ResourceLocation, JsonElement> jsons, IResourceManager manager, IProfiler profiler)
		{}
	};
		
	public static final Set<String> NAMESPACES = ImmutableSet.of(Autopalette.MODID);
	public static final List<ResourceLocation> NO_RESOURCES = Collections.emptyList();
	private final PackMetadataSection packInfo;
	private Map<ResourceLocation, NativeImage> textures = new HashMap<>();

	public AutopalettePack()
	{
		super(new File("autopalette_virtual_pack"));
		this.packInfo = new PackMetadataSection(new TranslationTextComponent("autopalette.pack_description"), 6);;
	}
	
	// resource loading stuff
	
	@Override
	public CompletableFuture<Void> reload(IFutureReloadListener.IStage stage, IResourceManager manager,
		IProfiler workerProfiler, IProfiler mainProfiler,
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
	
	protected void gatherTextureData(IResourceManager manager, IProfiler profiler)
	{
		System.out.println("Starting texture generation");
		// get all available packs (even unselected ones)
		Minecraft minecraft = Minecraft.getInstance();
		ResourcePackList packList = minecraft.getResourcePackRepository();
		Map<String, ResourcePackInfo> selectedPacks = packList.getSelectedPacks()
			.stream()
			.collect(Collectors.toMap(ResourcePackInfo::getId, info->info));
		Map<String, ResourcePackInfo> unselectedPacks = minecraft.getResourcePackRepository()
			.getAvailablePacks()
			.stream()
			.filter(info -> !selectedPacks.containsKey(info.getId()))
			.collect(Collectors.toMap(ResourcePackInfo::getId, info->info));
		
		// parse raw jsons from resource packs
		Map<ResourceLocation, JsonElement> rawJsons = JSON_HELPER.prepare(manager, profiler);
		// convert to palette override data
		Map<ResourceLocation, PaletteOverride> textureOverrides = new HashMap<>();
		rawJsons.forEach((id,json) -> PaletteOverride.CODEC.parse(JsonOps.INSTANCE, json)
			.resultOrPartial(LOGGER::error) // bad data -> log it
			.ifPresent(result -> textureOverrides.put(id, result))); // good data -> keep it
		
		// for each palette override, we want to
			// load the specified texture from the given available pack
			// if that was successful, use the palette override to generate a new texture
		Map<ResourceLocation, NativeImage> fakeTextures = new HashMap<>();
		textureOverrides.forEach((id,override) ->
			generateImage(id,override,selectedPacks,unselectedPacks)
				.ifPresent(image->fakeTextures.put(makeTextureID(id), image)));
		
		this.textures = fakeTextures;
		System.out.println("Concluded texture generation");
	}
	
	public static ResourceLocation makeTextureID(ResourceLocation jsonID)
	{
		return new ResourceLocation(jsonID.getNamespace(), TEXTURE_DIRECTORY+jsonID.getPath()+".png");
	}
	
	public static Optional<NativeImage> generateImage(ResourceLocation overrideID, PaletteOverride override, Map<String,ResourcePackInfo> selectedPacks, Map<String,ResourcePackInfo> unselectedPacks)
	{
		ResourceLocation parentTextureID = override.getParentTextureID();
		String parentPackID = override.getParentPack();
		ResourcePackInfo info = override.getPackInfo(selectedPacks, unselectedPacks);
		if (info == null)
		{
			LOGGER.debug("Cannot override texture {} in pack {} specified by override {}: pack does not exist", parentTextureID, parentPackID, overrideID);
			return Optional.empty();
		}
		
		// closing open packs might mess with zipfile packs, so let's not do that
		// (autopalette textures that load from standalone resource packs should always require that the pack be selected)
		IResourcePack pack = info.open();
		if (pack == null)
		{
			LOGGER.error("Cannot override texture {} in pack {} specified by override {}: pack cannot be opened", parentTextureID, parentPackID, overrideID);
			return Optional.empty();
		}
		ResourceLocation parentFile = makeTextureID(parentTextureID);
		try(InputStream inputStream = pack.getResource(ResourcePackType.CLIENT_RESOURCES, parentFile))
		{
			NativeImage image = NativeImage.read(inputStream);
			NativeImage transformedImage = override.transformImage(image);
			return Optional.of(transformedImage);
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
	public <T> T getMetadataSection(IMetadataSectionSerializer<T> serializer) throws IOException
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
	public Set<String> getNamespaces(ResourcePackType packType)
	{
		// the namespaces present in the pack are added to the resource manager using this method when the pack is added
		// this is used to find resources later, so it's very important
		// unfortunately, when resources reload, this is called BEFORE reload listeners fire!
		// seems like the best thing we can do for now is to require that all textures use the "autopalette" namespace
		return NAMESPACES;
	}

	@Override
	public boolean hasResource(ResourcePackType type, ResourceLocation id)
	{
		return type == ResourcePackType.CLIENT_RESOURCES && this.textures.containsKey(id);
	}

	@Override
	public InputStream getResource(ResourcePackType type, ResourceLocation id) throws IOException
	{
		// this will be called by the texture stitcher on the main thread after resources are loaded,
		// so textures will need to be ready and retrievable by then
		
		NativeImage image = this.textures.get(id);
		if (image == null)
		{
			// from ResourcePack
			String path = String.format("%s/%s/%s", type.getDirectory(), id.getNamespace(), id.getPath());
			throw new ResourcePackFileNotFoundException(this.file, path);
		}
		
		return new ByteArrayInputStream(image.asByteArray());
	}

	@Override
	public Collection<ResourceLocation> getResources(ResourcePackType packType, String namespace, String id, int maxDepth, Predicate<String> filter)
	{
		// getResources isn't called by anything related to texture stitching;
		// it IS called by some reload listeners on worker threads to load resources, we should make sure we don't break anything
		return NO_RESOURCES;
	}

}
