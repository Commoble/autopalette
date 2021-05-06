package commoble.autopalette.examplemod;

import net.minecraft.block.AbstractBlock.Properties;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;

@Mod(AutopaletteExampleMod.MODID)
public class AutopaletteExampleMod
{
	public static final String MODID = "autopaletteexamplemod";
	
	public AutopaletteExampleMod()
	{
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		
		DeferredRegister<Block> blocks = makeDeferredRegister(modBus, ForgeRegistries.BLOCKS);
		RegistryObject<Block> darkCobblestone = blocks.register("dark_cobblestone", () ->
			new Block(Properties.copy(Blocks.COBBLESTONE)));
	}
	
	private static <T extends IForgeRegistryEntry<T>> DeferredRegister<T> makeDeferredRegister(IEventBus modBus, IForgeRegistry<T> registry)
	{
		DeferredRegister<T> register = DeferredRegister.create(registry, MODID);
		register.register(modBus);
		return register;
	}
}