#
# OrangeFox (fox_12.1) options for the HiBy R4. Included by BoardConfig.mk only inside an OrangeFox source tree;
# everything else (partitions, USB, storage, battery, display) is shared with the TWRP build.
# SPDX-License-Identifier: Apache-2.0
#

FOX_BUILD_TYPE := Unofficial
FOX_VARIANT := R4
FOX_TARGET_DEVICES := R4
OF_MAINTAINER := AndryTheBeast

# A/B with real recovery partitions + Virtual A/B
OF_AB_DEVICE_WITH_RECOVERY_PARTITION := 1
FOX_VIRTUAL_AB_DEVICE := 1
# Never reflash itself: that would also write recovery_b, and slot b has no system on many units
OF_NO_REFLASH_CURRENT_ORANGEFOX := 1

# Screen 750x1334 (16:9), no notch, no LED/flashlight controls in the UI
OF_SCREEN_H := 1920
OF_STATUS_H := 80
OF_STATUS_INDENT_LEFT := 32
OF_STATUS_INDENT_RIGHT := 32
OF_HIDE_NOTCH := 1
OF_CLOCK_POS := 1
OF_USE_GREEN_LED := 0
OF_FLASHLIGHT_ENABLE := 0

# No userdata decryption in this build (same as TWRP): use microSD / USB OTG
OF_SKIP_FBE_DECRYPTION := 1
OF_DONT_PATCH_ENCRYPTED_DEVICE := 1

# Not a Xiaomi/MIUI device
OF_DISABLE_MIUI_SPECIFIC_FEATURES := 1
OF_NO_MIUI_PATCH_WARNING := 1
OF_DISABLE_MIUI_OTA_BY_DEFAULT := 1

# Battery: read straight from the cw2015 fuel gauge (TW_CUSTOM_BATTERY_PATH in BoardConfig.mk)
OF_USE_LEGACY_BATTERY_SERVICES := 1

# Tools
OF_USE_MAGISKBOOT := 1
OF_USE_MAGISKBOOT_FOR_ALL_PATCHES := 1
OF_ENABLE_LPTOOLS := 1
FOX_USE_NANO_EDITOR := 1
FOX_USE_UPDATED_MAGISKBOOT := 1
OF_QUICK_BACKUP_LIST := /boot;/dtbo;/super;
OF_NO_TREBLE_COMPATIBILITY_CHECK := 1
