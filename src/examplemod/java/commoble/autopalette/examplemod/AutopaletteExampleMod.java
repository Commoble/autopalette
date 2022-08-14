package commoble.autopalette.examplemod;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

@Mod(AutopaletteExampleMod.MODID)
public class AutopaletteExampleMod
{
	public static final String MODID = "autopaletteexamplemod";
	
	public AutopaletteExampleMod()
	{
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		
		DeferredRegister<Block> blocks = makeDeferredRegister(modBus, ForgeRegistries.BLOCKS);
		blocks.register("dark_cobblestone", () ->
			new Block(Properties.copy(Blocks.COBBLESTONE)));
		blocks.register("green_magma", () ->
			new Block(Properties.copy(Blocks.MAGMA_BLOCK)));
	}
	
	private static <T> DeferredRegister<T> makeDeferredRegister(IEventBus modBus, IForgeRegistry<T> registry)
	{
		DeferredRegister<T> register = DeferredRegister.create(registry, MODID);
		register.register(modBus);
		return register;
	}
}