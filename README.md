Autopalette is a forge mod for minecraft that enables mods and resource packs to generate palette-swaps of other textures when resource packs are loaded. These generated textures do not replace the original textures and may be used alongside them.

## Autotexture Jsons

Texture generation is done by way of autotexture jsons. These should be placed in the assets folder of a mod or resource pack at the file location

```
assets/autopalette/autotextures/id.json
```

This will cause a texture with the resource ID `autopalette:textures/id.png` or the texture ID `autopalette:id` to be generated (the latter can be used as a texture ID in model jsons, etc).

Due to technical limitations related to the way autopalette generates a fake resource pack, the namespace of the autotexture json must always be "autopalette" at this time.

Users who are creating autotexture jsons may wish to include the "actual" namespace in the ID, e.g. a mod or resource pack's `assets/autopalette/autotextures/examplemod/block/dark_cobblestone.json` becomes `autopalette:examplemod/block/dark_cobblestone`.

Autotexture jsons have the following format:

```json
{
	"pack": <optional-string>, // the ID of the pack to read the base texture from, defaults to "vanilla"
	"require_pack": <optional-boolean>, // defaults false; if true, an autotexture will only generate if the target pack is currently selected by the client
	"parent": <string>, // the ID of the parent texture to generate a palette swap from, e.g. "minecraft:cobblestone",
	"palette":
	{
		<rrggbbaa-input-string>: <rrggbbaa-output-string>,
		// more string-string pairs as needed
	}
}
```

The pack ID should be one of the following:

|ID|Pack Type|
|---|-
|vanilla|Standard vanilla assets
|programer_art|Original vanilla assets
|mod_resources|Assets from forge mods' builtin resources
|file/folder_name|Folder resource packs in the resourcepacks folder, where "folder_name" is the name of the resource pack's root folder
|file/zip_name.zip|Zip resource packs in the resourcepacks folder, where "zip_name" is the name of the resource pack's zip file

For each specific hexidecimal color in the palette map, every pixel in the original texture will be replaced with the output color in the newly generated texture.

The color strings may be six- or eight-character hexidecimal color codes in RRGGBB or RRGGBBAA format. If the alpha value is omitted, then FF (255 or 100% opacity) is used for the input alpha.

If the original texture has an .mcmeta file specifying a texture animation, that file will be reused for the palette-swapped texture as well. Using a different mcmeta file for the new texture is not currently possible.

Example autotexture json:

```json
assets/autopalette/autotextures/autopaletteexamplemod/block/dark_cobblestone.json
{
	"parent": "minecraft:block/cobblestone",
	"palette":
	{
		"6e6d6d": "3d3a41",
		"616161": "332c2f",
		"a6a6a6": "9d7e7b",
		"b5b5b5": "b38f8c",
		"525252": "261f1e",
		"888788": "77666e"
	}
}
```

This creates a palette swap of the vanilla cobblestone texture, which we can then refer to in a model json:

```
assets/autopaletteexamplemod/models/block/dark_cobblestone.json
{
	"parent": "block/cube_all",
	"textures":
	{
		"all": "autopalette:autopaletteexamplemod/block/dark_cobblestone"
	}
}
```

## Using autopalette in a mod development environment

Using autopalette or other forge mods in a forge mod development environment requires that the mod be added from a maven as an fg.deobf dependency.

Autopalette is currently available via cursemaven.

https://www.cursemaven.com/