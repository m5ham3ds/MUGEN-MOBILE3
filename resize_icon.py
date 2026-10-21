from PIL import Image
import os

img = Image.open("/tmp/logo.png").convert("RGBA")
sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

for density, size in sizes.items():
    resized = img.resize((size, size), Image.Resampling.LANCZOS)
    
    dir_path = f"app/src/main/res/mipmap-{density}"
    os.makedirs(dir_path, exist_ok=True)
    
    # Save standard and round
    resized.save(f"{dir_path}/ic_launcher.png")
    resized.save(f"{dir_path}/ic_launcher_round.png")
    
    # Save foreground for adaptive icon
    fg_size = int(size * (108/48)) # foreground scale
    resized_fg = img.resize((fg_size, fg_size), Image.Resampling.LANCZOS)
    resized_fg.save(f"{dir_path}/ic_launcher_foreground.png")

print("Done generating icons!")
