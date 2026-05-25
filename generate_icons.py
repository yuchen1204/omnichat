import os
from PIL import Image
import shutil

source_image_path = r"e:\omnichat\icon.png"
res_dir = r"e:\omnichat\app\src\main\res"

if not os.path.exists(source_image_path):
    print("Source image not found!")
    exit(1)

img = Image.open(source_image_path).convert("RGBA")

# Android mipmap densities and sizes
densities = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

for density, size in densities.items():
    folder_path = os.path.join(res_dir, f"mipmap-{density}")
    os.makedirs(folder_path, exist_ok=True)
    
    # Resize image
    resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
    
    # Save as ic_launcher.png
    resized_img.save(os.path.join(folder_path, "ic_launcher.png"), format="PNG")
    
    # Save as ic_launcher_round.png
    resized_img.save(os.path.join(folder_path, "ic_launcher_round.png"), format="PNG")
    
    # For adaptive icon foreground (optional but good for compatibility if we keep anydpi)
    foreground_size = int(size * (108 / 48)) # foregrounds are 108dp base
    foreground_img = img.resize((foreground_size, foreground_size), Image.Resampling.LANCZOS)
    foreground_img.save(os.path.join(folder_path, "ic_launcher_foreground.png"), format="PNG")
    
print("Successfully generated mipmap PNGs.")

# Remove anydpi-v26 to force fallback to standard PNGs, which guarantees the icon looks exactly like the PNG
anydpi_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
if os.path.exists(anydpi_dir):
    shutil.rmtree(anydpi_dir)
    print("Removed mipmap-anydpi-v26 to fallback to legacy PNGs.")
    
# Remove default foreground/background if they exist in drawable
for file in ["ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"]:
    path = os.path.join(res_dir, "drawable", file)
    if os.path.exists(path):
        os.remove(path)

print("Icon replacement complete!")
