package commoble.autopalette;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;

@Mod(Autopalette.MODID)
public class Autopalette
{
	public static final String MODID = "autopalette";
	
	public Autopalette()
	{
		// client-only mod, tell displaytest to ignore us
		ModLoadingContext modLoader = ModLoadingContext.get();
		modLoader.registerExtensionPoint(IExtensionPoint.DisplayTest.class,
			() -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
	}

	// we need to use EBS/SubscribeEvent to enqueue mainthread work to the mod construct event
	// static classes are compiled into wholly separate classes, so this is safe to be here
	// (we put this here so this is the only class in the sources with entry points in it)
	@EventBusSubscriber(modid=Autopalette.MODID, value=Dist.CLIENT, bus=Bus.MOD)
	public static class ClientProxy
	{
		public static final AutopalettePack VIRTUAL_PACK = new AutopalettePack();
		
		@SubscribeEvent
		public static void onClientInit(FMLConstructModEvent event)
		{
			event.enqueueWork(ClientProxy::afterClientInit);
		}
		
		// runs on main thread after mod instance is constructed on the client jar
		private static void afterClientInit()
		{
			Minecraft minecraft = Minecraft.getInstance();
			
			// register our fake resource pack
			minecraft.getResourcePackRepository().addPackFinder((infoConsumer, packFactory) ->
				infoConsumer.accept(new Pack(
					"autopalette_textures",	// id
					true,	// required -- this MAY need to be true for the pack to be enabled by default
					() -> VIRTUAL_PACK, // pack supplier
					new TranslatableComponent("autopalette.pack_title"), // title
					new TranslatableComponent("autopalette.pack_description"), // description
					PackCompatibility.COMPATIBLE,
					Pack.Position.TOP,
					false, // fixed position? no
					PackSource.DEFAULT,
					false // hidden? no
					)));
			
			// if we can't cast the resource manager then modloading should fail
			ReloadableResourceManager resourceManager = (ReloadableResourceManager)(minecraft.getResourceManager());
			resourceManager.registerReloadListener(VIRTUAL_PACK);
			
		}
	}
}
