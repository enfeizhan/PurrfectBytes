# Font Setup Guide

This document explains how fonts are loaded in PurrfectBytes and how to ensure proper font rendering across different platforms.

## How Font Loading Works

The application uses a multi-tier font loading strategy:

1. **Primary fonts** - Platform-specific font paths defined in `src/config/settings.py`
2. **System font directories** - Automatic discovery in common system font locations
3. **Fallback font** - PIL's default bitmap font (very small, last resort)

## Platform-Specific Font Paths

### macOS
Default primary fonts:
- `/Library/Fonts/Arial Unicode.ttf`
- `/System/Library/Fonts/Helvetica.ttc`
- `/System/Library/Fonts/Avenir.ttc`
- `/System/Library/Fonts/Supplemental/Arial.ttf`

System directories searched:
- `/Library/Fonts`
- `/System/Library/Fonts`
- `/System/Library/Fonts/Supplemental`
- `~/Library/Fonts`

### Linux/Ubuntu
Default primary fonts:
- `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf`
- `/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf`
- `/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf`
- `/usr/share/fonts/truetype/ubuntu/Ubuntu-R.ttf`

System directories searched:
- `/usr/share/fonts`
- `/usr/local/share/fonts`
- `/usr/share/fonts/truetype`
- `/usr/share/fonts/truetype/dejavu`
- `/usr/share/fonts/truetype/liberation`
- `/usr/share/fonts/truetype/noto`
- `~/.fonts`
- `~/.local/share/fonts`

### Windows
Default primary fonts:
- `C:\Windows\Fonts\arial.ttf`
- `C:\Windows\Fonts\calibri.ttf`
- `C:\Windows\Fonts\segoeui.ttf`

System directories searched:
- `C:\Windows\Fonts`
- `~\AppData\Local\Microsoft\Windows\Fonts`

## Installing Required Fonts on Ubuntu/Debian

If you're experiencing small fonts or rendering issues on Ubuntu, install these recommended font packages:

```bash
# Install DejaVu fonts (recommended)
sudo apt-get update
sudo apt-get install fonts-dejavu fonts-dejavu-core fonts-dejavu-extra

# Install Liberation fonts (alternative)
sudo apt-get install fonts-liberation fonts-liberation2

# Install Noto fonts (excellent Unicode support, including CJK)
sudo apt-get install fonts-noto fonts-noto-core fonts-noto-cjk

# Install Ubuntu fonts
sudo apt-get install fonts-ubuntu
```

### For CJK (Chinese, Japanese, Korean) Support

If you're working with Asian languages, install comprehensive CJK font packages:

```bash
# Noto CJK fonts (recommended for best coverage)
sudo apt-get install fonts-noto-cjk fonts-noto-cjk-extra

# Alternative CJK fonts
sudo apt-get install fonts-wqy-microhei fonts-wqy-zenhei
```

### Verify Font Installation

After installation, you can verify the fonts are available:

```bash
# List installed fonts
fc-list | grep -i dejavu
fc-list | grep -i liberation
fc-list | grep -i noto

# Update font cache
fc-cache -fv
```

## Troubleshooting

### Problem: Generated videos have very small text

**Cause**: The application is falling back to PIL's default bitmap font because no TrueType fonts were found.

**Solution**:
1. Install one of the recommended font packages above
2. Restart the application after installing fonts
3. Check that fonts are in the expected directories

### Problem: Fonts work on macOS but not on Ubuntu

**Cause**: Platform-specific font paths are different.

**Solution**: The latest version of PurrfectBytes includes cross-platform font detection. Make sure you're using the updated version with:
- `src/utils/font_utils.py` - Platform detection
- `src/config/settings.py` - Platform-specific font paths

### Problem: Special characters or emojis not rendering

**Cause**: The loaded font doesn't support those Unicode characters.

**Solution**: Install Noto fonts which have comprehensive Unicode coverage:
```bash
sudo apt-get install fonts-noto fonts-noto-cjk fonts-noto-emoji
```

## Custom Font Configuration

You can add custom font paths by modifying `src/config/settings.py`:

```python
# Add your custom font paths to the beginning of the list
_primary_font_paths = [
    "/path/to/your/custom/font.ttf",
    # ... existing paths
]
```

## Font Size Configuration

The default font sizes are defined in `src/config/settings.py`:

```python
VIDEO_CONFIG = {
    "font_size": 48,        # Normal text
    "font_size_bold": 56,   # Highlighted text
    # ...
}
```

You can adjust these values to make text larger or smaller in generated videos.

## Technical Details

The font loading logic is implemented in `src/utils/font_utils.py`:

- `_get_system_font_directories()` - Returns platform-specific font directories
- `load_font(font_size)` - Loads a font with fallback strategy
- `get_available_fonts()` - Lists all available system fonts
- `find_best_font_for_text(text, font_size)` - Finds font that supports specific text

The system automatically:
- Detects the operating system
- Searches all relevant font directories
- Walks subdirectories to find fonts
- Handles permission errors gracefully
- Falls back to PIL default if no fonts found