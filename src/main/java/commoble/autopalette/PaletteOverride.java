package commoble.autopalette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.resources.ResourcePackInfo;
import net.minecraft.util.ResourceLocation;

public class PaletteOverride
{
	// json format codec
	public static final Codec<PaletteOverride> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		// the pack ID of the asset pack to pull base textures from
		Codec.STRING.optionalFieldOf("pack", "vanilla").forGetter(PaletteOverride::getParentPack),
		// if require_pack is true, the pack must be selected by the user or a texture will not be generated on resource load
		Codec.BOOL.optionalFieldOf("require_pack", false).forGetter(PaletteOverride::getRequirePack),
		// parent is the texture ID of the base texture to make a palette swap for
		ResourceLocation.CODEC.fieldOf("parent").forGetter(PaletteOverride::getParentTextureID),
		// map of hexidecimal integer codes for the palette swap
		Codec.unboundedMap(Codec.STRING,Codec.STRING).comapFlatMap(PaletteOverride::makePaletteMap, PaletteOverride::encodeMap)
			.fieldOf("palette").forGetter(PaletteOverride::getPalette)
	).apply(instance, PaletteOverride::new));

	private final String pack;	public String getParentPack() { return this.pack; }
	private final boolean requirePack; public boolean getRequirePack() { return this.requirePack; }
	private final ResourceLocation parent;	public ResourceLocation getParentTextureID() { return this.parent; }
	private final Int2IntMap map;	public Int2IntMap getPalette() { return this.map; }
	
	public PaletteOverride(String pack, boolean requirePack, ResourceLocation parent, Int2IntMap map)
	{
		this.pack = pack;
		this.requirePack = requirePack;
		this.parent = parent;
		this.map = map;
	}
	
	public @Nullable ResourcePackInfo getPackInfo(Map<String,ResourcePackInfo> selectedPacks, Map<String,ResourcePackInfo> unselectedPacks)
	{
		// if we require a pack to be selected to pull textures from it, only look in the selected packs
		// if we don't require a pack to be selected, look in the selected packs first,
			// then look in the unselected packs if the pack wasn't in the selected packs
		String packID = this.getParentPack();
		ResourcePackInfo selectedInfo = selectedPacks.get(packID);
		return selectedInfo != null || this.requirePack
			? selectedInfo
			: unselectedPacks.get(packID);
	}
	
	/**
	 * Convert a raw map from json to an int2int palette swap map
	 * @param raws A string-string map whose strings are in hexidecimal format "RRGGBB" or "RRGGBBAA"
	 * @return The map encoded as format-flipped ints.
	 * RRGGBB strings are encoded as AABBGGRR ints. RRGGBBAA strings are also encoded as AABBGGRR ints, using FF (=255) as the alpha value.
	 * Any parsing failures or incorrectly-formatted strings result in a partial result being returned instead.
	 */
	public static DataResult<Int2IntMap> makePaletteMap(Map<String,String> raws)
	{
		Int2IntMap result = new Int2IntOpenHashMap();
		List<String> errors = new ArrayList<>();
		raws.forEach((key,value) ->
		{
			int keySize = key.length();
			if (keySize == 6)
			{
				key = key + "FF"; // if only RGB is specified, treat alpha as fully opaque
			}
			else if (keySize != 8)
			{
				errors.add(String.format("Invalid RRGGBBAA key %s -- must be 6 or 8 characters", key));
				return;
			}
			
			int valueSize = value.length();
			if (valueSize == 6)
			{
				value = value + "FF";
			}
			else if (valueSize != 8)
			{
				errors.add(String.format("Invalid RRGGBBAA value %s -- must be 6 or 8 characters", value));
				return;
			}
			int intKey, intValue;
			try
			{
				intKey = Integer.parseUnsignedInt(key,16);
			}
			catch(NumberFormatException e)
			{
				errors.add(String.format("Could not parse RRGGBBAA key %s as integer", key));
				return;
			}
			try
			{
				intValue = Integer.parseUnsignedInt(value,16);
			}
			catch(NumberFormatException e)
			{
				errors.add(String.format("Could not parse RRGGBBAA value %s as integer", value));
				return;
			}
			// flip RGBA to ABGR for the nativeimage
			intKey = flipRGBA(intKey);
			intValue = flipRGBA(intValue);
			result.put(intKey, intValue);
		});
		if (errors.size() > 0)
		{
			return DataResult.error(String.format("Encountered errors parsing palette: %s", errors), result);
		}
		return DataResult.success(result);
	}

	/**
	 * Does the reverse of makePaletteMap, converting ints back to strings. Strings are always in RRGGBBAA format.
	 * @param colors int-to-int palette swap map
	 * @return the map encoded as string-string pairs in "RRGGBBAA" format
	 */
	public static Map<String,String> encodeMap(Int2IntMap colors)
	{
		Map<String,String> result = new HashMap<>();
		colors.forEach((key,value) -> result.put(String.format("%08X", flipRGBA(key)), String.format("%08X", flipRGBA(value))));
		return result;
	}
	
	/**
	 * Given a 32-bit integer in RRGGBBAA format, flips it to AABBGGRR format (or vice-versa)
	 * @param input An integer representing a 32-bit color in RRGGBBAA or AABBGGRR format
	 * @return A 32-bit color int in the other format
	 */
	// nativeimage's format is weird
	public static int flipRGBA(int input)
	{
		int r = NativeImage.getA(input);
		int g = NativeImage.getB(input);
		int b = NativeImage.getG(input);
		int a = NativeImage.getR(input);
		return NativeImage.combine(a, b, g, r);
	}
	
	/**
	 * Modifies a NativeImage *in-place* according to this override's palette map
	 * @param image an image to modify
	 * @return the image, modified in place
	 */
	public NativeImage transformImage(NativeImage image)
	{
		int width = image.getWidth();
		int height = image.getHeight();
		
		for (int x=0; x<width; x++)
		{
			for (int y=0; y<height; y++)
			{
				// the pixel format is ARGB, stupidly
				int oldValue = image.getPixelRGBA(x, y);
				int newValue = this.map.getOrDefault(oldValue, oldValue); // if the old value isn't present, give the old value back
				if (newValue != oldValue) // if a new RGBA value for the old value is specified, replace it 
				{
					image.setPixelRGBA(x, y, newValue);
				}
			}
		}
		
		return image;
	}
}
