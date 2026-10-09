# Gallery

A replacement for the stock Android gallery app. Kotlin + Jetpack Compose, **Android 13+**.

## Features
- **Photos tab**: everything grouped by month, with All / Photos / Videos filters
- **Albums tab**: Favorites, every folder with photos or videos, Stickers, and Trash
  - Create, rename and delete albums; move photos/videos between albums
  - Albums are real folders, so other apps (and a PC over USB) see the same organization.
    New albums are created under `Pictures/`.
- **Selecting**: long-press a thumbnail, keep your finger down and drag to select a range
  (the grid scrolls when you reach the top or bottom). Starting on a selected photo deselects.
  Then favorite, share, move or delete them together.
- **Trash**: see what's in Android's trash and how many days are left, restore items,
  delete them forever, or empty the trash
- **Favorites**: uses Android's own favorite flag, so other gallery apps see them too
- **Viewer**: swipe, pinch/double-tap zoom, favorite, share, move, delete, details,
  set as wallpaper, add to stickers
- **Video player**: scrub bar using ExoPlayer's scrubbing mode, ±10s, frame-by-frame stepping,
  and **Capture still** (saves the exact frame as a full-resolution JPEG)

## Photo editor
Tabs along the bottom:
- **Crop**: free or fixed ratios, rotate, flip, and a **Straighten** slider (±45°).
  When straightened, either **Crop in** (zooms just enough to hide the corners) or
  **Background** (keeps the whole photo, corners filled black, white or transparent)
- **Adjust**: exposure, brightness, contrast, highlights, shadows, saturation, vibrance,
  warmth, tint, sharpness, vignette, grain
- **Filters**: Vivid, Warm, Cool, Golden, Fade, Vintage, Dramatic, Sepia, B&W, Noir, with strength
- **Cutout**: on-device background removal (ML Kit through Google Play services), then make the
  background transparent, blurred or a solid color — or save the cutout as a sticker
- **Markup**: text (fonts, colors, plain/outline/bubble, alignment) and stickers. Drag to move,
  pinch/twist or use the corner dot to resize and rotate, the red dot to delete, tap selected text to edit

Undo/redo at the top; hold the compare button to see the photo without color changes.
**Save copy** puts the edited copy next to the original (same album, same date) and keeps the
date/camera/location info. **⋮ → Overwrite original** replaces the original (JPEG, PNG and WebP).
Edits are saved at up to 4096 px on the longest side.

## Stickers
Stickers live in `Pictures/Stickers`, so they survive reinstalls and you can drop images in from a PC.
They're hidden from the Photos tab and show up as the Stickers album. Add one from a photo
(viewer ⋮ → Add to stickers), from the sticker picker while editing, or from the Cutout tab.

## Confirmations
Android asks for confirmation before an app changes files another app created (moving, renaming,
favoriting, deleting, restoring, overwriting). **Albums → ⋮ → Move & delete without asking**
grants Gallery "Media management" access so it stops asking.

## Run it
1. Unzip, then in Android Studio choose **File → Open** and pick the `Gallery` folder.
2. Let Gradle sync finish.
3. Connect your phone with USB debugging on, pick it in the device dropdown, and press **Run**.

## Code map
- `data/MediaRepository.kt` reads photos, videos and trash from MediaStore
- `data/AlbumOps.kt` album folders, sticker folder, moving items
- `data/MediaSaver.kt` saving, overwriting, EXIF copying, wallpaper, video stills
- `data/Cutout.kt` background removal and sticker trimming
- `ui/PhotosScreen.kt`, `ui/AlbumsScreen.kt`, `ui/TrashScreen.kt` main screens
- `ui/MediaGrid.kt` + `ui/DragSelect.kt` shared grid with swipe-to-select
- `ui/MediaActions.kt` move / rename / favorite / trash / restore / delete with Android's prompts
- `ui/ViewerScreen.kt`, `ui/VideoPlayerScreen.kt` viewer and player
- `edit/EditModel.kt` edit state, filters, text options
- `edit/EditRenderer.kt` GPU rendering (AGSL color shader, geometry, text/stickers, full-size export)
- `edit/EditorScreen.kt`, `edit/EditCanvas.kt`, `edit/EditPanels.kt` editor UI
- `edit/TextEditorDialog.kt`, `edit/Stickers.kt` text entry and sticker picker/maker
