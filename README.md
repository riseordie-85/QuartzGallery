# Quartz Gallery

A replacement for the stock Android gallery app. Kotlin + Jetpack Compose, **Android 13+**.

## Features
- **Photos tab**: everything grouped by month, with All / Photos / Videos filters
- **Albums tab**: Favorites, every folder with photos or videos, Stickers, and Trash
  - Create, rename and delete albums; move photos/videos between albums
  - Albums are real folders, so other apps (and a PC over USB) see the same organization.
    New albums are created under `Pictures/`.
- **Selecting**: long-press a thumbnail, keep your finger down and drag to select a range
  (the grid scrolls when you reach the top or bottom). Starting on a selected photo deselects.
  Then favorite, share, move, delete or **watermark** them together.
- **Trash**: see what's in Android's trash and how many days are left, restore items,
  delete them forever, or empty the trash
- **Favorites**: uses Android's own favorite flag, so other gallery apps see them too
- **Viewer**: swipe, pinch/double-tap zoom, favorite, share, move, delete, details,
  set as wallpaper, add to stickers
- **Video player**: scrub bar using ExoPlayer's scrubbing mode, ±10s, big **−1 / +1 frame** buttons
  under play/pause (hold one to keep stepping), and **Capture still**, which copies the frame on
  screen at the video's full resolution (works for every format, including WebM)
- **Settings** (gear icon on Photos and Albums): theme Auto / Light / Dark, an optional
  **gradient background** (linear, radial or reflected; start/end colors; direction; presets) for the
  main screens, your watermarks, imported fonts, and media-management access

## Photo editor
Tabs along the bottom:
- **Crop**: free or fixed ratios, rotate, flip, and a **Straighten** slider (±45°).
  **Crop in** zooms just enough to hide the corners. **Background** keeps the whole photo and
  *adds* space instead of cutting: picking 9:16 on a square photo pads it out, and you can drag the
  box past the photo's edges. The added space is any color (palette, recent colors, a full picker
  with color wheel / hex / RGB, or the eyedropper to pick from the photo) or transparent
- **Adjust**: exposure, brightness, contrast, highlights, shadows, saturation, vibrance,
  warmth, tint, sharpness, vignette, grain
- **Filters**: Vivid, Warm, Cool, Golden, Fade, Vintage, Dramatic, Sepia, B&W, Noir, **Invert** and
  **Duotone** (pick the shadow and highlight colors, or a ready-made pair), with strength
- **Cutout**: on-device background removal (ML Kit through Google Play services), then make the
  background transparent, blurred or a solid color — or save the cutout as a sticker
- **Markup**: text, stickers, emoji, shapes and watermarks. Drag to move, pinch/twist or use the
  corner dot to resize and rotate, the red dot to delete, tap selected text to edit. A selected item
  gets an **opacity** slider and **Snap to** (9 positions: corners, edges, center)
  - **Text**: live preview; 7 built-in fonts, 24 Google Fonts (downloaded once, then cached) and your
    own .ttf/.otf; outline, shadow, box/highlight, glow, letter & line spacing, curved text, and
    one-tap styles (Meme, Label, Highlight, Neon, Retro...)
  - **Shapes**: rectangle, rounded, circle, line, arrow, triangle, star, heart, speech bubble — with
    fill, outline color and thickness, stretch, corner rounding
  - **Watermarks**: saved presets of text or a logo (optionally with its background removed), with
    position, distance from the edge, size and opacity. Add one to a photo in the editor, or to many
    photos at once from the selection bar (each gets a watermarked copy; originals aren't changed)

Undo/redo at the top; hold the compare button to see the photo without color changes.
**Save copy** puts the edited copy next to the original (same album, same date) and keeps the
date/camera/location info. **⋮ → Overwrite original** replaces the original (JPEG, PNG and WebP).
Edits are saved at up to 4096 px on the longest side.

## Personal folder
A passcode-protected folder (Albums tab → **Personal**). Select photos/videos and choose
**⋮ → Move to personal folder** (also in the viewer's ⋮ menu); they're removed from the gallery
and kept only inside the app.
- **6-digit passcode** that's separate from the phone's lock screen. Optionally also unlock with
  **fingerprint or face** (Android shows whichever the phone has set up — apps can't pick one) and/or
  the phone's **screen lock** (pattern, PIN or password)
- You choose how many **wrong passcodes (1–10)** empty the folder. Fingerprint/face/screen-lock
  attempts don't count
- Files are encrypted (AES-256-GCM, key protected by the phone's secure hardware) in the app's private
  storage. Each copy is read back and checked before the original is deleted. The folder locks when
  you leave it or switch apps, and it can't be screenshotted or previewed in Recents
- **Move back to gallery** puts items back in their original album; **Delete forever** removes them
- **Nothing in it can be recovered** if the passcode is forgotten, the wrong-passcode limit is hit,
  the app is uninstalled or its data cleared, or the phone is reset. It isn't backed up. (Copies
  that Google Photos already backed up before you moved something aren't affected.)

## Stickers
Stickers live in `Pictures/Stickers`, so they survive reinstalls and you can drop images in from a PC.
They're hidden from the Photos tab and show up as the Stickers album. Add one from a photo
(viewer ⋮ → Add to stickers), from the sticker picker while editing, or from the Cutout tab.

### Built-in sticker packs
Stickers that ship with the app go in `app/src/main/assets/stickers/<Pack name>/`. Each folder is a
pack (tab) in the sticker picker; `_` and `-` in folder names show as spaces. Recommended format:
- **WebP** with transparency (lossy, quality 85–90), PNG also works
- **1024 px** on the longest side (512 px is plenty for small icon-style stickers)
- Trim the empty transparent space around each sticker
- Typically 30–120 KB each, so 100 stickers add only a few MB to the app

## Confirmations
Android asks for confirmation before an app changes files another app created (moving, renaming,
favoriting, deleting, restoring, overwriting). **Settings → Move & delete without asking**
grants Gallery "Media management" access so it stops asking.

## Run it
1. In Android Studio choose **File → Open** and pick the project folder.
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
- `edit/EditModel.kt` edit state, filters, text effects, shapes
- `edit/Fonts.kt` built-in, Google (downloadable) and imported fonts
- `edit/ColorPicker.kt` color rows, full picker, eyedropper
- `edit/Watermarks.kt` watermark presets, editor and batch watermarking
- `edit/MarkupSheets.kt`, `edit/LayerLoader.kt` emoji/shape pickers, sticker packs, loading layer pictures
- `settings/AppSettings.kt`, `ui/SettingsScreen.kt`, `ui/Theme.kt` settings, theme and gradient
- `personal/` personal folder: `Vault.kt` storage and passcode, `VaultCrypto.kt` encryption,
  `VaultAuth.kt` fingerprint/face/screen lock, `PersonalScreen.kt` setup, lock screen and contents
- `edit/EditRenderer.kt` GPU rendering (AGSL color shader, geometry, text/stickers, full-size export)
- `edit/EditorScreen.kt`, `edit/EditCanvas.kt`, `edit/EditPanels.kt` editor UI
- `edit/TextEditorDialog.kt`, `edit/Stickers.kt` text editor and sticker picker/maker
